package com.rameez.fit3zone.core

import java.time.Instant
import java.time.ZoneId

/** One week's totals for the My Day summary. */
data class WeekTotals(
    val activities: Int,
    val activeMs: Long,
    val distanceM: Double,
    val load: Int,
    val intensityMin: Int,
    /** Time in heart-rate zones 0..5. */
    val zoneMs: List<Long>,
    /** Days (Mon = 0 … Sun = 6) with at least one activity. */
    val activeDays: Set<Int>,
)

/**
 * This week so far, compared with last week up to the same moment (Monday 00:00 → same weekday and time),
 * so a Wednesday isn't measured against a whole finished week. [lastWeekTotal] is the whole of last week.
 */
data class WeekComparison(val thisWeek: WeekTotals, val lastWeekSoFar: WeekTotals, val lastWeekTotal: WeekTotals)

enum class SuggestionKind(val label: String) {
    REST("Rest day"),
    EASY("Easy"),
    TEMPO("Tempo"),
    HARD("Intervals"),
}

/**
 * @param workoutName a workout from [WorkoutTemplates] that fits, null for rest
 * @param reason one short sentence on why
 */
data class Suggestion(val kind: SuggestionKind, val workoutName: String?, val reason: String)

/**
 * Weekly summary and a simple, transparent daily suggestion from load, recovery and the last hard day.
 * Rules (in order): already trained today → none; recovery > 24 h or very high load → rest; any recovery left,
 * high load, a hard day in the last 48 h or no baseline yet → easy; otherwise a hard day every 3+ days,
 * alternating intervals and tempo (low load → intervals first), else easy.
 */
object Coach {
    private const val DAY_MS = 86_400_000L
    const val EASY = "Zone 2 easy · 40 min"
    const val TEMPO = "Tempo · 20 min Zone 3"
    const val INTERVALS = "4 × 4 min Zone 4"

    fun weekComparison(activities: List<ActivityRecord>, nowMs: Long, zone: ZoneId): WeekComparison {
        val week = TrainingStats.weekStart(nowMs, zone)
        val lastWeek = week.minusWeeks(1)
        fun totals(list: List<ActivityRecord>) = WeekTotals(
            activities = list.size,
            activeMs = list.sumOf { it.activeMs },
            distanceM = list.sumOf { it.distanceM },
            load = list.sumOf { TrainingStats.load(it) },
            intensityMin = list.sumOf { Performance.intensityMinutes(it) },
            zoneMs = (0..HrZones.COUNT).map { z -> list.sumOf { it.zoneMs.getOrElse(z) { 0L } } },
            activeDays = list.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).dayOfWeek.value - 1 }.toSet(),
        )
        val inLast = activities.filter { TrainingStats.weekStart(it.startedAt, zone) == lastWeek }
        return WeekComparison(
            thisWeek = totals(activities.filter { TrainingStats.weekStart(it.startedAt, zone) == week && it.startedAt <= nowMs }),
            lastWeekSoFar = totals(inLast.filter { it.startedAt <= nowMs - 7 * DAY_MS }),
            lastWeekTotal = totals(inLast),
        )
    }

    /** A session counts as hard with ≥ 10 min in Z4–Z5 or an anaerobic effect of 2.0 or more. */
    fun isHard(a: ActivityRecord): Boolean {
        val z45 = if (a.zoneMs.size == HrZones.COUNT + 1) a.zoneMs[4] + a.zoneMs[5] else a.aboveMs
        return z45 >= 10 * 60_000 || Performance.trainingEffect(a).anaerobic >= 2.0
    }

    /** Tempo = mostly Z3 without much Z4–Z5. */
    private fun isTempo(a: ActivityRecord) = a.zoneMs.size == HrZones.COUNT + 1 && a.zoneMs[3] >= 15 * 60_000

    /** Null when there was already a real session (≥ 15 min) today. */
    fun suggest(activities: List<ActivityRecord>, nowMs: Long, zone: ZoneId): Suggestion? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val past = activities.filter { it.startedAt <= nowMs }.sortedBy { it.startedAt }
        if (past.any { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == today && it.activeMs >= 15 * 60_000 }) return null

        val recovery = Performance.recoveryRemainingHours(past, nowMs)
        val load = Performance.loadStatus(past, nowMs)
        val lastHard = past.lastOrNull { isHard(it) || isTempo(it) }
        val hoursSinceHard = lastHard?.let { (nowMs - it.startedAt - it.activeMs) / 3_600_000 }

        return when {
            recovery > 24 -> Suggestion(SuggestionKind.REST, null, "About $recovery h of recovery left from your last session.")
            load.band == LoadBand.VERY_HIGH -> Suggestion(SuggestionKind.REST, null, "Your load this week is very high for your usual training.")
            recovery > 0 -> Suggestion(SuggestionKind.EASY, EASY, "Still recovering ($recovery h left): keep it easy.")
            load.band == LoadBand.HIGH -> Suggestion(SuggestionKind.EASY, EASY, "Load is high this week: an easy run builds fitness without adding much.")
            hoursSinceHard != null && hoursSinceHard < 48 -> Suggestion(SuggestionKind.EASY, EASY, "You had a hard session in the last two days.")
            load.band == LoadBand.BASELINE -> Suggestion(SuggestionKind.EASY, EASY, "Building your baseline: easy Zone 2 running for the first two weeks.")
            hoursSinceHard == null || hoursSinceHard >= 72 -> {
                // Alternate the two quality sessions; a low load favours intervals.
                val intervals = when {
                    lastHard == null -> load.band == LoadBand.LOW
                    else -> isTempo(lastHard) && !isHard(lastHard)
                }
                if (intervals) Suggestion(SuggestionKind.HARD, INTERVALS, "You're recovered and your last quality day was a while ago.")
                else Suggestion(SuggestionKind.TEMPO, TEMPO, "You're recovered: a steady tempo run today.")
            }
            else -> Suggestion(SuggestionKind.EASY, EASY, "An easy day between quality sessions.")
        }
    }
}
