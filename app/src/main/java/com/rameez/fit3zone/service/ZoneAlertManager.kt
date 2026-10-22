package com.rameez.fit3zone.service

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.rameez.fit3zone.data.AlertSettings
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.VibrationPattern

/**
 * Phone vibration for zone events. Uses the ALARM usage so the cue is delivered while the app is
 * backgrounded and the screen is off; Do Not Disturb settings that block alarms will still block it.
 * Calls return immediately (the platform plays the waveform), so nothing here blocks a thread.
 */
class ZoneAlertManager(context: Context) {
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    val hasVibrator: Boolean get() = vibrator.hasVibrator()
    val hasAmplitudeControl: Boolean get() = vibrator.hasAmplitudeControl()

    fun aboveZone(settings: AlertSettings) {
        val timings = patternTimings(settings.pattern, settings.durationMs)
        play(timings, settings.intensity, "above-zone ${settings.pattern} ${settings.durationMs}ms")
    }

    /** Same length and strength as above-zone, but the user's separate below pattern. */
    fun belowZone(settings: AlertSettings) {
        val timings = patternTimings(settings.belowPattern, settings.durationMs)
        play(timings, settings.intensity, "below-zone ${settings.belowPattern} ${settings.durationMs}ms")
    }

    /** Two short firm taps: clearly different from the long above-zone alert. */
    fun backInZone(settings: AlertSettings) {
        play(longArrayOf(0, 250, 200, 250), settings.intensity, "back-in-zone")
    }

    /** Workout step change: one long and one short buzz, unlike any zone alert. */
    fun stepChange(settings: AlertSettings) {
        play(longArrayOf(0, 900, 250, 200), settings.intensity, "step-change")
    }

    /** Lap button acknowledged: a single short tap. */
    fun lapTap(settings: AlertSettings) {
        play(longArrayOf(0, 120), settings.intensity, "lap")
    }

    /** Left the planned route: four quick taps then a long buzz, unlike any zone or workout alert. */
    fun offRoute(settings: AlertSettings) {
        play(longArrayOf(0, 150, 100, 150, 100, 150, 100, 150, 250, 700), settings.intensity, "off-route")
    }

    /** Back on the planned route: two medium buzzes. */
    fun backOnRoute(settings: AlertSettings) {
        play(longArrayOf(0, 400, 200, 400), settings.intensity, "back-on-route")
    }

    /** Auto-pause / auto-resume: two quick light taps. */
    fun autoPauseTap(settings: AlertSettings) {
        play(longArrayOf(0, 80, 120, 80), settings.intensity, "auto-pause")
    }

    /**
     * Waveform timings [off, on, off, on, ...] starting with a 0 ms delay. Long on-times feel
     * strongest because the motor reaches full acceleration; amplitude is already at the user's max.
     */
    internal fun patternTimings(pattern: VibrationPattern, durationMs: Long): LongArray {
        if (pattern == VibrationPattern.CONTINUOUS) return longArrayOf(0, durationMs)
        // one cycle as (on, off) pairs
        val cycle: List<Pair<Long, Long>> = when (pattern) {
            VibrationPattern.STRONG_PULSES -> listOf(700L to 150L)
            VibrationPattern.TRIPLE -> listOf(350L to 120L, 350L to 120L, 350L to 500L)
            VibrationPattern.HEARTBEAT -> listOf(250L to 120L, 500L to 450L)
            VibrationPattern.CONTINUOUS -> error("handled above")
        }
        val out = mutableListOf(0L)
        var elapsed = 0L
        while (elapsed < durationMs) {
            for ((on, off) in cycle) {
                if (elapsed >= durationMs) break
                val onMs = minOf(on, durationMs - elapsed)
                out += onMs
                elapsed += onMs
                if (elapsed >= durationMs) break
                out += off
                elapsed += off
            }
        }
        if (out.size % 2 != 0) out.removeAt(out.lastIndex) // end on an "on" segment
        return out.toLongArray()
    }

    private fun play(timings: LongArray, amplitude: Int, label: String) {
        if (!vibrator.hasVibrator()) {
            HeartRateRepository.log.add("alert", "no vibrator; skipped $label")
            return
        }
        // Waveform amplitudes: 0 for gaps, the chosen amplitude for pulses.
        val amps = IntArray(timings.size) { i -> if (i % 2 == 1) amplitude.coerceIn(1, 255) else 0 }
        val effect = if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1)
        else VibrationEffect.createWaveform(timings, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
            )
        }
        HeartRateRepository.log.add("alert", "vibrate $label")
    }
}
