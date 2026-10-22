package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingsTest {

    private fun reading(at: Long) = HeartRateReading(140, SensorContact.DETECTED, at, 0, ReadingSource.BLE)

    @Test fun `reading becomes stale after timeout`() {
        val f = Freshness(5_000)
        val r = reading(10_000)
        assertTrue(f.isFresh(r, 10_000))
        assertTrue(f.isFresh(r, 15_000))
        assertFalse(f.isFresh(r, 15_001))
        assertFalse(f.isFresh(null, 10_000))
    }

    @Test fun `reading from the future is not fresh`() {
        assertFalse(Freshness(5_000).isFresh(reading(10_000), 9_000))
    }

    @Test fun `backoff doubles and caps then resets`() {
        val b = ReconnectBackoff(1_000, 30_000)
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000), List(7) { b.nextDelayMs() })
        b.reset()
        assertEquals(1_000, b.nextDelayMs())
    }

    @Test fun `backoff never overflows`() {
        val b = ReconnectBackoff(1_000, 30_000)
        repeat(100) { assertEquals(true, b.nextDelayMs() in 1_000..30_000) }
    }

    @Test fun `simulated sequence parsing`() {
        assertEquals(listOf(128, 138, 146, 151, 149, 143, 139), SimulatedSequence.parse(SimulatedSequence.DEFAULT))
        assertEquals(19, SimulatedSequence.parse(SimulatedSequence.ZONE_TOUR).size)
        assertEquals(listOf(120, 130), SimulatedSequence.parse("120, 130"))
        assertFailsWith<IllegalArgumentException> { SimulatedSequence.parse("120 -> abc") }
        assertFailsWith<IllegalArgumentException> { SimulatedSequence.parse("10") }
    }
}

class DuplicateFilterTest {
    private val a = byteArrayOf(0x06, 0x54)
    private val b = byteArrayOf(0x06, 0x55)

    @Test fun `identical packet inside window is duplicate`() {
        val f = DuplicateFilter(250)
        assertFalse(f.isDuplicate(a, 1_000))
        assertTrue(f.isDuplicate(a, 1_100))
    }

    @Test fun `identical packet after window is a new sample`() {
        val f = DuplicateFilter(250)
        assertFalse(f.isDuplicate(a, 1_000))
        assertFalse(f.isDuplicate(a, 2_000)) // steady BPM at 1 Hz must not be dropped
    }

    @Test fun `different packet is never duplicate`() {
        val f = DuplicateFilter(250)
        assertFalse(f.isDuplicate(a, 1_000))
        assertFalse(f.isDuplicate(b, 1_010))
    }
}
