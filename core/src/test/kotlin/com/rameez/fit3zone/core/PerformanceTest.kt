package com.rameez.fit3zone.core

import org.junit.Test
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceTest {
    private val min = 60_000L
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun near(expected: Double, actual: Double, tol: Double) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected ± $tol, was $actual")

    private fun zoned(id: Long, start: Long, vararg zMin: Long, dist: Double = 0.0, bpm: Int? = null) =
        ActivityRecord(id, start, zMin.sum() * min, 0, 0, 0, dist, zMin.map { it * min }, avgBpm = bpm)

    @Test fun `aerobic effect grows with load`() {
        val easy = Performance.trainingEffect(zoned(1, 0, 0, 20, 0, 0, 0, 0)) // 20 min Z1 = 20
        val z2 = Performance.trainingEffect(zoned(1, 0, 0, 0, 40, 0, 0, 0)) // 40 min Z2 = 80
        val hard = Performance.trainingEffect(zoned(1, 0, 0, 0, 0, 60, 0, 0)) // 60 min Z3 = 180
        near(1.03, easy.aerobic, 0.1)
        near(3.0, z2.aerobic, 0.05)
        near(4.4, hard.aerobic, 0.1)
        assertEquals(0.0, z2.anaerobic)
        assertEquals("Improving", Performance.effectLabel(z2.aerobic))
    }

    @Test fun `anaerobic effect comes from Z5 and some Z4`() {
        val intervals = Performance.trainingEffect(zoned(1, 0, 10, 10, 5, 5, 8, 4)) // Z5 4 + Z4 8/4 = 6 min
        near(3.2, intervals.anaerobic, 0.05)
    }

    @Test fun `recovery hours and remaining`() {
        assertEquals(20, Performance.recoveryHours(TrainingEffect(2.5, 0.0)))
        assertEquals("Maintaining", Performance.effectLabel(2.5))
        assertEquals(96, Performance.recoveryHours(TrainingEffect(5.0, 4.0), 1.5))
        val run = zoned(1, now - 10 * 3_600_000L - 30 * min, 0, 0, 30, 0, 0, 0) // 30 min Z2 → TE 2.5 → 20 h; ended 10 h ago
        assertEquals(10, Performance.recoveryRemainingHours(listOf(run), now))
        assertEquals(0, Performance.recoveryRemainingHours(listOf(run), now + day))
    }

    @Test fun `load ratio needs two weeks then bands`() {
        val young = listOf(zoned(1, now - 3 * day, 0, 0, 50, 0, 0, 0))
        assertEquals(LoadBand.BASELINE, Performance.loadStatus(young, now).band)
        // 100 load per week for 4 weeks → acute 100, chronic 100, ratio 1.0
        val steady = (0 until 4).map { w -> zoned(w.toLong(), now - (w * 7 + 1) * day, 0, 0, 50, 0, 0, 0) }
        val s = Performance.loadStatus(steady, now)
        assertEquals(100, s.acute); assertEquals(100, s.chronicWeekly); assertEquals(1.0, s.ratio)
        assertEquals(LoadBand.OPTIMAL, s.band)
        // Big week on top → ratio (100+200)/((400+200)/4) = 2.0
        val spike = steady + zoned(9, now - 2 * day, 0, 0, 0, 0, 50, 0)
        assertEquals(LoadBand.VERY_HIGH, Performance.loadStatus(spike, now).band)
    }

    @Test fun `daily loads cover the window`() {
        val loads = Performance.dailyLoads(listOf(zoned(1, now - day, 0, 0, 10, 0, 0, 0)), now, zone, days = 7)
        assertEquals(7, loads.size)
        assertEquals(20, loads[5].second)
        assertEquals(20, loads.sumOf { it.second })
    }

    @Test fun `intensity minutes count vigorous double`() {
        assertEquals(10 + 2 * 30, Performance.intensityMinutes(zoned(1, 0, 5, 10, 20, 10, 0, 0)))
        val old = ActivityRecord(2, 0, 30 * min, 5 * min, 20 * min, 5 * min, 0.0)
        assertEquals(25, Performance.intensityMinutes(old))
    }

    @Test fun `vo2max from heart rate reserve`() {
        // 5 km in 25:00 (200 m/min → VO2 43.5) at 70 % HRR (rest 60, max 190 → 151 bpm) → 3.5 + 40/0.7 = 60.6
        near(60.6, Performance.vo2maxFromRun(5_000.0, 25 * min, 151, 190, 60)!!, 0.1)
        // without resting HR: 80 % HRmax → (0.8-0.37)/0.64 = 67 % VO2max → 43.5 / 0.672 = 64.7
        near(64.7, Performance.vo2maxFromRun(5_000.0, 25 * min, 152, 190, null)!!, 0.2)
        assertNull(Performance.vo2maxFromRun(1_000.0, 25 * min, 151, 190, 60), "too short")
        assertNull(Performance.vo2maxFromRun(5_000.0, 25 * min, 110, 190, 60), "too easy")
        assertNull(Performance.vo2maxFromRun(3_000.0, 40 * min, 150, 190, 60), "walking speed")
    }

    @Test fun `vo2max estimate averages recent runs only`() {
        val runs = listOf(
            ActivityRecord(1, now - 200 * day, 25 * min, 0, 0, 0, 5_000.0, avgBpm = 151), // too old
            ActivityRecord(2, now - 10 * day, 25 * min, 0, 0, 0, 5_000.0, avgBpm = 151),
            ActivityRecord(3, now - 5 * day, 25 * min, 0, 0, 0, 5_000.0, avgBpm = 151, type = ActivityType.WALK),
        )
        val e = assertNotNull(Performance.vo2maxEstimate(runs, now, 190, 60))
        assertEquals(1, e.runsUsed)
        assertEquals(2, e.history.size)
    }

    @Test fun `daniels race times match the VDOT tables`() {
        // Daniels' Running Formula, VDOT 50: 5K 19:57, 10K 41:21, marathon 3:10:49
        near(19 * 60 + 57.0, Performance.predictRaceSec(50.0, 5_000.0), 10.0)
        near(41 * 60 + 21.0, Performance.predictRaceSec(50.0, 10_000.0), 20.0)
        near(3 * 3600 + 10 * 60 + 49.0, Performance.predictRaceSec(50.0, 42_195.0), 90.0)
        assertEquals("19:57", formatRaceTime(1197.0))
        assertEquals("3:10:49", formatRaceTime(11449.0))
    }

    @Test fun `riegel scales with exponent 1_06`() {
        near(1200 * Math.pow(2.0, 1.06), Performance.riegelSec(1200.0, 5_000.0, 10_000.0), 0.01)
    }
}
