package com.rameez.fit3zone.core

/** Alert timing shared by all targets. */
data class AlertTiming(
    /** Continuous above-ceiling time required before the first alert (filters single-sample spikes). */
    val confirmMs: Long = 3_000,
    /** Continuous below-floor time before a below-zone alert; longer, since easing off is less urgent. */
    val belowConfirmMs: Long = 10_000,
    /** Repeat interval while still outside the target. */
    val repeatMs: Long = 30_000,
    /** Minimum gap between any two alerts, across episodes (stops 145/146 flapping storms). */
    val cooldownMs: Long = 15_000,
    /** Time back inside the target before the "back in zone" cue. Alerts stop immediately regardless. */
    val recoveryMs: Long = 3_000,
    val staleTimeoutMs: Long = Freshness.DEFAULT_TIMEOUT_MS,
    val backInZoneCue: Boolean = true,
    /** Alert when below the target floor (only once the target has been reached, so warm-ups are quiet). */
    val alertBelow: Boolean = true,
) {
    init {
        require(confirmMs in 0..60_000) { "confirmation must be 0-60 s" }
        require(belowConfirmMs in 0..120_000) { "below confirmation must be 0-120 s" }
        require(repeatMs in 5_000..600_000) { "repeat interval must be 5-600 s" }
        require(cooldownMs in 0..600_000) { "cooldown must be 0-600 s" }
        require(recoveryMs in 0..60_000) { "recovery must be 0-60 s" }
        require(staleTimeoutMs in 2_000..30_000) { "stale timeout must be 2-30 s" }
    }
}

/** Position relative to the run's target range. */
enum class Zone { BELOW_ZONE, IN_ZONE, ABOVE_ZONE }

enum class MonitorStatus { WAITING_FOR_DATA, ACTIVE, STALE, PAUSED }

sealed interface ZoneAction {
    data class AboveZoneAlert(val bpm: Int, val alertNumber: Int, val isRepeat: Boolean) : ZoneAction
    data class BelowZoneAlert(val bpm: Int, val alertNumber: Int, val isRepeat: Boolean) : ZoneAction
    data class BackInZone(val bpm: Int, val fromAbove: Boolean) : ZoneAction
}

data class ZoneSnapshot(
    val status: MonitorStatus,
    /** Confirmed position vs target: ABOVE only after confirmation. Null without fresh data or target. */
    val zone: Zone?,
    /** Position of the latest fresh reading, without confirmation. */
    val rawZone: Zone?,
    /** Heart-rate zone 0..5 of the latest fresh reading; null without fresh data or zones. */
    val hrZone: Int?,
    val bpm: Int?,
    val lastReadingElapsedMs: Long?,
    val aboveConfirmed: Boolean,
    val belowConfirmed: Boolean,
    val pendingAboveSinceMs: Long?,
    val pendingBelowSinceMs: Long?,
    val activeDurationMs: Long,
    val timeBelowMs: Long,
    val timeInMs: Long,
    val timeAboveMs: Long,
    val timeStaleMs: Long,
    /** Time in heart-rate zones 0..5 (index = zone). */
    val zoneMs: List<Long>,
    val aboveAlerts: Int,
    val belowAlerts: Int,
    val dataInterruptions: Int,
) {
    val alertCount: Int get() = aboveAlerts + belowAlerts
}

/**
 * Deterministic zone state machine. No threads, no clocks: callers pass a monotonic `now` with
 * every call. Feed every reading to [onReading] and call [onTick] periodically (e.g. every 500 ms)
 * so confirmation, repeats and staleness advance even when samples stop.
 *
 * [target] null is a free run: zones are recorded, nothing alerts. Only readings from
 * [acceptedSource] count, so simulated data can never drive a real session.
 */
class ZoneMonitor(
    target: ZoneTarget?,
    zones: HrZones?,
    timing: AlertTiming,
    private val acceptedSource: ReadingSource,
    startElapsedMs: Long,
) {
    var target: ZoneTarget? = target
        private set
    var zones: HrZones? = zones
        private set
    var timing: AlertTiming = timing
        private set

    /** The most recent reading this monitor accepted (right source, fresh, in order). */
    val lastAccepted: HeartRateReading? get() = lastReading

    private class Episode {
        var sinceMs: Long? = null
        var confirmed = false
        var alerted = false
        val alerting get() = confirmed && alerted
        fun reset() { sinceMs = null; confirmed = false; alerted = false }
    }

    private var lastTickMs = startElapsedMs
    private var lastReading: HeartRateReading? = null
    private var everFresh = false
    private var wasFresh = false
    private var paused = false

    private var zone: Zone? = null
    private val above = Episode()
    private val below = Episode()
    /** Below alerts arm once the target has been reached, so the warm-up is quiet. */
    private var belowArmed = false
    private var lastAlertAtMs: Long? = null
    private var quietUntilMs: Long? = null
    private var recoverySinceMs: Long? = null
    private var recoveryFromAbove = true

    private var activeMs = 0L
    private var belowMs = 0L
    private var inMs = 0L
    private var aboveMs = 0L
    private var staleMs = 0L
    private val zoneMs = LongArray(HrZones.COUNT + 1)
    private var aboveAlerts = 0
    private var belowAlerts = 0
    private var interruptions = 0

    fun onReading(reading: HeartRateReading, nowMs: Long): List<ZoneAction> {
        if (paused || reading.source != acceptedSource) return emptyList()
        if (nowMs - reading.elapsedMs !in 0..timing.staleTimeoutMs) return emptyList()
        if (lastReading?.let { reading.elapsedMs <= it.elapsedMs } == true) return emptyList()
        account(nowMs)
        lastReading = reading
        everFresh = true
        wasFresh = true
        return evaluate(nowMs)
    }

    fun onTick(nowMs: Long): List<ZoneAction> {
        if (paused) return emptyList()
        account(nowMs)
        if (!isFresh(nowMs)) {
            if (wasFresh) interruptions++
            wasFresh = false
            suspendAlerts()
            zone = null
            return emptyList()
        }
        return evaluate(nowMs)
    }

    fun pause(nowMs: Long) {
        if (paused) return
        account(nowMs)
        paused = true
        suspendAlerts()
    }

    fun resume(nowMs: Long) {
        if (!paused) return
        paused = false
        lastTickMs = nowMs
    }

    fun updateTiming(newTiming: AlertTiming) {
        timing = newTiming
    }

    /**
     * A new target (e.g. the next workout step) starts fresh: episodes reset, below alerts re-arm on reaching it.
     * [quietMs] holds back alerts for a while after the change (heart rate takes a minute or so to come down
     * after an interval); an episode that is still confirmed when the quiet period ends alerts then.
     */
    fun updateTarget(newTarget: ZoneTarget?, nowMs: Long, quietMs: Long = 0) {
        if (newTarget == target) return
        account(nowMs)
        target = newTarget
        suspendAlerts()
        belowArmed = false
        zone = null
        quietUntilMs = if (quietMs > 0) nowMs + quietMs else null
    }

    fun snapshot(nowMs: Long): ZoneSnapshot {
        val fresh = !paused && isFresh(nowMs)
        val r = lastReading
        return ZoneSnapshot(
            status = when {
                paused -> MonitorStatus.PAUSED
                fresh -> MonitorStatus.ACTIVE
                everFresh -> MonitorStatus.STALE
                else -> MonitorStatus.WAITING_FOR_DATA
            },
            zone = if (fresh) zone else null,
            rawZone = if (fresh && r != null) target?.classify(r.bpm) else null,
            hrZone = if (fresh && r != null) zones?.zoneOf(r.bpm) else null,
            bpm = if (fresh) r?.bpm else null,
            lastReadingElapsedMs = r?.elapsedMs,
            aboveConfirmed = above.confirmed,
            belowConfirmed = below.confirmed,
            pendingAboveSinceMs = if (!above.confirmed) above.sinceMs else null,
            pendingBelowSinceMs = if (!below.confirmed) below.sinceMs else null,
            activeDurationMs = activeMs,
            timeBelowMs = belowMs,
            timeInMs = inMs,
            timeAboveMs = aboveMs,
            timeStaleMs = staleMs,
            zoneMs = zoneMs.toList(),
            aboveAlerts = aboveAlerts,
            belowAlerts = belowAlerts,
            dataInterruptions = interruptions,
        )
    }

    private fun isFresh(nowMs: Long): Boolean {
        val r = lastReading ?: return false
        return nowMs - r.elapsedMs in 0..timing.staleTimeoutMs
    }

    /** Attribute time since the last call to the zone of the latest reading (or to "stale"). */
    private fun account(nowMs: Long) {
        val delta = nowMs - lastTickMs
        if (delta <= 0) return
        lastTickMs = nowMs
        if (paused) return
        activeMs += delta
        val r = lastReading
        if (r != null && isFresh(nowMs)) {
            zones?.let { zoneMs[it.zoneOf(r.bpm)] += delta }
            when (target?.classify(r.bpm)) {
                Zone.BELOW_ZONE -> belowMs += delta
                Zone.IN_ZONE -> inMs += delta
                Zone.ABOVE_ZONE -> aboveMs += delta
                null -> Unit
            }
        } else if (everFresh) {
            staleMs += delta
        }
    }

    private fun evaluate(nowMs: Long): List<ZoneAction> {
        val t = target ?: return emptyList()
        val r = lastReading ?: return emptyList()
        val raw = t.classify(r.bpm)
        if (raw != Zone.BELOW_ZONE) belowArmed = true
        val belowAlerting = timing.alertBelow && belowArmed

        return when (raw) {
            Zone.ABOVE_ZONE -> {
                below.reset()
                recoverySinceMs = null
                val repeat = step(above, r, nowMs, timing.confirmMs)
                if (above.confirmed) zone = Zone.ABOVE_ZONE
                if (repeat == null) emptyList()
                else listOf(ZoneAction.AboveZoneAlert(r.bpm, ++aboveAlerts, repeat))
            }
            Zone.BELOW_ZONE -> {
                zone = Zone.BELOW_ZONE
                if (belowAlerting) {
                    // Dropping straight from an above alert to below: the below alert takes over.
                    if (above.alerting) recoverySinceMs = null
                    above.reset()
                    val repeat = step(below, r, nowMs, timing.belowConfirmMs)
                    if (repeat == null) emptyList()
                    else listOf(ZoneAction.BelowZoneAlert(r.bpm, ++belowAlerts, repeat))
                } else {
                    // Below alerts off: under the floor counts as recovery from an above episode.
                    leaveEpisodes(r)
                    recoveryCue(r, nowMs)
                }
            }
            Zone.IN_ZONE -> {
                zone = Zone.IN_ZONE
                leaveEpisodes(r)
                recoveryCue(r, nowMs)
            }
        }
    }

    /** Advances an out-of-target episode; returns isRepeat when an alert is due, else null. */
    private fun step(e: Episode, r: HeartRateReading, nowMs: Long, confirmMs: Long): Boolean? {
        val since = e.sinceMs ?: r.elapsedMs.also {
            e.sinceMs = it
            e.alerted = false
        }
        if (!e.confirmed && nowMs - since >= confirmMs) e.confirmed = true
        if (!e.confirmed) return null
        if (quietUntilMs?.let { nowMs < it } == true) return null
        val last = lastAlertAtMs
        val due = if (!e.alerted) last == null || nowMs - last >= timing.cooldownMs
        else last != null && nowMs - last >= timing.repeatMs
        if (!due) return null
        val repeat = e.alerted
        e.alerted = true
        lastAlertAtMs = nowMs
        return repeat
    }

    /** Alerting stops on this very reading; a recovery cue is scheduled if an alert had fired. */
    private fun leaveEpisodes(r: HeartRateReading) {
        if (timing.backInZoneCue && recoverySinceMs == null && (above.alerting || below.alerting)) {
            recoverySinceMs = r.elapsedMs
            recoveryFromAbove = above.alerting
        }
        above.reset()
        below.reset()
    }

    private fun recoveryCue(r: HeartRateReading, nowMs: Long): List<ZoneAction> {
        val rec = recoverySinceMs ?: return emptyList()
        if (nowMs - rec < timing.recoveryMs) return emptyList()
        recoverySinceMs = null
        return listOf(ZoneAction.BackInZone(r.bpm, recoveryFromAbove))
    }

    private fun suspendAlerts() {
        above.reset()
        below.reset()
        recoverySinceMs = null
    }
}
