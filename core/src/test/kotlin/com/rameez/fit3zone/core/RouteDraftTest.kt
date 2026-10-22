package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RouteDraftTest {
    private val lat0 = 19.0
    private val lon0 = 72.8
    private val mLat = 1.0 / 111_195.0
    private val mLon = mLat / cos(Math.toRadians(lat0))
    private fun p(east: Double, north: Double) = RoutePoint(lat0 + north * mLat, lon0 + east * mLon)
    private fun east(q: RoutePoint) = (q.lon - lon0) / mLon
    private fun north(q: RoutePoint) = (q.lat - lat0) / mLat

    /** n × n blocks of 100 m, all residential. */
    private fun grid(n: Int): OsmData {
        fun way(id: Long, cells: List<Pair<Int, Int>>) = OsmWay(
            id, cells.map { (c, r) -> r * 10_000L + c }.toLongArray(),
            cells.map { lat0 + it.second * 100.0 * mLat }.toDoubleArray(), cells.map { lon0 + it.first * 100.0 * mLon }.toDoubleArray(),
            mapOf("highway" to "residential"),
        )
        return OsmData((0..n).map { r -> way(r.toLong(), (0..n).map { it to r }) } + (0..n).map { c -> way(10_000L + c, (0..n).map { c to it }) }, emptyList())
    }

    private val gridGraph = RouteGraph.build(grid(10))

    /** Counts calls so tests can check how much was routed again. */
    private class Counting(val inner: DraftRouter) : DraftRouter {
        var paths = 0
        override fun snap(p: RoutePoint) = inner.snap(p)
        override fun path(from: RoutePoint, to: RoutePoint): List<RoutePoint>? { paths++; return inner.path(from, to) }
    }

    @Test fun `snaps a tap to the nearest point on a path`() {
        val at = assertNotNull(gridGraph.snap(p(150.0, 30.0).lat, p(150.0, 30.0).lon, 150.0))
        assertEquals(30.0, at.distM, 0.5)
        assertEquals(150.0, east(RoutePoint(at.lat, at.lon)), 0.5)
        assertEquals(0.0, north(RoutePoint(at.lat, at.lon)), 0.5)
        assertNull(gridGraph.snap(p(-300.0, -300.0).lat, p(-300.0, -300.0).lon, 150.0))
    }

    @Test fun `route follows the streets between mid-block points`() {
        val a = p(150.0, 10.0)
        val b = p(420.0, 250.0)
        val pts = assertNotNull(gridGraph.route(a.lat, a.lon, b.lat, b.lon, preferParks = false))
        // From (150, 0) to (400, 250) (b snaps to the nearer street x = 400): 500 m on the grid.
        assertEquals(500.0, Route("r", pts).distanceM, 2.0)
        for (q in pts) {
            val onStreet = east(q).let { e -> kotlin.math.abs(e - Math.round(e / 100) * 100) < 0.5 } ||
                north(q).let { n -> kotlin.math.abs(n - Math.round(n / 100) * 100) < 0.5 }
            assertTrue(onStreet, "point (${east(q)}, ${north(q)}) on a street")
        }
        // Both on one street: straight along it, in the direction of travel.
        val same = assertNotNull(gridGraph.route(p(130.0, 0.0).lat, p(130.0, 0.0).lon, p(110.0, 0.0).lat, p(110.0, 0.0).lon, false))
        assertEquals(20.0, Route("s", same).distanceM, 0.5)
        assertTrue(east(same.first()) > east(same.last()))
    }

    @Test fun `draft snaps pieces, undo restores exactly`() {
        val router = GraphRouter(gridGraph, false)
        val d1 = RouteDraft().add(p(5.0, 5.0), true, router)
        val d2 = d1.add(p(305.0, 195.0), true, router)
        assertEquals(WaypointState.SNAPPED, d2.waypoints[1].state)
        assertEquals(PieceKind.SNAPPED, d2.pieces[0].kind)
        assertEquals(500.0, d2.distanceM, 12.0)
        val d3 = d2.add(p(600.0, 600.0), true, router)
        assertSame(d2, d3.undo())
        assertSame(d1, d3.undo().undo())
        assertTrue(!RouteDraft().canUndo)
    }

    @Test fun `moving a waypoint routes only its two pieces`() {
        val counting = Counting(GraphRouter(gridGraph, false))
        var d = RouteDraft()
        for ((x, y) in listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0, 0.0 to 300.0, 0.0 to 600.0)) d = d.add(p(x, y), true, counting)
        counting.paths = 0
        val moved = d.move(2, p(500.0, 300.0), counting)
        assertEquals(2, counting.paths)
        assertSame(d.pieces[0], moved.pieces[0])
        assertSame(d.pieces[3], moved.pieces[3])
        assertEquals(d.distanceM + 400.0, moved.distanceM, 2.0)
        // Removing a middle waypoint joins its neighbours with one new piece.
        counting.paths = 0
        val removed = moved.remove(2, counting)
        assertEquals(1, counting.paths)
        assertEquals(4, removed.waypoints.size)
        assertEquals(3, removed.pieces.size)
        assertEquals(2, removed.remove(0, counting).remove(2, counting).waypoints.size)
    }

    @Test fun `back to start closes the loop, out and back doubles it`() {
        val router = GraphRouter(gridGraph, false)
        val d = RouteDraft().add(p(0.0, 0.0), true, router).add(p(400.0, 0.0), true, router).add(p(400.0, 300.0), true, router)
        val loop = d.closeLoop(router)
        assertTrue(loop.isClosed)
        assertEquals(1400.0, loop.distanceM, 2.0)
        assertSame(loop, loop.closeLoop(router))
        // Dragging the start of a loop moves its finish too; deleting the start opens the loop.
        val moved = loop.move(0, p(0.0, 100.0), router)
        assertTrue(moved.isClosed)
        assertEquals(1400.0, moved.distanceM, 2.0)
        val opened = loop.remove(0, router)
        assertTrue(!opened.isClosed)
        assertEquals(3, opened.waypoints.size)
        val outBack = d.outAndBack()
        assertEquals(2 * d.distanceM, outBack.distanceM, 0.01)
        assertTrue(Route.metres(outBack.points.first(), outBack.points.last()) < 1.0)
        assertEquals(5, outBack.waypoints.size)
        val route = loop.toRoute()
        assertEquals("Route 1.4 km", route.name)
        assertTrue(route.isLoop)
    }

    @Test fun `tap far from paths or in free mode is drawn straight`() {
        val router = GraphRouter(gridGraph, false)
        val d = RouteDraft().add(p(1000.0, 1000.0), true, router).add(p(1300.0, 1000.0), true, router)
        assertEquals(WaypointState.NO_PATH, d.waypoints[1].state)
        assertEquals(PieceKind.STRAIGHT, d.pieces[0].kind)
        val free = RouteDraft().add(p(0.0, 0.0), true, router).add(p(250.0, 250.0), false, router)
        assertEquals(WaypointState.FREE, free.waypoints[1].state)
        assertEquals(353.6, free.distanceM, 1.0)
    }

    @Test fun `pieces wait for map data, then snap on refresh`() {
        var loaded = false
        val waiting = RouteDraft().add(p(5.0, 2.0), true, GraphRouter(null, false) { _, _ -> loaded })
            .add(p(305.0, 2.0), true, GraphRouter(null, false) { _, _ -> loaded })
        assertTrue(waiting.hasPending)
        assertEquals(PieceKind.PENDING, waiting.pieces[0].kind)
        assertSame(waiting, waiting.refresh(GraphRouter(null, false) { _, _ -> false }))
        loaded = true
        val done = waiting.refresh(GraphRouter(gridGraph, false) { _, _ -> loaded })
        assertTrue(!done.hasPending)
        assertEquals(PieceKind.SNAPPED, done.pieces[0].kind)
        assertEquals(300.0, done.distanceM, 0.5)
        // Refreshing isn't an undo step: undo goes to the draft before the last tap.
        assertSame(waiting.undo(), done.undo())
    }

    private val bandra by lazy { Overpass.parse(javaClass.getResource("/osm/bandra-joggers-park.json")!!.readText()) }

    @Test fun `drawn pieces follow real Bandra paths`() {
        val g = RouteGraph.build(bandra)
        val router = GraphRouter(g, true)
        val taps = listOf(19.05946 to 72.82136, 19.0625 to 72.8240, 19.0570 to 72.8260, 19.0555 to 72.8240)
        var d = RouteDraft()
        val t0 = System.nanoTime()
        for ((la, lo) in taps) d = d.add(RoutePoint(la, lo), true, router)
        d = d.closeLoop(router)
        println("Bandra draft: ${"%.2f".format(d.distanceM / 1000)} km, ${d.points.size} points in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(d.pieces.all { it.kind == PieceKind.SNAPPED }, "all snapped: ${d.pieces.map { it.kind }} ${d.waypoints.map { it.state }}")
        // 19.0555, 72.8240 is 45 m from a lone 5 m footway: it snaps to the street network instead.
        // Every point lies on a path, and each piece is no shorter than the straight line between its ends.
        for (q in d.points) assertTrue(assertNotNull(g.snap(q.lat, q.lon, 10.0)).distM < 0.5, "point on a path")
        for (pc in d.pieces) assertTrue(pc.lengthM >= Route.metres(pc.points.first(), pc.points.last()) - 0.5)
        assertTrue(d.isClosed)
    }

    @Test fun `two downloads merged join at the border`() {
        // Split the fixture into a west and an east half (ways crossing the line are in both).
        val line = 72.8225
        val west = OsmData(bandra.ways.filter { it.lons.min() < line }, bandra.parks)
        val east = OsmData(bandra.ways.filter { it.lons.max() >= line }, bandra.parks)
        val whole = RouteGraph.build(bandra)
        val merged = RouteGraph.build(OsmData.merge(listOf(west, east)))
        assertEquals(whole.edgeCount, merged.edgeCount)
        assertEquals(whole.nodeCount, merged.nodeCount)
        val a = RoutePoint(19.05946, 72.82136)
        val b = RoutePoint(19.0570, 72.8260)
        val viaWhole = Route("w", assertNotNull(whole.route(a.lat, a.lon, b.lat, b.lon, false))).distanceM
        val viaMerged = Route("m", assertNotNull(merged.route(a.lat, a.lon, b.lat, b.lon, false))).distanceM
        assertEquals(viaWhole, viaMerged, 0.5)
        // Without merging, the west half alone can't reach far into the east.
        assertTrue(RouteGraph.build(west).snap(19.0570, 72.8290, 150.0) == null || viaMerged > 0)
    }
}
