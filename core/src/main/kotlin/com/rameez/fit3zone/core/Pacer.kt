package com.rameez.fit3zone.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Run [distanceM] in [timeMs] at an even pace. */
data class PaceGoal(val distanceM: Double, val timeMs: Long) {
    init {
        require(distanceM >= 100 && timeMs >= 60_000) { "goal too short" }
    }

    val paceSecPerKm: Double get() = timeMs / 1000.0 / (distanceM / 1000.0)

    fun encode(): String = "${distanceM.roundToLong()}:$timeMs"

    companion object {
        fun decode(s: String?): PaceGoal? = s?.split(":")?.takeIf { it.size == 2 }?.let { (d, t) ->
            runCatching { PaceGoal(d.toDouble(), t.toLong()) }.getOrNull()
        }

        /** "3:10" (h:mm) or "3:10:00" (h:mm:ss) or "45" (minutes) → ms; null if it doesn't parse. */
        fun parseTime(text: String): Long? {
            val parts = text.trim().split(":").map { it.trim().toIntOrNull() ?: return null }
            if (parts.any { it < 0 }) return null
            val sec = when (parts.size) {
                1 -> parts[0] * 60L
                2 -> { if (parts[1] > 59) return null; parts[0] * 3600L + parts[1] * 60L }
                3 -> { if (parts[1] > 59 || parts[2] > 59) return null; parts[0] * 3600L + parts[1] * 60L + parts[2] }
                else -> return null
            }
            return (sec * 1000).takeIf { it >= 60_000 }
        }
    }
}

/**
 * Where the runner stands against an even-pace [goal] after [distanceM] in [activeMs] (timer time: pauses don't
 * count, like a Garmin pacer).
 */
data class PacerStatus(val goal: PaceGoal, val distanceM: Double, val activeMs: Long) {
    val done: Boolean get() = distanceM >= goal.distanceM
    val remainingM: Double get() = (goal.distanceM - distanceM).coerceAtLeast(0.0)
    val fraction: Double get() = (distanceM / goal.distanceM).coerceIn(0.0, 1.0)

    /** Positive = ahead of the even-pace schedule, negative = behind. */
    val aheadMs: Long get() = (distanceM.coerceAtMost(goal.distanceM) / goal.distanceM * goal.timeMs).roundToLong() - activeMs

    /** Pace needed from here to still make the goal time; null when done or the goal time has already passed. */
    val neededPaceSecPerKm: Double?
        get() {
            val leftMs = goal.timeMs - activeMs
            return if (done || leftMs <= 0) null else leftMs / 1000.0 / (remainingM / 1000.0)
        }

    /** Finish time if the average pace so far holds; null before 500 m (too early to say). */
    val projectedFinishMs: Long?
        get() = if (distanceM < MIN_PROJECTION_M) null else (activeMs / distanceM * goal.distanceM).roundToLong()

    /** Early on, GPS lock and the first steps swing the numbers: the UI shows them after this distance. */
    val meaningful: Boolean get() = distanceM >= MIN_SHOW_M

    companion object {
        const val MIN_SHOW_M = 200.0
        const val MIN_PROJECTION_M = 500.0
        /** Within this much of the schedule counts as "on pace". */
        const val ON_PACE_MS = 10_000L
    }
}

/**
 * Spoken pacer cues: ahead / behind at every kilometre, halfway with the projected finish, one kilometre to go,
 * and the finish against the goal time. Feed it every tick; it returns what to say (usually nothing).
 */
class PacerCues(val goal: PaceGoal) {
    private var kmAnnounced = 0
    private var halfway = false
    private var lastKm = false
    var finished = false
        private set

    fun onTick(s: PacerStatus): List<String> {
        if (finished) return emptyList()
        if (s.done) {
            finished = true
            return listOf(finish(s))
        }
        val out = mutableListOf<String>()
        val km = (s.distanceM / 1000).toInt()
        val longGoal = goal.distanceM >= 2_000
        val newKm = km > kmAnnounced
        if (newKm) kmAnnounced = km
        when {
            longGoal && !lastKm && s.remainingM <= 1_000 -> {
                lastKm = true
                halfway = true
                out += "One kilometre to go. ${status(s)}"
            }
            longGoal && !halfway && s.distanceM >= goal.distanceM / 2 -> {
                halfway = true
                out += buildString {
                    append("Halfway. ${Cues.spokenDistance(s.remainingM)} to go. ${status(s)}")
                    s.projectedFinishMs?.let { append(" Projected finish ${Cues.spokenDuration(roundToMinute(it))}.") }
                }
            }
            newKm -> out += "Pacer: ${status(s)}"
        }
        return out
    }

    /** "On pace." / "20 seconds ahead." / "1 minute 5 seconds behind. Needed pace 8 minutes 10 per kilometre." */
    fun status(s: PacerStatus): String {
        val ahead = s.aheadMs
        return when {
            abs(ahead) < PacerStatus.ON_PACE_MS -> "On pace."
            ahead > 0 -> "${Cues.spokenDuration(roundToFive(ahead))} ahead."
            else -> buildString {
                append("${Cues.spokenDuration(roundToFive(-ahead))} behind.")
                s.neededPaceSecPerKm?.let { append(" Needed pace ${Cues.spokenPace(it)}.") }
            }
        }
    }

    private fun finish(s: PacerStatus): String {
        val diff = goal.timeMs - s.activeMs
        val vs = when {
            abs(diff) < PacerStatus.ON_PACE_MS -> "Right on your goal time."
            diff > 0 -> "${Cues.spokenDuration(roundToFive(diff))} under your goal time."
            else -> "${Cues.spokenDuration(roundToFive(-diff))} over your goal time."
        }
        return "Goal distance done. ${Cues.spokenDistance(goal.distanceM)} in ${Cues.spokenDuration(s.activeMs)}. $vs Well done!"
    }

    private fun roundToFive(ms: Long) = ((ms / 5_000.0).roundToInt() * 5_000L).coerceAtLeast(5_000)
    private fun roundToMinute(ms: Long) = (ms / 60_000.0).roundToLong() * 60_000
}
