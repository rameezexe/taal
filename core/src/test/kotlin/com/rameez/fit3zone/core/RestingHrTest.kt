package com.rameez.fit3zone.core

import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestingHrTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun minute(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    /** A full day: 70 bpm awake, a night from 01:00–07:00 at [night] with one 5-minute dip to 40 (artefact). */
    private fun day(d: Int, night: Int) = (0 until 24 * 60).map { i ->
        val h = i / 60
        val bpm = when {
            h in 1..6 && i % 60 in 10..14 && h == 3 -> 40
            h in 1..6 -> night
            else -> 70
        }
        minute(d, h, i % 60) to bpm
    }

    @Test fun `lowest 30 minute average ignores short dips`() {
        val r = BandRestingHr.daily(day(15, 52), zone)
        val v = r.getValue(LocalDate.of(2026, 10, 15))
        assertTrue(v in 50..52, "was $v") // a 5-min dip to 40 only lowers one window by ~2
    }

    @Test fun `days with too little data are skipped`() {
        val sparse = day(15, 52).filterIndexed { i, _ -> i % 5 == 0 } // 288 minutes
        assertTrue(BandRestingHr.daily(sparse, zone).isEmpty())
    }

    @Test fun `estimate is the median of recent days`() {
        val daily = mapOf(
            LocalDate.of(2026, 10, 1) to 70, // older than 14 days
            LocalDate.of(2026, 10, 14) to 50,
            LocalDate.of(2026, 10, 15) to 49,
            LocalDate.of(2026, 10, 16) to 60,
        )
        val e = BandRestingHr.estimate(daily, LocalDate.of(2026, 10, 18))!!
        assertEquals(50, e.bpm)
        assertEquals(3, e.daysUsed)
        assertEquals(LocalDate.of(2026, 10, 16), e.latestDay)
        assertNull(BandRestingHr.estimate(emptyMap(), LocalDate.of(2026, 10, 18)))
    }
}
