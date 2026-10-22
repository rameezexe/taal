package com.rameez.fit3zone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rameez.fit3zone.core.PlanStatus
import com.rameez.fit3zone.core.WorkoutSchedule
import com.rameez.fit3zone.data.SessionStatus
import com.rameez.fit3zone.data.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Names of the structured workouts run on each day (real, finished sessions only), for plan status. */
fun List<SessionSummary>.workoutsRunByDay(zone: ZoneId): Map<LocalDate, Set<String>> =
    filter { !it.simulated && it.status != SessionStatus.IN_PROGRESS && it.workoutName != null }
        .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        .mapValues { (_, list) -> list.mapNotNull { it.workoutName }.toSet() }

fun planColor(status: PlanStatus): Color = when (status) {
    PlanStatus.PLANNED -> Palette.accent2
    PlanStatus.DONE -> ZoneColors.inZone
    PlanStatus.MISSED -> Palette.faint
}

/** "Tue 21 Oct". */
fun shortDay(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))

/** Monday-first month grid; [cell] draws each day (square cells). */
@Composable
fun MonthGrid(month: YearMonth, modifier: Modifier = Modifier, cell: @Composable BoxScope.(LocalDate) -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), color = ZoneColors.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) { if (day != null) cell(day) }
                }
            }
        }
    }
}

/** Ring around a calendar day that has a planned workout: violet planned, green done, dashed grey missed. */
@Composable
fun PlanRing(status: PlanStatus, modifier: Modifier = Modifier) {
    val c = planColor(status)
    Canvas(modifier.size(36.dp)) {
        val w = 2.2.dp.toPx()
        drawCircle(
            c, radius = size.minDimension / 2 - w / 2,
            style = Stroke(w, pathEffect = if (status == PlanStatus.MISSED) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null),
        )
    }
}

@Composable
fun DayNumber(day: LocalDate, today: LocalDate, enabled: Boolean = true) {
    Text(
        day.dayOfMonth.toString(), fontSize = 13.sp,
        color = when { day == today -> Palette.accent; !enabled -> Palette.faint; else -> Palette.text },
        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
    )
}

/**
 * Pick the days [workoutId] is planned on: tap a day to plan it there (replacing another workout's
 * plan that day) or tap again to clear it. Past days can't be planned.
 */
@Composable
fun ScheduleDialog(
    workoutId: String,
    workoutName: String,
    schedule: WorkoutSchedule,
    nameOf: (String) -> String?,
    today: LocalDate,
    onPlan: (LocalDate) -> Unit,
    onUnplan: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var lastTapped by remember { mutableStateOf<LocalDate?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        GlowCard(accent = Palette.accent2, contentPadding = 16.dp) {
            CardLabel("Schedule", Palette.accent2)
            Text(workoutName, style = MaterialTheme.typography.titleLarge)
            Text("Tap the days to run it. Tap again to remove.", color = ZoneColors.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextAction("‹", { month = month.minusMonths(1) }, enabled = month > YearMonth.from(today))
                Text(
                    "${month.month.getDisplayName(TextStyle.FULL, currentLocale())} ${month.year}",
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                )
                TextAction("›", { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today).plusMonths(12))
            }
            MonthGrid(month) { day ->
                val planned = schedule[day]
                val past = day < today
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !past) {
                        lastTapped = day
                        if (planned == workoutId) onUnplan(day) else onPlan(day)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        planned == workoutId -> PlanRing(PlanStatus.PLANNED)
                        planned != null && !past -> PlanRing(PlanStatus.MISSED) // another workout: grey
                    }
                    DayNumber(day, today, enabled = !past)
                }
            }
            val note = lastTapped?.let { d ->
                val id = schedule[d]
                when {
                    id == workoutId -> "Planned for ${shortDay(d)}"
                    id != null -> "${shortDay(d)}: ${nameOf(id) ?: "another workout"}"
                    else -> "Removed from ${shortDay(d)}"
                }
            } ?: schedule.upcoming(workoutId, today).let { up ->
                if (up.isEmpty()) "Not planned yet" else "Next: ${shortDay(up.first())}" + if (up.size > 1) " · ${up.size} days planned" else ""
            }
            Text(note, color = ZoneColors.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Done", onDismiss)
            }
        }
    }
}
