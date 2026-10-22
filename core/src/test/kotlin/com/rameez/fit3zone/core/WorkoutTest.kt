package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutTest {
    private val zones = HrZones(listOf(132, 144, 156, 168, 181), maxHr = 193)
    private fun z(a: Int, b: Int = a) = TargetChoice.Zones(a, b)
    private val intervals = Workout("4x4", listOf(
        WorkoutBlock(listOf(WorkoutStep(StepKind.WARMUP, StepEnd.Time(600_000), z(1, 2)))),
        WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Distance(800.0), z(4)), WorkoutStep(StepKind.RECOVER, StepEnd.Time(120_000), z(1, 2))), repeats = 4),
        WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, StepEnd.LapPress))),
    ))

    @Test fun `encode and decode round trip`() {
        val s = intervals.encodeSteps()
        assertEquals("1x[W,t600,zones:1-2]/4x[R,d800,zones:4-4;V,t120,zones:1-2]/1x[C,lap,free]", s)
        assertEquals(intervals, Workout.decode("4x4", s))
        WorkoutTemplates.all.forEach { assertEquals(it, Workout.decode(it.name, it.encodeSteps())) }
    }

    @Test fun `decode rejects garbage`() {
        assertNull(Workout.decode("x", ""))
        assertNull(Workout.decode("x", "2x[R,t0,free]")) // 0 s step
        assertNull(Workout.decode("x", "1x[Q,t60,free]"))
        assertNull(Workout.decode("x", "1x[R,t60,zones:4-2]"))
    }

    @Test fun `plan flattens repeats and resolves zone targets`() {
        val plan = intervals.plan(zones)
        assertEquals(10, plan.size)
        assertEquals(10, intervals.stepCount)
        assertEquals("Warm-up", plan[0].title)
        assertEquals(ZoneTarget(132, 155, "Zones 1–2"), plan[0].target)
        assertEquals("Run 1/4", plan[1].title)
        assertEquals(ZoneTarget(168, 180, "Zone 4"), plan[1].target)
        assertEquals("Recover 4/4", plan[8].title)
        assertNull(plan[9].target)
        assertEquals("800 m", plan[1].goal)
        assertEquals("2:00", plan[2].goal)
        assertEquals("Lap button", plan[9].goal)
        assertEquals(600_000L + 4 * 120_000L, intervals.timedMs)
        assertEquals(3200.0, intervals.distanceM)
        assertTrue(intervals.needsZones)
        assertFailsWith<IllegalArgumentException> { intervals.plan(null) }
    }

    @Test fun `runner advances on time, distance and lap press`() {
        val r = WorkoutRunner(intervals.plan(zones))
        val first = r.start(0, 0.0)
        assertEquals(0, first.step.index)
        assertEquals(emptyList(), r.update(599_000, 1_500.0))
        r.progress(599_000, 1_500.0)!!.let {
            assertEquals(1_000L, it.remainingMs)
            assertEquals(0.998f, it.fraction!!, 0.001f)
            assertEquals("Run 1/4", it.next!!.title)
        }
        val e = r.update(600_500, 1_502.0).single()
        assertIs<WorkoutEvent.StepStarted>(e).let { assertEquals("Run 1/4", it.step.title); assertEquals(false, it.byLap) }
        // the 800 m step counts from the distance at the step change
        r.progress(700_000, 2_000.0)!!.let { assertEquals(302.0, it.remainingM!!, 0.01); assertNull(it.remainingMs) }
        val toRecover = r.update(800_000, 2_310.0).single() as WorkoutEvent.StepStarted
        assertEquals("Recover 1/4", toRecover.step.title)
        // recovery started when 800 m was reached; its timer starts at that tick
        r.progress(800_000 + 60_000, 2_400.0)!!.let { assertEquals(60_000L, it.remainingMs) }
        // lap press ends a timed step early
        val byLap = r.lap(830_000, 2_420.0).single() as WorkoutEvent.StepStarted
        assertEquals("Run 2/4", byLap.step.title)
        assertTrue(byLap.byLap)
        r.progress(830_000, 2_420.0)!!.let { assertEquals(800.0, it.remainingM!!, 0.01) }
    }

    @Test fun `timed steps chain on exact boundaries even with a late tick`() {
        val w = Workout("t", listOf(WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Time(60_000)), WorkoutStep(StepKind.RECOVER, StepEnd.Time(30_000))), repeats = 2)))
        val r = WorkoutRunner(w.plan(null))
        r.start(10_000, 0.0)
        // one late tick crosses two boundaries (70 s and 100 s)
        val events = r.update(101_000, 0.0)
        assertEquals(listOf("Recover 1/2", "Run 2/2"), events.map { (it as WorkoutEvent.StepStarted).step.title })
        assertEquals(59_000L, r.progress(101_000, 0.0)!!.remainingMs)
    }

    @Test fun `workout finishes after the last step and stops emitting`() {
        val w = Workout("t", listOf(WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Time(60_000)), WorkoutStep(StepKind.COOLDOWN, StepEnd.LapPress)))))
        val r = WorkoutRunner(w.plan(null))
        r.start(0, 0.0)
        r.update(60_000, 0.0)
        assertEquals(emptyList(), r.update(10_000_000, 9_999.0), "lap step never ends by itself")
        val fin = r.lap(400_000, 900.0).single()
        assertIs<WorkoutEvent.Finished>(fin)
        assertTrue(r.finished)
        assertEquals(emptyList(), r.lap(500_000, 1000.0))
        assertEquals(emptyList(), r.update(600_000, 1000.0))
        assertNull(r.progress(600_000, 1000.0)!!.next)
    }

    @Test fun `templates are valid and plan with zones`() {
        assertEquals(4, WorkoutTemplates.all.size)
        WorkoutTemplates.all.forEach { assertTrue(it.plan(zones).isNotEmpty()) }
        assertEquals(1, WorkoutTemplates.all.count { it.usesDistance })
    }

    @Test fun `goal formatting`() {
        assertEquals("1 km", PlannedStep.describe(StepEnd.Distance(1000.0)))
        assertEquals("1.5 km", PlannedStep.describe(StepEnd.Distance(1500.0)))
        assertEquals("400 m", PlannedStep.describe(StepEnd.Distance(400.0)))
        assertEquals("1:30", PlannedStep.describe(StepEnd.Time(90_000)))
        assertEquals("1:00:00", PlannedStep.describe(StepEnd.Time(3_600_000)))
    }
}

class LiveRunTest {
    @Test fun `laps carry duration, distance, heart rate and labels`() {
        val t = LapTracker()
        t.begin(0, 0.0, "Warm-up")
        listOf(120, 130, 140).forEach(t::addHeartRate)
        val l1 = t.startLap(600_000, 1_500.0, "Run 1/4")
        assertEquals(Lap(1, 0, 600_000, 1_500.0, 130, 140, "Warm-up"), l1)
        assertEquals(400.0, l1.paceSecPerKm!!, 0.01)
        t.addHeartRate(170)
        val l2 = t.startLap(800_000, 2_300.0)
        assertEquals(2, l2.number)
        assertEquals(800.0, l2.distanceM, 0.01)
        assertEquals("Run 1/4", l2.label)
        assertNull(t.finish(800_500, 2_301.0), "under a second: no empty last lap")
        assertEquals(2, t.laps.size)
    }

    @Test fun `auto-lap is due per lap distance and a manual lap restarts the count`() {
        val t = LapTracker()
        t.begin(0, 0.0, null)
        assertFalse(t.autoLapDue(999.0, 1_000.0))
        assertTrue(t.autoLapDue(1_004.0, 1_000.0))
        t.startLap(300_000, 1_004.0)
        assertFalse(t.autoLapDue(1_900.0, 1_000.0), "counts from the lap start, not from 0")
        assertTrue(t.autoLapDue(2_004.0, 1_000.0))
        t.startLap(400_000, 1_500.0) // manual lap mid-way
        assertFalse(t.autoLapDue(2_400.0, 1_000.0))
        assertTrue(t.autoLapDue(2_500.0, 1_000.0))
        assertFalse(t.autoLapDue(5_000.0, 0.0), "off")
    }

    @Test fun `current lap reports the running lap without closing it`() {
        val t = LapTracker()
        t.begin(0, 0.0, null)
        t.startLap(300_000, 1_000.0)
        listOf(140, 150).forEach(t::addHeartRate)
        assertEquals(Lap(2, 300_000, 60_000, 250.0, 145, 150, null), t.current(360_000, 1_250.0))
        assertEquals(1, t.laps.size)
    }

    @Test fun `a run without laps stores none, a manual lap closes the rest on finish`() {
        val t = LapTracker()
        t.begin(0, 0.0, null)
        assertNull(t.finish(600_000, 2_000.0))
        val m = LapTracker()
        m.begin(0, 0.0, null)
        m.startLap(300_000, 1_000.0)
        val last = m.finish(600_000, 2_100.0)!!
        assertEquals(2, last.number)
        assertEquals(1_100.0, last.distanceM, 0.01)
        assertNull(last.avgBpm)
    }

    @Test fun `auto pause needs a sustained stop and resumes with hysteresis`() {
        val d = AutoPauseDetector()
        assertNull(d.onSpeed(0, 3.0))
        assertNull(d.onSpeed(1_000, 0.3))
        assertNull(d.onSpeed(3_000, 0.2))
        assertNull(d.onSpeed(4_000, 1.2), "walking resets the stop timer")
        assertNull(d.onSpeed(5_000, 0.1))
        assertNull(d.onSpeed(9_000, 0.1))
        assertEquals(AutoPauseEvent.PAUSE, d.onSpeed(10_000, 0.0))
        assertNull(d.onSpeed(11_000, 1.2), "walking pace doesn't resume")
        assertNull(d.onSpeed(12_000, 2.5))
        assertNull(d.onSpeed(13_000, 1.0), "a single fast fix doesn't resume")
        assertNull(d.onSpeed(14_000, 2.5))
        assertEquals(AutoPauseEvent.RESUME, d.onSpeed(16_000, 2.8))
        d.reset(paused = true)
        assertTrue(d.paused)
    }

    @Test fun `spoken cues`() {
        assertEquals("Kilometre 3. Pace 6 minutes 12 per kilometre. Heart rate 148.", Cues.split(3, 372.4, 148))
        assertEquals("Kilometre 1.", Cues.split(1, null, null))
        val zones = HrZones(listOf(132, 144, 156, 168, 181), maxHr = 193)
        val w = Workout("t", listOf(
            WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Distance(800.0), TargetChoice.Zones(4, 4)), WorkoutStep(StepKind.RECOVER, StepEnd.Time(90_000), TargetChoice.Zones(1, 2))), repeats = 4),
            WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, StepEnd.LapPress))),
        )).plan(zones)
        assertEquals("Run, 1 of 4. 800 metres. Zone 4.", Cues.step(w[0]))
        assertEquals("Recover, 1 of 4. 1 minute 30 seconds. Zones 1 to 2.", Cues.step(w[1]))
        assertEquals("Cool-down. Press lap when done.", Cues.step(w[8]))
        assertEquals("Lap 2. 4 minutes. Pace 5 minutes per kilometre. Heart rate 160.", Cues.lap(Lap(2, 0, 240_000, 800.0, 160, 170, null)))
        assertEquals("1 hour 5 minutes", Cues.spokenDuration(3_900_000))
        assertEquals("0 seconds", Cues.spokenDuration(0))
        assertEquals("1.5 kilometres", Cues.spokenDistance(1500.0))
    }
}
