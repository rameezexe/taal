package com.rameez.fit3zone.core

import kotlin.math.roundToInt

/**
 * What kind of activity a session is. Decides whether GPS is used, whether pace (min/km) or speed
 * (km/h) is shown, the auto-pause speeds, and whether the lap button counts laps or sets.
 * Stored by [name] in the history database, so names must never change.
 */
enum class ActivityType(
    val label: String,
    /** Records a GPS route (if GPS is on in Settings). Indoor / gym activities never use location. */
    val usesGps: Boolean,
    /** Speed in km/h instead of pace in min/km. */
    val showsSpeed: Boolean = false,
    /** Auto-pause: stopped below this speed, moving again above the resume speed (m/s). */
    val autoPauseStopMps: Double = 0.8,
    val autoPauseResumeMps: Double = 1.6,
) {
    RUN("Run", usesGps = true),
    WALK("Walk", usesGps = true, autoPauseStopMps = 0.3, autoPauseResumeMps = 0.7),
    CYCLE("Cycle", usesGps = true, showsSpeed = true, autoPauseStopMps = 1.5, autoPauseResumeMps = 3.0),
    TREADMILL("Treadmill", usesGps = false),
    STRENGTH("Strength", usesGps = false),
    OTHER("Other", usesGps = false),
    ;

    /** Cadence from the phone's step detector makes sense (on foot). */
    val tracksCadence: Boolean get() = this == RUN || this == WALK || this == TREADMILL || this == OTHER

    /** What the lap button records: sets in the gym, laps everywhere else. */
    val lapWord: String get() = if (this == STRENGTH) "Set" else "Lap"

    /** "Pace" or "Speed". */
    val paceLabel: String get() = if (showsSpeed) "Speed" else "Pace"

    /** "/km" or "km/h". */
    val paceUnit: String get() = if (showsSpeed) "km/h" else "/km"

    /** Pace ("5:12") or speed ("18.5") from seconds per km, depending on the type. */
    fun formatPaceOrSpeed(secPerKm: Double?): String = if (showsSpeed) formatSpeed(secPerKm) else formatPace(secPerKm)

    /** Spoken pace or speed for voice cues. */
    fun spokenPaceOrSpeed(secPerKm: Double): String =
        if (showsSpeed) "Speed ${"%.1f".format(3600 / secPerKm)} kilometres per hour" else "Pace ${Cues.spokenPace(secPerKm)}"

    companion object {
        /** Unknown or missing names (older rows, future types) read as a run. */
        fun parse(name: String?): ActivityType = entries.firstOrNull { it.name == name } ?: RUN
    }
}

/** km/h with one decimal from seconds per km; "--" when unknown or slower than 1 km/h. */
fun formatSpeed(secPerKm: Double?): String {
    if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0 || secPerKm > 3600) return "--"
    val tenths = (36_000 / secPerKm).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
