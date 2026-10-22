package com.rameez.fit3zone.core

import kotlin.math.roundToInt

/** A lap of a session: manual (lap button) or one workout step. Times are active time (pauses excluded). */
data class Lap(
    /** 1-based. */
    val number: Int,
    val startMs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val avgBpm: Int?,
    val maxBpm: Int?,
    /** Workout step title ("Run 2/4"); null for manual laps. */
    val label: String?,
) {
    val paceSecPerKm: Double? get() = if (distanceM >= 50) durationMs / 1000.0 / (distanceM / 1000.0) else null
}

/**
 * Splits a session into laps. Call [startLap] at each lap button press or workout step change; the
 * lap that was running is closed and returned. [finish] closes the last lap at the end of the session.
 */
class LapTracker {
    private val _laps = mutableListOf<Lap>()
    val laps: List<Lap> get() = _laps

    private var startMs = 0L
    private var startM = 0.0
    private var label: String? = null
    private var bpmSum = 0L
    private var bpmCount = 0
    private var bpmMax: Int? = null

    /** Label for the lap that is running now (the first workout step). */
    fun begin(activeMs: Long, distanceM: Double, label: String?) {
        startMs = activeMs
        startM = distanceM
        this.label = label
    }

    fun addHeartRate(bpm: Int) {
        bpmSum += bpm
        bpmCount++
        bpmMax = maxOf(bpmMax ?: bpm, bpm)
    }

    /** Closes the running lap; the next one is labelled [nextLabel]. */
    fun startLap(activeMs: Long, distanceM: Double, nextLabel: String? = null): Lap {
        val lap = current(activeMs, distanceM)
        _laps += lap
        begin(activeMs, distanceM, nextLabel)
        bpmSum = 0; bpmCount = 0; bpmMax = null
        return lap
    }

    /** The lap that is running now, as if it were closed at [activeMs] / [distanceM]. */
    fun current(activeMs: Long, distanceM: Double): Lap = Lap(
        _laps.size + 1, startMs, (activeMs - startMs).coerceAtLeast(0), (distanceM - startM).coerceAtLeast(0.0),
        if (bpmCount > 0) (bpmSum.toDouble() / bpmCount).roundToInt() else null, bpmMax, label,
    )

    /** True once the running lap has covered [everyM] (auto-lap); a manual lap restarts the count. */
    fun autoLapDue(distanceM: Double, everyM: Double): Boolean = everyM > 0 && distanceM - startM >= everyM

    /** Closes the last lap if laps were used at all and it isn't empty; a lap-less run stores no laps. */
    fun finish(activeMs: Long, distanceM: Double): Lap? {
        if (_laps.isEmpty() && label == null) return null
        if (activeMs - startMs < 1_000) return null
        return startLap(activeMs, distanceM)
    }
}

enum class AutoPauseEvent { PAUSE, RESUME }

/**
 * Speed-based auto-pause for GPS runs, fed with each fix's speed. Pauses after [stopAfterMs] below
 * [stopBelowMps] and resumes after [resumeAfterMs] above [resumeAboveMps]; the gap between the two
 * thresholds stops it flapping at walking pace. Fixes without speed are ignored.
 */
class AutoPauseDetector(
    private val stopBelowMps: Double = 0.8,
    private val resumeAboveMps: Double = 1.6,
    private val stopAfterMs: Long = 5_000,
    private val resumeAfterMs: Long = 2_000,
) {
    var paused = false
        private set
    private var since: Long? = null

    fun onSpeed(tMs: Long, speedMps: Double): AutoPauseEvent? {
        if (!paused) {
            if (speedMps >= stopBelowMps) { since = null; return null }
            val s = since ?: tMs.also { since = it }
            if (tMs - s < stopAfterMs) return null
            paused = true; since = null
            return AutoPauseEvent.PAUSE
        } else {
            if (speedMps <= resumeAboveMps) { since = null; return null }
            val s = since ?: tMs.also { since = it }
            if (tMs - s < resumeAfterMs) return null
            paused = false; since = null
            return AutoPauseEvent.RESUME
        }
    }

    /** After a manual pause or resume: start over in the given state. */
    fun reset(paused: Boolean = false) {
        this.paused = paused
        since = null
    }
}

/** Text for spoken cues (kept here so it is unit-tested and reads naturally through text-to-speech). */
object Cues {
    fun split(km: Int, paceSecPerKm: Double?, avgBpm: Int?, type: ActivityType = ActivityType.RUN): String = buildList {
        add("Kilometre $km.")
        paceSecPerKm?.let { add(type.spokenPaceOrSpeed(it) + ".") }
        avgBpm?.let { add("Heart rate $it.") }
    }.joinToString(" ")

    fun step(s: PlannedStep): String = buildList {
        add(if (s.repeats > 1) "${s.step.kind.label}, ${s.repeat} of ${s.repeats}." else "${s.step.kind.label}.")
        add(
            when (val e = s.step.end) {
                is StepEnd.Time -> spokenDuration(e.ms) + "."
                is StepEnd.Distance -> spokenDistance(e.m) + "."
                StepEnd.LapPress -> "Press lap when done."
            },
        )
        s.target?.let { add(spokenTarget(it) + ".") }
    }.joinToString(" ")

    fun lap(l: Lap, type: ActivityType = ActivityType.RUN): String = buildList {
        add("${type.lapWord} ${l.number}. ${spokenDuration(l.durationMs)}.")
        l.paceSecPerKm?.let { add(type.spokenPaceOrSpeed(it) + ".") }
        l.avgBpm?.let { add("Heart rate $it.") }
    }.joinToString(" ")

    const val FINISHED = "Workout complete. Keep going, or stop the run when you're ready."
    const val AUTO_PAUSED = "Auto paused."
    const val AUTO_RESUMED = "Resumed."

    fun above(bpm: Int) = "Heart rate $bpm. Slow down."
    fun below(bpm: Int) = "Heart rate $bpm. Speed up."

    fun spokenPace(secPerKm: Double): String {
        val s = secPerKm.roundToInt()
        val m = s / 60
        val r = s % 60
        return if (r == 0) "$m minutes per kilometre" else "$m minutes $r per kilometre"
    }

    fun spokenDuration(ms: Long): String {
        val total = ((ms + 500) / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return buildList {
            if (h > 0) add(plural(h, "hour"))
            if (m > 0) add(plural(m, "minute"))
            if (s > 0 || (h == 0 && m == 0)) add(plural(s, "second"))
        }.joinToString(" ")
    }

    fun spokenDistance(m: Double): String =
        if (m >= 1000) "%.2f".format(java.util.Locale.US, m / 1000).trimEnd('0').trimEnd('.') + " kilometres"
        else "${m.roundToInt()} metres"

    private fun spokenTarget(t: ZoneTarget): String = when {
        t.label.startsWith("Zones ") -> "Zones " + t.label.removePrefix("Zones ").replace("–", " to ")
        t.label == "Custom" -> "${t.lowerBpm} to ${t.upperBpm} beats"
        else -> t.label
    }

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
}
