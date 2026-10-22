package com.rameez.fit3zone.core

import java.time.LocalDate

enum class PlanStatus { PLANNED, DONE, MISSED }

/**
 * Workouts planned on calendar days: at most one per day, by workout id. Immutable; the app
 * persists it with [encode] / [decode].
 */
data class WorkoutSchedule(val days: Map<LocalDate, String> = emptyMap()) {

    operator fun get(day: LocalDate): String? = days[day]

    fun with(day: LocalDate, workoutId: String) = WorkoutSchedule(days + (day to workoutId))

    fun without(day: LocalDate) = WorkoutSchedule(days - day)

    /** Drops every day planned with [workoutId] (the workout was deleted). */
    fun withoutWorkout(workoutId: String) = WorkoutSchedule(days.filterValues { it != workoutId })

    /** Days planned with [workoutId] from [from] on, soonest first. */
    fun upcoming(workoutId: String, from: LocalDate): List<LocalDate> =
        days.filter { (d, id) -> id == workoutId && d >= from }.keys.sorted()

    /** The first planned day on or after [from], if any. */
    fun next(from: LocalDate): Pair<LocalDate, String>? =
        days.filterKeys { it >= from }.minByOrNull { it.key }?.toPair()

    /** One line per day: `2026-10-21 \t workoutId`. */
    fun encode(): String = days.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        /** Done = a session that day ran the planned workout (matched by name); missed = a past day without one. */
        fun status(day: LocalDate, today: LocalDate, workoutName: String, workoutsRunThatDay: Collection<String>): PlanStatus = when {
            workoutName in workoutsRunThatDay -> PlanStatus.DONE
            day < today -> PlanStatus.MISSED
            else -> PlanStatus.PLANNED
        }

        /** Ignores malformed lines. */
        fun decode(raw: String?): WorkoutSchedule = WorkoutSchedule(
            raw.orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2 || parts[1].isBlank()) return@mapNotNull null
                runCatching { LocalDate.parse(parts[0]) }.getOrNull()?.let { it to parts[1] }
            }.toMap(),
        )
    }
}
