package com.rameez.fit3zone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.core.PaceGoal
import com.rameez.fit3zone.core.PacerStatus
import com.rameez.fit3zone.core.formatClockDuration
import com.rameez.fit3zone.core.formatPace
import kotlin.math.abs
import kotlin.math.roundToInt

private val PRESETS = listOf("5K" to 5_000.0, "10K" to 10_000.0, "Half" to 21_097.5, "23K" to 23_000.0)

/** Before a GPS run: pacer on/off, goal distance (presets or custom km) and goal time; shows the even pace. */
@Composable
fun PacerCard(on: Boolean, goal: PaceGoal, onToggle: (Boolean) -> Unit, onGoal: (PaceGoal) -> Unit) {
    // The fields keep what is typed; they only follow the goal when it changes from elsewhere (a preset chip).
    var timeText by remember { mutableStateOf(formatClockDuration(goal.timeMs)) }
    LaunchedEffect(goal.timeMs) { if (PaceGoal.parseTime(timeText) != goal.timeMs) timeText = formatClockDuration(goal.timeMs) }
    val preset = PRESETS.firstOrNull { abs(it.second - goal.distanceM) < 1 }
    var customOpen by remember { mutableStateOf(preset == null) }
    var kmText by remember { mutableStateOf(trimKm(goal.distanceM)) }
    LaunchedEffect(goal.distanceM) {
        if (kmText.replace(',', '.').toDoubleOrNull()?.let { abs(it * 1000 - goal.distanceM) < 1 } != true) kmText = trimKm(goal.distanceM)
    }
    GlowCard(accent = Palette.accent2, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardLabel("Pacer", Palette.accent2)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip("Off", !on, Palette.accent2) { onToggle(false) }
                PRESETS.forEach { (label, m) ->
                    PillChip(label, on && !customOpen && preset?.second == m, Palette.accent2) {
                        customOpen = false
                        onGoal(PaceGoal(m, goal.timeMs))
                        onToggle(true)
                    }
                }
                PillChip("Custom", on && customOpen, Palette.accent2) { customOpen = true; onToggle(true) }
            }
            if (on) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (customOpen) {
                        OutlinedTextField(
                            kmText, { t ->
                                kmText = t
                                t.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.1..500.0 }?.let { onGoal(PaceGoal(it * 1000, goal.timeMs)) }
                            },
                            label = { Text("Distance km") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        timeText, { t ->
                            timeText = t
                            PaceGoal.parseTime(t)?.let { onGoal(PaceGoal(goal.distanceM, it)) }
                        },
                        label = { Text("Goal time h:mm:ss") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        isError = PaceGoal.parseTime(timeText) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "${trimKm(goal.distanceM)} km in ${formatClockDuration(goal.timeMs)} → even pace ${formatPace(goal.paceSecPerKm)} /km. " +
                        "Live ahead/behind on the Pacer page, spoken every km with voice cues on.",
                    color = ZoneColors.muted, fontSize = 13.sp,
                )
            } else {
                Text("Set a distance and goal time to see if you're ahead or behind an even pace.", color = ZoneColors.muted, fontSize = 13.sp)
            }
        }
    }
}

private fun trimKm(m: Double) = "%.2f".format(java.util.Locale.US, m / 1000).trimEnd('0').trimEnd('.')

/** "+1:20" ahead / "−0:35" behind; "on pace" within 10 s. */
fun aheadText(ms: Long): String =
    if (abs(ms) < PacerStatus.ON_PACE_MS) "on pace" else (if (ms > 0) "+" else "−") + formatClockDuration(abs(ms))

fun aheadColor(ms: Long): Color = when {
    abs(ms) < PacerStatus.ON_PACE_MS -> ZoneColors.inZone
    ms > 0 -> ZoneColors.inZone
    ms > -60_000 -> ZoneColors.pending
    else -> ZoneColors.above
}

/** One line under the heart page's summary: where you stand against the goal. */
@Composable
fun PacerLine(p: PacerStatus) {
    Text(
        if (!p.meaningful) "Pacer · ${trimKm(p.goal.distanceM)} km in ${formatClockDuration(p.goal.timeMs)}"
        else if (p.done) "Pacer · goal distance done"
        else "Pacer · ${aheadText(p.aheadMs)} · ${"%.2f".format(p.remainingM / 1000)} km to go",
        color = if (p.meaningful && !p.done) aheadColor(p.aheadMs) else Palette.accent2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** The pacer in big numbers: ahead/behind, a progress bar, then the paces and the projected finish. */
@Composable
fun PacerPage(p: PacerStatus, avgPaceSecPerKm: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlowCard(accent = if (p.meaningful) aheadColor(p.aheadMs) else Palette.accent2) {
            CardLabel("Goal · ${trimKm(p.goal.distanceM)} km in ${formatClockDuration(p.goal.timeMs)}", Palette.accent2)
            val headline = when {
                p.done -> "Done"
                !p.meaningful -> "--"
                else -> aheadText(p.aheadMs)
            }
            Text(
                headline, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp, lineHeight = 68.sp),
                color = if (p.meaningful && !p.done) aheadColor(p.aheadMs) else Palette.text,
            )
            Text(
                when {
                    p.done -> "Finished in ${formatClockDuration(p.activeMs)} (${aheadText(p.goal.timeMs - p.activeMs).let { if (it == "on pace") "right on goal" else "$it vs goal" }})"
                    !p.meaningful -> "Numbers start after 200 m"
                    p.aheadMs >= PacerStatus.ON_PACE_MS -> "ahead of even pace"
                    p.aheadMs <= -PacerStatus.ON_PACE_MS -> "behind even pace"
                    else -> "within 10 s of even pace"
                },
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = ZoneColors.muted,
            )
            ProgressBar(p.fraction.toFloat())
            Row(Modifier.fillMaxWidth()) {
                Text("${"%.2f".format(p.distanceM / 1000)} km", fontSize = 13.sp, color = ZoneColors.muted)
                Box(Modifier.weight(1f))
                Text("${(p.fraction * 100).roundToInt()} % · ${"%.2f".format(p.remainingM / 1000)} km to go", fontSize = 13.sp, color = ZoneColors.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaceTile("Goal pace", formatPace(p.goal.paceSecPerKm), Modifier.weight(1f))
            PaceTile("Needed pace", if (p.done) "--:--" else formatPace(p.neededPaceSecPerKm), Modifier.weight(1f), accent = if (p.meaningful) aheadColor(p.aheadMs) else ZoneColors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaceTile("Avg pace", formatPace(avgPaceSecPerKm), Modifier.weight(1f))
            PaceTile("Projected", p.projectedFinishMs?.let { formatClockDuration(it) } ?: "--", Modifier.weight(1f), unit = "finish")
        }
    }
}

@Composable
private fun PaceTile(label: String, value: String, modifier: Modifier, unit: String = "/km", accent: Color = ZoneColors.muted) {
    GlowCard(modifier, accent = accent, contentPadding = 14.dp) {
        CardLabel(label, accent)
        RollingText(value, style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp))
        Text(unit, color = ZoneColors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun ProgressBar(frac: Float) {
    val grow = rememberGrow(Unit, durationMs = 900)
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Palette.hairline)) {
        Box(
            Modifier.fillMaxWidth((frac * grow).coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Palette.accent2, Palette.accent))),
        )
    }
}
