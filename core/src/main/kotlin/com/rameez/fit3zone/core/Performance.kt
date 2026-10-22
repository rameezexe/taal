package com.rameez.fit3zone.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Aerobic / anaerobic training effect on a 0–5 scale (one decimal). */
data class TrainingEffect(val aerobic: Double, val anaerobic: Double) {
    val primary: Double get() = max(aerobic, anaerobic)
}

enum class LoadBand(val label: String) {
    BASELINE("Building baseline"),
    LOW("Low"),
    OPTIMAL("Optimal"),
    HIGH("High"),
    VERY_HIGH("Very high"),
}

/**
 * @param acute load of the last 7 days
 * @param chronicWeekly average weekly load over the last 28 days
 * @param ratio acute ÷ chronic weekly; null without a baseline
 */
data class LoadStatus(val acute: Int, val chronicWeekly: Int, val ratio: Double?, val band: LoadBand)

/** One run's VO2max estimate. */
data class Vo2Point(val activityId: Long, val startedAt: Long, val vo2max: Double)

data class Vo2Estimate(val vo2max: Double, val runsUsed: Int, val history: List<Vo2Point>)

/**
 * Performance estimates from heart rate, time and GPS. Everything here is a transparent estimate
 * from published formulas — not Garmin's (Firstbeat) numbers:
 *  - load = Edwards TRIMP ([TrainingStats.load]); acute 7 d vs chronic 28 d (acute:chronic workload ratio);
 *  - training effect = TRIMP (aerobic) / time in Z5 + ¼ Z4 (anaerobic), mapped onto 0–5 with a saturating curve;
 *  - VO2max = ACSM running equation (VO2 = 0.2·v + 3.5) divided by the fraction of heart-rate reserve
 *    (Swain: %HRR ≈ %VO2 reserve), or %HRmax = 0.64·%VO2max + 0.37 without a resting HR;
 *  - race times = Daniels–Gilbert VDOT equations with the VO2max estimate as VDOT; Riegel (T₂ = T₁·(D₂/D₁)^1.06);
 *  - intensity minutes = minutes ≥ 40 % HRR (Z1 moderate ×1, Z2+ vigorous ×2), weekly goal 150 (WHO).
 */
object Performance {
    const val INTENSITY_GOAL_MIN = 150
    private const val DAY_MS = 86_400_000L

    // ---- Training effect & recovery -------------------------------------------------------------

    /** TRIMP at which aerobic training effect reaches 2.5: 30 min in Z2 (25 min easy Z2 ≈ 2.1, 40 min Z2 = 3.0, 60 min Z3 ≈ 4.4). */
    private const val AEROBIC_HALF_TRIMP = 60.0
    /** Z5-equivalent minutes (Z5 + ¼ Z4) per e-fold: 6 min → 3.2, 12 min → 4.3. */
    private const val ANAEROBIC_SCALE_MIN = 6.0

    fun trainingEffect(a: ActivityRecord): TrainingEffect {
        val trimp = TrainingStats.load(a).toDouble()
        val aerobic = 5 * (1 - exp(-trimp * kotlin.math.ln(2.0) / AEROBIC_HALF_TRIMP))
        val hard = if (a.zoneMs.size == HrZones.COUNT + 1) (a.zoneMs[5] + a.zoneMs[4] / 4.0) / 60_000.0 else 0.0
        val anaerobic = 5 * (1 - exp(-hard / ANAEROBIC_SCALE_MIN))
        return TrainingEffect(round1(aerobic), round1(anaerobic))
    }

    /** Garmin-style wording for a 0–5 effect. */
    fun effectLabel(te: Double): String = when {
        te < 1.0 -> "No effect"
        te < 2.0 -> "Minor"
        te < 3.0 -> "Maintaining"
        te < 4.0 -> "Improving"
        te < 5.0 -> "Highly improving"
        else -> "Overreaching"
    }

    /** Hours to recover from one activity: grows exponentially with the effect, more when load is ramping up. */
    fun recoveryHours(effect: TrainingEffect, loadRatio: Double? = null): Int {
        val base = 4 * exp(0.65 * effect.primary)
        val ramp = (loadRatio ?: 1.0).coerceIn(0.8, 1.3)
        return (base * ramp).roundToInt().coerceIn(0, 96)
    }

    /** Hours of recovery still left at [nowMs] (0 = recovered), from the activities of the last 4 days. */
    fun recoveryRemainingHours(activities: List<ActivityRecord>, nowMs: Long): Int {
        val ratio = loadStatus(activities, nowMs).ratio
        return activities.filter { nowMs - it.startedAt in 0..4 * DAY_MS }.maxOfOrNull { a ->
            val endMs = a.startedAt + a.activeMs
            val left = recoveryHours(trainingEffect(a), ratio) - (nowMs - endMs) / 3_600_000.0
            kotlin.math.ceil(left).toInt()
        }?.coerceAtLeast(0) ?: 0
    }

    // ---- Load status -----------------------------------------------------------------------------

    fun loadStatus(activities: List<ActivityRecord>, nowMs: Long): LoadStatus {
        fun loadSince(days: Int) = activities.filter { it.startedAt in (nowMs - days * DAY_MS)..nowMs }.sumOf { TrainingStats.load(it) }
        val acute = loadSince(7)
        val chronicWeekly = loadSince(28) / 4.0
        // Two weeks of history before the ratio means anything.
        val first = activities.minOfOrNull { it.startedAt }
        if (first == null || nowMs - first < 14 * DAY_MS || chronicWeekly <= 0) {
            return LoadStatus(acute, chronicWeekly.roundToInt(), null, LoadBand.BASELINE)
        }
        val ratio = acute / chronicWeekly
        val band = when {
            ratio < 0.8 -> LoadBand.LOW
            ratio <= 1.3 -> LoadBand.OPTIMAL
            ratio <= 1.5 -> LoadBand.HIGH
            else -> LoadBand.VERY_HIGH
        }
        return LoadStatus(acute, chronicWeekly.roundToInt(), round2(ratio), band)
    }

    /** Load per day for the last [days] days ending today, oldest first. */
    fun dailyLoads(activities: List<ActivityRecord>, nowMs: Long, zone: ZoneId, days: Int = 28): List<Pair<LocalDate, Int>> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val byDay = activities.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            d to byDay[d].orEmpty().sumOf { TrainingStats.load(it) }
        }
    }

    // ---- Intensity minutes -------------------------------------------------------------------------

    /** Moderate minutes ×1 + vigorous ×2. Old sessions without zone times: time in or above target, ×1. */
    fun intensityMinutes(a: ActivityRecord): Int =
        if (a.zoneMs.size == HrZones.COUNT + 1 && a.zoneMs.sum() > 0) {
            ((a.zoneMs[1] + 2 * (a.zoneMs[2] + a.zoneMs[3] + a.zoneMs[4] + a.zoneMs[5])) / 60_000).toInt()
        } else {
            ((a.inMs + a.aboveMs) / 60_000).toInt()
        }

    fun weeklyIntensityMinutes(activities: List<ActivityRecord>, nowMs: Long, zone: ZoneId): Int {
        val week = TrainingStats.weekStart(nowMs, zone)
        return activities.filter { TrainingStats.weekStart(it.startedAt, zone) == week }.sumOf { intensityMinutes(it) }
    }

    // ---- VO2max ----------------------------------------------------------------------------------

    /**
     * VO2max (ml/kg/min) from one steady run: average speed and average heart rate. Null when the run
     * is too short, too easy (< 50 % HRR / 70 % HRmax) or outside running speeds.
     */
    fun vo2maxFromRun(distanceM: Double, activeMs: Long, avgBpm: Int?, maxHr: Int, restingHr: Int?): Double? {
        if (avgBpm == null || distanceM < 1_600 || activeMs < 10 * 60_000) return null
        val metresPerMin = distanceM / (activeMs / 60_000.0)
        if (metresPerMin !in 100.0..400.0) return null // slower than 10:00 /km or faster than 2:30 /km
        val vo2 = 0.2 * metresPerMin + 3.5
        val estimate = if (restingHr != null) {
            val hrr = (avgBpm - restingHr).toDouble() / (maxHr - restingHr)
            if (hrr !in 0.5..1.0) return null
            3.5 + (vo2 - 3.5) / hrr
        } else {
            val pctMax = avgBpm.toDouble() / maxHr
            if (pctMax !in 0.7..1.0) return null
            vo2 / ((pctMax - 0.37) / 0.64)
        }
        return estimate.takeIf { it in 20.0..90.0 }?.let(::round1)
    }

    /** Average of the last (up to) 5 qualifying runs within 90 days; null with none. */
    fun vo2maxEstimate(runs: List<ActivityRecord>, nowMs: Long, maxHr: Int, restingHr: Int?): Vo2Estimate? {
        val points = runs.filter { it.type == ActivityType.RUN }
            .sortedBy { it.startedAt }
            .mapNotNull { r -> vo2maxFromRun(r.distanceM, r.activeMs, r.avgBpm, maxHr, restingHr)?.let { Vo2Point(r.id, r.startedAt, it) } }
        val recent = points.filter { nowMs - it.startedAt <= 90 * DAY_MS }.takeLast(5)
        if (recent.isEmpty()) return null
        return Vo2Estimate(round1(recent.map { it.vo2max }.average()), recent.size, points)
    }

    // ---- Race prediction ---------------------------------------------------------------------------

    /** Oxygen cost (ml/kg/min) of running at [metresPerMin] (Daniels–Gilbert). */
    private fun danielsVo2(metresPerMin: Double) = -4.60 + 0.182258 * metresPerMin + 0.000104 * metresPerMin * metresPerMin

    /** Fraction of VO2max sustainable for [minutes] (Daniels–Gilbert). */
    private fun danielsFraction(minutes: Double) = 0.8 + 0.1894393 * exp(-0.012778 * minutes) + 0.2989558 * exp(-0.1932605 * minutes)

    /** Race time in seconds for [distanceM] at a VDOT of [vdot]. */
    fun predictRaceSec(vdot: Double, distanceM: Double): Double {
        var lo = 1.0
        var hi = 1_000.0 // minutes
        repeat(60) {
            val mid = (lo + hi) / 2
            // Faster than possible → required VDOT above ours → go slower.
            if (danielsVo2(distanceM / mid) / danielsFraction(mid) > vdot) lo = mid else hi = mid
        }
        return (lo + hi) / 2 * 60
    }

    /** Riegel: time for [toM] from a performance of [timeSec] over [fromM]. */
    fun riegelSec(timeSec: Double, fromM: Double, toM: Double): Double = timeSec * (toM / fromM).pow(1.06)

    val RACES: List<Pair<String, Double>> = listOf("5K" to 5_000.0, "10K" to 10_000.0, "Half marathon" to 21_097.5, "Marathon" to 42_195.0)

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}

/** "1:23:45" or "23:45" from seconds. */
fun formatRaceTime(sec: Double): String {
    val s = sec.roundToInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
