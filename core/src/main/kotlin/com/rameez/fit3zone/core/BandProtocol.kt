package com.rameez.fit3zone.core

/*
 * Passive decoder for the health data a Galaxy Fit3 sends to the phone over SAP (service 0x1A1A).
 * Our own implementation of these layers:
 *
 *   BLE notification → transport frame (length + CRC) → SAP Lite header (fragments, ACKs)
 *   → channel message → Samsung Health message (channel 0x0a) → steps / heart-rate records.
 *
 * Read-only: the app only sees band → phone notifications and never writes to the band.
 */

/** A per-minute heart-rate bin. On firmware AZE2 [avg] always equals [min], so a bin is min–max. */
data class BandHrBin(val offsetMin: Int, val avg: Int, val min: Int, val max: Int)

sealed interface BandRecord

/**
 * One heart-rate record. Continuous records cover an hour (UTC-aligned) and carry per-minute [bins];
 * records without bins are the band's periodic spot measurements (start == end, avg == min == max).
 * The same continuous record is resent with more bins as the hour fills up.
 */
data class BandHrRecord(
    val startMs: Long,
    val endMs: Long,
    val avg: Int,
    val min: Int,
    val max: Int,
    val tag: Int,
    val bins: List<BandHrBin>,
) : BandRecord {
    val isSpot: Boolean get() = bins.isEmpty()
}

/** One minute of pedometer data. */
data class BandStepsRecord(
    val startMs: Long,
    val endMs: Long,
    val steps: Int,
    val runSteps: Int,
    val walkSteps: Int,
    val distanceM: Float,
    val kcal: Float,
    val activeMs: Long,
) : BandRecord

/** One SpO2 bin (min–max over [startMs, endMs]). */
data class BandSpo2Bin(val startMs: Long, val endMs: Long, val spo2: Int, val min: Int, val max: Int)

/** A blood-oxygen measurement (type 0x70). [min]/[max]/[lowDurationS] are absent on spot readings. */
data class BandSpo2Record(
    val startMs: Long,
    val endMs: Long,
    val spo2: Int,
    val heartRate: Int?,
    val min: Int?,
    val max: Int?,
    val lowDurationS: Int?,
    val tag: Int,
    val bins: List<BandSpo2Bin>,
) : BandRecord

/** One live-data sample of a band workout (28-byte entry); fields outside their valid range are null. */
data class BandExerciseSample(
    val timeMs: Long,
    val heartRate: Float?,
    val cadence: Float?,
    val speedMps: Float?,
    val distanceM: Float?,
)

data class BandTrackPoint(val timeMs: Long, val lat: Double, val lon: Double, val altitude: Float, val accuracy: Float)

/** A workout recorded on the band (type 0x80). Exercise types are Samsung Health ids (1002 = running…). */
data class BandExerciseRecord(
    val startMs: Long,
    val endMs: Long,
    val exerciseType: Int,
    val durationMs: Long,
    val distanceM: Float?,
    val kcal: Float?,
    val meanHr: Float?,
    val maxHr: Float?,
    val minHr: Float?,
    val meanSpeedMps: Float?,
    val maxSpeedMps: Float?,
    val meanCadence: Float?,
    val maxCadence: Float?,
    val count: Int?,
    val samples: List<BandExerciseSample>,
    val track: List<BandTrackPoint>,
) : BandRecord

/**
 * A night's sleep: type 0x10 (sleep session) or 0x89 ([combined] = the merged night shown in Samsung Health).
 * [score] and [durationMin] are only sent in some records.
 */
data class BandSleepRecord(
    val startMs: Long,
    val endMs: Long,
    val combined: Boolean,
    val efficiency: Float?,
    val score: Int?,
    val durationMin: Int?,
    val cycles: Int?,
    val remMin: Int?,
) : BandRecord

/** One sleep-stage interval (type 0x11). Stage codes as in Samsung Health: 40001 awake … 40004 REM (unverified). */
data class BandSleepStage(val startMs: Long, val endMs: Long, val stage: Int) : BandRecord

/** CRC-16/ARC (poly 0x8005 reflected, init 0), used for both CRCs of a SAP BLE frame. */
object Crc16Arc {
    fun of(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}

/**
 * Layer 1: turns BLE notifications into transport payloads.
 * Frame = `len(u16 BE) · crc(len) · payload(len) · crc(payload)`. Frames may span notifications;
 * a CRC failure drops the buffered bytes and resynchronises on the next notification.
 */
class SapFrameAssembler(private val maxFrame: Int = 4096) {
    private var buf = ByteArray(0)
    var crcErrors = 0
        private set

    fun push(notification: ByteArray): List<ByteArray> {
        buf = if (buf.isEmpty()) notification.copyOf() else buf + notification
        val out = mutableListOf<ByteArray>()
        while (buf.size >= 4) {
            val len = u16be(buf, 0)
            if (Crc16Arc.of(buf, 0, 2) != u16be(buf, 2) || len > maxFrame) {
                fail(notification)
                if (buf === EMPTY || buf.size < 4) break else continue
            }
            if (buf.size < len + 6) break
            if (Crc16Arc.of(buf, 4, 4 + len) != u16be(buf, 4 + len)) {
                fail(notification)
                if (buf === EMPTY || buf.size < 4) break else continue
            }
            out += buf.copyOfRange(4, 4 + len)
            buf = buf.copyOfRange(len + 6, buf.size)
        }
        return out
    }

    /** Drops what was buffered; retries from the latest notification unless it was the culprit. */
    private fun fail(latest: ByteArray) {
        crcErrors++
        buf = if (buf.size > latest.size) latest.copyOf() else EMPTY
    }

    private companion object { val EMPTY = ByteArray(0) }
}

/** A complete message on one SAP channel (fragments joined). */
class SapMessage(val channel: Int, val payload: ByteArray)

/**
 * Layer 2: SAP Lite. Byte 0 = frameType(7–6) · msgType(5–3) · fragment(2–1) · reliable(0);
 * control frames (type 2) are ACKs and are skipped. Fragments 1/2/3 = first/middle/last.
 * The body starts with the channel id twice.
 */
class SapMessageAssembler {
    private var pending: ByteArray? = null
    var controlFrames = 0
        private set
    var encryptedFrames = 0
        private set

    fun push(frame: ByteArray): SapMessage? {
        if (frame.size < 2) return null
        val b0 = frame[0].toInt() and 0xff
        if (b0 ushr 6 == 2) { controlFrames++; return null }
        val fragment = (b0 ushr 1) and 3
        val body = frame.copyOfRange(2, frame.size)
        val whole = when (fragment) {
            0 -> {
                // Whole/first frames carry the encrypted bit; never seen set on this band (AuthMode 0).
                if (frame[1].toInt() and 0x04 != 0) { encryptedFrames++; return null }
                body
            }
            1 -> {
                if (frame[1].toInt() and 0x04 != 0) { encryptedFrames++; pending = null; return null }
                pending = body; return null
            }
            2 -> { pending = pending?.plus(body); return null }
            else -> pending?.plus(body).also { pending = null } ?: return null
        }
        if (whole.size < 2) return null
        return SapMessage(whole[0].toInt() and 0xff, whole.copyOfRange(2, whole.size))
    }
}

/**
 * Layer 3: Samsung Health agent messages (channel 0x0a). Joins large-data chunks (id 63) and
 * extracts records from sync (id 2) and request-data (id 3) messages.
 * Unknown data types end parsing of that message; records decoded before them are kept.
 * Most data types are `more · len(u32) · fields` (length-delimited, so an unknown field only ends
 * that record); steps and sleep stages have no length and need every field size known.
 */
class HealthMessageParser {
    private var large: ByteArray? = null
    private var nextChunk = 0L
    val unknownTypes = mutableSetOf<Int>()
    var malformed = 0
        private set

    fun push(payload: ByteArray): List<BandRecord> {
        if (payload.isEmpty()) return emptyList()
        return when (payload[0].toInt() and 0x3f) {
            ID_LARGE_CHUNK -> onChunk(payload)
            ID_SYNC, ID_REQUEST_DATA -> parse(payload)
            else -> emptyList()
        }
    }

    private fun onChunk(p: ByteArray): List<BandRecord> {
        if (p.size < 6) return emptyList()
        val index = u32le(p, 2)
        val data = p.copyOfRange(6, p.size)
        when (p[1].toInt() and 3) {
            0 -> { large = data; nextChunk = 1; return emptyList() }
            1 -> {
                large = if (large != null && index == nextChunk) large!! + data else null
                nextChunk++
                return emptyList()
            }
            2 -> {
                val whole = if (large != null && index == nextChunk) large!! + data else null
                large = null
                return whole?.let { parse(it) } ?: emptyList()
            }
            else -> return parse(data)
        }
    }

    fun parse(m: ByteArray): List<BandRecord> {
        val out = mutableListOf<BandRecord>()
        val r = Reader(m)
        try {
            val id = r.u8() and 0x3f
            if (id == ID_SYNC) r.u8() // parameter count
            while (r.remaining > 0) {
                when (val type = r.u8()) {
                    T_SEQ -> r.skip(2)
                    T_MSG_INFO, T_DEVICE_INFO -> r.skip(r.u8())
                    T_HEART_RATE -> do {
                        val more = r.u8()
                        val len = r.u32().toInt()
                        heartRate(r.bytes(len))?.let { out += it }
                    } while (more != 0)
                    T_STEPS -> do {
                        val more = r.u8()
                        out += steps(r)
                    } while (more != 0)
                    T_SPO2, T_EXERCISE, T_SLEEP, T_SLEEP_DATA, T_SLEEP_COMBINED -> do {
                        val more = r.u8()
                        val body = r.bytes(r.u32().toInt())
                        when (type) {
                            T_SPO2 -> spo2(body)
                            T_EXERCISE -> exercise(body)
                            T_SLEEP -> sleep(body, combined = false)
                            T_SLEEP_COMBINED -> sleep(body, combined = true)
                            else -> null // sleep data: movement/status timeline, not used yet
                        }?.let { out += it }
                    } while (more != 0)
                    T_SLEEP_STAGE -> do {
                        val more = r.u8()
                        sleepStage(r)?.let { out += it }
                    } while (more != 0)
                    else -> { unknownTypes += type; break }
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            malformed++
        }
        return out
    }

    private fun heartRate(b: ByteArray): BandHrRecord? {
        val r = Reader(b)
        var start = 0L; var end = 0L; var avg = 0; var min = 0; var max = 0; var tag = 0
        val bins = mutableListOf<BandHrBin>()
        while (r.remaining > 0) {
            when (r.u8()) {
                0x01 -> start = r.time()
                0x02 -> end = r.time()
                0x03, 0x05, 0x97, 0x99 -> r.skip(4) // tz offset, update time, versions
                0x20 -> avg = r.u8()
                0x21 -> min = r.u8()
                0x22 -> max = r.u8()
                0x23 -> r.skip(1) // bin count (redundant with the 0x24 length)
                0x25 -> tag = r.u16()
                0x24 -> {
                    val n = r.u32().toInt()
                    repeat(n / 4) { bins += BandHrBin(r.u8(), r.u8(), r.u8(), r.u8()) }
                    r.skip(n % 4)
                }
                else -> break // unknown field: the record is length-delimited, keep what we have
            }
        }
        return if (start > 0) BandHrRecord(start, end, avg, min, max, tag, bins) else null
    }

    private fun spo2(b: ByteArray): BandSpo2Record? {
        val r = Reader(b)
        var start = 0L; var end = 0L; var spo2 = 0; var tag = 0
        var hr: Int? = null; var min: Int? = null; var max: Int? = null; var low: Int? = null
        val bins = mutableListOf<BandSpo2Bin>()
        while (r.remaining > 0) {
            when (r.u8()) {
                0x01 -> start = r.time()
                0x02 -> end = r.time()
                0x03, 0x05, 0x97, 0x99 -> r.skip(4)
                0x75 -> r.skip(16) // data uuid
                0xa9 -> spo2 = r.u8()
                0xaa -> hr = r.u8()
                0xab -> min = r.u8()
                0xac -> max = r.u8()
                0xad -> tag = r.u16()
                0xae -> low = r.u32().toInt()
                0xaf -> {
                    val n = r.u32().toInt()
                    repeat(n / 11) {
                        val v = r.u8(); val lo = r.u8(); val hi = r.u8()
                        bins += BandSpo2Bin(r.time(), r.time(), v, lo, hi)
                    }
                    r.skip(n % 11)
                }
                else -> break
            }
        }
        return if (start > 0 && spo2 > 0) BandSpo2Record(start, end, spo2, hr, min, max, low, tag, bins) else null
    }

    private fun exercise(b: ByteArray): BandExerciseRecord? {
        val r = Reader(b)
        var start = 0L; var end = 0L; var type = 0; var duration = 0L; var count: Int? = null
        val f = mutableMapOf<Int, Float>()
        val samples = mutableListOf<BandExerciseSample>()
        val track = mutableListOf<BandTrackPoint>()
        fun Float.within(lo: Float, hi: Float, loOpen: Boolean = false) =
            takeIf { (if (loOpen) it > lo else it >= lo) && it <= hi }
        while (r.remaining > 0) {
            when (val id = r.u8()) {
                0x01 -> start = r.time()
                0x02 -> end = r.time()
                0x12 -> duration = r.u32()
                0x14 -> type = r.u32().toInt()
                0x16 -> count = r.u32().toInt()
                0x11, 0x13, in 0x18..0x2b, 0x41 -> f[id] = r.f32()
                0x2d -> { // location data: 28-byte points
                    val n = r.u32().toInt()
                    repeat(n / 28) { track += BandTrackPoint(r.time(), r.f64(), r.f64(), r.f32(), r.f32()) }
                    r.skip(n % 28)
                }
                0x2e -> { // live data: 28-byte samples
                    val n = r.u32().toInt()
                    repeat(n / 28) {
                        val t = r.time(); val hr = r.f32(); val cad = r.f32(); r.skip(8) // count, power
                        val speed = r.f32(); val dist = r.f32()
                        samples += BandExerciseSample(
                            t, hr.within(0f, 300f, loOpen = true), cad.within(0f, 500_000f),
                            speed.within(0f, 140f, loOpen = true), dist.within(0f, 2_000_000f),
                        )
                    }
                    r.skip(n % 28)
                }
                // Length-prefixed strings/blobs: comment, custom type, additional, pace/program ids,
                // pace live data, subset data, HR device, internal live/location data, title.
                0x10, 0x15, 0x30, 0x31, 0x32, 0x33, 0x3a, 0x3d, 0x3e, 0x3f, 0x40, 0x44 -> r.skip(r.u32().toInt())
                0x75 -> r.skip(16)
                0x43 -> r.skip(10) // sensing status: sampling rate + HR sensing (protocol ≥ 3.01)
                0x03, 0x05, 0x97, 0x99, 0x17, 0x2c, in 0x34..0x39, 0x3b, 0x3c -> r.skip(4)
                else -> break
            }
        }
        if (start == 0L) return null
        return BandExerciseRecord(
            start, end, type, duration, f[0x13], f[0x41] ?: f[0x11], f[0x26], f[0x24], f[0x25],
            f[0x1b], f[0x1a], f[0x23], f[0x22], count, samples, track,
        )
    }

    private fun sleep(b: ByteArray, combined: Boolean): BandSleepRecord? {
        val r = Reader(b)
        var start = 0L; var end = 0L
        var eff: Float? = null; var score: Int? = null; var dur: Int? = null; var cycles: Int? = null; var rem: Int? = null
        while (r.remaining > 0) {
            val id = r.u8()
            when {
                id == 0x01 -> start = r.time()
                id == 0x02 -> end = r.time()
                id == 0x28 -> rem = r.u32().toInt()
                id == 0x75 -> r.skip(16)
                id in 0x1c..0x25 -> if (combined && id == 0x23) r.skip(1) else r.skip(2) // factors
                id == 0x03 || id == 0x05 || id == 0x97 || id == 0x99 -> r.skip(4)
                !combined && id == 0x50 -> eff = r.f32()
                !combined && (id == 0x51 || id == 0x56) -> r.skip(1) // has data, result type
                !combined && id == 0x52 -> r.skip(16) // combined id
                !combined && id == 0x53 -> score = r.u8()
                combined && id == 0x11 -> eff = r.f32()
                combined && id == 0x12 -> r.skip(1)
                combined && id == 0x16 -> score = r.u8()
                combined && id == 0x17 -> dur = r.u16()
                combined && id == 0x18 -> cycles = r.u8()
                combined && (id == 0x10 || id in 0x13..0x15 || id in 0x19..0x1b || id == 0x26) -> r.skip(4)
                else -> break
            }
        }
        return if (start > 0) BandSleepRecord(start, end, combined, eff, score, dur, cycles, rem) else null
    }

    /** Sleep stages have no length: exactly 8 fields. */
    private fun sleepStage(r: Reader): BandSleepStage? {
        var start = 0L; var end = 0L; var stage = 0
        repeat(SLEEP_STAGE_FIELDS) {
            when (val f = r.u8()) {
                0x01 -> start = r.time()
                0x02 -> end = r.time()
                0x52 -> stage = r.u32().toInt()
                0x55 -> r.skip(16) // sleep id
                0x03, 0x05, 0x97, 0x99 -> r.skip(4)
                else -> throw IndexOutOfBoundsException("unknown sleep stage field $f")
            }
        }
        return if (start > 0) BandSleepStage(start, end, stage) else null
    }

    private fun steps(r: Reader): BandStepsRecord {
        var start = 0L; var end = 0L; var steps = 0; var run = 0; var walk = 0
        var dist = 0f; var kcal = 0f; var active = 0L
        // Fixed 5-byte fields until the 2-byte version field (0x17) that ends the record.
        while (true) {
            when (val f = r.u8()) {
                0x01 -> start = r.time()
                0x02 -> end = r.time()
                0x03, 0x05, 0x12 -> r.skip(4) // tz offset, update time, speed
                0x10 -> steps = r.u32().toInt()
                0x11 -> dist = r.f32()
                0x13 -> kcal = r.f32()
                0x14 -> run = r.u32().toInt()
                0x15 -> walk = r.u32().toInt()
                0x16 -> active = r.u32()
                0x17 -> { r.skip(1); break }
                else -> throw IndexOutOfBoundsException("unknown steps field $f")
            }
        }
        return BandStepsRecord(start, end, steps, run, walk, dist, kcal, active)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        val remaining get() = b.size - pos
        private fun need(n: Int) { if (n < 0 || pos + n > b.size) throw IndexOutOfBoundsException("need $n at $pos/${b.size}") }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }
        fun u16(): Int { need(2); return u16le(b, pos).also { pos += 2 } }
        fun u32(): Long { need(4); return u32le(b, pos).also { pos += 4 } }
        fun f32(): Float = Float.fromBits(u32().toInt())
        fun f64(): Double { val lo = u32(); return Double.fromBits(lo or (u32() shl 32)) }
        fun skip(n: Int) { need(n); pos += n }
        fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
        /** Seconds since 1990-01-01 UTC → epoch ms; 0 stays 0 (unset). */
        fun time(): Long = u32().let { if (it == 0L) 0L else (it + EPOCH_1990_S) * 1000 }
    }

    companion object {
        const val EPOCH_1990_S = 631_152_000L
        const val ID_SYNC = 2
        const val ID_REQUEST_DATA = 3
        const val ID_LARGE_CHUNK = 63
        const val T_SEQ = 0xe0
        const val T_MSG_INFO = 0x04
        const val T_DEVICE_INFO = 0x05
        const val T_STEPS = 0x07
        const val T_HEART_RATE = 0x0a
        const val T_SLEEP = 0x10
        const val T_SLEEP_STAGE = 0x11
        const val T_SLEEP_DATA = 0x12
        const val T_SPO2 = 0x70
        const val T_EXERCISE = 0x80
        const val T_SLEEP_COMBINED = 0x89
        const val SLEEP_STAGE_FIELDS = 8
    }
}

/** Counters for the Diagnostics screen. */
data class BandDecoderStats(
    val notifications: Int = 0,
    val frames: Int = 0,
    val crcErrors: Int = 0,
    val healthMessages: Int = 0,
    val hrRecords: Int = 0,
    val stepRecords: Int = 0,
    val otherRecords: Int = 0,
    val encryptedFrames: Int = 0,
    val malformed: Int = 0,
)

/** All three layers: feed every SAP notification, get decoded health records back. */
class BandDecoder {
    private val frames = SapFrameAssembler()
    private val messages = SapMessageAssembler()
    private val health = HealthMessageParser()
    var stats = BandDecoderStats()
        private set
    val unknownHealthTypes: Set<Int> get() = health.unknownTypes

    fun onNotification(value: ByteArray): List<BandRecord> {
        val out = mutableListOf<BandRecord>()
        var nFrames = 0; var nHealth = 0
        for (frame in frames.push(value)) {
            nFrames++
            val msg = messages.push(frame) ?: continue
            if (msg.channel != HEALTH_CHANNEL) continue
            nHealth++
            out += health.push(msg.payload)
        }
        stats = stats.copy(
            notifications = stats.notifications + 1,
            frames = stats.frames + nFrames,
            crcErrors = frames.crcErrors,
            healthMessages = stats.healthMessages + nHealth,
            hrRecords = stats.hrRecords + out.count { it is BandHrRecord },
            stepRecords = stats.stepRecords + out.count { it is BandStepsRecord },
            otherRecords = stats.otherRecords + out.count { it !is BandHrRecord && it !is BandStepsRecord },
            encryptedFrames = messages.encryptedFrames,
            malformed = health.malformed,
        )
        return out
    }

    companion object { const val HEALTH_CHANNEL = 0x0a }
}

private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
private fun u32le(b: ByteArray, i: Int): Long =
    (b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or
        ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24)
