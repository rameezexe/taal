package com.rameez.fit3zone.service

import com.rameez.fit3zone.APP_NAME
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.R
import com.rameez.fit3zone.ble.Fit3BleManager
import com.rameez.fit3zone.core.MonitorStatus
import com.rameez.fit3zone.core.ReadingSource
import com.rameez.fit3zone.core.SimulatedSequence
import com.rameez.fit3zone.core.TargetChoice
import com.rameez.fit3zone.core.formatClockDuration
import com.rameez.fit3zone.core.Zone
import com.rameez.fit3zone.core.ZoneTarget
import com.rameez.fit3zone.data.BleConnectionState
import com.rameez.fit3zone.data.DevicePrefs
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SessionRepository
import com.rameez.fit3zone.data.SettingsStore
import com.rameez.fit3zone.data.WorkoutStore
import com.rameez.fit3zone.data.RouteStore
import com.rameez.fit3zone.ui.MainActivity
import com.rameez.fit3zone.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the BLE link and the run session alive while the app is in the background or the
 * screen is off. Type connectedDevice: the app is talking to a BLE peripheral.
 *
 * The service lives while the band is connected or a session is running, whichever is longer.
 */
class HeartRateForegroundService : Service() {

    private var ble: Fit3BleManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = HeartRateRepository
    private lateinit var session: SessionController
    private var notificationLoopStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = SessionController(applicationContext, scope, ZoneAlertManager(applicationContext))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must satisfy the startForeground contract, even ones that end up stopping.
        if (!enterForeground()) return START_NOT_STICKY
        startNotificationUpdates()

        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDRESS)?.let(::connect)
            ACTION_DISCONNECT -> {
                disconnectBle()
                stopIfIdle()
            }
            ACTION_START_SESSION -> startSession(intent.getBooleanExtra(EXTRA_SIMULATED, false))
            ACTION_PAUSE_SESSION -> session.pause()
            ACTION_RESUME_SESSION -> session.resume()
            ACTION_LAP -> session.lap()
            ACTION_VOICE -> session.setVoice(intent.getBooleanExtra(EXTRA_VOICE_ON, false))
            ACTION_STOP_ROUTE -> session.stopFollowingRoute()
            ACTION_END_SESSION -> {
                endSession()
                stopIfIdle()
            }
            ACTION_STOP_ALL -> {
                endSession()
                disconnectBle()
                stopIfIdle()
            }
            null -> {
                // System restart after the process was killed: reconnect to the saved band.
                // An interrupted session is not resumed silently; the user restarts it.
                val address = DevicePrefs(this).device?.address
                if (address != null) connect(address) else stopIfIdle()
            }
        }
        return START_STICKY
    }

    private fun connect(address: String) {
        val manager = ble ?: Fit3BleManager(applicationContext).also { ble = it }
        manager.connect(address)
    }

    private fun disconnectBle() {
        ble?.shutdown()
        ble = null
    }

    private fun startSession(simulated: Boolean) {
        val settings = SettingsStore.get(this)
        val zones = settings.zones()
        val choice = settings.target.value
        val saved = WorkoutStore.get(this).get(settings.workoutId.value)
        val workout = saved?.let { s ->
            runCatching { WorkoutPlan(s.workout.name, s.workout.plan(zones)) }.getOrElse {
                repo.log.add("session", "not started: workout '${s.workout.name}' needs heart-rate zones (set age / max HR)")
                stopIfIdle()
                return
            }
        }
        // Zone targets need a heart-rate profile; free runs and custom ranges don't.
        if (workout == null && choice is TargetChoice.Zones && zones == null) {
            repo.log.add("session", "not started: ${choice.encode()} needs heart-rate zones (set age / max HR)")
            stopIfIdle()
            return
        }
        val target = zones?.let { choice.resolve(it) } ?: (choice as? TargetChoice.Custom)?.let { ZoneTarget(it.lowerBpm, it.upperBpm, "Custom") }
        val app = application as Fit3ZoneApp
        val type = settings.activityType.value
        val useGps = settings.gpsEnabled.value && type.usesGps
        if (simulated) {
            app.simulator.start(SimulatedSequence.parse(SimulatedSequence.ZONE_TOUR))
        } else {
            app.simulator.stop()
            if (ble == null) DevicePrefs(this).device?.address?.let(::connect)
            if (useGps) enterForegroundWithLocation()
        }
        val planned = if (useGps) RouteStore.get(this).get(settings.routeId.value) else null
        session.start(target, zones, simulated, useGps, type, workout, planned)
    }

    private fun endSession() {
        session.stop()
        (application as Fit3ZoneApp).simulator.stop()
    }

    /** Stop the service once nothing needs it; launched so a pending session.stop() runs first. */
    private fun stopIfIdle() = scope.launch {
        delay(100)
        if (ble == null && !session.sessionActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enterForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(APP_NAME, "Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        repo.log.add("service", "foreground start not allowed: ${e.message}")
        stopSelf()
        false
    } catch (e: SecurityException) {
        // Android 14+: connectedDevice type requires BLUETOOTH_CONNECT at runtime.
        repo.log.add("service", "foreground start refused (permission): ${e.message}")
        stopSelf()
        false
    }

    /**
     * Location updates in the background need the location FGS type, which may only be added while
     * the app is in the foreground (true here: the user just tapped Start) and permission is granted.
     */
    private fun enterForegroundWithLocation() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            startForeground(
                NOTIFICATION_ID, buildNotification(APP_NAME, "Starting run…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: RuntimeException) {
            repo.log.add("service", "could not add location to foreground service: ${e.message}")
        }
    }

    /** Refresh the ongoing notification at most once per second; staleness needs a ticker anyway. */
    private fun startNotificationUpdates() {
        if (notificationLoopStarted) return
        notificationLoopStarted = true
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            var last: Pair<String, String>? = null
            while (isActive) {
                val content = notificationContent()
                if (content != last) {
                    nm.notify(NOTIFICATION_ID, buildNotification(content.first, content.second))
                    last = content
                }
                delay(1_000)
            }
        }
    }

    private fun notificationContent(): Pair<String, String> {
        val now = SystemClock.elapsedRealtime()
        val s = SessionRepository.session.value?.takeIf { it.isActive }
        val reading = repo.latest.value
        val fresh = repo.isFresh(now)
        val conn = repo.connection.value

        if (s != null) {
            val snap = s.snapshot
            val sim = if (s.simulated) " · SIMULATED" else ""
            // Same calm display as the Run screen: the last heart rate is held between readings (alerts still pause on stale data).
            val held = snap.bpm ?: reading?.bpm
            val title = when {
                snap.status == MonitorStatus.PAUSED -> (if (s.autoPaused) "Auto-paused" else "Paused") + sim
                held == null -> "Waiting for heart rate…$sim"
                snap.bpm != null -> listOfNotNull("$held BPM", snap.hrZone?.let { "Z$it" }, zoneLabel(snap.zone, s.target?.label).ifEmpty { null })
                    .joinToString(" · ") + sim
                else -> listOfNotNull("$held BPM", s.zones?.zoneOf(held)?.let { "Z$it" }).joinToString(" · ") + sim
            }
            val gps = if (s.gps == com.rameez.fit3zone.data.GpsStatus.OFF) "" else
                " · ${"%.2f".format(s.distanceM / 1000)} km${if (s.distanceEstimated) " est." else ""} · ${com.rameez.fit3zone.core.formatPace(s.paceSecPerKm)}/km" +
                    (if (s.gpsLost) " · GPS lost" else if (s.powerSaver) " · Power saving on" else "")
            val target = s.target?.let { "${it.label} ${it.lowerBpm}–${it.upperBpm}" } ?: "Free run"
            val w = s.workout
            val step = when {
                w == null -> null
                w.finished -> "Workout done"
                else -> "${w.step.title} · " + (w.remainingMs?.let { "${formatClockDuration(it)} left" }
                    ?: w.remainingM?.let { "${it.toInt()} m left" } ?: "lap when done")
            }
            val text = listOfNotNull(step, target, formatDuration(snap.activeDurationMs) + gps).joinToString(" · ") +
                if (s.target != null && w == null) " · ${snap.alertCount} alerts" else ""
            return title to text
        }

        val title = if (reading != null && conn != BleConnectionState.Idle && (fresh || reading.source == ReadingSource.BLE)) {
            "${reading.bpm} BPM" + if (reading.source == ReadingSource.SIMULATED) " (simulated)" else ""
        } else {
            "-- BPM"
        }
        val text = describe(conn)
        return title to text
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lap = PendingIntent.getService(
            this, 2, Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_LAP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val inSession = ::session.isInitialized && session.sessionActive
        val voiceOn = SessionRepository.session.value?.voiceOn == true
        val voice = PendingIntent.getService(
            this, 3, Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_VOICE).putExtra(EXTRA_VOICE_ON, !voiceOn),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .apply {
                if (inSession) {
                    addAction(0, SettingsStore.get(this@HeartRateForegroundService).activityType.value.lapWord, lap)
                    addAction(0, if (voiceOn) "Mute voice" else "Voice on", voice)
                }
            }
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Live heart rate", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing notification while the band is connected or a session runs"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        session.stop()
        ble?.shutdown()
        ble = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "live_hr"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CONNECT = "com.rameez.fit3zone.CONNECT"
        private const val ACTION_DISCONNECT = "com.rameez.fit3zone.DISCONNECT"
        private const val ACTION_START_SESSION = "com.rameez.fit3zone.START_SESSION"
        private const val ACTION_PAUSE_SESSION = "com.rameez.fit3zone.PAUSE_SESSION"
        private const val ACTION_RESUME_SESSION = "com.rameez.fit3zone.RESUME_SESSION"
        private const val ACTION_END_SESSION = "com.rameez.fit3zone.END_SESSION"
        private const val ACTION_STOP_ALL = "com.rameez.fit3zone.STOP_ALL"
        private const val ACTION_LAP = "com.rameez.fit3zone.LAP"
        private const val ACTION_VOICE = "com.rameez.fit3zone.VOICE"
        private const val ACTION_STOP_ROUTE = "com.rameez.fit3zone.STOP_ROUTE"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_VOICE_ON = "voiceOn"
        private const val EXTRA_SIMULATED = "simulated"

        private fun send(context: Context, action: String, build: Intent.() -> Unit = {}) {
            ContextCompat.startForegroundService(
                context, Intent(context, HeartRateForegroundService::class.java).setAction(action).apply(build),
            )
        }

        fun connect(context: Context, address: String) = send(context, ACTION_CONNECT) { putExtra(EXTRA_ADDRESS, address) }
        fun disconnect(context: Context) = send(context, ACTION_DISCONNECT)
        fun startSession(context: Context, simulated: Boolean) = send(context, ACTION_START_SESSION) { putExtra(EXTRA_SIMULATED, simulated) }
        fun pauseSession(context: Context) = send(context, ACTION_PAUSE_SESSION)
        fun resumeSession(context: Context) = send(context, ACTION_RESUME_SESSION)
        fun endSession(context: Context) = send(context, ACTION_END_SESSION)
        fun lap(context: Context) = send(context, ACTION_LAP)
        /** Mute or unmute spoken cues for the running session; vibration alerts keep going either way. */
        fun setVoice(context: Context, on: Boolean) = send(context, ACTION_VOICE) { putExtra(EXTRA_VOICE_ON, on) }
        /** Stop the off-route alerts for the rest of the running session (the route stays on the map). */
        fun stopFollowingRoute(context: Context) = send(context, ACTION_STOP_ROUTE)

        /** "ABOVE ZONE 2", "IN ZONES 2–3", "BELOW CUSTOM"; empty for a free run. */
        fun zoneLabel(zone: Zone?, targetLabel: String?): String {
            val t = targetLabel?.uppercase() ?: return ""
            return when (zone) {
                Zone.ABOVE_ZONE -> "ABOVE $t"
                Zone.IN_ZONE -> "IN $t"
                Zone.BELOW_ZONE -> "BELOW $t"
                null -> "—"
            }
        }

        fun describe(state: BleConnectionState): String = when (state) {
            BleConnectionState.Idle -> "Not connected"
            BleConnectionState.BluetoothOff -> "Bluetooth is off"
            BleConnectionState.MissingPermission -> "Nearby devices permission missing"
            is BleConnectionState.Connecting -> "Connecting…"
            BleConnectionState.DiscoveringServices -> "Discovering services…"
            BleConnectionState.Subscribing -> "Subscribing to heart rate…"
            BleConnectionState.Subscribed -> "Connected"
            is BleConnectionState.WaitingToReconnect ->
                "Reconnecting in ${state.delayMs / 1000}s (attempt ${state.attempt})"
        }
    }
}
