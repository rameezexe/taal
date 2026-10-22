package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacerTest {
    private val min = 60_000L
    /** 23 km in 3:10 → 8:15.65 /km. */
    private val goal = PaceGoal(23_000.0, 190 * min)

    @Test fun `goal pace, encode and time parsing`() {
        assertEquals(495.65, goal.paceSecPerKm, 0.01)
        assertEquals(goal, PaceGoal.decode(goal.encode()))
        assertNull(PaceGoal.decode("garbage"))
        assertEquals(190 * min, PaceGoal.parseTime("3:10"))
        assertEquals(190 * min + 30_000, PaceGoal.parseTime("3:10:30"))
        assertEquals(45 * min, PaceGoal.parseTime("45"))
        assertNull(PaceGoal.parseTime("3:75"))
        assertNull(PaceGoal.parseTime("abc"))
        assertNull(PaceGoal.parseTime("0:00"))
    }

    @Test fun `ahead, behind, needed pace and projection`() {
        // 10 km at 8:00 /km = 80 min; schedule says 82:36 → about 2:36 ahead.
        val ahead = PacerStatus(goal, 10_000.0, 80 * min)
        assertEquals(156_522L, ahead.aheadMs, 1_000L.toLong())
        assertEquals(13_000.0, ahead.remainingM)
        assertEquals(110 * 60.0 / 13, ahead.neededPaceSecPerKm!!, 0.01) // 110 min left over 13 km
        assertEquals(184 * min, ahead.projectedFinishMs) // 23 × 8:00
        val behind = PacerStatus(goal, 10_000.0, 85 * min)
        assertTrue(behind.aheadMs < 0)
        assertNull(PacerStatus(goal, 100.0, 30_000).projectedFinishMs)
        assertNull(PacerStatus(goal, 20_000.0, 191 * min).neededPaceSecPerKm) // goal time already gone
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Long) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")

    @Test fun `cues at each km, halfway, last km and finish`() {
        val cues = PacerCues(goal)
        val pace = goal.paceSecPerKm * 1000 // ms per km, exactly on schedule
        val said = mutableListOf<String>()
        var d = 0.0
        while (d <= 23_100.0) {
            said += cues.onTick(PacerStatus(goal, d, (d / 1000 * pace).toLong()))
            d += 50.0
        }
        assertEquals("Pacer: On pace.", said.first())
        assertTrue(said.any { it.startsWith("Halfway. 11.5 kilometres to go. On pace. Projected finish 3 hours 10 minutes") }, said.toString())
        assertTrue(said.any { it.startsWith("One kilometre to go.") })
        assertTrue(said.last().startsWith("Goal distance done. 23 kilometres in 3 hours 10 minutes"), said.last())
        assertTrue(said.last().contains("Right on your goal time."))
        // 21 km cues + halfway; the 22 km mark is the one-to-go cue; the finish replaces the 23 km cue.
        assertEquals(21 + 1 + 1 + 1, said.size)
        assertTrue(cues.finished)
        assertTrue(cues.onTick(PacerStatus(goal, 23_500.0, 200 * min)).isEmpty())
    }

    @Test fun `behind says the needed pace, ahead rounds to 5 s`() {
        val cues = PacerCues(goal)
        val behind = PacerStatus(goal, 5_000.0, 5 * 515_000L) // 8:35 /km
        assertEquals("1 minute 35 seconds behind. Needed pace 8 minutes 10 per kilometre.", cues.status(behind))
        val ahead = PacerStatus(goal, 5_000.0, 5 * 492_000L)
        assertEquals("20 seconds ahead.", cues.status(ahead))
    }
}
