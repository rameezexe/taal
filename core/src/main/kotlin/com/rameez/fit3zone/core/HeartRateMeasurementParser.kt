package com.rameez.fit3zone.core

/**
 * Decoder for the Bluetooth SIG Heart Rate Measurement characteristic (0x2A37).
 *
 * Layout (all multi-byte fields little-endian):
 *   byte 0      flags
 *                 bit 0    0 = BPM is uint8, 1 = BPM is uint16
 *                 bits 1-2 sensor contact: 0b0x = not supported, 0b10 = not detected, 0b11 = detected
 *                 bit 3    Energy Expended (uint16, kJ) present
 *                 bit 4    one or more RR intervals (uint16, 1/1024 s) present
 *   byte 1..    BPM, then optional Energy Expended, then RR intervals
 *
 * The Galaxy Fit3 is reported (Fit3Free README, not yet verified here) to send flags 0x06:
 * uint8 BPM, contact supported and detected, no energy, no RR.
 */
object HeartRateMeasurementParser {

    private const val FLAG_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10

    fun parse(data: ByteArray?): ParseResult {
        if (data == null || data.isEmpty()) return ParseResult.Invalid("empty packet")
        val flags = data[0].toInt() and 0xFF
        var offset = 1

        val bpm: Int
        if (flags and FLAG_UINT16 != 0) {
            if (data.size < offset + 2) return ParseResult.Invalid("truncated uint16 BPM")
            bpm = u16(data, offset)
            offset += 2
        } else {
            if (data.size < offset + 1) return ParseResult.Invalid("truncated uint8 BPM")
            bpm = data[offset].toInt() and 0xFF
            offset += 1
        }

        val contact = when {
            flags and FLAG_CONTACT_SUPPORTED == 0 -> SensorContact.NOT_SUPPORTED
            flags and FLAG_CONTACT_DETECTED != 0 -> SensorContact.DETECTED
            else -> SensorContact.NOT_DETECTED
        }

        var energy: Int? = null
        if (flags and FLAG_ENERGY != 0) {
            if (data.size < offset + 2) return ParseResult.Invalid("truncated energy expended")
            energy = u16(data, offset)
            offset += 2
        }

        val rr = mutableListOf<Int>()
        if (flags and FLAG_RR != 0) {
            val remaining = data.size - offset
            if (remaining < 2 || remaining % 2 != 0) return ParseResult.Invalid("truncated RR intervals")
            while (offset + 1 < data.size) {
                // 1/1024 s units -> milliseconds
                rr += (u16(data, offset) * 1000L / 1024L).toInt()
                offset += 2
            }
        }

        return ParseResult.Valid(
            HeartRateMeasurement(
                bpm = bpm,
                contact = contact,
                energyExpendedKj = energy,
                rrIntervalsMs = rr,
                flags = flags,
            )
        )
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

enum class SensorContact { NOT_SUPPORTED, NOT_DETECTED, DETECTED }

data class HeartRateMeasurement(
    val bpm: Int,
    val contact: SensorContact,
    val energyExpendedKj: Int?,
    val rrIntervalsMs: List<Int>,
    val flags: Int,
)

sealed interface ParseResult {
    data class Valid(val measurement: HeartRateMeasurement) : ParseResult
    data class Invalid(val reason: String) : ParseResult
}

/**
 * Decides whether a well-formed packet is usable as a heart-rate reading.
 * A packet can parse correctly and still be unusable (band off-wrist, BPM 0 while acquiring).
 */
object HeartRatePlausibility {
    const val MIN_BPM = 25
    const val MAX_BPM = 250

    fun rejectionReason(m: HeartRateMeasurement): String? = when {
        m.contact == SensorContact.NOT_DETECTED -> "no skin contact"
        m.bpm == 0 -> "BPM 0 (sensor acquiring)"
        m.bpm < MIN_BPM || m.bpm > MAX_BPM -> "BPM ${m.bpm} out of range $MIN_BPM..$MAX_BPM"
        else -> null
    }
}
