package com.rameez.fit3zone.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rameez.fit3zone.data.HeartRateRepository

/**
 * 1 Hz high-accuracy GPS for the duration of a run. Callbacks arrive on a private thread and are
 * handed to [onFix]; nothing here touches the main thread.
 */
class LocationTracker(private val context: Context, private val onFix: (Location) -> Unit) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var thread: HandlerThread? = null

    /** Latest satellite status: used in fix / visible, and the mean signal (C/N0 dB-Hz) of the 4 strongest. */
    @Volatile var satellites: String = "-"
        private set

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val n = status.satelliteCount
            val used = (0 until n).count { status.usedInFix(it) }
            val top = (0 until n).map { status.getCn0DbHz(it) }.sortedDescending().take(4)
            satellites = "$used/$n cn0=${if (top.isEmpty()) "-" else top.average().toInt()}"
        }

        override fun onStopped() { satellites = "off" }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(onFix)
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns false (and logs) when location permission is missing. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun start(): Boolean {
        if (!hasPermission()) {
            HeartRateRepository.log.add("gps", "location permission not granted; run without GPS")
            return false
        }
        if (thread != null) return true
        val t = HandlerThread("fit3-gps").apply { start() }
        thread = t
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(1_000)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, t.looper)
            .addOnFailureListener { HeartRateRepository.log.add("gps", "location updates failed: ${it.message}") }
        // Diagnostics only: tells "phone has no satellites" (pocket, trees) apart from fixes being thrown away.
        runCatching { locationManager?.registerGnssStatusCallback(gnssCallback, Handler(t.looper)) }
        HeartRateRepository.log.add("gps", "GPS started")
        return true
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(onResult: (Location?) -> Unit) {
        if (!hasPermission()) return onResult(null)
        client.lastLocation.addOnSuccessListener { onResult(it) }.addOnFailureListener { onResult(null) }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
        runCatching { locationManager?.unregisterGnssStatusCallback(gnssCallback) }
        satellites = "-"
        thread?.quitSafely()
        thread = null
    }
}
