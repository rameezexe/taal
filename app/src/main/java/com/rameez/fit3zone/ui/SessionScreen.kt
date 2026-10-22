package com.rameez.fit3zone.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import com.rameez.fit3zone.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.core.MonitorStatus
import com.rameez.fit3zone.core.ReadingSource
import com.rameez.fit3zone.core.SensorContact
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.TargetChoice
import com.rameez.fit3zone.core.Zone
import com.rameez.fit3zone.core.ZoneTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.rameez.fit3zone.core.formatPace
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.data.BleConnectionState
import com.rameez.fit3zone.data.GpsStatus
import com.rameez.fit3zone.data.DevicePrefs
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SessionRepository
import com.rameez.fit3zone.data.SessionState
import com.rameez.fit3zone.data.SettingsStore
import com.rameez.fit3zone.data.SavedWorkout
import com.rameez.fit3zone.data.WorkoutStore
import com.rameez.fit3zone.data.RouteStore
import com.rameez.fit3zone.core.WorkoutProgress
import com.rameez.fit3zone.core.formatClockDuration
import com.rameez.fit3zone.service.HeartRateForegroundService
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(modifier: Modifier, onPickDevice: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = HeartRateRepository
    val store = remember { SettingsStore.get(context) }
    val reading by repo.latest.collectAsStateWithLifecycle()
    val connection by repo.connection.collectAsStateWithLifecycle()
    val contact by repo.contact.collectAsStateWithLifecycle()
    val session by SessionRepository.session.collectAsStateWithLifecycle()
    val profile by store.profile.collectAsStateWithLifecycle()
    val choice by store.target.collectAsStateWithLifecycle()
    val activityType by store.activityType.collectAsStateWithLifecycle()
    val now = rememberElapsedNow()
    val saved = remember { DevicePrefs(context).device }
    val workoutStore = remember { WorkoutStore.get(context) }
    val workouts by workoutStore.workouts.collectAsStateWithLifecycle()
    val workoutId by store.workoutId.collectAsStateWithLifecycle()
    val workout = workouts.firstOrNull { it.id == workoutId }
    var showWorkouts by remember { mutableStateOf(false) }
    var showRoutes by remember { mutableStateOf(false) }
    val routeStore = remember { RouteStore.get(context) }
    val routes by routeStore.routes.collectAsStateWithLifecycle()
    val routeId by store.routeId.collectAsStateWithLifecycle()
    val chosenRoute = routes.firstOrNull { it.id == routeId }

    val active = session?.takeIf { it.isActive }
    val snap = active?.snapshot
    val fresh = repo.freshness.isFresh(reading, now)

    // What to show: the session's view when one runs, otherwise the raw live reading against the chosen target.
    val savedZones = remember(profile) { profile?.zones() }
    if (showWorkouts && active == null) {
        WorkoutsScreen(
            modifier, workoutId, savedZones,
            onSelect = { store.saveWorkoutId(it); showWorkouts = false },
            onBack = { showWorkouts = false },
        )
        return
    }
    if (showRoutes && active == null) {
        RoutesScreen(modifier, chosenRoute?.id, onSelect = { store.saveRouteId(it); if (it != null) showRoutes = false }, onBack = { showRoutes = false })
        return
    }
    val zones: HrZones? = active?.zones ?: savedZones
    // Before a workout starts, preview its first step's target.
    val firstStepTarget = workout?.let { w -> runCatching { w.workout.plan(savedZones).first().target }.getOrNull() }
    val target: ZoneTarget? = when {
        active != null -> active.target
        workout != null -> firstStepTarget
        else -> resolveTarget(choice, savedZones)
    }
    // Calm display (user decision 2026-10-18): while a band is connected or a session runs, hold the last heart rate
    // until a new one arrives instead of flickering to "--" between the band's intermittent readings. Alerts still
    // ignore stale data (ZoneMonitor); "--" only shows when no band is connected.
    val held = reading?.takeIf { active != null || (it.source == ReadingSource.BLE && connection != BleConnectionState.Idle) }
    val bpm: Int? = snap?.bpm ?: held?.bpm
    val zone: Zone? = if (snap?.bpm != null) snap.zone else bpm?.let { target?.classify(it) }
    val hrZone: Int? = if (snap?.bpm != null) snap.hrZone else bpm?.let { zones?.zoneOf(it) }
    val live = snap?.status == MonitorStatus.ACTIVE
    val pending = when {
        !live -> null
        snap?.pendingAboveSinceMs != null -> "OVER TARGET · CONFIRMING"
        snap?.pendingBelowSinceMs != null -> "UNDER TARGET · CONFIRMING"
        else -> null
    }
    val needsZones = savedZones == null && (if (workout != null) workout.workout.needsZones else choice is TargetChoice.Zones)

    val heart: @Composable () -> Unit = {
        HeartHero(bpm, hrZone, zones)
        Spacer(Modifier.height(6.dp))

        ZoneBanner(zone, hrZone, target, bpm != null, pending, snap?.status, active?.simulated == true, active?.autoPaused == true)
        active?.workout?.let { w ->
            Spacer(Modifier.height(10.dp))
            WorkoutPanel(active.workoutName ?: "Workout", w)
        }

        Spacer(Modifier.height(12.dp))
        if (zones != null) ZoneGauge(zones, target, bpm)
        else if (target != null) TargetBar(target, bpm)
        target?.let { Text("${it.label}: ${it.lowerBpm}–${it.upperBpm} BPM", color = ZoneColors.muted, fontSize = 15.sp) }

        Spacer(Modifier.height(12.dp))
        if (active != null) {
            active.plannedRoute?.let { RouteLine(it); Spacer(Modifier.height(8.dp)) }
            SessionSummary(active)
        } else {
            val gpsOn by store.gpsEnabled.collectAsStateWithLifecycle()
            if (activityType.usesGps && gpsOn) PowerSaverWarning()
            Spacer(Modifier.height(10.dp))
            ActivityPicker(activityType) { store.saveActivityType(it) }
            Spacer(Modifier.height(10.dp))
            TargetPicker(
                choice, savedZones, workout,
                onChange = { store.saveWorkoutId(null); store.saveTarget(it) },
                onWorkouts = { showWorkouts = true },
                onClearWorkout = { store.saveWorkoutId(null) },
            )
            val gpsEnabled by store.gpsEnabled.collectAsStateWithLifecycle()
            if (activityType.usesGps && gpsEnabled) {
                val pacerOn by store.pacerOn.collectAsStateWithLifecycle()
                val paceGoal by store.paceGoal.collectAsStateWithLifecycle()
                Spacer(Modifier.height(10.dp))
                RouteCard(chosenRoute, onOpen = { showRoutes = true }, onClear = { store.saveRouteId(null) })
                Spacer(Modifier.height(10.dp))
                PacerCard(pacerOn, paceGoal, store::savePacerOn, store::savePaceGoal)
            }
            Spacer(Modifier.height(12.dp))
            session?.let { StatsGrid(it) } // the last finished session's numbers until a new one starts
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusLine(connection, contact, saved?.kind?.word ?: "Sensor")
        if (active != null) {
            // During a session: swipeable data pages (Garmin-style). The page is kept while the session runs.
            val pages = runPages(active)
            val pager = rememberPagerState(initialPage = rememberedRunPage.coerceAtMost(pages.lastIndex)) { pages.size }
            LaunchedEffect(pager.currentPage) { rememberedRunPage = pager.currentPage }
            Spacer(Modifier.height(8.dp))
            PageTabs(pages, pager, active.type)
            Spacer(Modifier.height(6.dp))
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp, verticalAlignment = Alignment.Top) { i ->
                ScrollPage(Modifier.fillMaxSize()) {
                    when (pages[i]) {
                        RunPage.HEART -> heart()
                        RunPage.PACER -> active.pacer?.let { PacerPage(it, active.avgPaceSecPerKm) }
                        RunPage.DATA -> DataPage(active, bpm)
                        RunPage.LAPS -> LapsPage(active)
                        RunPage.MAP -> MapPage(active)
                    }
                }
            }
        } else {
            rememberedRunPage = 0
            Spacer(Modifier.height(6.dp))
            ScrollPage(Modifier.weight(1f).fillMaxWidth()) { heart() }
        }
        Spacer(Modifier.height(8.dp))
        Controls(
            needsZones = needsZones,
            startLabel = when {
                workout != null -> "Start workout"
                target != null -> "Start ${activityType.label.lowercase()} · ${target.label}"
                activityType == ActivityType.RUN -> "Start free run"
                else -> "Start ${activityType.label.lowercase()}"
            },
            activityType = activityType,
            hasDevice = saved != null,
            deviceWord = (saved?.kind?.word ?: "Sensor").lowercase(),
            active = active,
            bleActive = connection != BleConnectionState.Idle,
            onPickDevice = onPickDevice,
            onOpenSettings = onOpenSettings,
            onConnect = { saved?.let { HeartRateForegroundService.connect(context, it.address) } },
        )
    }
}

/** Run page shown last; kept across tab switches while a session runs, reset to Heart when none does. */
private var rememberedRunPage = 0

/** Scrolls if it outgrows the screen, with the bottom edge faded so content under the pinned buttons reads as scrollable. */
@Composable
private fun ScrollPage(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - 36.dp.toPx(), size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** Compact live numbers under the heart page; the full set is on the Data page. */
@Composable
private fun SessionSummary(s: SessionState) {
    Row(Modifier.fillMaxWidth()) {
        Stat("Time", formatDuration(s.snapshot.activeDurationMs), Modifier.weight(1f))
        if (s.hasGps()) {
            Stat("Km", "%.2f".format(s.distanceM / 1000), Modifier.weight(1f))
            Stat("${s.type.paceLabel} ${s.type.paceUnit}", s.type.formatPaceOrSpeed(s.paceSecPerKm), Modifier.weight(1f))
        } else if (s.steps != null) {
            Stat("Cadence", s.cadenceSpm?.toString() ?: "--", Modifier.weight(1f))
            Stat("Avg HR", s.avgBpm?.toString() ?: "--", Modifier.weight(1f))
        } else {
            Stat("Avg HR", s.avgBpm?.toString() ?: "--", Modifier.weight(1f))
            Stat("Max HR", s.maxBpm?.toString() ?: "--", Modifier.weight(1f))
        }
    }
    s.currentLap?.let { l ->
        Spacer(Modifier.height(6.dp))
        Text(
            "${s.type.lapWord} ${l.number} · ${formatDuration(l.durationMs)}" +
                (if (s.hasGps()) " · %.2f km · ${s.type.formatPaceOrSpeed(l.paceSecPerKm)} ${s.type.paceUnit}".format(l.distanceM / 1000) else ""),
            color = Palette.accent, fontSize = 14.sp,
        )
    }
    s.pacer?.let { Spacer(Modifier.height(6.dp)); PacerLine(it) }
    Spacer(Modifier.height(6.dp))
    Text("Swipe for ${if (s.pacer != null) "pacer, " else ""}data, ${s.type.lapWord.lowercase()}s" + (if (s.hasGps()) " and map" else ""), color = Palette.faint, fontSize = 12.sp)
}

/** What the next session is: outdoor types record GPS (if on in Settings), indoor ones never do. */
@Composable
private fun ActivityPicker(type: ActivityType, onChange: (ActivityType) -> Unit) {
    val context = LocalContext.current
    val gpsEnabled by SettingsStore.get(context).gpsEnabled.collectAsStateWithLifecycle()
    GlowCard(accent = Palette.accent2, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardLabel("Activity", Palette.accent2)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityType.entries.forEach { t -> PillChip(t.label, t == type, Palette.accent2) { onChange(t) } }
            }
            Text(
                when {
                    !type.usesGps && type.tracksCadence -> "No GPS: heart rate, time and cadence from the phone's step sensor."
                    !type.usesGps -> "No GPS: heart rate and time."
                    !gpsEnabled -> "GPS is off in Settings: no route or distance."
                    type.showsSpeed -> "GPS route, distance and speed in km/h."
                    else -> "GPS route, distance, pace and cadence."
                },
                color = ZoneColors.muted, fontSize = 13.sp,
            )
        }
    }
}

/** Mirrors the service's resolution so the preview matches what a run would use. */
private fun resolveTarget(choice: TargetChoice, zones: HrZones?): ZoneTarget? =
    zones?.let { choice.resolve(it) } ?: (choice as? TargetChoice.Custom)?.let { runCatching { ZoneTarget(it.lowerBpm, it.upperBpm, "Custom") }.getOrNull() }

/**
 * The live heart rate: a 270° ring coloured through the zones that springs to the current BPM, a glow
 * in the zone colour that beats at the measured heart rate, and the BPM rolling in the middle.
 */
@Composable
private fun HeartHero(bpm: Int?, hrZone: Int?, zones: HrZones?) {
    val color by animateColorAsState(if (bpm != null && hrZone != null) ZoneColors.hrZone(hrZone) else Palette.faint, tween(600), label = "zone")
    val lo = (zones?.floors?.first() ?: 100) - 30f
    val hi = (zones?.maxHr ?: 200) + 5f
    val frac by animateFloatAsState(
        if (bpm == null) 0f else ((bpm - lo) / (hi - lo)).coerceIn(0.02f, 1f),
        spring(dampingRatio = 0.7f, stiffness = 120f), label = "frac",
    )
    // One beat = quick swell then slow release, at the real heart rate.
    val period = (60_000 / (bpm ?: 60).coerceIn(30, 220))
    val beat = rememberInfiniteTransition(label = "beat")
    val pulse by beat.animateFloat(
        0f, 0f,
        infiniteRepeatable(keyframes {
            durationMillis = period
            0f at 0
            1f at (period * 0.14f).toInt() using LinearOutSlowInEasing
            0.25f at (period * 0.32f).toInt()
            0.55f at (period * 0.42f).toInt()
            0f at period using FastOutSlowInEasing
        }),
        label = "pulse",
    )
    val p = if (bpm != null) pulse else 0f
    // Colour stops at the real zone boundaries along the 270° sweep (0.75 of a turn), so the ring is orange in Z4.
    val stops = remember(zones, lo, hi) {
        fun pos(v: Float) = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * 0.75f
        val edges = listOf(lo) + (zones?.floors?.map { it.toFloat() } ?: List(HrZones.COUNT) { lo + (hi - lo) * (it + 1) / 6f }) + hi
        buildList {
            for (z in 0..HrZones.COUNT) {
                val c = ZoneColors.hrZone(z)
                add(pos(edges[z]) + 0.004f to c)
                add(pos(edges[z + 1]) - 0.004f to c)
            }
            add(1f to ZoneColors.hrZone(0)) // seam behind the start cap
        }.sortedBy { it.first }.toTypedArray()
    }
    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val ringR = size.minDimension / 2 - 18.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.30f + 0.18f * p), Color.Transparent), c, ringR * (1.05f + 0.12f * p)),
                radius = ringR * (1.05f + 0.12f * p), center = c,
            )
            val stroke = 14.dp.toPx()
            val tl = Offset(c.x - ringR, c.y - ringR)
            val box = Size(ringR * 2, ringR * 2)
            drawArc(Palette.hairline, 135f, 270f, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (frac > 0f) {
                rotate(135f, c) {
                    drawArc(
                        Brush.sweepGradient(*stops, center = c),
                        0f, 270f * frac, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                // Glowing tip at the current value.
                val a = Math.toRadians(135.0 + 270.0 * frac)
                val tip = Offset(c.x + ringR * kotlin.math.cos(a).toFloat(), c.y + ringR * kotlin.math.sin(a).toFloat())
                drawCircle(color.copy(alpha = 0.45f), stroke * (0.95f + 0.25f * p), tip)
                drawCircle(Color.White, stroke * 0.36f, tip)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (bpm != null) RollingText(bpm.toString(), style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 84.sp))
            else Text("--", style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 84.sp), color = Palette.faint)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painterResource(R.drawable.ic_heart), null, tint = if (bpm != null) Palette.heart else Palette.faint,
                    modifier = Modifier.size(16.dp).graphicsLayer { val k = 1f + 0.28f * p; scaleX = k; scaleY = k },
                )
                Text("BPM", color = ZoneColors.muted, style = MaterialTheme.typography.labelLarge)
            }
            AnimatedContent(hrZone?.takeIf { bpm != null }, transitionSpec = { (scaleIn(spring(0.5f, 500f)) + fadeIn()).togetherWith(scaleOut() + fadeOut()) }, label = "zp") { z ->
                if (z != null) Box(
                    Modifier.padding(top = 8.dp).background(ZoneColors.hrZone(z), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 4.dp),
                ) { Text(zoneName(z).uppercase().let { if (it == "REST") it else "ZONE ${z}" }, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

/**
 * Free / Z1–Z5 / Custom. Tapping a zone selects it; tapping the zone next to a selection extends it
 * to a range (Z2 then Z3 = Zones 2–3); tapping inside a range narrows it to that zone.
 */
@Composable
private fun TargetPicker(
    choice: TargetChoice,
    zones: HrZones?,
    workout: SavedWorkout?,
    onChange: (TargetChoice) -> Unit,
    onWorkouts: () -> Unit,
    onClearWorkout: () -> Unit,
) {
    var customLow by remember { mutableStateOf((choice as? TargetChoice.Custom)?.lowerBpm?.toString() ?: "") }
    var customHigh by remember { mutableStateOf((choice as? TargetChoice.Custom)?.upperBpm?.toString() ?: "") }
    var customOpen by remember { mutableStateOf(choice is TargetChoice.Custom) }
    GlowCard(accent = Palette.accent, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardLabel("Target", Palette.accent)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip("Workout", selected = workout != null, color = Palette.accent2, onClick = onWorkouts)
                PillChip(
                    "Free", selected = workout == null && choice is TargetChoice.Free && !customOpen, color = Palette.accent,
                    onClick = { customOpen = false; onChange(TargetChoice.Free) },
                )
                for (z in 1..HrZones.COUNT) {
                    val sel = choice as? TargetChoice.Zones
                    PillChip(
                        "Z$z",
                        selected = workout == null && !customOpen && sel != null && z in sel.from..sel.to,
                        color = ZoneColors.hrZone(z),
                        enabled = zones != null,
                        onClick = {
                            customOpen = false
                            onChange(
                                when {
                                    sel == null -> TargetChoice.Zones(z, z)
                                    // Tapping a selected end zone takes it off; the last one left goes back to Free.
                                    z == sel.from && z == sel.to -> TargetChoice.Free
                                    z == sel.from -> TargetChoice.Zones(z + 1, sel.to)
                                    z == sel.to -> TargetChoice.Zones(sel.from, z - 1)
                                    z in sel.from..sel.to -> TargetChoice.Zones(z, z)
                                    z == sel.to + 1 -> TargetChoice.Zones(sel.from, z)
                                    z == sel.from - 1 -> TargetChoice.Zones(z, sel.to)
                                    else -> TargetChoice.Zones(z, z)
                                },
                            )
                        },
                    )
                }
                PillChip("Custom", selected = workout == null && (customOpen || choice is TargetChoice.Custom), color = Palette.load, onClick = { customOpen = true })
            }
            if (customOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BpmField("Low", customLow, Modifier.weight(1f)) { customLow = it }
                    BpmField("High", customHigh, Modifier.weight(1f)) { customHigh = it }
                    OutlinedButton(onClick = {
                        val lo = customLow.toIntOrNull()
                        val hi = customHigh.toIntOrNull()
                        if (lo != null && hi != null && runCatching { ZoneTarget(lo, hi, "Custom") }.isSuccess) {
                            onChange(TargetChoice.Custom(lo, hi))
                            customOpen = false
                        }
                    }) { Text("Set") }
                }
            }
            if (workout != null) {
                Text(workout.workout.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(workoutSummary(workout.workout), color = ZoneColors.muted, fontSize = 13.sp)
                StepList(workout.workout, zones)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAction("Change workout", onWorkouts)
                    TextAction("No workout", onClearWorkout, color = Palette.muted)
                }
            }
            val hint = when {
                workout != null -> "Each step sets its own target; Lap ends the current step."
                zones == null -> "Set your age or max HR in Settings to pick zones."
                choice is TargetChoice.Free -> "No alerts. Time in each zone is recorded."
                choice is TargetChoice.Zones -> "Tap the next zone to widen the range, or a selected end zone to remove it."
                else -> null
            }
            hint?.let { Text(it, color = ZoneColors.muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BpmField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun StatusLine(connection: BleConnectionState, contact: SensorContact?, word: String) {
    val (color, text) = when {
        // One steady state while connected: whether data is flowing shows in the BPM ("--" when stale), not here.
        connection == BleConnectionState.Subscribed -> ZoneColors.inZone to "$word connected"
        connection == BleConnectionState.Idle -> ZoneColors.noData to "$word not connected"
        connection == BleConnectionState.BluetoothOff || connection == BleConnectionState.MissingPermission ->
            ZoneColors.above to HeartRateForegroundService.describe(connection)
        else -> ZoneColors.pending to HeartRateForegroundService.describe(connection)
    }
    val c by animateColorAsState(color, tween(400), label = "status")
    Row(
        Modifier.background(Palette.surface, RoundedCornerShape(50)).border(1.dp, Palette.hairline, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(12.dp)) {
            drawCircle(c, size.minDimension / 2.6f)
        }
        AnimatedContent(text, transitionSpec = { fadeIn(tween(250)).togetherWith(fadeOut(tween(150))) }, label = "st") {
            Text(it, style = MaterialTheme.typography.titleSmall)
        }
    }
    // Only the actionable warning; no per-second "last reading" counter.
    if (contact == SensorContact.NOT_DETECTED) Text(
        "NO SKIN CONTACT", color = ZoneColors.above,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ZoneBanner(
    zone: Zone?, hrZone: Int?, target: ZoneTarget?, hasData: Boolean, pending: String?, status: MonitorStatus?, simulated: Boolean,
    autoPaused: Boolean = false,
) {
    val t = target?.label?.uppercase()
    val (color, label) = when {
        status == MonitorStatus.PAUSED -> ZoneColors.noData to if (autoPaused) "AUTO-PAUSED" else "PAUSED"
        // No data/stale states are not shown: with no heart rate yet, the banner just names the target.
        !hasData -> ZoneColors.noData to (t?.let { "TARGET · $it" } ?: "FREE RUN")
        pending != null -> ZoneColors.pending to pending
        t != null && zone == Zone.ABOVE_ZONE -> ZoneColors.above to "ABOVE $t"
        t != null && zone == Zone.IN_ZONE -> ZoneColors.inZone to "IN $t"
        t != null && zone == Zone.BELOW_ZONE -> ZoneColors.below to "BELOW $t"
        hrZone != null -> ZoneColors.hrZone(hrZone) to if (hrZone == 0) "BELOW ZONE 1" else "ZONE $hrZone"
        else -> ZoneColors.noData to if (t == null) "FREE RUN" else "—"
    }
    val bg by animateColorAsState(color, tween(450), label = "banner")
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.72f))))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            label + if (simulated) "\nSIMULATED SESSION" else "",
            transitionSpec = { (slideInVertically { it / 2 } + fadeIn()).togetherWith(slideOutVertically { -it / 2 } + fadeOut()) },
            label = "bannerText",
        ) {
            Text(
                it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                letterSpacing = 0.5.sp, lineHeight = 26.sp,
            )
        }
    }
}

/** Five coloured zone segments with the target bracketed in white and a marker for the current BPM. */
@Composable
private fun ZoneGauge(zones: HrZones, target: ZoneTarget?, bpm: Int?) {
    val min = zones.floors.first() - 15f
    val max = zones.maxHr + 5f
    val marker by animateFloatAsState(bpm?.toFloat() ?: -1f, spring(dampingRatio = 0.65f, stiffness = 160f), label = "marker")
    val tl by animateFloatAsState(target?.lowerBpm?.toFloat() ?: min, spring(0.8f, 300f), label = "tl")
    val tr by animateFloatAsState(target?.upperBpm?.plus(1f) ?: min, spring(0.8f, 300f), label = "tr")
    val grow = rememberGrow()
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            // Inset by the marker's halo so it stays fully visible at either end of the scale.
            val pad = size.height / 2.4f
            fun x(v: Float) = pad + ((v - min) / (max - min)).coerceIn(0f, 1f) * (size.width - 2 * pad)
            val barTop = size.height * 0.32f
            val barH = size.height * 0.36f
            val gap = 3.dp.toPx()
            val edges = listOf(min) + zones.floors.map { it.toFloat() } + max
            for (z in 0..HrZones.COUNT) {
                val l = x(edges[z]); val w = (x(edges[z + 1]) - l - gap).coerceAtLeast(0f) * grow
                drawRoundRect(ZoneColors.hrZone(z), Offset(l, barTop), Size(w, barH), CornerRadius(barH / 2))
            }
            if (target != null) {
                val l = x(tl) - 3f
                val r = x(tr) + 3f
                drawRoundRect(Color.White, Offset(l, 3f), Size(r - l, size.height - 6f), CornerRadius(12f), style = Stroke(width = 3.5f))
            }
            if (bpm != null && marker > 0f) {
                val cx = x(marker)
                drawCircle(Color.White.copy(alpha = 0.25f), size.height / 2.4f, Offset(cx, size.height / 2))
                drawCircle(Palette.bg, size.height / 3.6f + 3f, Offset(cx, size.height / 2))
                drawCircle(Color.White, size.height / 3.6f, Offset(cx, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            // floors under each zone boundary, roughly placed
            for (z in 1..HrZones.COUNT) Text("Z$z ${zones.floors[z - 1]}", Modifier.weight(1f), color = ZoneColors.hrZone(z), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

/** Horizontal scale from lower-20 to upper+20 BPM with the target shaded (used when no zones are set). */
@Composable
private fun TargetBar(target: ZoneTarget, bpm: Int?) {
    val min = target.lowerBpm - 20f
    val max = target.upperBpm + 20f
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        fun x(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f) * size.width
        val barTop = size.height * 0.3f
        val barH = size.height * 0.4f
        val r = CornerRadius(barH / 2, barH / 2)
        drawRoundRect(ZoneColors.below.copy(alpha = 0.5f), Offset(0f, barTop), Size(x(target.lowerBpm.toFloat()), barH), r)
        drawRoundRect(
            ZoneColors.above.copy(alpha = 0.5f), Offset(x(target.upperBpm.toFloat()), barTop),
            Size(size.width - x(target.upperBpm.toFloat()), barH), r,
        )
        drawRect(
            ZoneColors.inZone, Offset(x(target.lowerBpm.toFloat()), barTop),
            Size(x(target.upperBpm.toFloat()) - x(target.lowerBpm.toFloat()), barH),
        )
        if (bpm != null) {
            val cx = x(bpm.toFloat())
            drawCircle(Color.White, radius = size.height / 2.2f, center = Offset(cx, size.height / 2))
        }
    }
}

/** Current workout step: what, target, how much is left, and what comes next. */
@Composable
private fun WorkoutPanel(name: String, w: WorkoutProgress) {
    GlowCard(accent = Palette.accent2, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CardLabel(name, Palette.accent2) }
                Text("Step ${w.step.index + 1}/${w.total}", color = ZoneColors.muted, fontSize = 12.sp)
            }
            if (w.finished) {
                Text("Workout complete", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ZoneColors.inZone)
                Text("Keep going as a free run, or stop to save.", color = ZoneColors.muted)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(w.step.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(w.step.target?.let { "${it.label} · ${it.lowerBpm}–${it.upperBpm}" } ?: "No target", color = ZoneColors.muted)
                }
                RollingText(
                    w.remainingMs?.let { formatClockDuration(it) } ?: w.remainingM?.let { "${it.toInt()} m" } ?: "Lap",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            w.fraction?.let { f ->
                val shown by animateFloatAsState(f, tween(500), label = "stepFrac")
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Palette.hairline, cornerRadius = r)
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Palette.accent2, Palette.accent)),
                        size = Size((size.width * shown).coerceAtLeast(size.height), size.height), cornerRadius = r,
                    )
                }
            }
            if (w.remainingMs == null && w.remainingM == null) Text("Press Next when done", color = ZoneColors.pending, fontSize = 13.sp)
            w.next?.let { n ->
                Text("Next: ${n.title} · ${n.goal}${n.target?.let { " · ${it.label}" } ?: ""}", color = ZoneColors.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatsGrid(s: SessionState) {
    val snap = s.snapshot
    val zoned = snap.timeBelowMs + snap.timeInMs + snap.timeAboveMs
    val inPct = if (zoned > 0) (snap.timeInMs * 100 / zoned).toInt() else 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.isActive) Text("Last session", color = ZoneColors.muted)
        if (s.gps != GpsStatus.OFF) {
            Row(Modifier.fillMaxWidth()) {
                Stat(if (s.isActive && s.distanceEstimated) "Distance · est." else "Distance", "%.2f".format(s.distanceM / 1000), Modifier.weight(1f))
                Stat(
                    (if (s.isActive) "" else "Avg ") + "${s.type.paceLabel.lowercase()} ${s.type.paceUnit}",
                    s.type.formatPaceOrSpeed(if (s.isActive) s.paceSecPerKm else s.avgPaceSecPerKm), Modifier.weight(1f),
                )
            }
            if (s.isActive) {
                val (c, t) = s.gpsLine()
                Text(t, color = c, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Duration", formatDuration(snap.activeDurationMs), Modifier.weight(1f))
            if (s.target != null) Stat("In target", "$inPct%", Modifier.weight(1f))
            else Stat("Max zone", snap.zoneMs.indexOfLast { it > 0 }.takeIf { it >= 0 }?.let { zoneName(it) } ?: "--", Modifier.weight(1f))
        }
        if (s.target != null) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Above", formatDuration(snap.timeAboveMs), Modifier.weight(1f))
                Stat("Below", formatDuration(snap.timeBelowMs), Modifier.weight(1f))
                Stat("Alerts", snap.alertCount.toString(), Modifier.weight(1f))
            }
        }
        if (s.zones != null && snap.zoneMs.sum() > 0) {
            Text("Time in zones", color = ZoneColors.muted, fontSize = 13.sp)
            HrZoneBars(snap.zoneMs, s.zones)
        }
        if (snap.dataInterruptions > 0) {
            Text("Data gaps: ${snap.dataInterruptions} (${formatDuration(snap.timeStaleMs)})", color = ZoneColors.muted)
        }
        s.currentLap?.takeIf { s.isActive }?.let { l ->
            Text("Lap ${l.number}${l.label?.let { " · $it" } ?: ""}", color = Palette.accent, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth()) {
                Stat("Lap time", formatDuration(l.durationMs), Modifier.weight(1f))
                if (s.gps != GpsStatus.OFF) {
                    Stat("Lap km", "%.2f".format(l.distanceM / 1000), Modifier.weight(1f))
                    Stat("Lap ${s.type.paceLabel.lowercase()}", s.type.formatPaceOrSpeed(l.paceSecPerKm), Modifier.weight(1f))
                }
            }
        }
        s.laps.lastOrNull()?.let { l ->
            Text(
                "Lap ${l.number}${l.label?.let { " · $it" } ?: ""}: ${formatDuration(l.durationMs)}" +
                    (l.paceSecPerKm?.let { " · ${formatPace(it)}/km" } ?: "") + (l.avgBpm?.let { " · avg $it" } ?: ""),
                color = ZoneColors.muted,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RollingText(value, style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp))
        Text(label.uppercase(), color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Controls(
    needsZones: Boolean,
    startLabel: String,
    activityType: ActivityType,
    hasDevice: Boolean,
    deviceWord: String,
    active: SessionState?,
    bleActive: Boolean,
    onPickDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val big = Modifier.fillMaxWidth().height(68.dp)
    val gpsEnabled by SettingsStore.get(context).gpsEnabled.collectAsStateWithLifecycle()
    // Permissions are asked when a session first needs them; the session starts either way (without GPS / cadence if denied).
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        HeartRateForegroundService.startSession(context, simulated = false)
    }
    val startRun = {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        // Ask for what this activity needs (location for GPS, physical activity for cadence); it starts either way.
        val missing = buildList {
            if (gpsEnabled && activityType.usesGps && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (activityType.tracksCadence && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            HeartRateForegroundService.startSession(context, simulated = false)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedContent(
            targetState = when { active != null -> 2; needsZones -> 0; else -> 1 },
            transitionSpec = { (fadeIn(tween(250)) + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut(tween(150))) },
            label = "controls",
        ) { mode ->
            when {
                mode == 0 -> GradientButton("Set up heart-rate zones", onOpenSettings)
                mode == 1 && !hasDevice -> GradientButton("Choose heart-rate sensor", onPickDevice)
                mode == 1 -> GradientButton(
                    startLabel, startRun, shimmer = true,
                )
                active != null -> {
                    val paused = active.snapshot.status == MonitorStatus.PAUSED
                    var confirmStop by remember { mutableStateOf(false) }
                    LaunchedEffect(confirmStop) {
                        if (confirmStop) { delay(3_000); confirmStop = false }
                    }
                    Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(
                            if (paused) "Resume" else "Pause",
                            onClick = {
                                if (paused) HeartRateForegroundService.resumeSession(context)
                                else HeartRateForegroundService.pauseSession(context)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton(
                            if (active.workout?.finished == false) "Next" else active.type.lapWord,
                            onClick = { HeartRateForegroundService.lap(context) },
                            enabled = !paused, modifier = Modifier.weight(1f),
                        )
                        GradientButton(
                            if (confirmStop) "Confirm" else "Stop",
                            onClick = { if (confirmStop) HeartRateForegroundService.endSession(context) else confirmStop = true },
                            modifier = Modifier.weight(1f),
                            colors = if (confirmStop) listOf(Color(0xFFFF2D55), Color(0xFFFF4D5E)) else listOf(Color(0xFFFF4D5E), Color(0xFFFF7A59)),
                        )
                    }
                    // Mid-run voice toggle: silences spoken cues only; vibration alerts keep going.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillChip(
                            if (active.voiceOn) "Voice cues on" else "Voice muted",
                            selected = active.voiceOn, color = Palette.accent, compact = true,
                        ) { HeartRateForegroundService.setVoice(context, !active.voiceOn) }
                        Text("Vibrations stay on", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                    }
                }
            }
        }
        if (active == null && !needsZones) {
            val dev by com.rameez.fit3zone.data.DevMode.on.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (dev) TextButton(onClick = { HeartRateForegroundService.startSession(context, simulated = true) }) {
                    Text("Simulated session", color = ZoneColors.muted)
                } else Spacer(Modifier.width(1.dp))
                if (hasDevice) {
                    TextButton(onClick = {
                        if (bleActive) HeartRateForegroundService.disconnect(context) else onConnect()
                    }) { Text(if (bleActive) "Disconnect $deviceWord" else "Connect $deviceWord", color = Palette.accent) }
                }
            }
        }
    }
}
