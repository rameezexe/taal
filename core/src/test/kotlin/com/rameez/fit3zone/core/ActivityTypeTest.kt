package com.rameez.fit3zone.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTypeTest {
    @Test fun `parse falls back to run`() {
        assertEquals(ActivityType.CYCLE, ActivityType.parse("CYCLE"))
        assertEquals(ActivityType.RUN, ActivityType.parse(null))
        assertEquals(ActivityType.RUN, ActivityType.parse("SWIM"))
    }

    @Test fun `indoor types never use gps`() {
        assertTrue(ActivityType.RUN.usesGps && ActivityType.WALK.usesGps && ActivityType.CYCLE.usesGps)
        assertFalse(ActivityType.TREADMILL.usesGps || ActivityType.STRENGTH.usesGps || ActivityType.OTHER.usesGps)
    }

    @Test fun `speed formatting`() {
        assertEquals("18.0", formatSpeed(200.0)) // 3:20 /km
        assertEquals("12.4", formatSpeed(290.0))
        assertEquals("--", formatSpeed(null))
        assertEquals("--", formatSpeed(4000.0))
        assertEquals("18.0", ActivityType.CYCLE.formatPaceOrSpeed(200.0))
        assertEquals("3:20", ActivityType.RUN.formatPaceOrSpeed(200.0))
    }

    @Test fun `cues use speed for cycling and sets for strength`() {
        assertEquals("Kilometre 2. Speed 18.0 kilometres per hour.", Cues.split(2, 200.0, null, ActivityType.CYCLE))
        assertEquals("Set 3. 45 seconds.", Cues.lap(Lap(3, 0, 45_000, 0.0, null, null, null), ActivityType.STRENGTH))
    }

    @Test fun `walk auto-pause is gentler than run`() {
        val d = AutoPauseDetector(ActivityType.WALK.autoPauseStopMps, ActivityType.WALK.autoPauseResumeMps)
        // 0.5 m/s is a slow walk: a run would pause, a walk must not.
        for (t in 0..10_000L step 1_000) assertNull(d.onSpeed(t, 0.5))
    }

    @Test fun `distance and pace records count runs only`() {
        val run = ActivityRecord(1, 0, 1_800_000, 0, 0, 0, 5_000.0)
        val ride = ActivityRecord(2, 0, 3_600_000, 0, 0, 0, 30_000.0, type = ActivityType.CYCLE)
        val r = TrainingStats.records(listOf(run, ride))
        assertEquals(1L, r.longestDistance?.id)
        assertEquals(1L, r.fastestAvgPace?.first?.id)
        assertEquals(2L, r.longestDuration?.id)
    }
}
