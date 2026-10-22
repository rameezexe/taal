package com.rameez.fit3zone.core

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoachTest {
    private val min = 60_000L
    private val day = 86_400_000L
    private val zone = ZoneId.of("Asia/Kolkata")
    /** Wednesday 21 Oct 2026, 08:00. */
    private val now = LocalDateTime.of(2026, 10, 21, 8, 0).atZone(zone).toInstant().toEpochMilli()

    private fun zoned(id: Long, start: Long, vararg zMin: Long, dist: Double = 0.0) =
        ActivityRecord(id, start, zMin.sum() * min, 0, 0, 0, dist, zMin.map { it * min })

    private fun easy(id: Long, start: Long) = zoned(id, start, 5, 10, 25, 0, 0, 0)

    /** Easy runs every other day for four weeks before [now], so the load ratio has a baseline. */
    private fun base(): List<ActivityRecord> = (30 downTo 5 step 2).mapIndexed { i, d -> easy(i.toLong(), now - d * day) }

    @Test fun `week comparison uses last week up to the same moment`() {
        val acts = listOf(
            zoned(1, now - 1 * day, 0, 0, 30, 0, 0, 0, dist = 5_000.0), // Tue this week
            zoned(2, now - 8 * day, 0, 0, 20, 0, 0, 0, dist = 3_000.0), // Tue last week
            zoned(3, now - 4 * day, 0, 0, 40, 0, 0, 0, dist = 7_000.0), // Sat last week (after "same point")
        )
        val c = Coach.weekComparison(acts, now, zone)
        assertEquals(1, c.thisWeek.activities)
        assertEquals(5_000.0, c.thisWeek.distanceM)
        assertEquals(60, c.thisWeek.intensityMin)
        assertEquals(setOf(1), c.thisWeek.activeDays)
        assertEquals(1, c.lastWeekSoFar.activities)
        assertEquals(2, c.lastWeekTotal.activities)
        assertEquals(10_000.0, c.lastWeekTotal.distanceM)
    }

    @Test fun `nothing suggested after training today`() {
        assertNull(Coach.suggest(base() + easy(99, now - 60 * min), now, zone))
    }

    @Test fun `no baseline means easy`() {
        val s = Coach.suggest(listOf(easy(1, now - 3 * day)), now, zone)!!
        assertEquals(SuggestionKind.EASY, s.kind)
        assertEquals(Coach.EASY, s.workoutName)
    }

    @Test fun `long recovery means rest`() {
        val brutal = zoned(1, now - 10 * 60 * min, 5, 10, 10, 20, 20, 15)
        assertEquals(SuggestionKind.REST, Coach.suggest(base() + brutal, now, zone)!!.kind)
    }

    @Test fun `hard day within 48 h means easy`() {
        val intervals = zoned(1, now - 40 * 60 * min, 10, 10, 2, 0, 16, 0)
        val s = Coach.suggest(base() + intervals, now - 0, zone)!!
        assertEquals(SuggestionKind.EASY, s.kind)
    }

    @Test fun `recovered after only easy running gets a quality day`() {
        val s = Coach.suggest(base(), now, zone)!!
        assertEquals(true, s.kind == SuggestionKind.TEMPO || s.kind == SuggestionKind.HARD)
    }

    @Test fun `quality days alternate`() {
        val tempo = zoned(1, now - 4 * day, 10, 5, 5, 20, 0, 0)
        assertEquals(SuggestionKind.HARD, Coach.suggest(base() + tempo, now, zone)!!.kind)
        val intervals = zoned(2, now - 4 * day, 10, 10, 2, 0, 16, 0)
        assertEquals(SuggestionKind.TEMPO, Coach.suggest(base() + intervals, now, zone)!!.kind)
    }
}
