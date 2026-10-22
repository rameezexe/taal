package com.rameez.fit3zone.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** What one finished run contributes to records: its length and its fastest stretches. */
data class RunFacts(
    val id: Long,
    val startedAtMs: Long,
    val distanceM: Double,
    /** Fastest time (ms) over each [Achievements.EFFORTS] distance the run covered. */
    val effortsMs: Map<Double, Long> = emptyMap(),
)

enum class AchievementKind { EFFORT, LONGEST }

/**
 * A Strava-style badge on an activity: "Fastest 5 km", "2nd fastest 1 km", "Longest run", "Longest run this month".
 * [rank] is 1–3 for efforts (1 = personal record), 1 for longest. [ms] / [bestMs] are the effort's time and the
 * record it was compared with (only when this one isn't the record).
 */
data class Achievement(
    val kind: AchievementKind,
    val rank: Int,
    val label: String,
    val ms: Long? = null,
    val bestMs: Long? = null,
    val distanceM: Double? = null,
) {
    val isRecord: Boolean get() = rank == 1
}

object Achievements {
    val EFFORTS = listOf(1_000.0 to "1 km", 5_000.0 to "5 km", 10_000.0 to "10 km", 21_097.5 to "half marathon")

    /**
     * Badges [run] earned when it was recorded, compared with the runs before it (so an old 2nd-fastest stays
     * 2nd fastest after a later PR, as on Strava). Nothing is awarded without at least one earlier run to beat.
     */
    fun of(run: RunFacts, all: List<RunFacts>, zone: ZoneId): List<Achievement> {
        val earlier = all.filter { it.id != run.id && it.startedAtMs < run.startedAtMs }
        val out = mutableListOf<Achievement>()
        for ((m, name) in EFFORTS) {
            val mine = run.effortsMs[m] ?: continue
            val others = earlier.mapNotNull { it.effortsMs[m] }
            if (others.isEmpty()) continue
            val rank = 1 + others.count { it < mine }
            // "2nd fastest" of two is just the slowest: a lower rank only counts when it beat someone.
            if (rank > 3 || (rank > 1 && rank == others.size + 1)) continue
            val label = when (rank) { 1 -> "Fastest $name"; 2 -> "2nd fastest $name"; else -> "3rd fastest $name" }
            out += Achievement(AchievementKind.EFFORT, rank, label, ms = mine, bestMs = others.minOrNull()?.takeIf { rank > 1 }, distanceM = m)
        }
        if (run.distanceM >= 1_000 && earlier.isNotEmpty()) {
            if (earlier.all { it.distanceM < run.distanceM }) {
                out += Achievement(AchievementKind.LONGEST, 1, "Longest run", distanceM = run.distanceM)
            } else {
                val month = YearMonth.from(Instant.ofEpochMilli(run.startedAtMs).atZone(zone))
                val sameMonth = earlier.filter { YearMonth.from(Instant.ofEpochMilli(it.startedAtMs).atZone(zone)) == month }
                if (sameMonth.isNotEmpty() && sameMonth.all { it.distanceM < run.distanceM }) {
                    out += Achievement(AchievementKind.LONGEST, 1, "Longest run this month", distanceM = run.distanceM)
                }
            }
        }
        // Records first, efforts before "longest", longer efforts first.
        return out.sortedWith(compareBy<Achievement>({ it.rank }, { it.kind.ordinal }).thenByDescending { it.distanceM ?: 0.0 })
    }

    /** Badges for every run in [all], each judged against the runs before it. */
    fun forAll(all: List<RunFacts>, zone: ZoneId): Map<Long, List<Achievement>> =
        all.associate { it.id to of(it, all, zone) }.filterValues { it.isNotEmpty() }
}
