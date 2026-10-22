package com.rameez.fit3zone.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.R
import com.rameez.fit3zone.core.Coach
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.Performance
import com.rameez.fit3zone.core.PlanStatus
import com.rameez.fit3zone.core.TrainingStats
import com.rameez.fit3zone.core.WorkoutSchedule
import com.rameez.fit3zone.core.formatPace
import com.rameez.fit3zone.data.BandData
import com.rameez.fit3zone.data.BandDay
import com.rameez.fit3zone.data.BandNight
import com.rameez.fit3zone.data.DailyHealth
import com.rameez.fit3zone.data.HealthData
import com.rameez.fit3zone.data.SavedWorkout
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SettingsStore
import com.rameez.fit3zone.data.WorkoutStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface HealthUi {
    data object Loading : HealthUi
    data object NotInstalled : HealthUi
    data object NeedsPermission : HealthUi
    data class Ready(val day: DailyHealth) : HealthUi
    data class Error(val message: String) : HealthUi
}

@Composable
fun MyDayScreen(modifier: Modifier, onOpenActivity: (Long) -> Unit, onStartRun: () -> Unit) {
    val context = LocalContext.current
    val health = remember { HealthData(context) }
    val history = remember { SessionHistory.get(context) }
    val sessions by history.sessions.collectAsStateWithLifecycle()
    var ui by remember { mutableStateOf<HealthUi>(HealthUi.Loading) }
    var reload by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        reload++
    }

    LaunchedEffect(reload) {
        ui = when (health.availability()) {
            HealthData.Availability.AVAILABLE -> runCatching {
                if (!health.hasAllPermissions() && health.grantedPermissions().isEmpty()) HealthUi.NeedsPermission
                else HealthUi.Ready(health.readToday())
            }.getOrElse { HealthUi.Error(it.message ?: it.javaClass.simpleName) }
            else -> HealthUi.NotInstalled
        }
    }
    LifecycleResumeEffect(Unit) {
        reload++
        onPauseOrDispose { }
    }

    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val bandState by BandData.state.collectAsStateWithLifecycle()
    var band by remember { mutableStateOf<BandDay?>(null) }
    LaunchedEffect(bandState.revision, reload, today) {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        band = withContext(Dispatchers.IO) { runCatching { BandData.day(context, start, end) }.getOrNull() }
    }
    val locale = currentLocale()
    val week = remember(sessions) { TrainingStats.weekly(sessions.forTraining(), System.currentTimeMillis(), zone, weeks = 1).single() }
    val lastActivity = sessions.firstOrNull { !it.simulated }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val hour = java.time.LocalTime.now(zone).hour
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, ${today.format(DateTimeFormatter.ofPattern("d MMMM", locale))}".uppercase(),
                color = ZoneColors.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            )
            Image(painterResource(R.drawable.taal_wordmark), contentDescription = APP_NAME, modifier = Modifier.height(30.dp))
        }
        Text(
            when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "My Day" },
            style = MaterialTheme.typography.headlineSmall,
        )
        UpdateCards()

        // The band's count only when it actually sent step records today; otherwise "--", never a made-up 0.
        val bandSteps = band?.takeIf { it.lastStepsAt != null }?.steps
        val steps = listOfNotNull((ui as? HealthUi.Ready)?.day?.steps?.toInt(), bandSteps).maxOrNull()
        val intensity = remember(sessions) { Performance.weeklyIntensityMinutes(sessions.forTraining(), System.currentTimeMillis(), zone) }
        TodayHero(steps, intensity.toLong())

        val workoutStore = remember { WorkoutStore.get(context) }
        val workouts by workoutStore.workouts.collectAsStateWithLifecycle()
        val schedule by workoutStore.schedule.collectAsStateWithLifecycle()
        val planned = schedule[today]?.let { workoutStore.get(it) }
        val plannedStatus = planned?.let {
            WorkoutSchedule.status(today, today, it.workout.name, sessions.workoutsRunByDay(zone)[today].orEmpty())
        }
        val profile by SettingsStore.get(context).profile.collectAsStateWithLifecycle()
        if (planned != null && plannedStatus != null) {
            TodayWorkoutCard(planned, plannedStatus, profile?.zones(), onStart = {
                SettingsStore.get(context).saveWorkoutId(planned.id)
                onStartRun()
            })
        }
        // Nothing planned today: suggest something from load and recovery (null once you've trained today).
        val suggestion = remember(sessions, today) { if (planned == null) Coach.suggest(sessions.forTraining(), System.currentTimeMillis(), zone) else null }
        suggestion?.let { sg ->
            val w = sg.workoutName?.let { n -> workouts.firstOrNull { it.workout.name == n } }
            SuggestionCard(
                sg, w, profile?.zones(),
                onStart = { w?.let { SettingsStore.get(context).saveWorkoutId(it.id); onStartRun() } },
                onPlan = { w?.let { workoutStore.plan(today, it.id) } },
            )
        }
        if (planned == null || plannedStatus == PlanStatus.DONE) {
            GradientButton("Start a run", onStartRun, shimmer = true, height = 58.dp)
        } else {
            GhostButton("Run without the workout", onStartRun, Modifier.fillMaxWidth(), height = 52.dp)
        }
        if (planned == null) {
            schedule.next(today.plusDays(1))?.takeIf { it.first <= today.plusDays(7) }?.let { (day, id) ->
                workouts.firstOrNull { it.id == id }?.let { w ->
                    MetricCard("Next workout", w.workout.name, detail = "${shortDay(day)} · ${workoutSummary(w.workout)}", accent = Palette.accent2)
                }
            }
        }

        if (bandState.enabled) BandCards(band, today.atStartOfDay(zone).toInstant().toEpochMilli())

        when (val state = ui) {
            HealthUi.Loading -> Text("Loading health data…", color = ZoneColors.muted)
            HealthUi.NotInstalled -> MetricCard("Health Connect", "Not available", detail = "Health Connect is needed to show steps, sleep and all-day heart rate from Samsung Health.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=com.google.android.apps.healthdata".toUri()))
                }) { Text("Get Health Connect") }
            }
            HealthUi.NeedsPermission -> MetricCard(
                "Daily health", "Connect Samsung Health",
                detail = "Allow read access to steps, sleep and heart rate. In Samsung Health, also enable Settings → Health Connect so your band's data is shared.",
            ) {
                Button(onClick = { permissionLauncher.launch(HealthData.PERMISSIONS) }) { Text("Allow access") }
            }
            is HealthUi.Error -> MetricCard("Daily health", "Couldn't read data", detail = state.message) {
                TextButton(onClick = { reload++ }) { Text("Retry") }
            }
            is HealthUi.Ready -> HealthCards(state.day, onFixPermissions = { permissionLauncher.launch(HealthData.PERMISSIONS) })
        }

        val comparison = remember(sessions) { Coach.weekComparison(sessions.forTraining(), System.currentTimeMillis(), zone) }
        WeekSummaryCard(comparison, week.inTargetMs / 60_000, today.dayOfWeek.value - 1)

        lastActivity?.let { a ->
            MetricCard(
                "Last activity", if (a.distanceM > 0) formatKm(a.distanceM) else formatDuration(a.activeMs),
                detail = "${formatDuration(a.activeMs)} · ${a.avgPaceSecPerKm?.let { "${a.type.formatPaceOrSpeed(it)} ${a.type.paceUnit} · " } ?: ""}${a.workoutName ?: a.targetLabel?.let { t -> "${a.inZonePercent}% in $t · ${a.alerts} alerts" } ?: "Free run"}",
                onClick = { onOpenActivity(a.id) },
            ) { if (a.hasZoneTimes) HrZoneStrip(a.zoneMs) else ZoneTimeBar(a.belowMs, a.inMs, a.aboveMs) }
        }
    }
}

/** The workout planned for today (Training → Calendar or Workouts → Schedule): start it, or see it's done. */
@Composable
private fun TodayWorkoutCard(planned: SavedWorkout, status: PlanStatus, zones: HrZones?, onStart: () -> Unit) {
    val done = status == PlanStatus.DONE
    GlowCard(accent = if (done) ZoneColors.inZone else Palette.accent2) {
        CardLabel(if (done) "Today's workout · done" else "Today's workout", if (done) ZoneColors.inZone else Palette.accent2)
        Text(planned.workout.name, style = MaterialTheme.typography.titleLarge)
        Text(workoutSummary(planned.workout), color = ZoneColors.muted, fontSize = 13.sp)
        StepList(planned.workout, zones)
        if (done) {
            Text("Nice work, it's in your activities.", color = ZoneColors.inZone, fontSize = 13.sp)
        } else {
            Spacer(Modifier.height(6.dp))
            GradientButton("Start workout", onStart, colors = listOf(Palette.accent2, Palette.accent), shimmer = true, height = 58.dp)
        }
    }
}

/** Two concentric rings that sweep in: steps vs 10,000 (outer) and weekly intensity minutes vs 150 (inner). */
@Composable
private fun TodayHero(steps: Int?, intensityMin: Long) {
    val stepGoal = 10_000
    val minGoal = Performance.INTENSITY_GOAL_MIN
    val grow = rememberGrow(steps to intensityMin, durationMs = 1400, delayMs = 250)
    val stepFrac = ((steps ?: 0).toFloat() / stepGoal).coerceIn(0f, 1f) * grow
    val minFrac = (intensityMin.toFloat() / minGoal).coerceIn(0f, 1f) * grow
    GlowCard(accent = Palette.steps) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Canvas(Modifier.size(132.dp)) {
                val w = 13.dp.toPx()
                fun ring(inset: Float, frac: Float, colors: List<Color>) {
                    val tl = Offset(inset + w / 2, inset + w / 2)
                    val sz = Size(size.width - 2 * inset - w, size.height - 2 * inset - w)
                    drawArc(colors.first().copy(alpha = 0.14f), 0f, 360f, false, tl, sz, style = Stroke(w))
                    if (frac > 0f) {
                        rotate(-90f) {
                            drawArc(Brush.sweepGradient(colors + colors.first()), 0f, 360f * frac, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                        }
                    }
                }
                ring(0f, stepFrac, listOf(Palette.steps, Palette.accent2))
                ring(w + 5.dp.toPx(), minFrac, listOf(ZoneColors.inZone, Color(0xFFB6F36A)))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    CardLabel("Steps", Palette.steps)
                    RollingText(steps?.let { "%,d".format(it) } ?: "--", style = MaterialTheme.typography.displaySmall.copy(fontSize = 32.sp))
                    Text(if (steps != null) "of %,d".format(stepGoal) else "No step data yet", color = ZoneColors.muted, style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    CardLabel("Intensity · week", ZoneColors.inZone)
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingText("$intensityMin", style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp))
                        Text(" / $minGoal min", color = ZoneColors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCards(day: DailyHealth, onFixPermissions: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Steps this week", day.steps?.let { "%,d".format(it) } ?: "--", Modifier.weight(1f), accent = Palette.steps) {
            if (day.weekSteps.isNotEmpty()) BarChart(
                day.weekSteps.map { it.second.toFloat() },
                day.weekSteps.map { it.first.dayOfWeek.getDisplayName(TextStyle.NARROW, currentLocale()) },
                Palette.steps,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Sleep", day.sleep?.let { formatHm(it) } ?: "--", Modifier.weight(1f), accent = Palette.sleep,
            detail = day.sleepEnd?.let { "woke ${timeOf(it)}" })
        MetricCard("Resting HR", day.restingHr?.let { "$it bpm" } ?: "--", Modifier.weight(1f), accent = ZoneColors.above)
    }
    MetricCard(
        "Heart rate today", day.hrAvg?.let { "$it bpm avg" } ?: "--",
        detail = if (day.hrMin != null) "min ${day.hrMin} · max ${day.hrMax}" else "No heart-rate data from Samsung Health today",
        accent = ZoneColors.above,
    ) { if (day.hrSeries.size >= 2) DayHrChart(day.hrSeries) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Calories", day.caloriesKcal?.let { "$it kcal" } ?: "--", Modifier.weight(1f), accent = ZoneColors.pending)
    }
    val fresh = day.lastDataAt?.let { "Samsung Health data up to ${timeOf(it)}" }
        ?: "No data from Samsung Health yet today. Check that Samsung Health shares to Health Connect."
    Text(fresh, color = ZoneColors.muted, fontSize = 12.sp)
    val missing = listOfNotNull("sleep".takeIf { day.sleep == null }, "heart rate".takeIf { day.hrAvg == null }, "resting HR".takeIf { day.restingHr == null })
    if (missing.isNotEmpty()) {
        Text(
            "No ${missing.joinToString(", ")} received. In Health Connect → App permissions → Samsung Health, " +
                "make sure Samsung Health is allowed to write these, then open Samsung Health to sync.",
            color = ZoneColors.muted, fontSize = 12.sp,
        )
        TextButton(onClick = onFixPermissions) { Text("Review Health Connect permissions") }
    }
}

/** Heart rate and steps decoded from the band's own sync traffic (passive SAP listener). */
@Composable
private fun BandCards(day: BandDay?, dayStartMs: Long) {
    if (day == null || day.isEmpty) {
        MetricCard(
            "From your band", "Waiting for a sync",
            detail = "Heart rate and steps appear here when the Fit3 syncs with Galaxy Wearable while $APP_NAME is connected.",
            accent = ZoneColors.above,
        )
        return
    }
    MetricCard(
        "Band heart rate", if (day.hrMin != null) "${day.hrMin}–${day.hrMax} bpm" else "--",
        detail = day.lastHrAt?.let { "per-minute range · up to ${formatClock(it)}" } ?: "No heart rate synced yet today",
        accent = ZoneColors.above,
    ) { if (day.hr.isNotEmpty()) BandHrChart(day, dayStartMs) }
    MetricCard(
        "Band steps", "%,d".format(day.steps),
        detail = "${formatKm(day.distanceM)}${day.lastStepsAt?.let { " · up to ${formatClock(it)}" } ?: ""}",
        accent = Palette.steps,
    ) {
        if (day.steps > 0) Column {
            BarChart(day.stepsByHour.map { it.toFloat() }, emptyList(), Palette.steps, highlightLast = false)
            DayAxis()
        }
    }
    day.night?.let { n ->
        MetricCard(
            "Band sleep", formatDuration((n.durationMin?.times(60_000L)) ?: (n.endMs - n.startMs)),
            detail = buildString {
                append("${formatClock(n.startMs)}–${formatClock(n.endMs)}")
                n.score?.let { append(" · score $it") }
                n.efficiency?.let { append(" · efficiency %.0f %%".format(it)) }
            },
            accent = Palette.sleep,
        ) { if (n.stages.isNotEmpty()) SleepStageStrip(n) }
    }
    if (day.spo2.isNotEmpty()) {
        val last = day.spo2.last()
        MetricCard(
            "Band blood oxygen", "${last.spo2} %",
            detail = "at ${formatClock(last.tMs)}${last.heartRate?.let { " · $it bpm" } ?: ""}" +
                if (day.spo2.size > 1) " · range ${day.spo2.minOf { it.spo2 }}–${day.spo2.maxOf { it.spo2 }} % (${day.spo2.size} readings)" else "",
            accent = Palette.oxygen,
        )
    }
    day.workouts.forEach { w ->
        MetricCard(
            "Band workout · ${bandExerciseName(w.exerciseType)}", formatDuration(w.durationMs.takeIf { it > 0 } ?: (w.endMs - w.startMs)),
            detail = listOfNotNull(
                formatClock(w.startMs),
                w.distanceM?.takeIf { it > 0 }?.let { formatKm(it.toDouble()) },
                w.meanHr?.let { "avg %.0f bpm".format(it) },
                w.maxHr?.let { "max %.0f".format(it) },
                w.kcal?.let { "%.0f kcal".format(it) },
            ).joinToString(" · "),
            accent = ZoneColors.inZone,
        )
    }
}

/** Samsung Health exercise type ids (public Health SDK values); others shown by number. */
private fun bandExerciseName(type: Int) = when (type) {
    1001 -> "Walking"; 1002 -> "Running"; 11007 -> "Cycling"; 13001 -> "Hiking"; 14001 -> "Swimming"
    15005 -> "Treadmill"; 0 -> "Other"; else -> "Type $type"
}

/** Sleep stages as a coloured strip across the night (codes 40001 awake · 40002 light · 40003 deep · 40004 REM). */
@Composable
private fun SleepStageStrip(n: BandNight) {
    val span = (n.endMs - n.startMs).coerceAtLeast(1).toFloat()
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        n.stages.forEach { s ->
            val (color, row) = when (s.stage) {
                40001 -> Color(0xFFFFB74D) to 0
                40004 -> Color(0xFF4FC3F7) to 1
                40002 -> Color(0xFF7986CB) to 2
                40003 -> Color(0xFF3949AB) to 3
                else -> ZoneColors.muted to 2
            }
            val x0 = (s.startMs - n.startMs) / span * size.width
            val x1 = (s.endMs - n.startMs) / span * size.width
            val h = size.height / 4
            drawRect(color, Offset(x0, row * h), androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), h))
        }
    }
    Text("awake · REM · light · deep", color = ZoneColors.muted, fontSize = 11.sp)
}

/** Min–max bar per minute bin across the whole day; spot readings as dots. */
@Composable
private fun BandHrChart(day: BandDay, dayStartMs: Long) {
    val lo = (day.hrMin ?: 40) - 5f
    val hi = (day.hrMax ?: 180) + 5f
    Column {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            fun x(t: Long) = (t - dayStartMs) / 86_400_000f * size.width
            fun y(bpm: Int) = size.height - (bpm - lo) / (hi - lo) * size.height
            day.hr.forEach { p ->
                if (p.spot) drawCircle(ZoneColors.pending, 3.5f, Offset(x(p.tMs), y(p.min)), style = Fill)
                else drawLine(ZoneColors.above, Offset(x(p.tMs), y(p.min)), Offset(x(p.tMs), y(p.max) - 1.5f), 2f)
            }
        }
        DayAxis()
    }
}

@Composable
private fun DayAxis() {
    Row(Modifier.fillMaxWidth()) {
        listOf("00", "06", "12", "18", "24").forEachIndexed { i, label ->
            if (i > 0) Spacer(Modifier.weight(1f))
            Text(label, color = ZoneColors.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DayHrChart(series: List<Pair<Instant, Int>>) {
    val min = series.minOf { it.second } - 5f
    val max = series.maxOf { it.second } + 5f
    val t0 = series.first().first.toEpochMilli()
    val span = (series.last().first.toEpochMilli() - t0).coerceAtLeast(1).toFloat()
    val grow = rememberGrow(series.size, durationMs = 1300)
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val path = Path()
        var last = Offset.Zero
        series.forEachIndexed { i, (t, bpm) ->
            val o = Offset((t.toEpochMilli() - t0) / span * size.width, size.height - (bpm - min) / (max - min) * size.height)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            last = o
        }
        val area = Path().apply { addPath(path); lineTo(last.x, size.height); lineTo(0f, size.height); close() }
        clipRect(right = size.width * grow) {
            drawPath(area, Brush.verticalGradient(listOf(Palette.heart.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, Palette.heart, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private fun formatHm(d: Duration) = "${d.toHours()}h ${d.toMinutesPart()}m"
private fun timeOf(i: Instant) = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(i)
