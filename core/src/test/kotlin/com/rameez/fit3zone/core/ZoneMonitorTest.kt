package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneMonitorTest {

    private val target = ZoneTarget(lowerBpm = 130, upperBpm = 145, label = "Zone 2")
    private val timing = AlertTiming()
    private val zones = HrZones(listOf(100, 115, 130, 146, 160), maxHr = 190)

    /** Drives a monitor with 1 Hz readings and 500 ms ticks, collecting actions with their times. */
    private class Harness(
        target: ZoneTarget?,
        timing: AlertTiming = AlertTiming(),
        zones: HrZones? = null,
        source: ReadingSource = ReadingSource.BLE,
    ) {
        val monitor = ZoneMonitor(target, zones, timing, source, startElapsedMs = 0)
        var now = 0L
        val actions = mutableListOf<Pair<Long, ZoneAction>>()

        fun reading(bpm: Int, source: ReadingSource = ReadingSource.BLE) {
            monitor.onReading(HeartRateReading(bpm, SensorContact.DETECTED, now, now, source), now)
                .forEach { actions += now to it }
        }

        fun tick() = monitor.onTick(now).forEach { actions += now to it }

        /** One second: a reading at the start, ticks at +500 and +1000. */
        fun second(bpm: Int?) {
            if (bpm != null) reading(bpm)
            now += 500; tick()
            now += 500; tick()
        }

        fun seconds(bpm: Int?, n: Int) = repeat(n) { second(bpm) }

        val alerts get() = actions.filter { it.second is ZoneAction.AboveZoneAlert }
        val belows get() = actions.filter { it.second is ZoneAction.BelowZoneAlert }
        val backs get() = actions.filter { it.second is ZoneAction.BackInZone }
        fun snap() = monitor.snapshot(now)
    }

    @Test fun `classification boundaries are inclusive of both limits`() {
        assertEquals(Zone.BELOW_ZONE, target.classify(129))
        assertEquals(Zone.IN_ZONE, target.classify(130))
        assertEquals(Zone.IN_ZONE, target.classify(145))
        assertEquals(Zone.ABOVE_ZONE, target.classify(146))
    }

    @Test fun `invalid configs are rejected`() {
        assertFailsWith<IllegalArgumentException> { ZoneTarget(150, 145, "") }
        assertFailsWith<IllegalArgumentException> { ZoneTarget(145, 145, "") }
        assertFailsWith<IllegalArgumentException> { ZoneTarget(10, 145, "") }
        assertFailsWith<IllegalArgumentException> { AlertTiming(repeatMs = 1_000) }
    }

    @Test fun `spec sequence 128-138-146-151-149-143-139 alerts once then stops`() {
        val h = Harness(target)
        // 5 s per value, like the in-app simulator
        listOf(128, 138, 146, 151, 149, 143, 139).forEach { h.seconds(it, 5) }

        assertEquals(1, h.alerts.size, "one alert for one 15 s above-ceiling episode")
        val (alertAt, alert) = h.alerts.single()
        // 146 first seen at t=10 s; confirmed 3 s later
        assertEquals(13_000, alertAt)
        assertEquals(ZoneAction.AboveZoneAlert(146, 1, isRepeat = false), alert)

        // back at/below 145 at t=25 s (143) -> cue after 3 s recovery
        val (backAt, back) = h.backs.single()
        assertEquals(28_000, backAt)
        assertEquals(ZoneAction.BackInZone(143, fromAbove = true), back)

        val s = h.snap()
        assertEquals(Zone.IN_ZONE, s.zone)
        assertEquals(1, s.alertCount)
        assertEquals(15_000, s.timeAboveMs)
    }

    @Test fun `enters ABOVE_ZONE only after confirmation`() {
        val h = Harness(target)
        h.seconds(140, 2)
        h.reading(151)
        assertEquals(Zone.IN_ZONE, h.snap().zone)
        assertEquals(Zone.ABOVE_ZONE, h.snap().rawZone)
        h.now += 500; h.tick(); h.now += 500; h.tick()
        h.seconds(151, 2)
        assertEquals(Zone.ABOVE_ZONE, h.snap().zone)
        assertTrue(h.snap().aboveConfirmed)
    }

    @Test fun `single sample spike does not alert`() {
        val h = Harness(target)
        h.seconds(140, 3)
        h.second(160)
        h.seconds(140, 10)
        assertEquals(0, h.alerts.size)
        assertEquals(0, h.backs.size, "no back-in-zone cue without an alert")
    }

    @Test fun `alert stops immediately when a reading returns to the ceiling`() {
        val h = Harness(target)
        h.seconds(150, 5)
        assertTrue(h.snap().aboveConfirmed)
        h.reading(145)
        assertEquals(Zone.IN_ZONE, h.snap().zone)
        assertTrue(!h.snap().aboveConfirmed)
    }

    @Test fun `repeats every 30 s while above and never per sample`() {
        val h = Harness(target)
        h.seconds(155, 95)
        // confirmed at 3 s, repeats at 33, 63, 93
        assertEquals(listOf(3_000L, 33_000, 63_000, 93_000), h.alerts.map { it.first })
        assertEquals(listOf(false, true, true, true), h.alerts.map { (it.second as ZoneAction.AboveZoneAlert).isRepeat })
    }

    @Test fun `cooldown limits alerts when hovering around the ceiling`() {
        val h = Harness(target, timing.copy(cooldownMs = 15_000))
        // 4 s above (confirms + alerts), 1 s below, repeated: would alert every 5 s without cooldown
        repeat(8) { h.seconds(147, 4); h.second(144) }
        val times = h.alerts.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 15_000 }, "alerts at $times")
        assertTrue(times.size in 2..3, "alerts at $times")
    }

    @Test fun `stale data suspends alerts and needs fresh confirmation`() {
        val h = Harness(target)
        h.seconds(150, 4)
        assertEquals(1, h.alerts.size)
        h.seconds(null, 40) // band stops sending
        val s = h.snap()
        assertEquals(MonitorStatus.STALE, s.status)
        assertNull(s.zone)
        assertNull(s.bpm)
        assertEquals(1, h.alerts.size, "no repeat alert from stale data")
        assertEquals(1, s.dataInterruptions)

        h.seconds(150, 2) // fresh again but not yet confirmed
        assertEquals(1, h.alerts.size)
        h.seconds(150, 2)
        assertEquals(2, h.alerts.size)
    }

    @Test fun `old reading delivered late is ignored`() {
        val h = Harness(target)
        h.now = 10_000
        h.monitor.onReading(HeartRateReading(160, SensorContact.DETECTED, 2_000, 0, ReadingSource.BLE), h.now)
        assertEquals(MonitorStatus.WAITING_FOR_DATA, h.snap().status)
    }

    @Test fun `simulated readings never drive a real session`() {
        val h = Harness(target, source = ReadingSource.BLE)
        repeat(60) {
            h.reading(170, ReadingSource.SIMULATED)
            h.now += 1_000; h.tick()
        }
        assertEquals(0, h.alerts.size)
        assertEquals(MonitorStatus.WAITING_FOR_DATA, h.snap().status)
    }

    @Test fun `simulated session accepts only simulated readings`() {
        val h = Harness(target, source = ReadingSource.SIMULATED)
        repeat(5) {
            h.reading(170, ReadingSource.SIMULATED)
            h.now += 1_000; h.tick()
        }
        assertEquals(1, h.alerts.size)
    }

    @Test fun `pause stops accounting and alerts`() {
        val h = Harness(target)
        h.seconds(150, 2)
        h.monitor.pause(h.now)
        h.seconds(150, 60)
        assertEquals(0, h.alerts.size)
        assertEquals(MonitorStatus.PAUSED, h.snap().status)
        val active = h.snap().activeDurationMs
        h.monitor.resume(h.now)
        h.seconds(150, 4)
        assertEquals(1, h.alerts.size)
        assertEquals(active + 4_000, h.snap().activeDurationMs)
    }

    @Test fun `time is attributed to zones`() {
        val h = Harness(target)
        h.seconds(120, 10)
        h.seconds(140, 20)
        h.seconds(150, 5)
        val s = h.snap()
        assertEquals(10_000, s.timeBelowMs)
        assertEquals(20_000, s.timeInMs)
        assertEquals(5_000, s.timeAboveMs)
        assertEquals(35_000, s.activeDurationMs)
    }

    @Test fun `falling below the lower limit never triggers above-zone alert`() {
        val h = Harness(target)
        h.seconds(110, 120)
        assertEquals(0, h.alerts.size)
        assertEquals(Zone.BELOW_ZONE, h.snap().zone)
    }

    @Test fun `recovery period zero gives immediate back-in-zone cue`() {
        val h = Harness(target, timing.copy(recoveryMs = 0))
        h.seconds(150, 4)
        h.reading(140)
        assertEquals(1, h.backs.size)
    }

    @Test fun `going back above during recovery cancels the cue`() {
        val h = Harness(target)
        h.seconds(150, 4)
        h.second(140)
        h.seconds(150, 2)
        h.seconds(140, 2)
        assertEquals(0, h.backs.size)
    }

    // ---- multi-zone ----

    @Test fun `below alerts stay quiet during the warm-up until the target is reached`() {
        val h = Harness(target)
        h.seconds(110, 60)
        assertEquals(0, h.belows.size)
        h.seconds(135, 10) // reached the target: below alerts armed
        h.seconds(120, 15)
        // 120 first seen at t=70 s; below confirmation is 10 s
        assertEquals(listOf(80_000L), h.belows.map { it.first })
        assertEquals(ZoneAction.BelowZoneAlert(120, 1, isRepeat = false), h.belows.single().second)
        assertEquals(Zone.BELOW_ZONE, h.snap().zone)
        assertTrue(h.snap().belowConfirmed)
    }

    @Test fun `below alerts repeat and end with a back-in-zone cue`() {
        val h = Harness(target)
        h.seconds(135, 5)
        h.seconds(120, 45)
        // confirmed at 15 s, repeats at 45
        assertEquals(listOf(15_000L, 45_000), h.belows.map { it.first })
        h.seconds(135, 5)
        val (at, back) = h.backs.single()
        assertEquals(53_000, at)
        assertEquals(ZoneAction.BackInZone(135, fromAbove = false), back)
    }

    @Test fun `short dips below the floor do not alert`() {
        val h = Harness(target)
        h.seconds(135, 5)
        repeat(5) { h.seconds(125, 8); h.seconds(135, 2) }
        assertEquals(0, h.belows.size)
    }

    @Test fun `below alerts can be switched off`() {
        val h = Harness(target, timing.copy(alertBelow = false))
        h.seconds(135, 5)
        h.seconds(115, 120)
        assertEquals(0, h.belows.size)
        assertEquals(Zone.BELOW_ZONE, h.snap().zone)
    }

    @Test fun `dropping from above straight to below hands over to the below alert`() {
        val h = Harness(target)
        h.seconds(150, 5) // above alert at 3 s
        h.seconds(120, 15)
        assertEquals(1, h.alerts.size)
        assertEquals(0, h.backs.size, "no back-in-zone cue while below")
        // below confirmed at 15 s, but the 15 s cooldown since the 3 s alert holds it until 18 s
        assertEquals(listOf(18_000L), h.belows.map { it.first })
    }

    @Test fun `with below alerts off, dropping under the floor still ends an above episode`() {
        val h = Harness(target, timing.copy(alertBelow = false))
        h.seconds(150, 5)
        h.seconds(120, 5)
        assertEquals(ZoneAction.BackInZone(120, fromAbove = true), h.backs.single().second)
    }

    @Test fun `free run records zones and never alerts`() {
        val h = Harness(null, zones = zones)
        h.seconds(90, 10)   // zone 0
        h.seconds(120, 10)  // Z2
        h.seconds(150, 10)  // Z4
        h.seconds(185, 10)  // Z5
        assertEquals(0, h.actions.size)
        val s = h.snap()
        assertEquals(listOf(10_000L, 0, 10_000, 0, 10_000, 10_000), s.zoneMs)
        assertEquals(5, s.hrZone)
        assertNull(s.zone)
        assertNull(s.rawZone)
        assertEquals(0L, s.timeInMs + s.timeBelowMs + s.timeAboveMs)
        assertEquals(185, s.bpm)
    }

    @Test fun `zone time and target time are counted together`() {
        val h = Harness(target, zones = zones)
        h.seconds(135, 10) // Z3, in target
        h.seconds(150, 5)  // Z4, above
        val s = h.snap()
        assertEquals(10_000, s.zoneMs[3])
        assertEquals(5_000, s.zoneMs[4])
        assertEquals(10_000, s.timeInMs)
        assertEquals(5_000, s.timeAboveMs)
    }

    @Test fun `changing target mid-run resets episodes and re-arms the warm-up rule`() {
        val h = Harness(target)
        h.seconds(150, 5)
        assertEquals(1, h.alerts.size)
        h.monitor.updateTarget(ZoneTarget(150, 170, "Zone 4"), h.now)
        h.seconds(152, 30)
        assertEquals(1, h.alerts.size, "152 is inside the new target")
        assertEquals(Zone.IN_ZONE, h.snap().zone)
        h.monitor.updateTarget(ZoneTarget(160, 175, "Zone 5"), h.now)
        h.seconds(152, 30)
        assertEquals(0, h.belows.size, "new target not reached yet: no below alert")
    }

    @Test fun `quiet period after an easier step holds alerts, then alerts if still above`() {
        val h = Harness(ZoneTarget(160, 175, "Zone 4"))
        h.seconds(168, 20)
        h.monitor.updateTarget(ZoneTarget(115, 145, "Zones 1–2"), h.now, quietMs = 60_000)
        val changedAt = h.now
        h.seconds(165, 59)
        assertEquals(0, h.alerts.size, "no above alert while heart rate comes down")
        h.seconds(160, 3)
        assertEquals(1, h.alerts.size)
        assertTrue(h.alerts.single().first >= changedAt + 60_000)
    }

    @Test fun `quiet period ends silently when heart rate came down in time`() {
        val h = Harness(ZoneTarget(160, 175, "Zone 4"))
        h.seconds(168, 20)
        h.monitor.updateTarget(ZoneTarget(115, 145, "Zones 1–2"), h.now, quietMs = 60_000)
        h.seconds(158, 30)
        h.seconds(140, 60)
        assertEquals(0, h.alerts.size)
        assertEquals(0, h.backs.size, "no back-in cue without an alert")
    }
}
