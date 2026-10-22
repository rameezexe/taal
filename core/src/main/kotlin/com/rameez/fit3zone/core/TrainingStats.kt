package com.rameez.fit3zone.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The minimum a training calculation needs from a stored session. */
data class ActivityRecord(
    val id: Long,
    val startedAt: Long,
    val activeMs: Long,
    val belowMs: Long,
    val inMs: Long,
    val aboveMs: Long,
    val distanceM: Double,
    /** Time in heart-rate zones 0..5; empty for sessions recorded before zones existed. */
    val zoneMs: List<Long> = emptyList(),
    val type: ActivityType = ActivityType.RUN,
    val avgBpm: Int? = null,
)

data class WeekSummary(
    val weekStart: LocalDate,
    val activities: Int,
    val activeMs: Long,
    /** Time inside each session's own target range. */
    val inTargetMs: Long,
    val aboveMs: Long,
    val distanceM: Double,
    val load: Int,
    /** Time in heart-rate zones 0..5. */
    val zoneMs: List<Long>,
)

data class PersonalRecords(
    val longestDuration: ActivityRecord?,
    val longestDistance: ActivityRecord?,
    val mostInTarget: ActivityRecord?,
    /** Best average pace over runs of at least 1 km, s/km. */
    val fastestAvgPace: Pair<ActivityRecord, Double>?,
)

/**
 * Training summaries computed from stored sessions. Pass only real (non-simulated) sessions.
 *
 * "Load" is Edwards' TRIMP: minutes in heart-rate zone k × k (Z1×1 … Z5×5). Sessions recorded
 * before zones existed fall back to minutes below target ×1, in ×2, above ×4. It is a transparent
 * estimate, not Garmin's (Firstbeat) Training Load.
 */
object TrainingStats {

    fun load(a: ActivityRecord): Int =
        if (a.zoneMs.size == HrZones.COUNT + 1 && a.zoneMs.sum() > 0) {
            (a.zoneMs.withIndex().sumOf { (k, ms) -> ms * k } / 60_000.0).toInt()
        } else {
            ((a.belowMs * 1 + a.inMs * 2 + a.aboveMs * 4) / 60_000.0).toInt()
        }

    fun weekStart(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The last [weeks] calendar weeks (Monday start) ending with the week containing [nowMs], oldest first. */
    fun weekly(activities: List<ActivityRecord>, nowMs: Long, zone: ZoneId, weeks: Int = 8): List<WeekSummary> {
        val current = weekStart(nowMs, zone)
        val byWeek = activities.groupBy { weekStart(it.startedAt, zone) }
        return (weeks - 1 downTo 0).map { back ->
            val start = current.minusWeeks(back.toLong())
            val list = byWeek[start].orEmpty()
            WeekSummary(
                weekStart = start,
                activities = list.size,
                activeMs = list.sumOf { it.activeMs },
                inTargetMs = list.sumOf { it.inMs },
                aboveMs = list.sumOf { it.aboveMs },
                distanceM = list.sumOf { it.distanceM },
                load = list.sumOf { load(it) },
                zoneMs = (0..HrZones.COUNT).map { z -> list.sumOf { it.zoneMs.getOrElse(z) { 0L } } },
            )
        }
    }

    /** Distance and pace records count runs only (a bike ride would beat every run); time records count everything. */
    fun records(activities: List<ActivityRecord>): PersonalRecords = PersonalRecords(
        longestDuration = activities.maxByOrNull { it.activeMs },
        longestDistance = activities.filter { it.type == ActivityType.RUN && it.distanceM > 0 }.maxByOrNull { it.distanceM },
        mostInTarget = activities.filter { it.inMs > 0 }.maxByOrNull { it.inMs },
        fastestAvgPace = activities.filter { it.type == ActivityType.RUN && it.distanceM >= 1_000 }
            .map { it to it.activeMs / 1000.0 / (it.distanceM / 1000.0) }
            .minByOrNull { it.second },
    )

    /** Days in [month] that have at least one activity, with that day's in-target share (0..100), or -1 if only free runs. */
    fun calendar(activities: List<ActivityRecord>, month: java.time.YearMonth, zone: ZoneId): Map<LocalDate, Int> =
        activities.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .filterKeys { java.time.YearMonth.from(it) == month }
            .mapValues { (_, list) ->
                val zoned = list.sumOf { it.belowMs + it.inMs + it.aboveMs }
                if (zoned == 0L) -1 else (list.sumOf { it.inMs } * 100 / zoned).toInt()
            }
}
