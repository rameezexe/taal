package com.rameez.fit3zone.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.rameez.fit3zone.data.HeartRateRepository

/**
 * The phone's step detector during a session (for cadence). Each step is handed to [onStep] with
 * its time in elapsed-realtime ms (same clock as heart-rate readings), on a private thread.
 */
class StepTracker(private val context: Context, private val onStep: (Long) -> Unit) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onStep(event.timestamp / 1_000_000)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Returns false (and logs) without permission or without a step detector. */
    fun start(): Boolean {
        if (!hasPermission()) {
            HeartRateRepository.log.add("steps", "physical activity permission not granted; no cadence")
            return false
        }
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: run {
            HeartRateRepository.log.add("steps", "no step detector on this phone")
            return false
        }
        stop()
        val t = HandlerThread("Fit3Zone-steps").apply { start() }
        thread = t
        // Fastest delivery: the detector only reports steps, so this costs nothing extra.
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
        HeartRateRepository.log.add("steps", "step detector on")
        return true
    }

    fun stop() {
        sensors?.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
    }
}
