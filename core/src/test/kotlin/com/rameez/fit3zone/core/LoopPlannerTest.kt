package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopPlannerTest {
    private val lat0 = 19.0
    private val lon0 = 72.8
    private val mLat = 1.0 / 111_195.0
    private val mLon = mLat / cos(Math.toRadians(lat0))
    private fun lat(north: Double) = lat0 + north * mLat
    private fun lon(east: Double) = lon0 + east * mLon

    /** A way through grid points (col, row) of a [spacing] m grid; node id = row * 10 000 + col + [idBase]. */
    private fun way(id: Long, cells: List<Pair<Int, Int>>, highway: String = "residential", spacing: Double = 100.0, idBase: Long = 0) =
        OsmWay(
            id, cells.map { (c, r) -> idBase + r * 10_000L + c }.toLongArray(),
            cells.map { lat(it.second * spacing) }.toDoubleArray(), cells.map { lon(it.first * spacing) }.toDoubleArray(),
            mapOf("highway" to highway),
        )

    /** n × n blocks of 100 m; every 10th street is a primary road. */
    private fun grid(n: Int, highway: (Int) -> String = { if (it % 10 == 5) "primary" else "residential" }): List<OsmWay> =
        (0..n).map { r -> way(r.toLong(), (0..n).map { it to r }, highway(r)) } +
            (0..n).map { c -> way(10_000L + c, (0..n).map { c to it }, highway(c)) }

    private fun assertClosedLoop(loop: Loop, targetM: Double, tolerance: Double) {
        val pts = loop.route.points
        assertTrue(Route.metres(pts.first(), pts.last()) < 1.0, "loop ends where it starts")
        assertEquals(targetM, loop.lengthM, targetM * tolerance, "length of ${loop.route.name}")
    }

    @Test fun `reads an Overpass answer`() {
        val json = """{"version":0.6,"elements":[
            {"type":"way","id":1,"nodes":[10,11,12],"geometry":[{"lat":19.0,"lon":72.8},{"lat":19.001,"lon":72.8},{"lat":19.002,"lon":72.8}],
             "tags":{"highway":"footway","name":"Sea \"Face\" é","surface":"paved","ignored":"x"}},
            {"type":"way","id":2,"nodes":[12,13],"geometry":[{"lat":19.002,"lon":72.8},null],"tags":{"highway":"residential"}},
            {"type":"way","id":3,"nodes":[20,21,22,20],"geometry":[{"lat":19.0,"lon":72.8},{"lat":19.0,"lon":72.801},{"lat":19.001,"lon":72.801},{"lat":19.0,"lon":72.8}],
             "tags":{"leisure":"park"}},
            {"type":"node","id":5,"lat":1.5,"lon":2.5,"tags":{}}
        ]}"""
        val data = Overpass.parse(json)
        assertEquals(listOf(1L, 2L), data.ways.map { it.id })
        assertEquals("Sea \"Face\" é", data.ways[0].tags["name"])
        assertEquals(null, data.ways[0].tags["ignored"])
        assertTrue(data.ways[1].lons[1].isNaN())
        assertEquals(1, data.parks.size)
        // The way with a missing vertex is cut there: only way 1 makes an edge (2 vertices → 1 edge of ~221 m).
        val g = RouteGraph.build(data)
        assertEquals(1, g.edgeCount)
        assertEquals(222.4, g.edgeLenM[0], 1.0)
        assertFailsWith<Overpass.FormatException> { Overpass.parse("<html>502 Gateway Timeout</html>") }
        assertFailsWith<Overpass.FormatException> { Overpass.parse("""{"elements":[],"remark":"runtime error: Query timed out"}""") }
    }

    @Test fun `graph splits ways at junctions and finds components`() {
        val g = RouteGraph.build(OsmData(grid(2) + way(99, listOf(50 to 50, 51 to 50)), emptyList()))
        // 3 × 3 junctions + the island's 2 ends; 12 grid edges + 1.
        assertEquals(11, g.nodeCount)
        assertEquals(13, g.edgeCount)
        assertEquals(2, g.componentLengthM.size)
        assertEquals(1200.0, g.componentLengthM.max(), 5.0)
    }

    @Test fun `ring hanging off one junction stays a loop`() {
        val ring = way(1, listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, 0 to 0), "footway")
        val g = RouteGraph.build(OsmData(listOf(ring, way(2, listOf(0 to 0, -1 to 0))), emptyList()))
        assertEquals(400.0 + 100.0, g.edgeLenM.sum(), 3.0)
    }

    @Test fun `loops on a grid city hit the length`() {
        val g = RouteGraph.build(OsmData(grid(100), emptyList()))
        for (km in listOf(5.0, 12.0, 23.0)) {
            val t0 = System.nanoTime()
            val loops = LoopPlanner.plan(g, LoopRequest(lat(5000.0), lon(5000.0), km * 1000))
            println("grid $km km: ${loops.map { "%.2f".format(it.lengthM / 1000) }} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertEquals(3, loops.size)
            for (l in loops) {
                assertClosedLoop(l, km * 1000, 0.05)
                assertTrue(l.overlapShare < 0.1, "few repeats (${l.overlapShare})")
            }
            // Three different directions.
            val b = loops.map { it.bearingDeg }
            for (i in b.indices) for (j in i + 1 until b.size) assertTrue(abs(b[i] - b[j]).let { if (it > 180) 360 - it else it } >= 30)
        }
    }

    @Test fun `avoids busy roads when side streets do the same`() {
        val g = RouteGraph.build(OsmData(grid(40), emptyList()))
        val loops = LoopPlanner.plan(g, LoopRequest(lat(2000.0), lon(2000.0), 6000.0))
        // Every 10th street is primary (10 % of the streets); a loop should use well under that share.
        assertTrue(loops.first().busyShare < 0.1, "busy share ${loops.first().busyShare}")
    }

    @Test fun `dead ends don't become out-and-back spurs`() {
        // A 20 × 20 grid where every street off the main roads (every 4th) is a dead end that stops short.
        val ways = ArrayList<OsmWay>()
        for (r in 0..20) ways += if (r % 4 == 0) way(r.toLong(), (0..20).map { it to r }) else way(r.toLong(), (0..2).map { it to r }, idBase = 900_000)
        for (c in 0..20) ways += if (c % 4 == 0) way(10_000L + c, (0..20).map { c to it }) else way(10_000L + c, (17..20).map { c to it }, idBase = 900_000)
        val g = RouteGraph.build(OsmData(ways, emptyList()))
        val loops = LoopPlanner.plan(g, LoopRequest(lat(800.0), lon(800.0), 4000.0))
        assertTrue(loops.isNotEmpty())
        for (l in loops) assertTrue(l.overlapShare < 0.15, "overlap ${l.overlapShare}")
    }

    @Test fun `river with one bridge is crossed only on the bridge`() {
        // Two 15 × 15 grids 300 m apart (east of x = 1500 m is across a river); one bridge at row 7.
        val west = (0..15).map { r -> way(r.toLong(), (0..15).map { it to r }) } + (0..15).map { c -> way(1_000L + c, (0..15).map { c to it }) }
        val east = (0..15).map { r -> way(2_000L + r, (18..33).map { it to r }) } + (18..33).map { c -> way(3_000L + c, (0..15).map { c to it }) }
        val bridge = way(5_000, listOf(15 to 7, 16 to 7, 17 to 7, 18 to 7))
        val g = RouteGraph.build(OsmData(west + east + bridge, emptyList()))
        val loops = LoopPlanner.plan(g, LoopRequest(lat(700.0), lon(1300.0), 5000.0))
        assertTrue(loops.isNotEmpty())
        for (l in loops) {
            val pts = l.route.points
            for (i in 1 until pts.size) {
                val x1 = (pts[i - 1].lon - lon0) / mLon
                val x2 = (pts[i].lon - lon0) / mLon
                if ((x1 < 1550 && x2 > 1550) || (x1 > 1550 && x2 < 1550)) assertEquals(700.0, (pts[i].lat - lat0) / mLat, 5.0, "crosses on the bridge")
            }
        }
    }

    @Test fun `prefer parks pulls the loop into the park`() {
        // Grid of streets with a 1 × 1 km park (footways) in one corner of the area.
        val streets = grid(30)
        val parkPaths = (0..10).map { r -> way(20_000L + r, (20..30).map { it to r + 20 }, "footway") } +
            (20..30).map { c -> way(30_000L + c, (20..30).map { c to it }, "footway") }
        val park = OsmWay(1, longArrayOf(1, 2, 3, 4, 1), doubleArrayOf(lat(1990.0), lat(1990.0), lat(3010.0), lat(3010.0), lat(1990.0)),
            doubleArrayOf(lon(1990.0), lon(3010.0), lon(3010.0), lon(1990.0), lon(1990.0)), mapOf("leisure" to "park"))
        // Park footways share the grid's node ids, so they're joined to the streets.
        val g = RouteGraph.build(OsmData(streets + parkPaths, listOf(park)))
        val plain = LoopPlanner.plan(g, LoopRequest(lat(1900.0), lon(1900.0), 5000.0))
        val green = LoopPlanner.plan(g, LoopRequest(lat(1900.0), lon(1900.0), 5000.0, preferParks = true))
        assertTrue(green.first().footShare >= plain.first().footShare, "green ${green.first().footShare} vs ${plain.first().footShare}")
        assertTrue(green.first().footShare > 0.3, "green share ${green.first().footShare}")
    }

    @Test fun `out and back beside itself counts as doubled`() {
        // 1 km east on one street and back 10 m to the side: all doubled. A 1 km square: none.
        val outBack = Route("x", (0..100).map { RoutePoint(lat(0.0), lon(it * 10.0)) } + (100 downTo 0).map { RoutePoint(lat(10.0), lon(it * 10.0)) })
        assertTrue(LoopPlanner.doubledShare(outBack) > 0.85, "${LoopPlanner.doubledShare(outBack)}")
        val square = Route("y", listOf(0.0 to 0.0, 250.0 to 0.0, 250.0 to 250.0, 0.0 to 250.0, 0.0 to 0.0).map { RoutePoint(lat(it.second), lon(it.first)) })
        assertEquals(0.0, LoopPlanner.doubledShare(square), 0.001)
    }

    @Test fun `start on a lone path snaps to the street network`() {
        val island = way(99, listOf(-2 to -2, -3 to -2), "footway")
        val g = RouteGraph.build(OsmData(grid(30) + island, emptyList()))
        val loops = LoopPlanner.plan(g, LoopRequest(lat(-200.0), lon(-250.0), 3000.0))
        assertTrue(loops.isNotEmpty())
        assertClosedLoop(loops.first(), 3000.0, 0.15)
        assertTrue(LoopPlanner.plan(g, LoopRequest(lat(-5000.0), lon(-5000.0), 3000.0)).isEmpty(), "too far from any path")
    }

    @Test fun `real Mumbai paths around Joggers' Park`() {
        val json = javaClass.getResource("/osm/bandra-joggers-park.json")!!.readText()
        var t0 = System.nanoTime()
        val g = RouteGraph.build(Overpass.parse(json))
        println("Bandra graph: ${g.nodeCount} nodes, ${g.edgeCount} edges in ${(System.nanoTime() - t0) / 1_000_000} ms")
        for (km in listOf(3.0, 5.0)) {
            for (green in listOf(false, true)) {
                t0 = System.nanoTime()
                val loops = LoopPlanner.plan(g, LoopRequest(19.05946, 72.82136, km * 1000, preferParks = green))
                println("Bandra $km km green=$green in ${(System.nanoTime() - t0) / 1_000_000} ms: " + loops.joinToString {
                    "%.2f km @%.0f° repeat %.0f%% foot %.0f%% busy %.0f%%".format(it.lengthM / 1000, it.bearingDeg, it.overlapShare * 100, it.footShare * 100, it.busyShare * 100)
                })
                assertEquals(3, loops.size)
                // The start is on the coast (half the directions are sea): the best loop is on length, the rest clean.
                assertClosedLoop(loops.first(), km * 1000, 0.05)
                for (l in loops) {
                    assertClosedLoop(l, km * 1000, 0.10)
                    assertTrue(l.overlapShare <= 0.15, "repeat share ${l.overlapShare}")
                }
                for (i in loops.indices) for (j in i + 1 until loops.size) assertTrue(loops[i].sharedWith(loops[j]) <= 0.6, "loops $i and $j differ")
            }
        }
    }
}
