package com.rameez.fit3zone.data

import android.content.Context
import androidx.core.content.edit
import com.rameez.fit3zone.core.Workout
import com.rameez.fit3zone.core.WorkoutSchedule
import com.rameez.fit3zone.core.WorkoutTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.UUID

data class SavedWorkout(val id: String, val workout: Workout)

/**
 * The user's workout library, kept in SharedPreferences as one line per workout
 * (`id \t name \t steps`, steps in [Workout.encodeSteps] form). Seeded with [WorkoutTemplates] once.
 * The calendar plan ([schedule]) is stored next to it as [WorkoutSchedule.encode] text.
 */
class WorkoutStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("workouts", Context.MODE_PRIVATE)

    private val _workouts = MutableStateFlow(read())
    val workouts: StateFlow<List<SavedWorkout>> = _workouts.asStateFlow()

    private val _schedule = MutableStateFlow(WorkoutSchedule.decode(prefs.getString(SCHEDULE_KEY, null)))
    val schedule: StateFlow<WorkoutSchedule> = _schedule.asStateFlow()

    fun get(id: String?): SavedWorkout? = id?.let { i -> _workouts.value.firstOrNull { it.id == i } }

    /** Inserts or replaces; returns the id. */
    fun save(workout: Workout, id: String? = null): String {
        val key = id ?: UUID.randomUUID().toString()
        val list = _workouts.value.toMutableList()
        val i = list.indexOfFirst { it.id == key }
        if (i >= 0) list[i] = SavedWorkout(key, workout) else list += SavedWorkout(key, workout)
        write(list)
        return key
    }

    fun duplicate(id: String): String? = get(id)?.let { save(it.workout.copy(name = it.workout.name + " (copy)")) }

    fun delete(id: String) {
        write(_workouts.value.filter { it.id != id })
        writeSchedule(_schedule.value.withoutWorkout(id))
    }

    /** Plans [workoutId] on [day], replacing whatever was planned there. */
    fun plan(day: LocalDate, workoutId: String) = writeSchedule(_schedule.value.with(day, workoutId))

    fun unplan(day: LocalDate) = writeSchedule(_schedule.value.without(day))

    private fun writeSchedule(s: WorkoutSchedule) {
        prefs.edit { putString(SCHEDULE_KEY, s.encode()) }
        _schedule.value = s
    }

    private fun write(list: List<SavedWorkout>) {
        persist(list)
        _workouts.value = list
    }

    private fun persist(list: List<SavedWorkout>) = prefs.edit {
        putString(KEY, list.joinToString("\n") { "${it.id}\t${clean(it.workout.name)}\t${it.workout.encodeSteps()}" })
    }

    private fun read(): List<SavedWorkout> {
        val raw = prefs.getString(KEY, null)
            ?: return WorkoutTemplates.all.mapIndexed { i, w -> SavedWorkout("template-$i", w) }.also { persist(it) }
        return raw.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            Workout.decode(parts[1], parts[2])?.let { SavedWorkout(parts[0], it) }
        }
    }

    private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').trim().ifEmpty { "Workout" }

    companion object {
        private const val KEY = "list"
        private const val SCHEDULE_KEY = "schedule"
        @Volatile private var instance: WorkoutStore? = null
        fun get(context: Context): WorkoutStore =
            instance ?: synchronized(this) { instance ?: WorkoutStore(context.applicationContext).also { instance = it } }
    }
}
