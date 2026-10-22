package com.rameez.fit3zone.core

/** One stored heart-rate point, [offsetMs] from session start. */
data class HrSample(val offsetMs: Long, val bpm: Int)

/**
 * Collects accepted readings for storage. Samples are averaged into fixed buckets of
 * [intervalMs] so a 1 h run at 5 s is 720 rows instead of 3600; null records no samples (summary
 * statistics are kept either way).
 */
class SessionRecorder(private val intervalMs: Long?) {
    init { require(intervalMs == null || intervalMs >= 1_000) }

    private val pending = mutableListOf<HrSample>()
    private var bucket = -1L
    private var bucketSum = 0L
    private var bucketCount = 0

    private var sum = 0L
    var count = 0
        private set
    var maxBpm: Int? = null
        private set
    var minBpm: Int? = null
        private set
    val avgBpm: Int? get() = if (count == 0) null else ((sum + count / 2) / count).toInt()

    fun add(offsetMs: Long, bpm: Int) {
        count++
        sum += bpm
        maxBpm = maxOf(maxBpm ?: bpm, bpm)
        minBpm = minOf(minBpm ?: bpm, bpm)
        val interval = intervalMs ?: return
        val b = offsetMs / interval
        if (b != bucket) {
            closeBucket()
            bucket = b
        }
        bucketSum += bpm
        bucketCount++
    }

    /** Completed buckets since the last drain; the bucket still filling is kept. */
    fun drain(): List<HrSample> = pending.toList().also { pending.clear() }

    /** Everything, including the partial last bucket. */
    fun finish(): List<HrSample> {
        closeBucket()
        bucket = -1
        return drain()
    }

    private fun closeBucket() {
        val interval = intervalMs ?: return
        if (bucketCount == 0) return
        pending += HrSample(bucket * interval, ((bucketSum + bucketCount / 2) / bucketCount).toInt())
        bucketSum = 0
        bucketCount = 0
    }
}
