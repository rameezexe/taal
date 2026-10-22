package com.rameez.fit3zone.core

import kotlin.math.roundToInt

/**
 * Five heart-rate zones, Garmin-style. [floors] are the lowest BPM of Z1..Z5; zone k spans
 * floors[k-1] until the next floor - 1, and Z5 is open-ended. Below Z1 is "zone 0" (rest/warm-up).
 */
data class HrZones(val floors: List<Int>, val maxHr: Int) {
    init {
        require(floors.size == COUNT) { "need $COUNT zone floors" }
        require(floors.zipWithNext().all { (a, b) -> a < b }) { "zone floors must increase" }
        require(floors.first() >= MIN_BPM && maxHr > floors.last()) { "zones must lie within $MIN_BPM..max HR" }
    }

    /** 0 below Z1, otherwise 1..5. */
    fun zoneOf(bpm: Int): Int = floors.indexOfLast { bpm >= it } + 1

    /** Inclusive BPM range of zone [z] (1..5); Z5 ends at max HR. */
    fun range(z: Int): IntRange {
        require(z in 1..COUNT)
        return floors[z - 1]..if (z == COUNT) maxHr else floors[z] - 1
    }

    companion object {
        const val COUNT = 5
        const val MIN_BPM = 40
        /** Lower edge of Z1..Z5 as a fraction of heart-rate reserve or of max HR. */
        val DEFAULT_PERCENTS = listOf(0.50, 0.60, 0.70, 0.80, 0.90)

        /** Karvonen: floor = resting + pct × (max − resting). */
        fun fromReserve(maxHr: Int, restingHr: Int, percents: List<Double> = DEFAULT_PERCENTS): HrZones {
            require(restingHr in 30..120 && maxHr > restingHr + 20) { "resting HR must be well below max HR" }
            return HrZones(percents.map { (restingHr + it * (maxHr - restingHr)).roundToInt() }, maxHr)
        }

        fun fromMax(maxHr: Int, percents: List<Double> = DEFAULT_PERCENTS): HrZones =
            HrZones(percents.map { (it * maxHr).roundToInt() }, maxHr)

        /** Parses [encode] output; null for blank/invalid (e.g. sessions recorded before zones existed). */
        fun decode(s: String?): HrZones? = runCatching {
            val n = s!!.split(",").map { it.trim().toInt() }
            HrZones(n.dropLast(1), n.last())
        }.getOrNull()
    }

    fun encode(): String = (floors + maxHr).joinToString(",")
}

enum class MaxHrSource { AGE_ESTIMATE, DETECTED, MANUAL }
enum class RestingHrSource { MANUAL, MEASURED, HEALTH_CONNECT, BAND }

/**
 * What zones are computed from. Zones use heart-rate reserve when a resting HR is known, otherwise
 * % of max HR (and [usesReserve] is false so the UI can say so).
 */
data class HeartRateProfile(
    val age: Int?,
    val maxHr: Int,
    val maxHrSource: MaxHrSource,
    val restingHr: Int?,
    val restingSource: RestingHrSource?,
) {
    init {
        require(age == null || age in 10..100) { "age must be 10-100" }
        require(maxHr in 120..230) { "max HR must be 120-230" }
        require(restingHr == null || restingHr in 30..120) { "resting HR must be 30-120" }
        require(restingHr == null || maxHr > restingHr + 20) { "resting HR must be well below max HR" }
    }

    val usesReserve: Boolean get() = restingHr != null

    fun zones(): HrZones = restingHr?.let { HrZones.fromReserve(maxHr, it) } ?: HrZones.fromMax(maxHr)

    /**
     * After a real session: raise an estimated/detected max HR when the session's sustained peak is
     * higher. A manually entered max HR is never changed. Returns null when nothing changes.
     */
    fun withObservedPeak(sustainedPeak: Int?): HeartRateProfile? {
        if (sustainedPeak == null || maxHrSource == MaxHrSource.MANUAL) return null
        if (sustainedPeak <= maxHr || sustainedPeak > 230) return null
        return copy(maxHr = sustainedPeak, maxHrSource = MaxHrSource.DETECTED)
    }

    companion object {
        /** Tanaka, Monahan & Seals 2001: 208 − 0.7 × age (better than 220 − age for adults). */
        fun ageEstimate(age: Int): Int = (208 - 0.7 * age).roundToInt()

        fun fromAge(age: Int) = HeartRateProfile(age, ageEstimate(age), MaxHrSource.AGE_ESTIMATE, null, null)
    }
}

/**
 * Highest average BPM over any [windowMs] window holding at least [minReadings] readings. Used for
 * max-HR detection so a single optical spike can't redefine every zone.
 */
class SustainedPeak(private val windowMs: Long = 10_000, private val minReadings: Int = 5) {
    private val window = ArrayDeque<HrSample>()
    private var sum = 0L
    var peak: Int? = null
        private set

    fun add(offsetMs: Long, bpm: Int) {
        window.addLast(HrSample(offsetMs, bpm))
        sum += bpm
        while (offsetMs - window.first().offsetMs > windowMs) sum -= window.removeFirst().bpm
        if (window.size >= minReadings) {
            val avg = (sum.toDouble() / window.size).roundToInt()
            peak = maxOf(peak ?: avg, avg)
        }
    }
}

/**
 * Resting HR from a quiet measurement: lowest [windowMs] average over [durationMs] of readings.
 * Done when [durationMs] has passed since the first reading; [result] is null if too few readings.
 */
class RestingHrMeasurement(private val durationMs: Long = 120_000, private val windowMs: Long = 30_000) {
    private val readings = mutableListOf<HrSample>()

    fun add(elapsedMs: Long, bpm: Int) {
        if (readings.isNotEmpty() && elapsedMs <= readings.last().offsetMs) return
        readings += HrSample(elapsedMs, bpm)
    }

    fun progress(nowMs: Long): Float =
        readings.firstOrNull()?.let { ((nowMs - it.offsetMs).toFloat() / durationMs).coerceIn(0f, 1f) } ?: 0f

    fun isDone(nowMs: Long): Boolean = progress(nowMs) >= 1f

    val result: Int?
        get() {
            if (readings.isEmpty()) return null
            val start = readings.first().offsetMs
            if (readings.last().offsetMs - start < windowMs) return null
            var best: Double? = null
            var lo = 0
            var sum = 0L
            for (hi in readings.indices) {
                sum += readings[hi].bpm
                while (readings[hi].offsetMs - readings[lo].offsetMs > windowMs) sum -= readings[lo++].bpm
                val count = hi - lo + 1
                // full windows only, with roughly one reading every 2 s
                if (readings[hi].offsetMs - start >= windowMs && count >= windowMs / 2_000) {
                    val avg = sum.toDouble() / count
                    if (best == null || avg < best) best = avg
                }
            }
            return best?.roundToInt()?.takeIf { it in 30..120 }
        }
}

/** A run's target range. [label] is what the UI and history show ("Zone 2", "Zones 2–3", "Custom"). */
data class ZoneTarget(val lowerBpm: Int, val upperBpm: Int, val label: String) {
    init {
        require(lowerBpm in MIN_LIMIT..MAX_LIMIT && upperBpm in MIN_LIMIT..MAX_LIMIT) { "limits must be within $MIN_LIMIT..$MAX_LIMIT BPM" }
        require(lowerBpm < upperBpm) { "lower limit must be below upper limit" }
    }

    fun classify(bpm: Int): Zone = when {
        bpm > upperBpm -> Zone.ABOVE_ZONE
        bpm < lowerBpm -> Zone.BELOW_ZONE
        else -> Zone.IN_ZONE
    }

    companion object {
        const val MIN_LIMIT = 40
        const val MAX_LIMIT = 230
    }
}

/** What the user picked before a run, resolved against the current zones at start. */
sealed interface TargetChoice {
    data object Free : TargetChoice
    data class Zones(val from: Int, val to: Int) : TargetChoice {
        init { require(from in 1..HrZones.COUNT && to in from..HrZones.COUNT) }
    }
    data class Custom(val lowerBpm: Int, val upperBpm: Int) : TargetChoice

    fun resolve(zones: HrZones): ZoneTarget? = when (this) {
        Free -> null
        is Zones -> ZoneTarget(
            zones.range(from).first, zones.range(to).last,
            if (from == to) "Zone $from" else "Zones $from–$to",
        )
        is Custom -> ZoneTarget(lowerBpm, upperBpm, "Custom")
    }

    fun encode(): String = when (this) {
        Free -> "free"
        is Zones -> "zones:$from-$to"
        is Custom -> "custom:$lowerBpm-$upperBpm"
    }

    companion object {
        fun decode(s: String?): TargetChoice? = runCatching {
            val (kind, rest) = s!!.split(":").let { it[0] to it.getOrNull(1) }
            val nums = rest?.split("-")?.map { it.toInt() }
            when (kind) {
                "free" -> Free
                "zones" -> Zones(nums!![0], nums[1])
                "custom" -> Custom(nums!![0], nums[1]).also { ZoneTarget(it.lowerBpm, it.upperBpm, "") }
                else -> null
            }
        }.getOrNull()
    }
}
