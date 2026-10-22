package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTrackerTest {

    // ~1 m of latitude in degrees
    private val mLat = 1.0 / 111_195.0
    private val lat0 = 19.0760
    private val lon0 = 72.8777

    /** A fix [northM] metres north of the origin. */
    private fun fix(t: Long, northM: Double, acc: Float = 5f) = GeoPoint(t, lat0 + northM * mLat, lon0, acc)

    private fun near(expected: Double, actual: Double, tol: Double) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected ± $tol, was $actual")

    @Test fun `haversine matches known distance`() {
        // Mumbai CST -> Gateway of India is ~1.4 km; check 1 degree of latitude instead (exact-ish)
        near(111_195.0, RouteTracker.haversineM(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test fun `steady 3 m per s run gives distance and 1 km splits`() {
        val t = RouteTracker()
        for (s in 0..700) {
            t.addHeartRate(s * 1000L, if (s < 334) 130 else 150)
            t.addPoint(fix(s * 1000L, s * 3.0))
        }
        t.finish()
        near(2_100.0, t.distanceM, 1.0)
        val splits = t.completedSplits()
        assertEquals(2, splits.size)
        near(333_333.0, splits[0].durationMs.toDouble(), 1_500.0) // 1000 m at 3 m/s
        near(333.3, splits[0].paceSecPerKm!!, 2.0)
        assertEquals(130, splits[0].avgBpm)
        assertEquals(150, splits[1].avgBpm)
        val all = t.allSplits()
        assertEquals(3, all.size)
        near(100.0, all[2].distanceM, 1.0)
    }

    @Test fun `inaccurate fixes are dropped`() {
        val t = RouteTracker()
        t.addPoint(fix(0, 0.0))
        assertFalse(t.addPoint(fix(1_000, 3.0, acc = 60f)))
        assertEquals(1, t.points.size)
        assertEquals(1, t.rejectedAccuracy)
        assertEquals(0, t.rejectedJump)
    }

    @Test fun `gps jumps are rejected`() {
        val t = RouteTracker()
        t.addPoint(fix(0, 0.0))
        assertFalse(t.addPoint(fix(1_000, 500.0))) // 500 m in 1 s
        assertTrue(t.addPoint(fix(2_000, 6.0)))
        t.finish()
        near(6.0, t.distanceM, 0.5)
        assertEquals(1, t.rejectedJump)
    }

    @Test fun `standing still does not accumulate jitter`() {
        val t = RouteTracker()
        for (s in 0..600) t.addPoint(fix(s * 1000L, if (s % 2 == 0) 0.0 else 3.0))
        assertEquals(0.0, t.distanceM)
    }

    @Test fun `slow walking still counts once past the step threshold`() {
        val t = RouteTracker()
        for (s in 0..100) t.addPoint(fix(s * 1000L, s * 1.0)) // 1 m/s
        t.finish()
        near(100.0, t.distanceM, 5.0)
    }

    @Test fun `segment break skips movement during pause`() {
        val t = RouteTracker()
        for (s in 0..10) t.addPoint(fix(s * 1000L, s * 3.0))
        t.breakSegment()
        // resume 200 m further on after a long pause
        for (s in 0..10) t.addPoint(fix(100_000 + s * 1000L, 230.0 + s * 3.0))
        t.finish()
        near(60.0, t.distanceM, 1.0)
    }

    @Test fun `current pace over window`() {
        val t = RouteTracker()
        for (s in 0..60) t.addPoint(fix(s * 1000L, s * 2.5)) // 2.5 m/s = 400 s/km
        near(400.0, t.currentPaceSecPerKm()!!, 5.0)
        assertNull(RouteTracker().currentPaceSecPerKm())
    }

    @Test fun `replay reproduces live result`() {
        val live = RouteTracker()
        val pts = (0..500).map { fix(it * 1000L, it * 3.2) }
        val hr = (0..500).map { HrSample(it * 1000L, 140) }
        hr.forEach { live.addHeartRate(it.offsetMs, it.bpm) }
        pts.forEach { live.addPoint(it) }
        live.finish()
        val replayed = RouteTracker.replay(pts, hr)
        assertEquals(live.distanceM, replayed.distanceM)
        assertEquals(live.allSplits(), replayed.allSplits())
    }

    @Test fun `pace formatting`() {
        assertEquals("5:42", formatPace(342.0))
        assertEquals("--:--", formatPace(null))
        assertEquals("--:--", formatPace(99_999.0))
    }

    @Test fun `best effort finds the fastest stretch inside a segment`() {
        val t = RouteTracker()
        // 0-1000 m at 5 m/s (200 s), then 1000-2000 m at 4 m/s (250 s); fix every 10 m worth of time
        var ms = 0L
        var m = 0.0
        while (m < 2000.0) {
            t.addPoint(fix(ms, m))
            val v = if (m < 1000) 5.0 else 4.0
            m += 10.0; ms += (10.0 / v * 1000).toLong()
        }
        t.addPoint(fix(ms, m))
        t.finish()
        near(200_000.0, t.bestEffortMs(1000.0)!!.toDouble(), 500.0)
        assertNull(t.bestEffortMs(5000.0))
    }

    @Test fun `best effort never spans a pause`() {
        val t = RouteTracker()
        for (i in 0..60) t.addPoint(fix(i * 1_000L, i * 10.0)) // 600 m in 60 s
        t.breakSegment()
        for (i in 0..60) t.addPoint(fix(600_000L + i * 1_000L, 600 + i * 10.0))
        assertNull(t.bestEffortMs(1000.0), "each segment is only 600 m")
    }

    @Test fun `pace series follows speed changes and skips pauses`() {
        val t = RouteTracker()
        for (i in 0..120) t.addPoint(fix(i * 1_000L, i * (if (i <= 60) 4.0 else 0.0) + (if (i > 60) 240.0 + (i - 60) * 2.5 else 0.0)))
        t.finish()
        val s = t.paceSeries()
        near(250.0, s.first { it.first == 50_000L }.second, 3.0) // 4 m/s = 4:10 /km
        near(400.0, s.last().second, 5.0) // 2.5 m/s = 6:40 /km
    }

    @Test fun `route pieces split at long gaps and resumes`() {
        val pts = listOf(fix(0, 0.0), fix(1_000, 3.0), fix(30_000, 60.0), fix(31_000, 63.0), fix(40_000, 80.0), fix(41_000, 83.0))
        val pieces = pts.drawablePieces(breaksMs = listOf(35_000))
        assertEquals(listOf(2, 2, 2), pieces.map { it.size })
        assertEquals(1, pts.take(2).drawablePieces().size)
        assertTrue(emptyList<GeoPoint>().drawablePieces().isEmpty())
    }
}
