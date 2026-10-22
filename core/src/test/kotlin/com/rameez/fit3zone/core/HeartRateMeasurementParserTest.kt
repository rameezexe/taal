package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HeartRateMeasurementParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun valid(vararg v: Int) =
        assertIs<ParseResult.Valid>(HeartRateMeasurementParser.parse(bytes(*v))).measurement

    @Test fun `Fit3 style packet 0x06 0x54 is 84 bpm with contact`() {
        val m = valid(0x06, 0x54)
        assertEquals(84, m.bpm)
        assertEquals(SensorContact.DETECTED, m.contact)
        assertNull(m.energyExpendedKj)
        assertEquals(emptyList(), m.rrIntervalsMs)
    }

    @Test fun `uint8 bpm above 127 is unsigned`() {
        assertEquals(200, valid(0x00, 0xC8).bpm)
    }

    @Test fun `uint16 bpm little endian`() {
        val m = valid(0x01, 0x2C, 0x01) // 300 — parser decodes, plausibility rejects
        assertEquals(300, m.bpm)
        assertEquals(151, valid(0x01, 0x97, 0x00).bpm)
    }

    @Test fun `contact states`() {
        assertEquals(SensorContact.NOT_SUPPORTED, valid(0x00, 70).contact)
        assertEquals(SensorContact.NOT_SUPPORTED, valid(0x02, 70).contact) // bit1 without bit2 means unsupported
        assertEquals(SensorContact.NOT_DETECTED, valid(0x04, 70).contact)
        assertEquals(SensorContact.DETECTED, valid(0x06, 70).contact)
    }

    @Test fun `energy and rr intervals`() {
        // flags: energy + rr, bpm 72, energy 0x0102 = 258 kJ, RR 1024 -> 1000 ms, 512 -> 500 ms
        val m = valid(0x18, 72, 0x02, 0x01, 0x00, 0x04, 0x00, 0x02)
        assertEquals(72, m.bpm)
        assertEquals(258, m.energyExpendedKj)
        assertEquals(listOf(1000, 500), m.rrIntervalsMs)
    }

    @Test fun `uint16 bpm with rr`() {
        val m = valid(0x11, 0x8C, 0x00, 0x00, 0x04)
        assertEquals(140, m.bpm)
        assertEquals(listOf(1000), m.rrIntervalsMs)
    }

    @Test fun `invalid and truncated packets`() {
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(null))
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(bytes()))
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(bytes(0x06)))
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(bytes(0x01, 0x50)))
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(bytes(0x08, 70, 0x01)))
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(bytes(0x10, 70)))
        assertIs<ParseResult.Invalid>(HeartRateMeasurementParser.parse(bytes(0x10, 70, 0x00, 0x04, 0x01)))
    }

    @Test fun `plausibility rejects no contact zero and out of range`() {
        assertEquals("no skin contact", HeartRatePlausibility.rejectionReason(valid(0x04, 80)))
        assertEquals("BPM 0 (sensor acquiring)", HeartRatePlausibility.rejectionReason(valid(0x06, 0)))
        assertEquals(true, HeartRatePlausibility.rejectionReason(valid(0x06, 20))?.contains("out of range"))
        assertEquals(true, HeartRatePlausibility.rejectionReason(valid(0x01, 0x2C, 0x01))?.contains("out of range"))
        assertNull(HeartRatePlausibility.rejectionReason(valid(0x06, 151)))
        assertNull(HeartRatePlausibility.rejectionReason(valid(0x00, 151)))
    }
}
