package com.rameez.fit3zone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.Suggestion
import com.rameez.fit3zone.core.SuggestionKind
import com.rameez.fit3zone.core.WeekComparison
import com.rameez.fit3zone.data.SavedWorkout
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * This week so far: distance, time, load and intensity minutes, each against last week up to the same day and
 * time; which days were active; time in zones; in-target minutes.
 */
@Composable
fun WeekSummaryCard(c: WeekComparison, inTargetMin: Long, todayIndex: Int) {
    val now = c.thisWeek
    val then = c.lastWeekSoFar
    GlowCard(accent = Palette.accent) {
        CardLabel("This week", Palette.accent)
        Row(Modifier.fillMaxWidth()) {
            WeekStat("%.1f".format(now.distanceM / 1000), "km", delta(now.distanceM, then.distanceM), Modifier.weight(1f))
            WeekStat(formatHoursMin(now.activeMs), "time", delta(now.activeMs.toDouble(), then.activeMs.toDouble()), Modifier.weight(1f))
            WeekStat("${now.load}", "load", delta(now.load.toDouble(), then.load.toDouble()), Modifier.weight(1f))
            WeekStat("${now.intensityMin}", "int. min", delta(now.intensityMin.toDouble(), then.intensityMin.toDouble()), Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        DayDots(now.activeDays, todayIndex)
        if (now.zoneMs.drop(1).sum() > 0) {
            Spacer(Modifier.height(2.dp))
            HrZoneStrip(now.zoneMs)
            Text(
                (1..HrZones.COUNT).filter { now.zoneMs[it] >= 60_000 }.joinToString(" · ") { "Z$it ${now.zoneMs[it] / 60_000} min" },
                color = ZoneColors.muted, fontSize = 12.sp,
            )
        }
        Text(
            buildString {
                append("${now.activities} ${if (now.activities == 1) "activity" else "activities"}")
                if (inTargetMin > 0) append(" · $inTargetMin min in target")
                append(" · last week ${c.lastWeekTotal.activities} · %.1f km".format(c.lastWeekTotal.distanceM / 1000))
            },
            color = ZoneColors.muted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Change against last week so far: "+12%", "new" when last week had none, null when both are zero. */
private fun delta(now: Double, then: Double): Pair<String, Color>? = when {
    now == 0.0 && then == 0.0 -> null
    then == 0.0 -> "new" to ZoneColors.inZone
    else -> {
        val pct = ((now - then) / then * 100).roundToInt()
        when {
            pct == 0 -> "±0%" to ZoneColors.muted
            pct > 0 -> "+$pct%" to ZoneColors.inZone
            else -> "−${abs(pct)}%" to ZoneColors.muted
        }
    }
}

@Composable
private fun WeekStat(value: String, label: String, delta: Pair<String, Color>?, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = Numeric, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1, softWrap = false)
        Text(label.uppercase(), color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall)
        Text(delta?.first ?: " ", color = delta?.second ?: ZoneColors.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Mon–Sun: filled = trained, ring = today, faint = rest or still to come. */
@Composable
private fun DayDots(active: Set<Int>, todayIndex: Int) {
    val locale = currentLocale()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEachIndexed { i, d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val dot = Modifier.size(18.dp)
                Box(
                    when {
                        i in active -> dot.background(Palette.accent, CircleShape)
                        i == todayIndex -> dot.border(2.dp, Palette.accent, CircleShape)
                        else -> dot.background(Palette.hairline, CircleShape)
                    },
                )
                Text(d.getDisplayName(TextStyle.NARROW, locale), color = if (i == todayIndex) Palette.text else ZoneColors.muted, fontSize = 11.sp)
            }
        }
    }
}

private fun formatHoursMin(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h${"%02d".format(m % 60)}" else "${m}m"
}

/**
 * Today's suggestion when nothing is planned: rest, or a workout from the library with Start / Plan for today.
 * [workout] is null for rest days or when the suggested workout isn't in the library any more.
 */
@Composable
fun SuggestionCard(s: Suggestion, workout: SavedWorkout?, zones: HrZones?, onStart: () -> Unit, onPlan: () -> Unit) {
    val accent = when (s.kind) {
        SuggestionKind.REST -> Palette.sleep
        SuggestionKind.EASY -> ZoneColors.inZone
        SuggestionKind.TEMPO -> ZoneColors.pending
        SuggestionKind.HARD -> ZoneColors.above
    }
    GlowCard(accent = accent) {
        CardLabel("Suggested today · ${s.kind.label}", accent)
        Text(if (s.kind == SuggestionKind.REST) "Take a rest day" else workout?.workout?.name ?: s.workoutName.orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(s.reason, color = ZoneColors.muted, fontSize = 13.sp)
        if (workout != null) {
            Text(workoutSummary(workout.workout), color = ZoneColors.muted, fontSize = 13.sp)
            StepList(workout.workout, zones)
            Spacer(Modifier.height(6.dp))
            GhostButton("Start this workout", onStart, Modifier.fillMaxWidth(), height = 52.dp)
            TextAction("Plan it for today", onPlan)
        } else if (s.kind == SuggestionKind.REST) {
            Text("A short walk is fine; easy movement helps recovery.", color = ZoneColors.muted, fontSize = 13.sp)
        }
        Text("Estimate from your load and recovery, not medical advice.", color = ZoneColors.muted, fontSize = 11.sp)
    }
}
