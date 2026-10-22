package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.assertTrue

/**
 * Distance on two real runs round the same loop (fixtures in resources/runs, metres from an arbitrary origin).
 *
 * Ground truth: the loop is ~405–413 m (averaged path of all laps), and the full-GPS run went round 18.16 times
 * (counted by the angle round the loop's centre, which jitter can't inflate) → 7.35–7.50 km.
 *  - loop-full-gps: 60 min with the screen on, GPS nearly continuous (longest gap 78 s).
 *  - loop-power-saving: 37 min with Samsung Power saving on — GPS off whenever the screen was off, 55 % of the
 *    run without fixes (gaps up to 225 s); 5,966 steps. Estimated from steps × the stride of known laps: 5.25–5.5 km.
 */
class RealRunDistanceTest {
    private val lat0 = 19.0
    private val lon0 = 10.0
    private val mLat = 1.0 / 111_195.0
    private val mLon = mLat / cos(Math.toRadians(lat0))

    private fun lines(name: String) = javaClass.getResource("/runs/$name")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }

    private fun fixes(name: String) = lines("$name.csv").map { l ->
        val (t, e, n, acc) = l.split(",")
        GeoPoint(t.toLong(), lat0 + n.toDouble() * mLat, lon0 + e.toDouble() * mLon, acc.toFloat())
    }

    private fun steps(name: String) = stepTimelineFromCadence(lines("$name-cadence.csv").map { l -> l.split(",").let { it[0].toLong() to it[1].toInt() } })

    /** Turns round the loop's centre: jitter-free lap count for a run that stays on the loop. */
    private fun revolutions(points: List<GeoPoint>): Double {
        val cLat = points.map { it.lat }.sorted()[points.size / 2]
        val cLon = points.map { it.lon }.sorted()[points.size / 2]
        var total = 0.0
        var prev: Double? = null
        for (p in points) {
            val a = atan2((p.lat - cLat) / mLat, (p.lon - cLon) / mLon)
            prev?.let { var d = a - it; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; total += d }
            prev = a
        }
        return abs(total) / (2 * PI)
    }

    private fun within(lo: Double, hi: Double, actual: Double, what: String) =
        assertTrue(actual in lo..hi, "$what: expected $lo..$hi, was ${actual.toInt()}")

    /** Completed laps (one turn each) of [points] whose fixes are all ≤ 2 s apart, as (start, end) times. */
    private fun continuousLaps(points: List<GeoPoint>): List<Pair<Long, Long>> {
        val laps = mutableListOf<Pair<Long, Long>>()
        var start = 0
        for (i in 1 until points.size) if (revolutions(points.subList(start, i + 1)) >= 1.0) {
            if (points.subList(start, i + 1).zipWithNext().all { (a, b) -> b.offsetMs - a.offsetMs <= 2_000 }) laps += points[start].offsetMs to points[i].offsetMs
            start = i
        }
        return laps
    }

    private fun lapLengths(track: List<GeoPoint>, laps: List<Pair<Long, Long>>) = laps.map { (a, b) ->
        track.filter { it.offsetMs in a..b }.zipWithNext().sumOf { (p, q) -> RouteTracker.haversineM(p.lat, p.lon, q.lat, q.lon) }
    }.sorted()

    @Test fun `smoothing removes the zig-zag on continuous laps`() {
        val full = fixes("loop-full-gps")
        val laps = continuousLaps(full)
        assertTrue(laps.size >= 5, "only ${laps.size} continuous laps")
        val raw = lapLengths(full, laps)
        val smooth = lapLengths(RouteTracker.replay(full, emptyList()).smoothedTrack, laps)
        within(400.0, 416.0, smooth[smooth.size / 2], "median smoothed lap")
        assertTrue(raw[raw.size / 2] > smooth[smooth.size / 2] + 4, "raw ${raw[raw.size / 2]} vs smoothed ${smooth[smooth.size / 2]}")
    }

    @Test fun `full gps run`() {
        val full = fixes("loop-full-gps")
        within(18.0, 18.3, revolutions(full), "turns")
        val gpsOnly = RouteTracker.replay(full, emptyList())
        within(7_350.0, 7_650.0, gpsOnly.distanceM, "smoothed GPS") // raw fixes: 7,741
        val withSteps = RouteTracker.replay(full, emptyList(), steps = steps("loop-full-gps"))
        within(0.80, 0.88, withSteps.calibratedStrideM!!, "stride")
        within(7_600.0, 8_000.0, withSteps.distanceM, "with steps across its short gaps") // 19 gaps, 592 s
    }

    @Test fun `screen-off gaps cost less than 4 percent`() {
        // The full run with the fixes removed wherever the power-saving run had a gap (its pattern repeated).
        val full = fixes("loop-full-gps")
        val ps = fixes("loop-power-saving")
        val gaps = ps.zipWithNext().filter { (p, q) -> q.offsetMs - p.offsetMs > 5_000 }.map { (p, q) -> p.offsetMs + 1 until q.offsetMs }
        val period = ps.last().offsetMs
        val dropped = full.filter { f -> gaps.none { f.offsetMs % period in it } }
        assertTrue(dropped.size < full.size * 0.6, "kept ${dropped.size} of ${full.size}")
        val steps = steps("loop-full-gps")
        val reference = RouteTracker.replay(full, emptyList(), steps = steps).distanceM
        val d = RouteTracker.replay(dropped, emptyList(), steps = steps).distanceM
        within(reference * 0.96, reference * 1.04, d, "screen-off simulation")
        assertTrue(RouteTracker.replay(dropped, emptyList()).distanceM < reference * 0.85, "without steps it undercounts")
    }

    @Test fun `power saving run`() {
        val ps = fixes("loop-power-saving")
        within(3_900.0, 4_200.0, RouteTracker.replay(ps, emptyList(), smoothFixes = RouteTracker.LEGACY).distanceM, "as recorded on the day")
        within(5_200.0, 5_700.0, RouteTracker.replay(ps, emptyList(), steps = steps("loop-power-saving")).distanceM, "with steps")
    }

    @Test fun `legacy mode reproduces the distances saved before smoothing`() {
        // Saved on the phone by the v9 migration (raw fixes + steps); old activities must keep showing them.
        within(8_166.0, 8_176.0, RouteTracker.replay(fixes("loop-full-gps"), emptyList(), steps = steps("loop-full-gps"), smoothFixes = RouteTracker.LEGACY).distanceM, "run of 18 Oct")
        within(5_756.0, 5_766.0, RouteTracker.replay(fixes("loop-power-saving"), emptyList(), steps = steps("loop-power-saving"), smoothFixes = RouteTracker.LEGACY).distanceM, "run of 20 Oct")
    }
}
