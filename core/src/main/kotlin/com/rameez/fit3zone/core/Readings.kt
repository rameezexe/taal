package com.rameez.fit3zone.core

enum class ReadingSource { BLE, SIMULATED }

/**
 * A usable heart-rate sample. [elapsedMs] is a monotonic timestamp (SystemClock.elapsedRealtime
 * on Android) used for all freshness and timing decisions; [wallClockMs] is for display/storage only.
 */
data class HeartRateReading(
    val bpm: Int,
    val contact: SensorContact,
    val elapsedMs: Long,
    val wallClockMs: Long,
    val source: ReadingSource,
)

/** Stale = no valid reading within [timeoutMs]. Pure so it can be tested with a fake clock. */
class Freshness(val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    init { require(timeoutMs > 0) }

    fun isFresh(reading: HeartRateReading?, nowElapsedMs: Long): Boolean =
        reading != null && nowElapsedMs - reading.elapsedMs in 0..timeoutMs

    companion object { const val DEFAULT_TIMEOUT_MS = 5_000L }
}

/** Bounded exponential backoff: base, 2*base, 4*base ... capped at [maxMs]. */
class ReconnectBackoff(
    private val baseMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long {
        val shift = attempt.coerceAtMost(20)
        attempt++
        return (baseMs shl shift).coerceAtMost(maxMs)
    }

    fun reset() { attempt = 0 }
}

object SimulatedSequence {
    /** Parses "128 -> 138 -> 146" (also accepts commas/spaces). Throws on non-numbers. */
    fun parse(text: String): List<Int> =
        text.split("->", ",", " ", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                val v = token.toIntOrNull() ?: throw IllegalArgumentException("not a number: '$token'")
                require(v in HeartRatePlausibility.MIN_BPM..HeartRatePlausibility.MAX_BPM) { "BPM $v out of range" }
                v
            }

    const val DEFAULT = "128 -> 138 -> 146 -> 151 -> 149 -> 143 -> 139"

    /**
     * Simulated-session tour through every zone for a 20-30 year old (max ≈ 190): warm-up, settle,
     * surge above, recover, sag below for 15 s, settle, then climb through Z4-Z5. 5 s per value.
     */
    const val ZONE_TOUR = "100 -> 110 -> 125 -> 128 -> 130 -> 150 -> 152 -> 150 -> 128 -> 126 -> " +
        "105 -> 104 -> 106 -> 125 -> 160 -> 175 -> 186 -> 188 -> 140"
}

/**
 * Drops a packet that repeats the previous one within [windowMs]. On API 31-32 some stacks deliver
 * the same notification twice; a real 1 Hz stream never repeats inside a few hundred ms.
 */
class DuplicateFilter(private val windowMs: Long = 250L) {
    private var lastBytes: ByteArray? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    fun isDuplicate(bytes: ByteArray, atElapsedMs: Long): Boolean {
        val dup = lastBytes?.contentEquals(bytes) == true && atElapsedMs - lastAtMs in 0 until windowMs
        if (!dup) {
            lastBytes = bytes.copyOf()
            lastAtMs = atElapsedMs
        }
        return dup
    }

    fun reset() {
        lastBytes = null
        lastAtMs = Long.MIN_VALUE
    }
}
