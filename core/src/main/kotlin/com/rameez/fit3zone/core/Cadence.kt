package com.rameez.fit3zone.core

/**
 * Running / walking cadence from the phone's step detector. Feed each detected step's time
 * (monotonic ms, same clock as heart-rate readings); steps while paused are not counted.
 * Live cadence is the step rate over the last [windowMs]; it drops to 0 once steps stop.
 */
class CadenceTracker(private val windowMs: Long = 10_000, private val stoppedAfterMs: Long = 3_000) {
    private val recent = ArrayDeque<Long>()

    /** Steps counted during the session (not while paused). */
    var steps: Int = 0
        private set

    fun onStep(tMs: Long) {
        steps++
        recent.addLast(tMs)
        trim(tMs)
    }

    /** Clears the live window (after a pause), keeping the total. */
    fun resetWindow() = recent.clear()

    /** Steps per minute now; 0 when stopped, null until there are enough steps to tell. */
    fun current(nowMs: Long): Int? {
        trim(nowMs)
        val last = recent.lastOrNull() ?: return if (steps > 0) 0 else null
        if (nowMs - last > stoppedAfterMs) return 0
        if (recent.size < MIN_STEPS) return null
        val spanMs = last - recent.first()
        if (spanMs <= 0) return null
        return ((recent.size - 1) * 60_000.0 / spanMs).toInt()
    }

    /** Average steps per minute over the active (unpaused) time; null under a minute or without steps. */
    fun average(activeMs: Long): Int? =
        if (activeMs < 60_000 || steps == 0) null else (steps * 60_000.0 / activeMs).toInt()

    private fun trim(nowMs: Long) {
        while (recent.isNotEmpty() && nowMs - recent.first() > windowMs) recent.removeFirst()
    }

    companion object {
        private const val MIN_STEPS = 4
    }
}

/**
 * (time, cumulative steps) from stored cadence samples (steps per minute every few seconds), for activities saved
 * before step counts were stored. Stretches longer than [maxGapMs] between samples (paused or stopped) add nothing.
 */
fun stepTimelineFromCadence(samples: List<Pair<Long, Int>>, maxGapMs: Long = 15_000): List<Pair<Long, Long>> {
    if (samples.isEmpty()) return emptyList()
    var total = 0.0
    return buildList {
        add(samples.first().first to 0L)
        for ((a, b) in samples.zipWithNext()) {
            val dt = b.first - a.first
            if (dt in 1..maxGapMs) total += (a.second + b.second) / 2.0 * dt / 60_000.0
            if (dt > 0) add(b.first to kotlin.math.round(total).toLong())
        }
    }
}
