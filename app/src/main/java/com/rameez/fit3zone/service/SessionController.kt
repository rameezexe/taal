package com.rameez.fit3zone.service

import com.rameez.fit3zone.core.CadenceTracker
import com.rameez.fit3zone.core.ActivityType
import android.content.Context
import android.location.Location
import android.os.PowerManager
import android.os.SystemClock
import com.rameez.fit3zone.core.AutoPauseDetector
import com.rameez.fit3zone.core.AutoPauseEvent
import com.rameez.fit3zone.core.Cues
import com.rameez.fit3zone.core.Freshness
import com.rameez.fit3zone.core.LapTracker
import com.rameez.fit3zone.core.PacerCues
import com.rameez.fit3zone.core.PacerStatus
import com.rameez.fit3zone.core.formatPace
import com.rameez.fit3zone.core.PlannedStep
import com.rameez.fit3zone.core.WorkoutEvent
import com.rameez.fit3zone.core.WorkoutRunner
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.RouteTracker
import com.rameez.fit3zone.core.RouteEvent
import com.rameez.fit3zone.core.RouteFollower
import com.rameez.fit3zone.data.RouteProgress
import com.rameez.fit3zone.data.SavedRoute
import com.rameez.fit3zone.core.HrSample
import com.rameez.fit3zone.core.ReadingSource
import com.rameez.fit3zone.core.SessionRecorder
import com.rameez.fit3zone.core.AlertTiming
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.SustainedPeak
import com.rameez.fit3zone.core.ZoneAction
import com.rameez.fit3zone.core.ZoneMonitor
import com.rameez.fit3zone.core.ZoneSnapshot
import com.rameez.fit3zone.core.ZoneTarget
import com.rameez.fit3zone.data.BpmStats
import com.rameez.fit3zone.data.GpsStatus
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.CadenceSample
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionStatus
import com.rameez.fit3zone.data.SessionRepository
import com.rameez.fit3zone.data.SessionState
import com.rameez.fit3zone.data.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A structured workout ready to run: its name and steps with targets resolved. */
class WorkoutPlan(val name: String, val steps: List<PlannedStep>)

/**
 * Runs one session: feeds readings and a 500 ms tick into [ZoneMonitor] on a single
 * thread, turns monitor actions into vibrations, and publishes snapshots for UI/notification.
 * Holds a partial wake lock for the session so timers keep running with the screen off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionController(
    private val context: Context,
    parentScope: CoroutineScope,
    private val alerts: ZoneAlertManager,
) {
    private val settings = SettingsStore.get(context)
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(parentScope.coroutineContext + dispatcher)
    private var job: Job? = null
    private var monitor: ZoneMonitor? = null
    private var state: SessionState? = null
    private var paused = false
    private val history = SessionHistory.get(context)
    private var recorder: SessionRecorder? = null
    private var sessionId: Long? = null
    private var startElapsedMs = 0L
    private var simulated = false
    private var peak = SustainedPeak()

    // Workout, laps, auto-pause, voice
    private var runner: WorkoutRunner? = null
    private var workoutName: String? = null
    private var laps = LapTracker()
    private var autoPause: AutoPauseDetector? = null
    private var autoPaused = false
    private var voice: VoiceCues? = null
    private var voiceOn = false
    private var splitsAnnounced = 0
    /** Auto-lap distance for this session; 0 = off (no GPS, a workout, or turned off in Settings). */
    private var autoLapM = 0.0
    private var activityType = ActivityType.RUN
    /** Even-pace goal for this session (GPS runs with the pacer on); null otherwise. */
    private var pacer: PacerCues? = null
    /** Planned route being followed (GPS sessions with a route chosen); null otherwise. */
    private var follower: RouteFollower? = null
    /** False after "Stop following" mid-run: progress is still tracked, but no more off-route alerts or cues. */
    private var routeAlertsOn = true

    // Cadence (phone step detector); null when not tracked this session
    private val stepTracker = StepTracker(context) { tMs -> scope.launch { onStep(tMs) } }
    private var cadence: CadenceTracker? = null
    private var pendingCadence = mutableListOf<CadenceSample>()
    private var lastCadenceSampleMs = 0L

    // GPS
    private val location = LocationTracker(context) { fix -> scope.launch { onFix(fix) } }
    private var route: RouteTracker? = null
    private var pendingPoints = mutableListOf<GeoPoint>()
    private val segmentBreaks = mutableListOf<Long>()
    private var gpsStatus = GpsStatus.OFF
    private var gpsAccuracy: Float? = null
    /** No accepted fix for [GPS_LOST_MS] while moving: told once ("GPS lost"), and again when it's back. */
    private var gpsLost = false
    private var gpsLostAtMs = 0L
    /** Power saving with its GPS rule on (checked every few seconds during GPS sessions). */
    private var powerSaver = false
    private var lastPowerCheckMs = 0L
    /** Route so far for the live map; a new list per accepted fix so published states stay immutable. */
    private var routePoints: List<GeoPoint> = emptyList()
    // GPS diagnostics for the log: fixes received, the last one's time, and the current run of dropped fixes.
    private var fixCount = 0
    private var lastRawFixMs: Long? = null
    private var dropStartMs: Long? = null
    private var dropCount = 0
    private var dropWorstAccuracy = 0f
    private var altitudeSource: String? = null
    private val altitudeConverter by lazy { if (android.os.Build.VERSION.SDK_INT >= 34) MslAltitude(context) else null }

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fit3Zone:session")
        .apply { setReferenceCounted(false) }

    val sessionActive: Boolean get() = state?.isActive == true

    fun start(
        target: ZoneTarget?, zones: HrZones?, simulated: Boolean, useGps: Boolean, type: ActivityType, workout: WorkoutPlan? = null,
        plannedRoute: SavedRoute? = null,
    ) = scope.launch {
        if (sessionActive) return@launch
        val now = SystemClock.elapsedRealtime()
        val source = if (simulated) ReadingSource.SIMULATED else ReadingSource.BLE
        val timing = settings.timing.value
        // A workout's first step sets the starting target.
        val startTarget = if (workout != null) workout.steps.first().target else target
        val m = ZoneMonitor(startTarget, zones, timing, source, now)
        monitor = m
        paused = false
        this@SessionController.simulated = simulated
        peak = SustainedPeak()
        HeartRateRepository.freshness = Freshness(timing.staleTimeoutMs)
        val startedAt = System.currentTimeMillis()
        runner = workout?.let { WorkoutRunner(it.steps) }
        workoutName = workout?.name
        laps = LapTracker()
        autoPaused = false
        activityType = type
        autoPause = if (useGps && settings.autoPause.value) AutoPauseDetector(type.autoPauseStopMps, type.autoPauseResumeMps) else null
        voice = if (settings.audioCues.value) VoiceCues(context) else null
        voiceOn = voice != null
        splitsAnnounced = 0
        autoLapM = if (useGps && workout == null) settings.autoLapM.value.toDouble() else 0.0
        pacer = if (useGps && settings.pacerOn.value) PacerCues(settings.paceGoal.value) else null
        follower = if (useGps) plannedRoute?.route?.takeIf { it.points.size >= 2 }?.let { RouteFollower(it) } else null
        routeAlertsOn = true
        routePoints = emptyList()
        pendingCadence = mutableListOf()
        lastCadenceSampleMs = 0L
        cadence = if (type.tracksCadence && (simulated || stepTracker.hasPermission())) CadenceTracker() else null
        state = SessionState(simulated, type, startedAt, startTarget, zones, m.snapshot(now), workoutName = workoutName, voiceOn = voiceOn)
        startElapsedMs = now
        val interval = settings.sampleIntervalMs.value
        recorder = SessionRecorder(interval)
        route = if (useGps) RouteTracker(defaultStrideM = settings.strideM(type)) else null
        if (cadence != null) route?.addSteps(0, 0)
        fixCount = 0; lastRawFixMs = null; dropStartMs = null; dropCount = 0; altitudeSource = null
        gpsLost = false; lastPowerCheckMs = now
        powerSaver = useGps && !simulated && com.rameez.fit3zone.ui.powerSaverLimitsGps(context)
        pendingPoints = mutableListOf()
        segmentBreaks.clear()
        gpsStatus = when {
            !useGps -> GpsStatus.OFF
            !location.hasPermission() -> GpsStatus.NO_PERMISSION
            else -> GpsStatus.SEARCHING
        }
        gpsAccuracy = null
        sessionId = runCatching {
            withContext(SessionHistory.dispatcher) {
                history.start(
                    startedAt, simulated, startTarget, zones, timing, interval, hasGps = useGps, type = type, workoutName = workout?.name,
                    routeId = plannedRoute?.id.takeIf { follower != null }, routeName = follower?.route?.name,
                )
            }
        }.onFailure { HeartRateRepository.log.add("session", "history unavailable: ${it.message}") }.getOrNull()
        if (!sessionActive) return@launch // stopped while the row was being created
        runner.let { r ->
            if (r == null) {
                laps.begin(0, 0.0, null)
            } else {
                val first = r.start(0, 0.0)
                laps.begin(0, 0.0, first.step.title)
                voice?.say(Cues.step(first.step))
            }
        }
        publish(now)
        wakeLock.acquire(MAX_SESSION_MS)
        val targetInfo = workout?.let { "workout '${it.name}' (${it.steps.size} steps)" }
            ?: target?.let { "${it.label} ${it.lowerBpm}-${it.upperBpm}" } ?: "free run"
        HeartRateRepository.log.add("session", "start ${if (simulated) "SIMULATED" else "real"} ${type.name} gps=$useGps $targetInfo BPM zones=${zones?.floors}")
        if (powerSaver) {
            HeartRateRepository.log.add("gps", "WARNING power saving is on: GPS is switched off while the screen is off")
            voice?.say("Power saving is on. GPS stops when the screen is off, so distance will be estimated from your steps.")
        }
        follower?.route?.let { r -> HeartRateRepository.log.add("route", "following '${r.name}' ${r.distanceM.toInt()} m, ${r.points.size} points${if (r.isLoop) " (loop)" else ""}") }
        pacer?.goal?.let { g -> HeartRateRepository.log.add("pacer", "goal ${g.distanceM.toInt()} m in ${g.timeMs / 1000}s (${formatPace(g.paceSecPerKm)} /km)") }

        job = launch {
            launch {
                HeartRateRepository.latest.filterNotNull().collect { r ->
                    val actions = m.onReading(r, SystemClock.elapsedRealtime())
                    if (m.lastAccepted === r) {
                        recorder?.add(r.elapsedMs - startElapsedMs, r.bpm)
                        route?.addHeartRate(r.elapsedMs - startElapsedMs, r.bpm)
                        peak.add(r.elapsedMs - startElapsedMs, r.bpm)
                        laps.addHeartRate(r.bpm)
                    }
                    handle(actions)
                }
            }
            launch {
                settings.timing.collect { t ->
                    if (t != m.timing) {
                        m.updateTiming(t)
                        HeartRateRepository.freshness = Freshness(t.staleTimeoutMs)
                        HeartRateRepository.log.add("session", "alert timing updated")
                    }
                }
            }
            if (gpsStatus == GpsStatus.SEARCHING) {
                if (simulated) launch { simulateRoute() } else location.start()
            }
            if (cadence != null) {
                if (simulated) launch { simulateSteps() } else if (!stepTracker.start()) cadence = null
            }
            var lastSummaryMs = now
            while (isActive) {
                val t = SystemClock.elapsedRealtime()
                handle(m.onTick(t))
                runner?.let { r -> handleWorkout(r.update(m.snapshot(t).activeDurationMs, distance()), t) }
                checkAutoLap(t)
                announceSplits()
                updatePacer(t)
                checkGps(t)
                sampleCadence(t)
                publish(t)
                if (t - lastSummaryMs >= SUMMARY_EVERY_MS) {
                    // Periodic evidence that samples keep flowing (e.g. with the screen off).
                    lastSummaryMs = t
                    val s = m.snapshot(t)
                    persist(s, m.target, recorder?.drain().orEmpty(), SessionStatus.IN_PROGRESS)
                    val gpsInfo = route?.let {
                        " dist=${distance().toInt()}m bridged=${bridged().toInt()}m stride=${it.calibratedStrideM?.let { m -> "%.2f".format(m) }} gps=$gpsStatus acc=${gpsAccuracy?.toInt()} sats=${location.satellites} fixes=$fixCount " +
                            "kept=${it.points.size} dropAcc=${it.rejectedAccuracy} dropJump=${it.rejectedJump}" +
                            (follower?.let { f -> " route=${f.progressM.toInt()}/${f.route.distanceM.toInt()}m away=${f.distanceFromRouteM?.toInt()} off=${f.offRoute}" } ?: "")
                    } ?: ""
                    val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
                    HeartRateRepository.log.add(
                        "session",
                        "t=${s.activeDurationMs / 1000}s ${s.status} bpm=${s.bpm} z=${s.hrZone} target=${s.zone} " +
                            "valid=${HeartRateRepository.stats.value.valid} above=${s.timeAboveMs / 1000}s below=${s.timeBelowMs / 1000}s " +
                            "alerts=${s.aboveAlerts}+${s.belowAlerts} gaps=${s.dataInterruptions} screen=${if (screenOn) "on" else "off"}$gpsInfo",
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    fun pause() = scope.launch {
        if (doPause(auto = false)) autoPause?.reset(paused = true)
    }

    fun resume() = scope.launch {
        if (doResume(auto = false)) autoPause?.reset(paused = false)
    }

    /** Mute / unmute spoken cues for the rest of this session only (Settings keeps its default). Vibrations are unaffected. */
    fun setVoice(on: Boolean) = scope.launch {
        if (!sessionActive || on == voiceOn) return@launch
        voiceOn = on
        if (on) {
            val v = voice ?: VoiceCues(context).also { voice = it }
            v.muted = false
        } else {
            voice?.muted = true
        }
        HeartRateRepository.log.add("voice", if (on) "cues on (mid-run)" else "cues muted (mid-run)")
        publish(SystemClock.elapsedRealtime())
    }

    /** "Stop following" mid-run (the runner left the route on purpose): no more off-route alerts or route cues. */
    fun stopFollowingRoute() = scope.launch {
        val f = follower ?: return@launch
        if (!sessionActive || !routeAlertsOn) return@launch
        routeAlertsOn = false
        HeartRateRepository.log.add("route", "stopped following at ${f.progressM.toInt()} m (${(f.followedFraction * 100).toInt()} % followed)")
        publish(SystemClock.elapsedRealtime())
    }

    /** Lap button: ends the current workout step, or closes a manual lap outside a workout. */
    fun lap() = scope.launch {
        val m = monitor ?: return@launch
        if (!sessionActive || paused) return@launch
        val t = SystemClock.elapsedRealtime()
        handle(m.onTick(t))
        val active = m.snapshot(t).activeDurationMs
        val r = runner
        if (r != null && !r.finished) {
            handleWorkout(r.lap(active, distance()), t)
        } else {
            val lap = laps.startLap(active, distance())
            alerts.lapTap(settings.alerts.value)
            voice?.say(Cues.lap(lap, activityType))
            HeartRateRepository.log.add("session", "lap ${lap.number}: ${lap.durationMs / 1000}s ${lap.distanceM.toInt()}m avg=${lap.avgBpm}")
        }
        publish(t)
    }

    /** Runs on the controller thread. Returns false if there was nothing to pause. */
    private fun doPause(auto: Boolean): Boolean {
        val m = monitor ?: return false
        if (!sessionActive || paused) return false
        m.pause(SystemClock.elapsedRealtime())
        paused = true
        route?.let { if (!it.inGap(SystemClock.elapsedRealtime() - startElapsedMs)) it.finish() }
        autoPaused = auto
        HeartRateRepository.log.add("session", if (auto) "auto-paused" else "paused")
        return true
    }

    private fun doResume(auto: Boolean): Boolean {
        val m = monitor ?: return false
        if (!sessionActive || !paused) return false
        val t = SystemClock.elapsedRealtime()
        m.resume(t)
        paused = false
        autoPaused = false
        cadence?.resetWindow()
        route?.let {
            it.breakSegment()
            segmentBreaks += t - startElapsedMs
        }
        lastRawFixMs = null; dropStartMs = null // a pause isn't a GPS gap
        HeartRateRepository.log.add("session", if (auto) "auto-resumed" else "resumed")
        return true
    }

    /** Distance so far, with steps × stride across a GPS gap that is still going on. */
    private fun distance(): Double = route?.distanceAt(SystemClock.elapsedRealtime() - startElapsedMs) ?: 0.0

    /** Metres of [distance] estimated from steps (closed gaps plus one still going on). */
    private fun bridged(): Double = route?.let { it.bridgedM + (distance() - it.distanceM).coerceAtLeast(0.0) } ?: 0.0

    /**
     * GPS-lost cue once per gap (no accepted fix for [GPS_LOST_MS] while moving), and a Power saving warning
     * if it gets switched on mid-run (Samsung can turn it on by itself at low battery).
     */
    private fun checkGps(t: Long) {
        val r = route ?: return
        if (!simulated && t - lastPowerCheckMs >= POWER_CHECK_MS) {
            lastPowerCheckMs = t
            val on = com.rameez.fit3zone.ui.powerSaverLimitsGps(context)
            if (on != powerSaver) {
                powerSaver = on
                HeartRateRepository.log.add("gps", if (on) "WARNING power saving turned on mid-run" else "power saving turned off")
                if (on) voice?.say("Power saving turned on. GPS will stop when the screen is off.")
            }
        }
        if (paused || gpsLost) return
        val now = t - startElapsedMs
        val last = r.lastFixMs ?: return
        val moving = cadence?.let { (it.current(t) ?: 0) > 0 } ?: true
        if (r.inGap(now) && now - last >= GPS_LOST_MS && moving) {
            gpsLost = true
            gpsLostAtMs = last
            val fromSteps = cadence != null && r.distanceAt(now) > r.distanceM
            val screenOn = context.getSystemService(PowerManager::class.java).isInteractive
            HeartRateRepository.log.add("gps", "GPS lost at t=${last / 1000}s (screen=${if (screenOn) "on" else "off"} sats=${location.satellites} powerSaver=$powerSaver)")
            voice?.say(if (fromSteps) "GPS lost. Distance from your steps until it's back." else "GPS lost.")
        }
    }

    /** Off / back on the planned route and the finish: their own vibration patterns and a spoken cue. */
    private fun handleRoute(e: RouteEvent, f: RouteFollower) {
        if (!routeAlertsOn) return
        val alertSettings = settings.alerts.value
        when (e) {
            is RouteEvent.OffRoute -> {
                HeartRateRepository.log.add("route", "OFF ROUTE ${e.distanceM.toInt()} m away at ${f.progressM.toInt()} m${if (e.repeat) " (reminder)" else ""}")
                alerts.offRoute(alertSettings)
                voice?.say(if (e.repeat) "Still off route, ${(e.distanceM / 10).toInt() * 10} metres away." else "Off route.")
            }
            RouteEvent.BackOnRoute -> {
                HeartRateRepository.log.add("route", "back on route at ${f.progressM.toInt()} m, ${f.remainingM.toInt()} m left")
                alerts.backOnRoute(alertSettings)
                voice?.say("Back on route.")
            }
            RouteEvent.Complete -> {
                HeartRateRepository.log.add("route", "complete: ${(f.followedFraction * 100).toInt()} % followed")
                alerts.stepChange(alertSettings)
                voice?.say("Route complete.")
            }
        }
    }

    /** Workout step changes: new target (quiet period when it gets easier), a lap, a buzz and a spoken cue. */
    private fun handleWorkout(events: List<WorkoutEvent>, now: Long) {
        if (events.isEmpty()) return
        val m = monitor ?: return
        val active = m.snapshot(now).activeDurationMs
        val alertSettings = settings.alerts.value
        for (e in events) when (e) {
            is WorkoutEvent.StepStarted -> {
                laps.startLap(active, distance(), e.step.title)
                val prev = e.previous?.target
                val next = e.step.target
                val easier = prev != null && next != null && next.upperBpm < prev.upperBpm
                m.updateTarget(next, now, quietMs = if (easier) EASIER_STEP_QUIET_MS else 0)
                HeartRateRepository.log.add(
                    "workout",
                    "step ${e.step.index + 1}/${runner?.steps?.size}: ${e.step.title} ${e.step.goal} " +
                        "${next?.let { "${it.label} ${it.lowerBpm}-${it.upperBpm}" } ?: "free"}${if (e.byLap) " (lap)" else ""}",
                )
                voice?.say(Cues.step(e.step))
            }
            is WorkoutEvent.Finished -> {
                laps.startLap(active, distance(), null)
                m.updateTarget(null, now)
                HeartRateRepository.log.add("workout", "finished")
                voice?.say(Cues.FINISHED)
            }
        }
        alerts.stepChange(alertSettings)
    }

    private fun announceSplits() {
        val r = route ?: return
        val splits = r.completedSplits()
        if (splits.size <= splitsAnnounced) return
        val last = splits.last()
        splitsAnnounced = splits.size
        // With auto-lap on, the lap cue (time, pace, heart rate) replaces the km split cue.
        if (autoLapM <= 0) voice?.say(Cues.split(last.index, last.paceSecPerKm, last.avgBpm, activityType))
    }

    private fun pacerStatus(now: Long): PacerStatus? {
        val p = pacer ?: return null
        val m = monitor ?: return null
        return PacerStatus(p.goal, distance(), m.snapshot(now).activeDurationMs)
    }

    /** Spoken ahead / behind cues (after the km split cue in the same tick) and a buzz when the goal distance is done. */
    private fun updatePacer(now: Long) {
        val p = pacer ?: return
        if (paused || p.finished) return
        val s = pacerStatus(now) ?: return
        val said = p.onTick(s)
        said.forEach { voice?.say(it) }
        if (said.isNotEmpty()) HeartRateRepository.log.add("pacer", "d=${s.distanceM.toInt()}m t=${s.activeMs / 1000}s ahead=${s.aheadMs / 1000}s: ${said.joinToString(" | ")}")
        if (p.finished) alerts.stepChange(settings.alerts.value)
    }

    /** Closes a lap every [autoLapM] metres of GPS distance: a short tap and a spoken lap summary. */
    private fun checkAutoLap(now: Long) {
        if (autoLapM <= 0 || paused) return
        val d = distance()
        if (!laps.autoLapDue(d, autoLapM)) return
        val m = monitor ?: return
        val lap = laps.startLap(m.snapshot(now).activeDurationMs, d)
        alerts.lapTap(settings.alerts.value)
        voice?.say(Cues.lap(lap, activityType))
        HeartRateRepository.log.add("session", "auto-lap ${lap.number}: ${lap.durationMs / 1000}s ${lap.distanceM.toInt()}m avg=${lap.avgBpm}")
    }

    fun stop() = scope.launch {
        if (!sessionActive) return@launch
        job?.cancel()
        job = null
        location.stop()
        stepTracker.stop()
        val now = SystemClock.elapsedRealtime()
        val m = monitor!!
        m.onTick(now) // final accounting
        val endedAt = System.currentTimeMillis()
        val snap = m.snapshot(now)
        // Count the last few fixes still waiting for smoothing, unless GPS is gone (then steps carry the end).
        route?.let { if (!it.inGap(now - startElapsedMs)) it.finish() }
        laps.finish(snap.activeDurationMs, distance())
        if (!simulated) route?.calibratedStrideM?.let {
            settings.saveStride(activityType, it)
            HeartRateRepository.log.add("gps", "stride %.2f m (bridged %d m of GPS gaps)".format(it, bridged().toInt()))
        }
        state = withRoute(
            state?.copy(snapshot = snap, target = m.target, endedWallClockMs = endedAt, laps = laps.laps.toList(), autoPaused = false, currentLap = null,
                avgBpm = recorder?.avgBpm, maxBpm = recorder?.maxBpm,
                cadenceSpm = cadence?.current(SystemClock.elapsedRealtime()), avgCadenceSpm = cadence?.average(snap.activeDurationMs), steps = cadence?.steps,
            ),
            snap,
        )
        SessionRepository.publish(state)
        persist(snap, m.target, recorder?.finish().orEmpty(), SessionStatus.COMPLETED, endedAt)
        if (!simulated) updateMaxHr(peak.peak)
        recorder = null
        sessionId = null
        monitor = null
        route = null
        runner = null
        autoPause = null
        pacer = null
        follower?.let { f -> HeartRateRepository.log.add("route", "end: ${(f.followedFraction * 100).toInt()} % followed, ${f.remainingM.toInt()} m left") }
        follower = null
        voice?.let { v ->
            voice = null
            // Let a last cue finish; shut down even if the service scope is cancelled first.
            scope.launch { try { delay(VOICE_DRAIN_MS) } finally { v.shutdown() } }
        }
        if (wakeLock.isHeld) wakeLock.release()
        HeartRateRepository.log.add("session", "stopped: ${state?.snapshot?.alertCount} alerts")
    }

    /** Garmin-style auto-detect: a real session's sustained (10 s) peak above max HR raises it. */
    private fun updateMaxHr(sustainedPeak: Int?) {
        val profile = settings.profile.value ?: return
        val raised = profile.withObservedPeak(sustainedPeak) ?: return
        settings.saveProfile(raised)
        HeartRateRepository.log.add("zones", "max HR raised ${profile.maxHr} -> ${raised.maxHr} (sustained peak)")
    }

    /** Queued on the history dispatcher: never blocks the monitor thread, and writes stay in order. */
    private fun persist(
        snap: ZoneSnapshot, target: ZoneTarget?, samples: List<HrSample>, status: SessionStatus, endedAt: Long? = null,
    ) {
        val id = sessionId ?: return
        val rec = recorder ?: return
        val bpm = BpmStats(rec.avgBpm, rec.minBpm, rec.maxBpm)
        val distance = distance()
        val bridgedM = route?.let { bridged() }
        val points = pendingPoints.toList().also { pendingPoints.clear() }
        val breaks = segmentBreaks.toList()
        val lapList = laps.laps.toList()
        val steps = cadence?.steps
        val avgCadence = cadence?.average(snap.activeDurationMs)
        val cadenceSamples = pendingCadence.toList().also { pendingCadence.clear() }
        val followed = follower?.takeIf { it.acquired }?.followedFraction
        scope.launch(SessionHistory.dispatcher) {
            runCatching { history.update(id, snap, target, bpm, samples, status, endedAt, distance, points, breaks, lapList, steps, avgCadence, cadenceSamples, bridgedM, followed) }
                .onFailure { HeartRateRepository.log.add("session", "history write failed: ${it.message}") }
        }
    }

    private fun handle(actions: List<ZoneAction>) {
        if (actions.isEmpty()) return
        val alertSettings = settings.alerts.value
        for (a in actions) when (a) {
            is ZoneAction.AboveZoneAlert -> {
                HeartRateRepository.log.add("zone", "ABOVE TARGET ${a.bpm} BPM, alert #${a.alertNumber}${if (a.isRepeat) " (repeat)" else ""}")
                alerts.aboveZone(alertSettings)
                voice?.say(Cues.above(a.bpm))
            }
            is ZoneAction.BelowZoneAlert -> {
                HeartRateRepository.log.add("zone", "BELOW TARGET ${a.bpm} BPM, alert #${a.alertNumber}${if (a.isRepeat) " (repeat)" else ""}")
                alerts.belowZone(alertSettings)
                voice?.say(Cues.below(a.bpm))
            }
            is ZoneAction.BackInZone -> {
                HeartRateRepository.log.add("zone", "back in target from ${if (a.fromAbove) "above" else "below"} at ${a.bpm} BPM")
                alerts.backInZone(alertSettings)
            }
        }
    }

    private fun publish(now: Long) {
        val m = monitor ?: return
        val snap = m.snapshot(now)
        state = withRoute(
            state?.copy(
                snapshot = snap, target = m.target, workout = runner?.progress(snap.activeDurationMs, distance()),
                laps = laps.laps.toList(), autoPaused = autoPaused, voiceOn = voiceOn, pacer = pacerStatus(now),
                avgBpm = recorder?.avgBpm, maxBpm = recorder?.maxBpm,
                cadenceSpm = cadence?.current(SystemClock.elapsedRealtime()), avgCadenceSpm = cadence?.average(snap.activeDurationMs), steps = cadence?.steps,
                currentLap = if (runner != null || autoLapM > 0 || laps.laps.isNotEmpty()) laps.current(snap.activeDurationMs, distance()) else null,
            ),
            snap,
        )
        SessionRepository.publish(state)
    }

    private fun withRoute(s: SessionState?, snap: ZoneSnapshot): SessionState? {
        val r = route ?: return s?.copy(gps = gpsStatus)
        val d = distance()
        val avg = if (d >= 100) snap.activeDurationMs / 1000.0 / (d / 1000.0) else null
        val planned = follower?.let { f ->
            RouteProgress(
                f.route.name, f.route.points, f.route.distanceM, f.acquired, f.remainingM, f.offRoute, f.distanceFromRouteM, f.complete,
                following = routeAlertsOn,
            )
        }
        return s?.copy(
            plannedRoute = planned,
            gps = gpsStatus, gpsAccuracyM = gpsAccuracy, distanceM = d, route = routePoints,
            gpsLost = gpsLost, powerSaver = powerSaver, distanceEstimated = d > r.distanceM,
            paceSecPerKm = if (paused) null else r.currentPaceSecPerKm(), avgPaceSecPerKm = avg,
        )
    }

    /** Runs on the controller thread. Fix timestamps use the same monotonic clock as HR readings. */
    private fun onFix(fix: Location) {
        val r = route ?: return
        gpsAccuracy = fix.accuracy
        gpsStatus = if (fix.accuracy <= GOOD_FIX_M) GpsStatus.OK else GpsStatus.SEARCHING
        autoPause?.let { d ->
            // A manual pause is never undone by movement.
            if (fix.hasSpeed() && (!paused || autoPaused) && fix.accuracy <= GOOD_FIX_M) {
                when (d.onSpeed(fix.elapsedRealtimeNanos / 1_000_000, fix.speed.toDouble())) {
                    AutoPauseEvent.PAUSE -> if (doPause(auto = true)) {
                        alerts.autoPauseTap(settings.alerts.value); voice?.say(Cues.AUTO_PAUSED)
                    }
                    AutoPauseEvent.RESUME -> if (doResume(auto = true)) {
                        alerts.autoPauseTap(settings.alerts.value); voice?.say(Cues.AUTO_RESUMED)
                    }
                    null -> Unit
                }
            }
        }
        if (paused) return
        val offset = fix.elapsedRealtimeNanos / 1_000_000 - startElapsedMs
        fixCount++
        lastRawFixMs?.let { last ->
            if (offset - last >= GPS_GAP_LOG_MS) HeartRateRepository.log.add("gps", "no fix from the phone for ${(offset - last) / 1000}s (at t=${offset / 1000}s)")
        }
        lastRawFixMs = offset
        val p = GeoPoint(offset, fix.latitude, fix.longitude, fix.accuracy, seaLevelAltitude(fix))
        if (r.addPoint(p)) {
            dropStartMs?.let { start ->
                if (offset - start >= GPS_GAP_LOG_MS) HeartRateRepository.log.add(
                    "gps", "dropped $dropCount fixes over ${(offset - start) / 1000}s (worst accuracy ${dropWorstAccuracy.toInt()} m, at t=${start / 1000}s)",
                )
            }
            dropStartMs = null
            if (gpsLost) {
                gpsLost = false
                HeartRateRepository.log.add("gps", "GPS back after ${(offset - gpsLostAtMs) / 1000}s (bridged so far ${bridged().toInt()} m)")
                voice?.say("GPS back.")
            }
            pendingPoints += p
            routePoints = routePoints + p
            follower?.let { f ->
                val wasAcquired = f.acquired
                f.update(offset, p.lat, p.lon, distance())?.let { handleRoute(it, f) }
                if (!wasAcquired && f.acquired) HeartRateRepository.log.add("route", "on the route at ${f.progressM.toInt()} m")
            }
        } else {
            if (dropStartMs == null) { dropStartMs = offset; dropCount = 0; dropWorstAccuracy = 0f }
            dropCount++
            dropWorstAccuracy = maxOf(dropWorstAccuracy, fix.accuracy)
        }
    }

    /**
     * Metres above sea level. Fused fixes usually carry only GPS height above the WGS84 ellipsoid, which is off by the
     * local geoid height (about −60 m in Mumbai), so on API 34+ it is converted with the geoid model; the source is logged once.
     */
    private fun seaLevelAltitude(fix: Location): Double? {
        if (!fix.hasAltitude()) return null
        val converted = if (android.os.Build.VERSION.SDK_INT >= 34) {
            if (fix.hasMslAltitude()) fix.mslAltitudeMeters to "provider MSL"
            else altitudeConverter?.seaLevel(fix)?.let { it to "converted to MSL" }
        } else null
        val (value, source) = converted ?: (fix.altitude to "ellipsoid (no MSL)")
        if (source != altitudeSource) {
            altitudeSource = source
            HeartRateRepository.log.add("gps", "altitude: $source (GPS ${"%.1f".format(fix.altitude)} m -> ${"%.1f".format(value)} m)")
        }
        return value
    }

    /** Every 5 s while moving: cadence for the activity's chart. */
    private fun sampleCadence(t: Long) {
        val c = cadence ?: return
        if (paused || t - lastCadenceSampleMs < CADENCE_SAMPLE_MS) return
        lastCadenceSampleMs = t
        c.current(t)?.let { pendingCadence += CadenceSample(t - startElapsedMs, it, c.steps.toLong()) }
    }

    /** Runs on the controller thread. Steps while paused don't count. */
    private fun onStep(tMs: Long) {
        if (!sessionActive || paused) return
        val c = cadence ?: return
        c.onStep(tMs)
        route?.addSteps(tMs - startElapsedMs, c.steps.toLong())
    }

    /** Simulated sessions: steady ~165 steps/min so the cadence display can be checked. */
    private suspend fun simulateSteps() {
        while (sessionActive) {
            onStep(SystemClock.elapsedRealtime())
            kotlinx.coroutines.delay(364)
        }
    }

    /**
     * Simulated sessions draw a 200 m-radius loop at ~4 m/s around the phone's last known position,
     * so maps, distance and splits can be checked without running. Points are tagged by the
     * session's SIMULATED flag in history. Altitude rolls ±8 m so the elevation chart can be checked.
     */
    private suspend fun simulateRoute() {
        follower?.route?.let { return simulatePlannedRoute(it) }
        val origin = kotlinx.coroutines.suspendCancellableCoroutine<Location?> { cont ->
            location.lastKnown { if (cont.isActive) cont.resumeWith(Result.success(it)) }
        }
        if (origin == null) {
            gpsStatus = GpsStatus.NO_PERMISSION
            HeartRateRepository.log.add("gps", "no last known location; simulated route skipped")
            return
        }
        val radius = 200.0
        val speed = 4.0
        var angle = 0.0
        while (true) {
            val lat = origin.latitude + (radius * kotlin.math.sin(angle)) / 111_195.0
            val lon = origin.longitude + (radius * kotlin.math.cos(angle) - radius) /
                (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
            val fix = Location("simulated").apply {
                latitude = lat; longitude = lon; accuracy = 5f; altitude = 12 + 8 * kotlin.math.sin(angle * 2)
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            onFix(fix)
            angle += speed / radius
            delay(1_000)
        }
    }

    /**
     * Simulated session with a planned route: runs along it at 4 m/s and leaves it for 40 s (60 m to the side) after
     * one minute, so the off-route and back-on-route alerts can be checked without running.
     */
    private suspend fun simulatePlannedRoute(route: com.rameez.fit3zone.core.Route) {
        val pts = route.points
        val cum = route.cumulativeM
        var along = 0.0
        var seconds = 0
        while (along <= route.distanceM) {
            val i = cum.indexOfFirst { it >= along }.coerceAtLeast(1)
            val a = pts[i - 1]
            val b = pts[i]
            val k = if (cum[i] == cum[i - 1]) 0.0 else (along - cum[i - 1]) / (cum[i] - cum[i - 1])
            val detour = if (seconds in 60 until 100) 60.0 else 0.0
            val fix = Location("simulated").apply {
                latitude = a.lat + (b.lat - a.lat) * k + detour / 111_195.0
                longitude = a.lon + (b.lon - a.lon) * k
                accuracy = 5f
                altitude = (a.eleM ?: 12.0) + ((b.eleM ?: 12.0) - (a.eleM ?: 12.0)) * k
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            onFix(fix)
            seconds++
            along += 4.0
            delay(1_000)
        }
    }

    private companion object {
        const val TICK_MS = 500L
        const val CADENCE_SAMPLE_MS = 5_000L
        const val SUMMARY_EVERY_MS = 30_000L
        const val MAX_SESSION_MS = 6 * 60 * 60 * 1000L
        const val GOOD_FIX_M = 20f
        /** GPS silences or dropped stretches at least this long are written to the log. */
        const val GPS_GAP_LOG_MS = 10_000L
        const val GPS_LOST_MS = 30_000L
        const val POWER_CHECK_MS = 5_000L
        const val EASIER_STEP_QUIET_MS = 60_000L
        const val VOICE_DRAIN_MS = 10_000L
    }
}

/** Android 14+ geoid model: GPS ellipsoid height -> height above mean sea level. Blocking; call off the main thread. */
@androidx.annotation.RequiresApi(34)
private class MslAltitude(private val context: Context) {
    private val converter = android.location.altitude.AltitudeConverter()

    fun seaLevel(fix: Location): Double? = runCatching {
        val copy = Location(fix)
        converter.addMslAltitudeToLocation(context, copy)
        if (copy.hasMslAltitude()) copy.mslAltitudeMeters else null
    }.getOrNull()
}
