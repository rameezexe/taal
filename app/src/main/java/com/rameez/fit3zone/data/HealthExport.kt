package com.rameez.fit3zone.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.core.ElevationProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

data class HealthExportState(
    /** User turned it on (and granted the write permissions at that time). */
    val enabled: Boolean = false,
    /** Session ids written to Health Connect. */
    val written: Set<Long> = emptySet(),
    val progress: String? = null,
    val message: String? = null,
)

/**
 * Writes finished activities into Health Connect (exercise session with route, heart rate, distance, elevation
 * gain), where Samsung Health and other apps pick them up. Steps are not written: Samsung Health already counts
 * them from the band and phone, and ours would be counted twice. Every record has a client id per session, so
 * writing a session again replaces it instead of adding a copy.
 */
class HealthExport private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("healthExport", Context.MODE_PRIVATE)
    private val history = SessionHistory.get(context)
    private val devices = DevicePrefs(context)
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val lock = Mutex()

    private val _state = MutableStateFlow(
        HealthExportState(
            enabled = prefs.getBoolean("enabled", false),
            written = prefs.getStringSet("written", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
        ),
    )
    val state: StateFlow<HealthExportState> = _state.asStateFlow()

    suspend fun hasPermissions(): Boolean = runCatching {
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }.getOrDefault(false)

    /** Called after the permission dialog; turns export on only when everything was granted. */
    suspend fun enable(): Boolean {
        val ok = hasPermissions()
        if (ok) prefs.edit { putBoolean("enabled", true); putLong("enabledAt", System.currentTimeMillis()) }
        _state.update {
            it.copy(enabled = ok, message = if (ok) null else "Health Connect access wasn't granted for all items (exercise, route, heart rate, distance, elevation)")
        }
        return ok
    }

    fun disable() {
        prefs.edit { putBoolean("enabled", false) }
        _state.update { it.copy(enabled = false, message = null) }
    }

    fun exportable(s: SessionSummary): Boolean =
        !s.simulated && s.status != SessionStatus.IN_PROGRESS && s.activeMs >= 60_000

    fun notWritten(sessions: List<SessionSummary>): List<SessionSummary> =
        sessions.filter { exportable(it) && it.id !in _state.value.written }.sortedBy { it.startedAt }

    suspend fun write(s: SessionSummary): Result<Unit> = lock.withLock {
        val result = runCatching {
            val records = withContext(SessionHistory.dispatcher) { records(s) }
            client.insertRecords(records)
            Unit
        }
        result.onSuccess {
            val written = _state.value.written + s.id
            prefs.edit { putStringSet("written", written.map(Long::toString).toSet()) }
            _state.update { it.copy(written = written) }
        }
        HeartRateRepository.log.add("health", "session ${s.id} -> Health Connect: ${result.exceptionOrNull()?.let { "failed: ${it.message}" } ?: "written"}")
        result
    }

    /** Writes the session again (same client ids, newer version → replaced) when it's in Health Connect already. */
    suspend fun rewriteIfWritten(s: SessionSummary): Result<Boolean> =
        if (_state.value.enabled && s.id in _state.value.written) write(s).map { true } else Result.success(false)

    /**
     * Removes a session's records from Health Connect (call before deleting it here: the heart-rate record count
     * comes from the stored samples). Not written → nothing to do.
     */
    suspend fun remove(s: SessionSummary): Result<Unit> = lock.withLock {
        if (s.id !in _state.value.written) return Result.success(Unit)
        val result = runCatching {
            val hrParts = withContext(SessionHistory.dispatcher) { (history.samples(s.id).size + 999) / 1_000 }
            fun ids(vararg suffix: String) = suffix.map { "fit3zone-${s.id}-$it" }
            client.deleteRecords(ExerciseSessionRecord::class, emptyList(), ids("session"))
            client.deleteRecords(HeartRateRecord::class, emptyList(), (0 until hrParts).map { "fit3zone-${s.id}-hr$it" })
            client.deleteRecords(DistanceRecord::class, emptyList(), ids("distance"))
            client.deleteRecords(ElevationGainedRecord::class, emptyList(), ids("elevation"))
        }
        result.onSuccess {
            val written = _state.value.written - s.id
            prefs.edit { putStringSet("written", written.map(Long::toString).toSet()) }
            _state.update { it.copy(written = written) }
        }
        HeartRateRepository.log.add("health", "session ${s.id} removed from Health Connect: ${result.exceptionOrNull()?.let { "failed: ${it.message}" } ?: "ok"}")
        result
    }

    suspend fun writeHistory(sessions: List<SessionSummary>) {
        val todo = notWritten(sessions)
        var failed = 0
        todo.forEachIndexed { i, s ->
            _state.update { it.copy(progress = "Writing ${i + 1} of ${todo.size}…") }
            if (write(s).isFailure) failed++
        }
        _state.update {
            it.copy(progress = null, message = if (failed == 0) "Sent ${todo.size} ${if (todo.size == 1) "activity" else "activities"} to Health Connect" else "$failed of ${todo.size} failed (see Diagnostics log)")
        }
    }

    /** Writes each activity of at least [AUTO_SYNC_MIN_MS] that finishes after export was turned on. */
    fun watch(scope: CoroutineScope) {
        val tried = mutableSetOf<Long>()
        scope.launch {
            history.sessions.collect { list ->
                // Forget activities deleted here (e.g. before deleting also removed them from Health Connect).
                val gone = _state.value.written - list.map { it.id }.toSet()
                if (list.isNotEmpty() && gone.isNotEmpty()) {
                    val written = _state.value.written - gone
                    prefs.edit { putStringSet("written", written.map(Long::toString).toSet()) }
                    _state.update { it.copy(written = written) }
                }
                if (!_state.value.enabled) return@collect
                val since = prefs.getLong("enabledAt", Long.MAX_VALUE)
                list.filter { it.status == SessionStatus.COMPLETED && it.reviewed && SYNC_HEALTH !in it.skipSync && exportable(it) && it.activeMs >= AUTO_SYNC_MIN_MS && (it.endedAt ?: 0) >= since && it.id !in _state.value.written && tried.add(it.id) }
                    .forEach { launch { write(it) } }
            }
        }
    }

    private fun records(s: SessionSummary): List<Record> {
        val start = Instant.ofEpochMilli(s.startedAt)
        val end = Instant.ofEpochMilli(s.endedAt ?: (s.startedAt + s.activeMs)).coerceAtLeast(start.plusSeconds(1))
        val zone = ZoneId.systemDefault().rules
        val startOffset = zone.getOffset(start)
        val endOffset = zone.getOffset(end)
        val version = System.currentTimeMillis()
        val phone = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)
        val sensor = devices.device.let { d ->
            when (d?.kind) {
                DeviceKind.FIT3 -> Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Samsung", model = "Galaxy Fit3")
                DeviceKind.WATCH -> Device(type = Device.TYPE_WATCH, model = d.displayName)
                DeviceKind.STRAP -> Device(type = Device.TYPE_CHEST_STRAP, model = d.displayName)
                null -> Device(type = Device.TYPE_UNKNOWN)
            }
        }
        fun meta(suffix: String, device: Device) = Metadata.activelyRecorded(device, "fit3zone-${s.id}-$suffix", version)
        fun inSession(t: Instant) = !t.isBefore(start) && !t.isAfter(end)

        val points = history.points(s.id)
        val route = points.map { p ->
            ExerciseRoute.Location(
                time = Instant.ofEpochMilli(s.startedAt + p.offsetMs), latitude = p.lat, longitude = p.lon,
                horizontalAccuracy = Length.meters(p.accuracyM.toDouble()), altitude = p.altitudeM?.let(Length::meters),
            )
        }.filter { inSession(it.time) }

        return buildList {
            add(
                ExerciseSessionRecord(
                    startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                    metadata = meta("session", phone), exerciseType = exerciseType(s.type),
                    title = s.title, notes = s.notes(devices.device),
                    exerciseRoute = route.takeIf { it.isNotEmpty() }?.let(::ExerciseRoute),
                ),
            )
            val hr = history.samples(s.id).map { HeartRateRecord.Sample(Instant.ofEpochMilli(s.startedAt + it.offsetMs), it.bpm.toLong()) }
                .filter { inSession(it.time) && it.beatsPerMinute in 1..300 }
            // Several records keep each one a reasonable size on long sessions.
            hr.chunked(1_000).forEachIndexed { i, chunk ->
                add(HeartRateRecord(chunk.first().time, startOffset, chunk.last().time.plusMillis(1), endOffset, chunk, meta("hr$i", sensor)))
            }
            if (s.distanceM > 0) add(DistanceRecord(start, startOffset, end, endOffset, Length.meters(s.distanceM), meta("distance", phone)))
            ElevationProfile.of(points)?.takeIf { it.gainM > 0 }?.let {
                add(ElevationGainedRecord(start, startOffset, end, endOffset, Length.meters(it.gainM), meta("elevation", phone)))
            }
        }
    }

    private fun exerciseType(t: ActivityType) = when (t) {
        ActivityType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        ActivityType.TREADMILL -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        ActivityType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        ActivityType.CYCLE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        ActivityType.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        ActivityType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(ElevationGainedRecord::class),
        )

        @Volatile private var instance: HealthExport? = null
        fun get(context: Context): HealthExport =
            instance ?: synchronized(this) { instance ?: HealthExport(context.applicationContext).also { instance = it } }
    }
}
