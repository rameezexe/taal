package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutesTest {
    private val lat0 = 19.0
    private val lon0 = 72.8
    private val mLat = 1.0 / 111_195.0
    private val mLon = mLat / cos(Math.toRadians(lat0))

    /** Point [east] / [north] metres from the origin. */
    private fun at(east: Double, north: Double, ele: Double? = null) = RoutePoint(lat0 + north * mLat, lon0 + east * mLon, ele)

    /** 1 km straight east, a point every 10 m. */
    private val straight = Route("Straight", (0..100).map { at(it * 10.0, 0.0) })

    private fun RouteFollower.walk(fromS: Int, toS: Int, pos: (Int) -> RoutePoint): List<RouteEvent> =
        (fromS..toS).mapNotNull { s -> pos(s).let { update(s * 1000L, it.lat, it.lon) } }

    // --- Route ---

    @Test fun `distance, loop detection and climb`() {
        assertEquals(1000.0, straight.distanceM, 2.0)
        assertFalse(straight.isLoop)
        val square = Route("Square", listOf(at(0.0, 0.0), at(500.0, 0.0), at(500.0, 500.0), at(0.0, 500.0), at(0.0, 5.0)))
        assertTrue(square.isLoop)
        // 0 → 30 m up over 300 m, then back down; 1 m noise doesn't add climb.
        val hill = Route("Hill", (0..60).map { i -> at(i * 10.0, 0.0, (if (i <= 30) i.toDouble() else 60.0 - i) + if (i % 2 == 0) 0.5 else -0.5) })
        assertEquals(30.0, hill.ascentM!!, 4.0)
        assertEquals(30.0, hill.descentM!!, 4.0)
        assertNull(straight.ascentM)
    }

    @Test fun `simplify keeps the shape under the point limit`() {
        val wiggly = Route("Wiggly", (0..5000).map { at(it * 2.0, if (it % 50 < 25) 0.0 else 30.0) })
        val s = wiggly.simplified(500)
        assertTrue(s.points.size <= 500)
        assertEquals(wiggly.distanceM, s.distanceM, wiggly.distanceM * 0.02)
    }

    // --- GPX ---

    @Test fun `reads a Strava-style track with namespace prefix, elevation and CDATA name`() {
        val xml = """<?xml version="1.0"?><gpx:gpx xmlns:gpx="http://www.topografix.com/GPX/1/1" version="1.1">
            <gpx:trk><gpx:name><![CDATA[Sunday & long]]></gpx:name><gpx:trkseg>
            <gpx:trkpt lon="72.8000" lat="19.0000"><gpx:ele>10.0</gpx:ele></gpx:trkpt>
            <gpx:trkpt lat='19.0010' lon='72.8000'><gpx:ele>12.5</gpx:ele><gpx:time>2026-10-20T10:00:00Z</gpx:time></gpx:trkpt>
            <gpx:trkpt lat="19.0020" lon="72.8000"/>
            </gpx:trkseg></gpx:trk></gpx:gpx>"""
        val r = Gpx.read(xml)
        assertEquals("Sunday & long", r.name)
        assertEquals(3, r.points.size)
        assertEquals(12.5, r.points[1].eleM)
        assertNull(r.points[2].eleM)
        assertEquals(222.4, r.distanceM, 1.0)
    }

    @Test fun `prefers track, then route points, then waypoints`() {
        val rte = """<gpx><rte><name>Planned</name><rtept lat="19" lon="72.8"/><rtept lat="19.001" lon="72.8"/></rte>
            <wpt lat="10" lon="10"/><wpt lat="11" lon="11"/></gpx>"""
        assertEquals("Planned", Gpx.read(rte).name)
        assertEquals(19.0, Gpx.read(rte).points.first().lat)
        val wpt = """<gpx><wpt lat="19" lon="72.8"><name>A</name></wpt><wpt lat="19.002" lon="72.8"/></gpx>"""
        assertEquals(2, Gpx.read(wpt, "art.gpx").points.size)
    }

    @Test fun `rejects files without a usable route`() {
        assertFailsWith<Gpx.FormatException> { Gpx.read("<html></html>") }
        assertFailsWith<Gpx.FormatException> { Gpx.read("""<gpx><trk><trkseg><trkpt lat="19" lon="72.8"/></trkseg></trk></gpx>""") }
        assertFailsWith<Gpx.FormatException> { Gpx.read("""<gpx><trk><trkseg><trkpt lat="99" lon="72.8"/><trkpt lat="98" lon="72.8"/></trkseg></trk></gpx>""") }
    }

    @Test fun `write then read gives the same route`() {
        val r = Route("Loop <A> & \"B\"", listOf(at(0.0, 0.0, 5.0), at(300.0, 0.0, 8.0), at(300.0, 300.0), at(0.0, 0.0, 5.0)))
        val back = Gpx.read(Gpx.write(r, "© OpenStreetMap contributors"))
        assertEquals(r.name, back.name)
        assertEquals(r.points.size, back.points.size)
        assertEquals(r.distanceM, back.distanceM, 0.5)
        assertEquals(8.0, back.points[1].eleM)
    }

    @Test fun `activity GPX has one segment per resume, times and heart rate`() {
        val pts = (0..9).map { i -> at(i * 10.0, 0.0).let { GeoPoint(i * 1000L, it.lat, it.lon, 5f, 12.0) } }
        val s = ExportSession(
            startedAtMs = 1_792_077_194_000, type = ActivityType.RUN, activeMs = 10_000, distanceM = 90.0, avgBpm = 140,
            maxBpm = 150, samples = listOf(HrSample(0, 120), HrSample(5_000, 150)), points = pts, segmentBreaksMs = listOf(5_000),
        )
        val xml = Gpx.writeActivity(s, "Run")
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        assertTrue(xml.contains("<gpxtpx:hr>150</gpxtpx:hr>"))
        assertTrue(xml.contains("<time>2026-10-15T"))
        assertEquals(10, Gpx.read(xml).points.size)
    }

    // --- RouteFollower ---

    @Test fun `on the route all the way - no alerts, complete at the end`() {
        val f = RouteFollower(straight)
        val events = f.walk(0, 300) { s -> at(minOf(s * 3.4, 1000.0), if (s % 2 == 0) 6.0 else -6.0) }
        assertEquals(listOf<RouteEvent>(RouteEvent.Complete), events)
        assertEquals(0.0, f.remainingM, 0.1)
        assertTrue(f.followedFraction > 0.97)
    }

    @Test fun `detour triggers off route after the confirm time, then back on`() {
        val f = RouteFollower(straight)
        f.walk(0, 60) { s -> at(s * 3.0, 0.0) } // 180 m along
        // Turn north off the route for 40 s, then come back.
        val off = f.walk(61, 100) { s -> at(180.0, (s - 60) * 3.0) }
        val first = off.first()
        assertIs<RouteEvent.OffRoute>(first)
        assertFalse(first.repeat)
        assertTrue(f.offRoute)
        // Alert fires 12 s after passing 35 m (≈ 12 s at 3 m/s): first fix > 35 m is s = 72.
        val back = f.walk(101, 140) { s -> at(180.0 + (s - 100) * 3.0, maxOf(0.0, 120.0 - (s - 100) * 6.0)) }
        assertEquals(RouteEvent.BackOnRoute, back.first())
        assertFalse(f.offRoute)
    }

    @Test fun `a short wobble or a GPS gap never alerts`() {
        val f = RouteFollower(straight)
        val wobble = f.walk(0, 100) { s -> at(s * 3.0, if (s in 30..38) 50.0 else 0.0) } // 9 s away
        assertTrue(wobble.none { it is RouteEvent.OffRoute })
        // Fixes far off but 8 s apart (gaps): the count restarts every fix.
        val g = RouteFollower(straight)
        g.walk(0, 20) { s -> at(s * 3.0, 0.0) }
        val gappy = (0..10).mapNotNull { i -> at(60.0 + i * 24.0, 60.0).let { g.update(21_000L + i * 8_000L, it.lat, it.lon) } }
        assertTrue(gappy.none { it is RouteEvent.OffRoute })
    }

    @Test fun `reminder every minute while still off route`() {
        val f = RouteFollower(straight)
        f.walk(0, 20) { s -> at(s * 3.0, 0.0) }
        val events = f.walk(21, 200) { s -> at(60.0, 100.0) }
        assertEquals(listOf(false, true, true), events.map { (it as RouteEvent.OffRoute).repeat })
    }

    @Test fun `walking to the start is not off route`() {
        val f = RouteFollower(straight)
        val events = f.walk(0, 60) { s -> at(-200.0 + s * 3.0, 0.0) } // reaches the start at s = 67
        assertTrue(events.isEmpty())
        assertFalse(f.acquired)
        f.walk(61, 80) { s -> at(-200.0 + s * 3.0, 0.0) }
        assertTrue(f.acquired)
        assertEquals(40.0, f.progressM, 15.0)
    }

    @Test fun `out and back doesn't jump to the way back`() {
        val outBack = Route("Out and back", (0..50).map { at(it * 10.0, 0.0) } + (49 downTo 0).map { at(it * 10.0, 3.0) })
        val f = RouteFollower(outBack)
        f.walk(0, 100) { s -> at(s * 2.0, 1.5) } // 200 m out, in the middle of both directions
        assertEquals(200.0, f.progressM, 20.0)
        val events = f.walk(101, 500) { s -> if (s <= 250) at(s * 2.0, 1.5) else at(maxOf(0.0, 1000.0 - s * 2.0), 1.5) }
        assertTrue(events.none { it is RouteEvent.OffRoute })
        assertEquals(RouteEvent.Complete, events.last())
    }

    @Test fun `shortcut while off route rejoins ahead without crediting the skipped part`() {
        val square = Route("Square", listOf(at(0.0, 0.0), at(400.0, 0.0), at(400.0, 400.0), at(0.0, 400.0), at(0.0, 0.0)))
        val f = RouteFollower(square)
        f.walk(0, 66) { s -> at(s * 3.0, 0.0) } // ~200 m along the first side
        // Cut diagonally across the middle to the third side.
        f.walk(67, 200) { s -> at(200.0 + (s - 66) * 1.5, (s - 66) * 3.0).let { if (it.lat > lat0 + 400 * mLat) at(400.0 - (s - 200) * 3.0, 400.0) else it } }
        f.walk(201, 260) { s -> at(maxOf(0.0, 400.0 - (s - 200) * 3.0), 400.0) }
        assertFalse(f.offRoute)
        assertTrue(f.progressM > 800)
        assertTrue(f.followedFraction < 0.75, "followed ${f.followedFraction}")
    }

    // --- real run: 18 laps of the same ~410 m loop ---

    @Test fun `real lap run followed against its own track - no false alerts, no lap jumps`() {
        val lines = javaClass.getResource("/runs/loop-full-gps.csv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val fixes = lines.map { l -> val (t, e, n, acc) = l.split(","); Triple(t.toLong(), at(e.toDouble(), n.toDouble()), acc.toFloat()) }
            .filter { it.third <= 25f }
        val route = Route("Laps", fixes.map { it.second }).simplified()
        val f = RouteFollower(route)
        var prevProgress = 0.0
        var prevT = fixes.first().first
        val events = fixes.mapNotNull { (t, p, _) ->
            val e = f.update(t, p.lat, p.lon)
            val jump = f.progressM - prevProgress
            // Progress grows at running speed (≤ ~6 m/s plus slack after gaps), never a lap (~410 m) at once.
            assertTrue(jump <= 60 + (t - prevT) / 1000.0 * 6, "jump $jump m at $t")
            prevProgress = f.progressM
            prevT = t
            e
        }
        assertTrue(events.none { it is RouteEvent.OffRoute }, "events $events")
        assertNotNull(events.lastOrNull())
        assertTrue(f.followedFraction > 0.95, "followed ${f.followedFraction}")
        assertTrue(f.remainingM < 50, "remaining ${f.remainingM}")
    }

    @Test fun `power-saving run on another day follows the lap route without false alerts`() {
        fun load(name: String) = javaClass.getResource("/runs/$name.csv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> val (t, e, n, acc) = l.split(","); Triple(t.toLong(), at(e.toDouble(), n.toDouble()), acc.toFloat()) }
            .filter { it.third <= 25f }
        val route = Route("Laps", load("loop-full-gps").map { it.second }).simplified()
        val run = load("loop-power-saving")
        // Session distance incl. gaps bridged by steps, as SessionController has it.
        val steps = stepTimelineFromCadence(javaClass.getResource("/runs/loop-power-saving-cadence.csv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { l -> l.split(",").let { it[0].toLong() to it[1].toInt() } })
        val tracker = RouteTracker.replay(run.map { (t, p, acc) -> GeoPoint(t, p.lat, p.lon, acc) }, emptyList(), steps = steps)
        val runAt = tracker.fixDistances.toMap()
        val f = RouteFollower(route)
        var startProgress: Double? = null
        val events = run.mapNotNull { (t, p, _) -> f.update(t, p.lat, p.lon, runAt[t]).also { if (f.acquired) startProgress = startProgress ?: f.progressM } }
        assertTrue(events.none { it is RouteEvent.OffRoute }, "events $events")
        // 55 % of the run without GPS (gaps up to 225 s ≈ 2 laps): progress along the route follows the run distance
        // (route laps measure ~4 % long: built from raw fixes).
        val runM = runAt.values.max()
        val along = f.progressM - startProgress!!
        assertTrue(along in runM * 0.95..runM * 1.12, "along $along vs run $runM")
        // Without the run distance, each gap loses the laps run in it.
        val blind = RouteFollower(route)
        run.forEach { (t, p, _) -> blind.update(t, p.lat, p.lon) }
        assertTrue(blind.progressM < f.progressM - 500, "blind ${blind.progressM} vs ${f.progressM}")
    }
}
