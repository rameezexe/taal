package com.rameez.fit3zone.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Resting HR estimated from the band's all-day minute averages. */
data class RestingEstimate(val bpm: Int, val daysUsed: Int, val latestDay: LocalDate) {
    /** The value to use when the user limits the band value to [range] (e.g. 59..62); null range = as measured. */
    fun within(range: IntRange?): Int = range?.let { bpm.coerceIn(it) } ?: bpm
}

/**
 * Resting HR from the band's per-minute heart rate, Garmin-style: for each day, the lowest 30-minute
 * average (usually during sleep). A day only counts with enough data to contain a quiet period
 * ([minDayMinutes]); the estimate is the median of the counted days in the last [days] days, so one
 * odd night doesn't swing the zones.
 */
object BandRestingHr {
    private const val WINDOW_MS = 30 * 60_000L
    /** A 30-minute window needs at least this many minute values (gaps of a few minutes are fine). */
    private const val MIN_WINDOW_MINUTES = 24

    /** [bins] = (minute start ms, average bpm). */
    fun daily(bins: List<Pair<Long, Int>>, zone: ZoneId, minDayMinutes: Int = 360): Map<LocalDate, Int> =
        bins.groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
            .filterValues { it.size >= minDayMinutes }
            .mapNotNull { (day, list) -> lowestWindow(list.sortedBy { it.first })?.let { day to it } }
            .toMap()

    private fun lowestWindow(sorted: List<Pair<Long, Int>>): Int? {
        var best: Double? = null
        var hi = 0
        var sum = 0L
        for (lo in sorted.indices) {
            while (hi < sorted.size && sorted[hi].first < sorted[lo].first + WINDOW_MS) { sum += sorted[hi].second; hi++ }
            val n = hi - lo
            if (n >= MIN_WINDOW_MINUTES) {
                val avg = sum.toDouble() / n
                if (best == null || avg < best) best = avg
            }
            sum -= sorted[lo].second
        }
        return best?.roundToInt()?.takeIf { it in 30..110 }
    }

    fun estimate(daily: Map<LocalDate, Int>, today: LocalDate, days: Int = 14): RestingEstimate? {
        val recent = daily.filterKeys { !it.isAfter(today) && it.isAfter(today.minusDays(days.toLong())) }
        if (recent.isEmpty()) return null
        val sorted = recent.values.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2] + 1) / 2
        return RestingEstimate(median, recent.size, recent.keys.max())
    }
}
