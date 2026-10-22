package com.rameez.fit3zone.data

import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.Lap
import com.rameez.fit3zone.core.PacerStatus
import com.rameez.fit3zone.core.RoutePoint
import com.rameez.fit3zone.core.WorkoutProgress
import com.rameez.fit3zone.core.ZoneSnapshot
import com.rameez.fit3zone.core.ZoneTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GpsStatus { OFF, NO_PERMISSION, SEARCHING, OK }

data class SessionState(
    val simulated: Boolean,
    val type: ActivityType,
    val startedWallClockMs: Long,
    /** Null for a free run. */
    val target: ZoneTarget?,
    /** Null when no heart-rate profile is set. */
    val zones: HrZones?,
    val snapshot: ZoneSnapshot,
    val endedWallClockMs: Long? = null,
    val gps: GpsStatus = GpsStatus.OFF,
    val gpsAccuracyM: Float? = null,
    val distanceM: Double = 0.0,
    val paceSecPerKm: Double? = null,
    val avgPaceSecPerKm: Double? = null,
    /** Set while a structured workout runs (stays on the last step, finished = true, once done). */
    val workoutName: String? = null,
    val workout: WorkoutProgress? = null,
    /** Laps closed so far (lap button / workout steps). */
    val laps: List<Lap> = emptyList(),
    /** True while paused by auto-pause rather than by the user. */
    val autoPaused: Boolean = false,
    /** The lap running now; null until laps are in use (auto-lap, lap button or workout). */
    val currentLap: Lap? = null,
    /** GPS route so far (only points that counted toward distance). */
    val route: List<GeoPoint> = emptyList(),
    /** Session heart-rate average / peak so far (valid readings only). */
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    /** Steps per minute now / session average; null without cadence (no permission, sensor or not on foot). */
    val cadenceSpm: Int? = null,
    val avgCadenceSpm: Int? = null,
    val steps: Int? = null,
    /** Spoken cues on for this session (Settings default, toggled mid-run); vibrations are separate. */
    val voiceOn: Boolean = false,
    /** Even-pace goal progress; null when the pacer is off (or the session has no GPS). */
    val pacer: PacerStatus? = null,
    /** No GPS fix for 30 s while moving (distance continues from steps when known). */
    val gpsLost: Boolean = false,
    /** Power saving is limiting GPS (off with the screen off). */
    val powerSaver: Boolean = false,
    /** [distanceM] includes steps × stride for a GPS gap going on now. */
    val distanceEstimated: Boolean = false,
    /** Planned route being followed (GPS sessions with a route chosen); null otherwise. */
    val plannedRoute: RouteProgress? = null,
) {
    val isActive: Boolean get() = endedWallClockMs == null
}

/** Following a planned route: where the runner is on it and whether they've left it. */
data class RouteProgress(
    val name: String,
    val points: List<RoutePoint>,
    val distanceM: Double,
    /** False until the runner first reaches the route (walking to the start). */
    val started: Boolean,
    val remainingM: Double,
    val offRoute: Boolean,
    /** Distance from the route at the last fix, metres. */
    val awayM: Double?,
    val complete: Boolean,
    /** False after "Stop following" mid-run (no off-route alerts any more). */
    val following: Boolean = true,
)

/** The current (or most recently finished) session, published by the service. */
object SessionRepository {
    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    fun publish(state: SessionState?) {
        _session.value = state
    }
}
