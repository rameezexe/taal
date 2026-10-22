package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapBridgeTest {
    private val mLat = 1.0 / 111_195.0
    private val lat0 = 19.0760
    private val lon0 = 72.8777
    private val mLon = mLat / cos(Math.toRadians(lat0))

    /** Running round a loop of [circumference] m at 3 m/s, 3 steps/s (1.0 m stride); position at second [s]. */
    private fun loopFix(s: Long, circumference: Double = 450.0): GeoPoint {
        val r = circumference / (2 * PI)
        val a = 3.0 * s / r
        return GeoPoint(s * 1000, lat0 + r * sin(a) * mLat, lon0 + r * cos(a) * mLon, 5f)
    }

    private fun near(expected: Double, actual: Double, tol: Double) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected ± $tol, was $actual")

    /** 10 min on a 450 m loop with GPS missing between [gapFrom] and [gapTo] seconds. */
    private fun run(withSteps: Boolean, gapFrom: Long = 200, gapTo: Long = 350): RouteTracker {
        val t = RouteTracker()
        for (s in 0L..600L) {
            if (withSteps) t.addSteps(s * 1000, s * 3)
            if (s in gapFrom until gapTo) continue
            t.addPoint(loopFix(s))
        }
        t.finish()
        return t
    }

    @Test fun `without steps a gap on a loop is a straight line`() {
        // 150 s gap = 450 m = one full lap: the straight line is ~0, so the lap is lost.
        near(1350.0, run(withSteps = false).distanceM, 30.0)
    }

    @Test fun `steps bridge the gap with the calibrated stride`() {
        val t = run(withSteps = true)
        near(1.0, assertNotNull(t.calibratedStrideM), 0.03)
        near(1800.0, t.distanceM, 40.0)
        near(450.0, t.bridgedM, 30.0)
    }

    @Test fun `live distance keeps growing during a gap`() {
        val t = RouteTracker()
        for (s in 0L..200L) { t.addSteps(s * 1000, s * 3); t.addPoint(loopFix(s)) }
        val counted = t.distanceM // the last 3 fixes wait for smoothing
        for (s in 201L..260L) t.addSteps(s * 1000, s * 3)
        near(780.0, t.distanceAt(260_000), 8.0) // 260 s × 3 m/s: steps count from the last counted position
        near(counted, t.distanceAt(203_000), 0.1) // short gaps aren't bridged
    }

    @Test fun `standing still during a gap adds nothing`() {
        val t = RouteTracker()
        for (s in 0L..200L) { t.addSteps(s * 1000, s * 3); t.addPoint(loopFix(s)) }
        t.addSteps(300_000, 600) // no steps for 100 s
        t.addPoint(loopFix(200).copy(offsetMs = 300_000))
        t.finish()
        near(600.0, t.distanceM, 6.0)
    }

    @Test fun `no bridging before calibration without a default stride`() {
        val t = RouteTracker()
        for (s in 0L..20L) { t.addSteps(s * 1000, s * 3); t.addPoint(loopFix(s)) } // only ~60 steps measured
        assertNull(t.calibratedStrideM)
        for (s in 21L..120L) t.addSteps(s * 1000, s * 3)
        assertEquals(t.distanceM, t.distanceAt(120_000))
        val withDefault = RouteTracker(defaultStrideM = 1.0)
        for (s in 0L..20L) { withDefault.addSteps(s * 1000, s * 3); withDefault.addPoint(loopFix(s)) }
        for (s in 21L..120L) withDefault.addSteps(s * 1000, s * 3)
        near(360.0, withDefault.distanceAt(120_000), 8.0) // 120 s × 3 m/s
    }

    @Test fun `replay calibrates first so an early gap is bridged too`() {
        val points = (0L..600L).filter { it !in 20L until 120L }.map { loopFix(it) }
        val steps = (0L..600L).map { it * 1000 to it * 3 }
        val replayed = RouteTracker.replay(points, emptyList(), steps = steps)
        near(1800.0, replayed.distanceM, 40.0)
        near(1800.0 - 300.0 + 124.0, RouteTracker.replay(points, emptyList()).distanceM, 30.0) // old: the gap counts as its ~124 m chord
    }

    @Test fun `step timeline from cadence samples`() {
        val samples = (0L..60L step 5).map { it * 1000 to 180 } + (100_000L to 180) // 40 s pause before the last
        val timeline = stepTimelineFromCadence(samples)
        assertEquals(60_000L to 180L, timeline[12])
        assertEquals(180L, timeline.last().second) // nothing added across the pause
    }
}
