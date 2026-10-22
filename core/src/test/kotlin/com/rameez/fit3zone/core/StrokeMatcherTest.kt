package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrokeMatcherTest {
    private val lat0 = 19.0
    private val lon0 = 72.8
    private val mLat = 1.0 / 111_195.0
    private val mLon = mLat / cos(Math.toRadians(lat0))
    private fun p(east: Double, north: Double) = RoutePoint(lat0 + north * mLat, lon0 + east * mLon)
    private fun east(q: RoutePoint) = (q.lon - lon0) / mLon
    private fun north(q: RoutePoint) = (q.lat - lat0) / mLat

    /** n × n blocks of 100 m. */
    private val grid = RouteGraph.build(OsmData(
        (0..20).map { r -> way(r.toLong(), (0..20).map { it to r }) } + (0..20).map { c -> way(10_000L + c, (0..20).map { c to it }) },
        emptyList(),
    ))

    private fun way(id: Long, cells: List<Pair<Int, Int>>) = OsmWay(
        id, cells.map { (c, r) -> r * 10_000L + c }.toLongArray(),
        cells.map { lat0 + it.second * 100.0 * mLat }.toDoubleArray(), cells.map { lon0 + it.first * 100.0 * mLon }.toDoubleArray(),
        mapOf("highway" to "residential"),
    )

    /** A finger line through [corners] (metres east, north), a point every 5 m, wobbling up to [wobble] m sideways. */
    private fun stroke(corners: List<Pair<Double, Double>>, wobble: Double, seed: Int = 1): List<RoutePoint> {
        val rnd = Random(seed)
        val out = ArrayList<RoutePoint>()
        for (k in 1 until corners.size) {
            val (x1, y1) = corners[k - 1]
            val (x2, y2) = corners[k]
            val n = (Math.hypot(x2 - x1, y2 - y1) / 5).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val f = i.toDouble() / n
                val w = wobble * sin(i / 7.0 + rnd.nextDouble())
                val nx = -(y2 - y1) / Math.hypot(x2 - x1, y2 - y1)
                val ny = (x2 - x1) / Math.hypot(x2 - x1, y2 - y1)
                out += p(x1 + (x2 - x1) * f + nx * w, y1 + (y2 - y1) * f + ny * w)
            }
        }
        out += p(corners.last().first, corners.last().second)
        return out
    }

    private fun draft(points: List<MatchedPoint>, g: RouteGraph = grid) = RouteDraft().addMatched(points, GraphRouter(g, false))

    @Test fun `wobbly line along a street stays on it`() {
        val m = StrokeMatcher.match(grid, stroke(listOf(0.0 to 310.0, 800.0 to 290.0), wobble = 30.0), toleranceM = 40.0, preferParks = false)
        val d = draft(m)
        assertEquals(800.0, d.distanceM, 5.0)
        for (q in d.points) assertEquals(300.0, north(q), 1.0, "on the street y = 300")
        assertTrue(m.size <= 3, "thinned to few waypoints: ${m.size}")
        assertTrue(d.pieces.all { it.kind == PieceKind.SNAPPED })
    }

    @Test fun `rough L follows the streets without spurs`() {
        // Drawn a bit off the streets and cutting the corner.
        val line = stroke(listOf(105.0 to 120.0, 690.0 to 90.0, 720.0 to 140.0, 710.0 to 690.0), wobble = 20.0, seed = 3)
        val d = draft(StrokeMatcher.match(grid, line, toleranceM = 40.0, preferParks = false))
        assertEquals(595.0 + 590.0, d.distanceM, 5.0)
        assertTrue(LoopPlanner.doubledShare(d.toRoute()) < 0.02, "no out-and-back spurs: ${LoopPlanner.doubledShare(d.toRoute())}")
        // Ends snap to the nearest street: (105, 100) and (700, 690).
        assertEquals(100.0, north(d.points.first()), 1.0)
        assertEquals(700.0, east(d.points.last()), 1.0)
    }

    @Test fun `line over open ground keeps the drawn points`() {
        // Starts on the grid, then heads far outside it (no paths there).
        val line = stroke(listOf(1900.0 to 1000.0, 2000.0 to 1000.0, 3000.0 to 1000.0), wobble = 0.0)
        val m = StrokeMatcher.match(grid, line, toleranceM = 40.0, preferParks = false)
        assertTrue(m.first().onPath)
        assertTrue(!m.last().onPath)
        val d = draft(m)
        assertTrue(d.pieces.any { it.kind == PieceKind.STRAIGHT })
        assertEquals(1100.0, d.distanceM, 30.0)
    }

    @Test fun `resamples evenly`() {
        val pts = StrokeMatcher.resample(listOf(p(0.0, 0.0), p(95.0, 0.0), p(95.0, 105.0)), 20.0)
        assertEquals(11, pts.size)
        // 20 m apart along the line (a chord across the corner is shorter).
        for (i in 1 until pts.size - 1) assertTrue(Route.metres(pts[i - 1], pts[i]) in 14.0..20.5)
    }

    @Test fun `scribble over real Bandra paths`() {
        val g = RouteGraph.build(Overpass.parse(javaClass.getResource("/osm/bandra-joggers-park.json")!!.readText()))
        // A route along real paths, then "drawn" over it by a shaky finger (±20 m).
        val router = GraphRouter(g, false)
        var truth = RouteDraft()
        for ((la, lo) in listOf(19.05946 to 72.82136, 19.0625 to 72.8240, 19.0570 to 72.8260)) truth = truth.add(RoutePoint(la, lo), true, router)
        val rnd = Random(7)
        val kx = 1.0 / (111_195.0 * cos(Math.toRadians(19.06)))
        val shaky = StrokeMatcher.resample(truth.points, 5.0).mapIndexed { i, q ->
            val w = 20.0 * sin(i / 9.0) + rnd.nextDouble(-4.0, 4.0)
            RoutePoint(q.lat + w / 111_195.0 * 0.7, q.lon + w * kx * 0.7)
        }
        val t0 = System.nanoTime()
        val m = StrokeMatcher.match(g, shaky, toleranceM = 35.0, preferParks = false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val d = RouteDraft().addMatched(m, router)
        println("Bandra scribble: truth ${"%.0f".format(truth.distanceM)} m, matched ${"%.0f".format(d.distanceM)} m, ${m.size} waypoints, $ms ms")
        assertTrue(m.all { it.onPath })
        assertTrue(abs(d.distanceM / truth.distanceM - 1) < 0.05, "length ${d.distanceM} vs ${truth.distanceM}")
        // Most of the matched route lies on the true one.
        val truthRoute = truth.toRoute()
        val samples = StrokeMatcher.resample(d.points, 10.0)
        val near = samples.count { s -> StrokeMatcher.resample(truthRoute.points, 5.0).any { Route.metres(it, s) < 8 } }
        assertTrue(near >= samples.size * 0.95, "on the true route: $near / ${samples.size}")
    }
}
