package com.rameez.fit3zone.core

import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityExportTest {
    private val start = 1_760_541_780_000L // 2025-10-15T15:23:00Z

    private fun session(type: ActivityType = ActivityType.RUN, points: List<GeoPoint> = emptyList(), breaks: List<Long> = emptyList()) = ExportSession(
        startedAtMs = start, type = type, activeMs = 600_000, distanceM = 1_234.5, avgBpm = 150, maxBpm = 172,
        samples = listOf(HrSample(0, 120), HrSample(5_000, 130), HrSample(10_000, 140), HrSample(60_000, 160)),
        points = points,
        cadence = listOf(5_000L to 170, 10_000L to 168),
        segmentBreaksMs = breaks,
    )

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream())

    @Test fun `gps run is valid xml with position, heart rate and one-foot cadence`() {
        val pts = listOf(GeoPoint(0, 51.5, -0.1, 5f, 12.0), GeoPoint(5_000, 51.5001, -0.1001, 5f, 13.0), GeoPoint(30_000, 51.5004, -0.1004, 5f))
        val xml = TcxWriter.write(session(points = pts))
        val doc = parse(xml)
        assertEquals("Running", doc.getElementsByTagName("Activity").item(0).attributes.getNamedItem("Sport").nodeValue)
        assertEquals(3, doc.getElementsByTagName("Trackpoint").length)
        assertTrue("<Time>2025-10-15T15:23:05Z</Time>" in xml)
        assertTrue("<LatitudeDegrees>51.5001000</LatitudeDegrees>" in xml)
        assertTrue("<AltitudeMeters>13.0</AltitudeMeters>" in xml)
        // 5 s fix gets the 5 s sample and cadence 170 / 2
        assertTrue("<HeartRateBpm><Value>130</Value></HeartRateBpm><Extensions><ns3:TPX><ns3:RunCadence>85<" in xml)
        assertTrue("<TotalTimeSeconds>600.0</TotalTimeSeconds>" in xml)
        assertTrue("<DistanceMeters>1234.5</DistanceMeters>" in xml)
    }

    @Test fun `heart rate is left out when no sample is close`() {
        val pts = listOf(GeoPoint(35_000, 51.5, -0.1, 5f)) // nearest samples at 10 s and 60 s: 25 s away
        val xml = TcxWriter.write(session(points = pts))
        assertFalse("<HeartRateBpm>" in xml.substringAfter("<Track>"))
    }

    @Test fun `without gps the trackpoints are the heart-rate samples`() {
        val xml = TcxWriter.write(session(type = ActivityType.TREADMILL))
        assertEquals(4, parse(xml).getElementsByTagName("Trackpoint").length)
        assertFalse("<Position>" in xml)
        assertTrue("<HeartRateBpm><Value>160</Value></HeartRateBpm>" in xml)
    }

    @Test fun `each resume after a pause starts a new track`() {
        val pts = listOf(GeoPoint(0, 51.5, -0.1, 5f), GeoPoint(5_000, 51.5, -0.1, 5f), GeoPoint(90_000, 51.5, -0.1, 5f))
        val doc = parse(TcxWriter.write(session(points = pts, breaks = listOf(80_000))))
        assertEquals(2, doc.getElementsByTagName("Track").length)
    }

    @Test fun `each recorded lap becomes a tcx lap with its own points and totals`() {
        val pts = (0..12).map { GeoPoint(it * 10_000L, 51.5 + it * 0.0001, -0.1, 5f) } // 0..120 s
        val laps = listOf(
            Lap(1, 0, 40_000, 150.0, 140, 150, "Warm-up"),
            Lap(2, 40_000, 50_000, 220.0, 160, 170, "Run"),
            Lap(3, 90_000, 30_000, 100.0, 130, 135, null),
        )
        val doc = parse(TcxWriter.write(session(points = pts).copy(laps = laps)))
        val tcxLaps = doc.getElementsByTagName("Lap")
        assertEquals(3, tcxLaps.length)
        fun lap(i: Int) = tcxLaps.item(i) as org.w3c.dom.Element
        fun text(i: Int, tag: String) = lap(i).getElementsByTagName(tag).item(0).textContent
        assertEquals(listOf(4, 5, 4), (0..2).map { lap(it).getElementsByTagName("Trackpoint").length })
        assertEquals("2025-10-15T15:23:40Z", lap(1).getAttribute("StartTime"))
        assertEquals("50.0", text(1, "TotalTimeSeconds"))
        assertEquals("220.0", text(1, "DistanceMeters"))
        assertEquals("170", (lap(1).getElementsByTagName("MaximumHeartRateBpm").item(0) as org.w3c.dom.Element).textContent.trim())
    }

    @Test fun `laps are matched in active time when the session was paused`() {
        // Recording until 30 s, paused, resumed at 90 s (60 s pause): active 40 s = wall 100 s.
        val pts = listOf(0L, 10_000, 20_000, 30_000, 90_000, 100_000, 110_000).map { GeoPoint(it, 51.5, -0.1, 5f) }
        val laps = listOf(Lap(1, 0, 40_000, 200.0, null, null, null), Lap(2, 40_000, 10_000, 50.0, null, null, null))
        val s = session(points = pts, breaks = listOf(90_000)).copy(
            samples = listOf(HrSample(0, 120), HrSample(30_000, 125), HrSample(90_000, 130)), cadence = emptyList(), laps = laps,
        )
        val doc = parse(TcxWriter.write(s))
        val tcxLaps = doc.getElementsByTagName("Lap")
        fun lap(i: Int) = tcxLaps.item(i) as org.w3c.dom.Element
        assertEquals(listOf(5, 2), (0..1).map { lap(it).getElementsByTagName("Trackpoint").length })
        assertEquals(2, lap(0).getElementsByTagName("Track").length) // the pause still splits the first lap
        assertEquals("2025-10-15T15:24:40Z", lap(1).getAttribute("StartTime"))
    }

    @Test fun `a single recorded lap keeps the whole-session lap`() {
        val pts = listOf(GeoPoint(0, 51.5, -0.1, 5f), GeoPoint(5_000, 51.5, -0.1, 5f))
        val xml = TcxWriter.write(session(points = pts).copy(laps = listOf(Lap(1, 0, 600_000, 1_234.5, 150, 172, null))))
        assertEquals(1, parse(xml).getElementsByTagName("Lap").length)
        assertTrue("<TotalTimeSeconds>600.0</TotalTimeSeconds>" in xml)
    }

    @Test fun `strength has no cadence and maps to Strava weight training`() {
        val xml = TcxWriter.write(session(type = ActivityType.STRENGTH))
        assertFalse("RunCadence" in xml)
        assertTrue("""Sport="Other"""" in xml)
        assertEquals("WeightTraining", ActivityType.STRENGTH.stravaSportType())
        assertEquals("Walk", ActivityType.WALK.stravaSportType())
        assertTrue(ActivityType.TREADMILL.stravaTrainer)
    }
}
