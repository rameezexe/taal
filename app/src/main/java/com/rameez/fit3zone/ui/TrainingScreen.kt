package com.rameez.fit3zone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.core.ActivityRecord
import com.rameez.fit3zone.core.PlanStatus
import com.rameez.fit3zone.core.WorkoutSchedule
import com.rameez.fit3zone.core.TrainingStats
import com.rameez.fit3zone.core.formatPace
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SettingsStore
import com.rameez.fit3zone.data.SessionStatus
import com.rameez.fit3zone.data.SessionSummary
import com.rameez.fit3zone.data.SavedWorkout
import com.rameez.fit3zone.data.WorkoutStore
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle

/** Real, finished sessions only: simulated data never counts toward training stats. */
fun List<SessionSummary>.forTraining(): List<ActivityRecord> =
    filter { !it.simulated && it.status != SessionStatus.IN_PROGRESS }
        .map { it.toRecord() }

fun SessionSummary.toRecord(): ActivityRecord =
    ActivityRecord(id, startedAt, activeMs, belowMs, inMs, aboveMs, distanceM, zoneMs, type, avgBpm)

@Composable
fun TrainingScreen(modifier: Modifier, onOpenActivity: (Long) -> Unit) {
    val context = LocalContext.current
    val history = remember { SessionHistory.get(context) }
    val sessions by history.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(SessionHistory.dispatcher) { history.refresh() } }

    val zone = remember { ZoneId.systemDefault() }
    val acts = remember(sessions) { sessions.forTraining() }
    val weeks = remember(acts) { TrainingStats.weekly(acts, System.currentTimeMillis(), zone) }
    val records = remember(acts) { TrainingStats.records(acts) }
    var month by remember { mutableStateOf(YearMonth.now(zone)) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Training", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (acts.isEmpty()) Text("Complete a real run to see training stats. Simulated sessions are not counted.", color = ZoneColors.muted)

        val thisWeek = weeks.last()
        val lastWeek = weeks[weeks.size - 2]
        MetricCard(
            "In target this week", "${thisWeek.inTargetMs / 60_000} min",
            detail = "Last week ${lastWeek.inTargetMs / 60_000} min · ${thisWeek.activities} activities · ${formatKm(thisWeek.distanceM)}",
            accent = ZoneColors.inZone,
        ) {
            BarChart(
                values = weeks.map { it.inTargetMs / 60_000f },
                labels = weeks.map { "${it.weekStart.dayOfMonth}/${it.weekStart.monthValue}" },
                color = ZoneColors.inZone,
            )
        }

        MetricCard(
            "Training load (estimate)", thisWeek.load.toString(),
            detail = "Edwards TRIMP: minutes in Z1×1 … Z5×5 (older runs: below ×1, in target ×2, above ×4). Not Garmin's Training Load.",
            accent = ZoneColors.pending,
        ) {
            BarChart(weeks.map { it.load.toFloat() }, weeks.map { "${it.weekStart.dayOfMonth}/${it.weekStart.monthValue}" }, ZoneColors.pending)
        }

        val profile by SettingsStore.get(context).profile.collectAsStateWithLifecycle()
        if (thisWeek.zoneMs.sum() > 0) {
            MetricCard("Time in zones this week", formatDuration(thisWeek.zoneMs.sum())) {
                HrZoneBars(thisWeek.zoneMs, profile?.zones())
            }
        } else {
            MetricCard("Time this week", formatDuration(thisWeek.activeMs))
        }

        if (acts.isNotEmpty()) PerformanceSection(acts, sessions, zone, onOpenActivity)

        CalendarCard(
            month, sessions, acts, zone,
            onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) }, onOpenActivity = onOpenActivity,
        )

        Text("Personal records", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        records.longestDistance?.let { MetricCard("Longest run", formatKm(it.distanceM), onClick = { onOpenActivity(it.id) }) }
        records.longestDuration?.let { MetricCard("Longest session", formatDuration(it.activeMs), onClick = { onOpenActivity(it.id) }) }
        records.mostInTarget?.let { MetricCard("Most time in target, one session", formatDuration(it.inMs), accent = ZoneColors.inZone, onClick = { onOpenActivity(it.id) }) }
        records.fastestAvgPace?.let { (a, pace) -> MetricCard("Fastest run pace (≥1 km)", "${formatPace(pace)} /km", onClick = { onOpenActivity(a.id) }) }
    }
}

@Composable
private fun CalendarCard(
    month: YearMonth,
    sessions: List<SessionSummary>,
    acts: List<ActivityRecord>,
    zone: ZoneId,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenActivity: (Long) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { WorkoutStore.get(context) }
    val workouts by store.workouts.collectAsStateWithLifecycle()
    val schedule by store.schedule.collectAsStateWithLifecycle()
    val days = remember(month, acts) { TrainingStats.calendar(acts, month, zone) }
    val ran = remember(sessions) { sessions.workoutsRunByDay(zone) }
    val today = LocalDate.now(zone)
    var selected by remember { mutableStateOf(today) }
    fun nameOf(id: String) = workouts.firstOrNull { it.id == id }?.workout?.name
    fun statusOf(day: LocalDate): PlanStatus? = schedule[day]?.let { id ->
        nameOf(id)?.let { WorkoutSchedule.status(day, today, it, ran[day].orEmpty()) }
    }
    val planned = schedule.days.keys.count { YearMonth.from(it) == month && nameOf(schedule[it]!!) != null }

    MetricCard(
        "Calendar", "${month.month.getDisplayName(TextStyle.FULL, currentLocale())} ${month.year}",
        detail = "${days.size} days with activity" + if (planned > 0) " · $planned planned" else "",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextAction("‹ Prev", onPrev)
            TextAction("Next ›", onNext, enabled = month < YearMonth.now(zone).plusMonths(12))
        }
        MonthGrid(month) { day ->
            val pct = days[day]
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { selected = day }
                    .let { if (day == selected) it.background(Palette.surfaceHigh, CircleShape) else it },
                contentAlignment = Alignment.Center,
            ) {
                if (pct != null) {
                    // Dot colour: mostly in target = green, mixed = amber, mostly off = red; free runs only = blue.
                    val c = when { pct < 0 -> ZoneColors.below; pct >= 60 -> ZoneColors.inZone; pct >= 30 -> ZoneColors.pending; else -> ZoneColors.above }
                    Box(Modifier.size(28.dp).background(c.copy(alpha = 0.85f), CircleShape))
                }
                statusOf(day)?.let { PlanRing(it) }
                DayNumber(day, today)
            }
        }
        DayPanel(
            selected, today, sessions.filter { !it.simulated && Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == selected },
            plannedId = schedule[selected]?.takeIf { nameOf(it) != null }, status = statusOf(selected),
            workouts = workouts, onOpenActivity = onOpenActivity,
            onPlan = { store.plan(selected, it) }, onUnplan = { store.unplan(selected) },
        )
    }
}

/** What happened / is planned on the tapped calendar day; future days can get a workout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPanel(
    day: LocalDate,
    today: LocalDate,
    activities: List<SessionSummary>,
    plannedId: String?,
    status: PlanStatus?,
    workouts: List<SavedWorkout>,
    onOpenActivity: (Long) -> Unit,
    onPlan: (String) -> Unit,
    onUnplan: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (day == today) "Today · ${shortDay(day)}" else shortDay(day), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        activities.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenActivity(a.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.title, Modifier.weight(1f), maxLines = 1)
                Text(
                    if (a.distanceM > 0) formatKm(a.distanceM) else formatDuration(a.activeMs),
                    color = ZoneColors.muted, fontSize = 13.sp,
                )
                Text("  ›", color = ZoneColors.muted)
            }
        }
        val plannedName = workouts.firstOrNull { it.id == plannedId }?.workout?.name
        if (plannedName != null && status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(planColor(status), CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(plannedName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (status) { PlanStatus.PLANNED -> "Planned"; PlanStatus.DONE -> "Done"; PlanStatus.MISSED -> "Missed" },
                        color = planColor(status), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (status != PlanStatus.DONE) TextAction("Remove", onUnplan, color = ZoneColors.above)
            }
        }
        if (day >= today) {
            if (workouts.isEmpty()) {
                Text("No workouts in your library yet (Run → Workout).", color = ZoneColors.muted, fontSize = 13.sp)
            } else {
                Text(if (plannedName == null) "Plan a workout" else "Change to", color = ZoneColors.muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    workouts.forEach { w ->
                        PillChip(w.workout.name, selected = w.id == plannedId, color = Palette.accent2, compact = true) {
                            if (w.id == plannedId) onUnplan() else onPlan(w.id)
                        }
                    }
                }
            }
        } else if (activities.isEmpty() && plannedName == null) {
            Text("Nothing recorded.", color = ZoneColors.muted, fontSize = 13.sp)
        }
    }
}
