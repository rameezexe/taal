package com.rameez.fit3zone.core

import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrainingStatsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int = 7) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private fun act(id: Long, start: Long, below: Long, inZ: Long, above: Long, distM: Double = 0.0) =
        ActivityRecord(id, start, (below + inZ + above) * min, below * min, inZ * min, above * min, distM)

    @Test fun `load weights zones 1-2-4`() {
        assertEquals(10 + 2 * 30 + 4 * 5, TrainingStats.load(act(1, 0, 10, 30, 5)))
    }

    @Test fun `load is edwards TRIMP when zone times exist`() {
        val a = act(1, 0, 0, 0, 0).copy(zoneMs = listOf(5, 10, 20, 10, 5, 2).map { it * min })
        assertEquals(10 + 40 + 30 + 20 + 10, TrainingStats.load(a))
    }

    @Test fun `weekly sums zone times`() {
        val a = act(1, at(2026, 10, 13), 0, 0, 0).copy(zoneMs = listOf(1L, 2, 3, 4, 5, 6))
        val b = act(2, at(2026, 10, 14), 0, 0, 0) // old session without zones
        val w = TrainingStats.weekly(listOf(a, b), at(2026, 10, 14), zone, weeks = 1).single()
        assertEquals(listOf(1L, 2, 3, 4, 5, 6), w.zoneMs)
    }

    @Test fun `weeks start on monday in local time`() {
        // Wed 14 Oct 2026 -> Mon 12 Oct 2026
        assertEquals(LocalDate.of(2026, 10, 12), TrainingStats.weekStart(at(2026, 10, 14), zone))
        // Sunday 00:30 IST still belongs to the previous week
        assertEquals(LocalDate.of(2026, 10, 12), TrainingStats.weekStart(LocalDateTime.of(2026, 10, 18, 0, 30).atZone(zone).toInstant().toEpochMilli(), zone))
    }

    @Test fun `weekly buckets include empty weeks oldest first`() {
        val acts = listOf(
            act(1, at(2026, 10, 13), 5, 40, 2, 6_000.0),
            act(2, at(2026, 10, 14), 0, 30, 0, 5_000.0),
            act(3, at(2026, 9, 29), 10, 20, 10),
        )
        val weeks = TrainingStats.weekly(acts, at(2026, 10, 14), zone, weeks = 4)
        assertEquals(listOf(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)), weeks.map { it.weekStart })
        assertEquals(listOf(0, 1, 0, 2), weeks.map { it.activities })
        assertEquals(70 * min, weeks[3].inTargetMs)
        assertEquals(11_000.0, weeks[3].distanceM)
    }

    @Test fun `personal records`() {
        val a = act(1, 0, 0, 60, 0, 10_000.0)  // 6:00/km
        val b = act(2, 0, 0, 20, 5, 5_000.0)   // 25 min / 5 km = 5:00/km
        val c = act(3, 0, 30, 0, 50, 0.0)      // no GPS, longest
        val r = TrainingStats.records(listOf(a, b, c))
        assertEquals(3, r.longestDuration?.id)
        assertEquals(1, r.longestDistance?.id)
        assertEquals(1, r.mostInTarget?.id)
        assertEquals(2, r.fastestAvgPace?.first?.id)
        assertEquals(300.0, r.fastestAvgPace?.second)
    }

    @Test fun `records are empty without activities`() {
        assertNull(TrainingStats.records(emptyList()).longestDuration)
    }

    @Test fun `calendar marks days with zone 2 share`() {
        val acts = listOf(act(1, at(2026, 10, 3), 0, 30, 10), act(2, at(2026, 10, 3, 18), 0, 30, 30), act(3, at(2026, 9, 30), 0, 10, 0))
        val cal = TrainingStats.calendar(acts, YearMonth.of(2026, 10), zone)
        assertEquals(mapOf(LocalDate.of(2026, 10, 3) to 60), cal)
    }

    @Test fun `calendar marks free-run-only days with -1`() {
        val cal = TrainingStats.calendar(listOf(act(1, at(2026, 10, 5), 0, 0, 0)), YearMonth.of(2026, 10), zone)
        assertEquals(mapOf(LocalDate.of(2026, 10, 5) to -1), cal)
    }
}
