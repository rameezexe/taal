package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrZonesTest {

    @Test fun `karvonen zones from max and resting HR`() {
        // 22 years old: Tanaka max 193; resting 60 -> reserve 133
        val z = HrZones.fromReserve(193, 60)
        assertEquals(listOf(127, 140, 153, 166, 180), z.floors)
        assertEquals(140..152, z.range(2))
        assertEquals(180..193, z.range(5))
    }

    @Test fun `percent of max zones`() {
        assertEquals(listOf(97, 116, 135, 154, 174), HrZones.fromMax(193).floors)
    }

    @Test fun `zoneOf uses inclusive floors and zone 0 below Z1`() {
        val z = HrZones(listOf(100, 115, 130, 146, 160), 190)
        assertEquals(0, z.zoneOf(99))
        assertEquals(1, z.zoneOf(100))
        assertEquals(1, z.zoneOf(114))
        assertEquals(2, z.zoneOf(115))
        assertEquals(5, z.zoneOf(160))
        assertEquals(5, z.zoneOf(220), "above max HR is still Z5")
    }

    @Test fun `zones round-trip through encode`() {
        val z = HrZones.fromReserve(193, 60)
        assertEquals(z, HrZones.decode(z.encode()))
        assertNull(HrZones.decode(""))
        assertNull(HrZones.decode(null))
        assertNull(HrZones.decode("1,2,3"))
    }

    @Test fun `invalid zones are rejected`() {
        assertFailsWith<IllegalArgumentException> { HrZones(listOf(100, 100, 130, 146, 160), 190) }
        assertFailsWith<IllegalArgumentException> { HrZones(listOf(100, 115, 130, 146, 160), 150) }
        assertFailsWith<IllegalArgumentException> { HrZones.fromReserve(120, 110) }
    }

    @Test fun `tanaka age estimate`() {
        assertEquals(193, HeartRateProfile.ageEstimate(22))
        assertEquals(180, HeartRateProfile.ageEstimate(40))
    }

    @Test fun `profile uses reserve only when resting HR is known`() {
        val p = HeartRateProfile.fromAge(22)
        assertTrue(!p.usesReserve)
        assertEquals(HrZones.fromMax(193), p.zones())
        val withRest = p.copy(restingHr = 60, restingSource = RestingHrSource.MANUAL)
        assertEquals(HrZones.fromReserve(193, 60), withRest.zones())
    }

    @Test fun `max HR rises with a higher sustained peak but never for manual`() {
        val p = HeartRateProfile.fromAge(22)
        assertNull(p.withObservedPeak(190))
        assertNull(p.withObservedPeak(null))
        assertNull(p.withObservedPeak(240), "implausible peak ignored")
        val raised = p.withObservedPeak(198)!!
        assertEquals(198, raised.maxHr)
        assertEquals(MaxHrSource.DETECTED, raised.maxHrSource)
        assertNull(p.copy(maxHrSource = MaxHrSource.MANUAL).withObservedPeak(198))
    }

    @Test fun `sustained peak ignores a single spike`() {
        val peak = SustainedPeak(windowMs = 10_000, minReadings = 5)
        (0 until 30).forEach { s -> peak.add(s * 1_000L, if (s == 15) 210 else 170) }
        // one 210 among ten 170s in a 10 s window: avg ≈ 173.6
        assertEquals(174, peak.peak)
        val none = SustainedPeak(minReadings = 5)
        none.add(0, 200); none.add(1_000, 200)
        assertNull(none.peak)
    }

    @Test fun `resting HR is the lowest 30 s average over the measurement`() {
        val m = RestingHrMeasurement(durationMs = 120_000, windowMs = 30_000)
        // 70 bpm for the first minute, 58 for the second
        (0..120).forEach { s -> m.add(s * 1_000L, if (s < 60) 70 else 58) }
        assertTrue(m.isDone(120_000))
        assertEquals(58, m.result)
    }

    @Test fun `resting HR needs enough readings`() {
        val m = RestingHrMeasurement()
        (0..20).forEach { s -> m.add(s * 1_000L, 60) }
        assertNull(m.result, "under 30 s of data")
        val sparse = RestingHrMeasurement()
        (0..12).forEach { s -> sparse.add(s * 10_000L, 60) }
        assertNull(sparse.result, "one reading every 10 s is too sparse")
    }

    @Test fun `target choices resolve against zones`() {
        val z = HrZones.fromReserve(193, 60)
        assertNull(TargetChoice.Free.resolve(z))
        assertEquals(ZoneTarget(140, 152, "Zone 2"), TargetChoice.Zones(2, 2).resolve(z))
        assertEquals(ZoneTarget(140, 165, "Zones 2–3"), TargetChoice.Zones(2, 3).resolve(z))
        assertEquals(ZoneTarget(180, 193, "Zone 5"), TargetChoice.Zones(5, 5).resolve(z))
        assertEquals(ZoneTarget(118, 138, "Custom"), TargetChoice.Custom(118, 138).resolve(z))
    }

    @Test fun `target choices round-trip`() {
        listOf(TargetChoice.Free, TargetChoice.Zones(2, 3), TargetChoice.Custom(118, 138)).forEach {
            assertEquals(it, TargetChoice.decode(it.encode()))
        }
        assertNull(TargetChoice.decode("zones:4-2"))
        assertNull(TargetChoice.decode("custom:150-140"))
        assertNull(TargetChoice.decode("bogus"))
    }
}
