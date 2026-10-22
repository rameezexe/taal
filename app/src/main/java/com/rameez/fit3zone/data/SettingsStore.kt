package com.rameez.fit3zone.data

import com.rameez.fit3zone.core.PaceGoal

import com.rameez.fit3zone.core.ActivityType
import android.content.Context
import androidx.core.content.edit
import com.rameez.fit3zone.core.AlertTiming
import com.rameez.fit3zone.core.HeartRateProfile
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.MaxHrSource
import com.rameez.fit3zone.core.RestingHrSource
import com.rameez.fit3zone.core.TargetChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VibrationPattern(val label: String) {
    CONTINUOUS("Continuous"), STRONG_PULSES("Strong pulses"), TRIPLE("Triple pulse"), HEARTBEAT("Double knock"),
}

data class AlertSettings(
    val pattern: VibrationPattern = VibrationPattern.STRONG_PULSES,
    /** Pattern for below-zone alerts; should differ from [pattern] so the two are told apart in a pocket. */
    val belowPattern: VibrationPattern = VibrationPattern.TRIPLE,
    /** 1..255 amplitude; ignored on vibrators without amplitude control. */
    val intensity: Int = 255,
    /** Total length of one alert; the pattern repeats to fill it. */
    val durationMs: Long = 5_000,
) {
    init { require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) }

    companion object {
        const val MIN_DURATION_MS = 1_500L
        const val MAX_DURATION_MS = 10_000L
    }
}

/**
 * Persists the heart-rate profile, alert timing, alert feel and the last chosen run target locally.
 * [profile] stays null until the user enters it, so no zones are ever assumed.
 */
class SettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("zone2", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<HeartRateProfile?> = _profile.asStateFlow()

    private val _timing = MutableStateFlow(readTiming())
    val timing: StateFlow<AlertTiming> = _timing.asStateFlow()

    private val _target = MutableStateFlow(readTarget())
    /** What the Run screen starts with; the service resolves it against the zones at start. */
    val target: StateFlow<TargetChoice> = _target.asStateFlow()

    private val _alerts = MutableStateFlow(readAlerts())
    val alerts: StateFlow<AlertSettings> = _alerts.asStateFlow()

    /** Interval for stored HR samples; null = store only the session summary. */
    private val _sampleIntervalMs = MutableStateFlow(prefs.getLong("sampleIntervalMs", 5_000).takeIf { it > 0 })
    val sampleIntervalMs: StateFlow<Long?> = _sampleIntervalMs.asStateFlow()

    /** Record GPS route/distance/pace during runs. */
    private val _gpsEnabled = MutableStateFlow(prefs.getBoolean("gpsEnabled", true))
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()

    /** Workout chosen on the Run screen; null = run with the chosen target. */
    private val _workoutId = MutableStateFlow(prefs.getString("workoutId", null))
    val workoutId: StateFlow<String?> = _workoutId.asStateFlow()

    /** Pause automatically when GPS speed shows you've stopped (GPS runs only). */
    private val _autoPause = MutableStateFlow(prefs.getBoolean("autoPause", false))
    val autoPause: StateFlow<Boolean> = _autoPause.asStateFlow()

    /** Auto-lap distance in metres on GPS runs (not during structured workouts); 0 = off. Garmin's default: 1 km. */
    private val _autoLapM = MutableStateFlow(prefs.getInt("autoLapM", 1_000))
    val autoLapM: StateFlow<Int> = _autoLapM.asStateFlow()

    /** Spoken cues (km splits, workout steps, laps, zone alerts) through the phone's text-to-speech. */
    private val _audioCues = MutableStateFlow(prefs.getBoolean("audioCues", false))
    val audioCues: StateFlow<Boolean> = _audioCues.asStateFlow()

    /** Activity chosen on the Run screen for the next session. */
    private val _activityType = MutableStateFlow(ActivityType.parse(prefs.getString("activityType", null)))
    val activityType: StateFlow<ActivityType> = _activityType.asStateFlow()

    fun saveActivityType(type: ActivityType) {
        prefs.edit { putString("activityType", type.name) }
        _activityType.value = type
    }

    fun zones(): HrZones? = profile.value?.zones()

    /** Planned route to follow on the next GPS session (RouteStore id); null = none. */
    private val _routeId = MutableStateFlow(prefs.getLong("routeId", 0L).takeIf { it > 0 })
    val routeId: StateFlow<Long?> = _routeId.asStateFlow()

    fun saveRouteId(id: Long?) {
        prefs.edit { if (id == null) remove("routeId") else putLong("routeId", id) }
        _routeId.value = id
    }

    fun saveWorkoutId(id: String?) {
        prefs.edit { if (id == null) remove("workoutId") else putString("workoutId", id) }
        _workoutId.value = id
    }

    fun saveAutoPause(enabled: Boolean) {
        prefs.edit { putBoolean("autoPause", enabled) }
        _autoPause.value = enabled
    }

    /** Even-pace goal for GPS runs (distance + time); kept while the pacer is off so it's ready next time. */
    private val _paceGoal = MutableStateFlow(PaceGoal.decode(prefs.getString("paceGoal", null)) ?: PaceGoal(23_000.0, 190 * 60_000L))
    val paceGoal: StateFlow<PaceGoal> = _paceGoal.asStateFlow()

    private val _pacerOn = MutableStateFlow(prefs.getBoolean("pacerOn", false))
    val pacerOn: StateFlow<Boolean> = _pacerOn.asStateFlow()

    fun savePaceGoal(goal: PaceGoal) {
        prefs.edit { putString("paceGoal", goal.encode()) }
        _paceGoal.value = goal
    }

    fun savePacerOn(on: Boolean) {
        prefs.edit { putBoolean("pacerOn", on) }
        _pacerOn.value = on
    }

    /** Stride (metres per step) measured on good GPS in the last run of [type]; bridges GPS gaps early in the next. */
    fun strideM(type: ActivityType): Double? = prefs.getFloat("strideM_${type.name}", 0f).takeIf { it in 0.3f..2.5f }?.toDouble()

    fun saveStride(type: ActivityType, metres: Double) {
        if (metres in 0.3..2.5) prefs.edit { putFloat("strideM_${type.name}", metres.toFloat()) }
    }

    /** New loop screen: last asked length and the "prefer parks and footpaths" switch. */
    private val _loopTargetM = MutableStateFlow(prefs.getInt("loopTargetM", 5_000))
    val loopTargetM: StateFlow<Int> = _loopTargetM.asStateFlow()
    private val _loopPreferParks = MutableStateFlow(prefs.getBoolean("loopPreferParks", true))
    val loopPreferParks: StateFlow<Boolean> = _loopPreferParks.asStateFlow()

    fun saveLoopOptions(targetM: Int, preferParks: Boolean) {
        prefs.edit { putInt("loopTargetM", targetM); putBoolean("loopPreferParks", preferParks) }
        _loopTargetM.value = targetM
        _loopPreferParks.value = preferParks
    }

    fun saveAutoLap(metres: Int) {
        prefs.edit { putInt("autoLapM", metres) }
        _autoLapM.value = metres
    }

    /** Limits for the band's resting HR (user choice); null = use the band value as measured. */
    private val _bandRestingRange = MutableStateFlow(
        prefs.getInt("bandRestingMin", 0).takeIf { it > 0 }?.let { lo -> lo..prefs.getInt("bandRestingMax", lo) },
    )
    val bandRestingRange: StateFlow<IntRange?> = _bandRestingRange.asStateFlow()

    fun saveBandRestingRange(range: IntRange?) {
        prefs.edit {
            if (range == null) { remove("bandRestingMin"); remove("bandRestingMax") }
            else { putInt("bandRestingMin", range.first); putInt("bandRestingMax", range.last) }
        }
        _bandRestingRange.value = range
    }

    fun saveAudioCues(enabled: Boolean) {
        prefs.edit { putBoolean("audioCues", enabled) }
        _audioCues.value = enabled
    }

    fun saveGpsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("gpsEnabled", enabled) }
        _gpsEnabled.value = enabled
    }

    fun saveSampleInterval(ms: Long?) {
        prefs.edit { putLong("sampleIntervalMs", ms ?: 0) }
        _sampleIntervalMs.value = ms
    }

    fun saveProfile(p: HeartRateProfile) {
        prefs.edit {
            p.age?.let { putInt("age", it) } ?: remove("age")
            putInt("maxHr", p.maxHr)
            putString("maxHrSource", p.maxHrSource.name)
            p.restingHr?.let { putInt("restingHr", it) } ?: remove("restingHr")
            putString("restingSource", p.restingSource?.name)
        }
        _profile.value = p
    }

    fun saveTiming(t: AlertTiming) {
        prefs.edit {
            putLong("confirmMs", t.confirmMs)
            putLong("belowConfirmMs", t.belowConfirmMs)
            putLong("repeatMs", t.repeatMs)
            putLong("cooldownMs", t.cooldownMs)
            putLong("recoveryMs", t.recoveryMs)
            putLong("staleMs", t.staleTimeoutMs)
            putBoolean("backCue", t.backInZoneCue)
            putBoolean("alertBelow", t.alertBelow)
        }
        _timing.value = t
    }

    fun saveTarget(choice: TargetChoice) {
        prefs.edit { putString("target", choice.encode()) }
        _target.value = choice
    }

    fun saveAlerts(settings: AlertSettings) {
        prefs.edit {
            putString("pattern", settings.pattern.name)
            putString("belowPattern", settings.belowPattern.name)
            putInt("intensity", settings.intensity)
            putLong("alertDurationMs", settings.durationMs)
        }
        _alerts.value = settings
    }

    private fun readProfile(): HeartRateProfile? {
        if (!prefs.contains("maxHr")) return null
        return runCatching {
            HeartRateProfile(
                age = prefs.getInt("age", 0).takeIf { prefs.contains("age") },
                maxHr = prefs.getInt("maxHr", 0),
                maxHrSource = MaxHrSource.valueOf(prefs.getString("maxHrSource", null)!!),
                restingHr = prefs.getInt("restingHr", 0).takeIf { prefs.contains("restingHr") },
                restingSource = prefs.getString("restingSource", null)?.let { RestingHrSource.valueOf(it) },
            )
        }.getOrNull()
    }

    private fun readTiming() = runCatching {
        AlertTiming(
            confirmMs = prefs.getLong("confirmMs", 3_000),
            belowConfirmMs = prefs.getLong("belowConfirmMs", 10_000),
            repeatMs = prefs.getLong("repeatMs", 30_000),
            cooldownMs = prefs.getLong("cooldownMs", 15_000),
            recoveryMs = prefs.getLong("recoveryMs", 3_000),
            staleTimeoutMs = prefs.getLong("staleMs", 5_000),
            backInZoneCue = prefs.getBoolean("backCue", true),
            alertBelow = prefs.getBoolean("alertBelow", true),
        )
    }.getOrDefault(AlertTiming())

    /** v0.2 stored a single Zone 2 range as lower/upper: it becomes a custom target. */
    private fun readTarget(): TargetChoice =
        TargetChoice.decode(prefs.getString("target", null))
            ?: if (prefs.contains("lower") && prefs.contains("upper")) {
                TargetChoice.decode("custom:${prefs.getInt("lower", 0)}-${prefs.getInt("upper", 0)}")
            } else {
                null
            }
            ?: TargetChoice.Free

    private fun readAlerts() = AlertSettings(
        pattern = pattern("pattern", VibrationPattern.STRONG_PULSES),
        belowPattern = pattern("belowPattern", VibrationPattern.TRIPLE),
        intensity = prefs.getInt("intensity", 255).coerceIn(1, 255),
        durationMs = prefs.getLong("alertDurationMs", 5_000)
            .coerceIn(AlertSettings.MIN_DURATION_MS, AlertSettings.MAX_DURATION_MS),
    )

    private fun pattern(key: String, default: VibrationPattern) =
        runCatching { VibrationPattern.valueOf(prefs.getString(key, null)!!) }.getOrDefault(default)

    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context.applicationContext).also { instance = it } }
    }
}
