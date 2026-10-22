package com.rameez.fit3zone.core

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutScheduleTest {
    private val d = LocalDate.of(2026, 10, 18)

    @Test fun `encode and decode round trip`() {
        val s = WorkoutSchedule().with(d.plusDays(3), "b").with(d, "a")
        assertEquals("2026-10-18\ta\n2026-10-21\tb", s.encode())
        assertEquals(s, WorkoutSchedule.decode(s.encode()))
    }

    @Test fun `decode skips malformed lines`() {
        val s = WorkoutSchedule.decode("2026-10-18\ta\nnot a date\tb\n2026-10-19\t\n2026-10-20\n\n2026-10-21\tc")
        assertEquals(mapOf(d to "a", d.plusDays(3) to "c"), s.days)
        assertEquals(WorkoutSchedule(), WorkoutSchedule.decode(null))
    }

    @Test fun `one workout per day, replaced by a new plan`() {
        val s = WorkoutSchedule().with(d, "a").with(d, "b")
        assertEquals("b", s[d])
        assertNull(s.without(d)[d])
    }

    @Test fun `deleting a workout drops its days`() {
        val s = WorkoutSchedule().with(d, "a").with(d.plusDays(1), "b").with(d.plusDays(2), "a")
        assertEquals(mapOf(d.plusDays(1) to "b"), s.withoutWorkout("a").days)
    }

    @Test fun `upcoming and next ignore past days`() {
        val s = WorkoutSchedule().with(d.minusDays(1), "a").with(d.plusDays(4), "a").with(d, "a").with(d.plusDays(2), "b")
        assertEquals(listOf(d, d.plusDays(4)), s.upcoming("a", d))
        assertEquals(d to "a", s.next(d))
        assertEquals(d.plusDays(2) to "b", s.next(d.plusDays(1)))
        assertNull(s.next(d.plusDays(5)))
    }

    @Test fun `status is done when the workout was run that day`() {
        assertEquals(PlanStatus.DONE, WorkoutSchedule.status(d.minusDays(2), d, "4x4", listOf("Easy", "4x4")))
        assertEquals(PlanStatus.DONE, WorkoutSchedule.status(d, d, "4x4", listOf("4x4")))
    }

    @Test fun `status is missed only for past days`() {
        assertEquals(PlanStatus.MISSED, WorkoutSchedule.status(d.minusDays(1), d, "4x4", listOf("Easy")))
        assertEquals(PlanStatus.PLANNED, WorkoutSchedule.status(d, d, "4x4", emptyList()))
        assertEquals(PlanStatus.PLANNED, WorkoutSchedule.status(d.plusDays(1), d, "4x4", emptyList()))
    }
}
