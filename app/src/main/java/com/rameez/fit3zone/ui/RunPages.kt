package com.rameez.fit3zone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.data.GpsStatus
import com.rameez.fit3zone.data.SessionState
import kotlinx.coroutines.launch

/** Swipeable pages of the Run screen during a session (Garmin-style data screens). */
enum class RunPage(val title: String) { HEART("Heart"), PACER("Pacer"), DATA("Data"), LAPS("Laps"), MAP("Map") }

fun SessionState.hasGps(): Boolean = gps != GpsStatus.OFF && gps != GpsStatus.NO_PERMISSION

fun runPages(s: SessionState): List<RunPage> = RunPage.entries.filter {
    when (it) {
        RunPage.PACER -> s.pacer != null
        RunPage.MAP -> s.hasGps()
        else -> true
    }
}

/** Page names under the status line; the current one is highlighted, tap one to jump to it. */
@Composable
fun PageTabs(pages: List<RunPage>, pager: PagerState, type: ActivityType) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        pages.forEachIndexed { i, p ->
            val selected = pager.currentPage == i
            val bg by animateColorAsState(if (selected) Palette.surfaceHigh else Color.Transparent, tween(220), label = "pageBg")
            val fg by animateColorAsState(if (selected) Palette.accent else ZoneColors.muted, tween(220), label = "pageFg")
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(bg)
                    .clickable(interactionSource = source, indication = null) { scope.launch { pager.animateScrollToPage(i) } }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(if (p == RunPage.LAPS) "${type.lapWord}s" else p.title, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
        }
    }
}

/** One big Garmin-style field: label on top, large number, small unit. */
@Composable
private fun DataField(label: String, value: String, unit: String?, modifier: Modifier, valueColor: Color = Palette.text) {
    Column(modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall)
        RollingText(value, style = MaterialTheme.typography.displaySmall.copy(fontSize = 42.sp, color = valueColor))
        if (unit != null) Text(unit, color = Palette.faint, fontSize = 12.sp)
    }
}

private class Field(val label: String, val value: String, val unit: String? = null, val color: Color = Palette.text)

/** Fields two per row with hairline dividers between them. */
@Composable
private fun FieldGrid(fields: List<Field>) {
    GlowCard(accent = Palette.accent, contentPadding = 4.dp) {
        fields.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { i, f ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.hairline))
                    DataField(f.label, f.value, f.unit, Modifier.weight(1f), f.color)
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private fun km(m: Double) = "%.2f".format(m / 1000)

/** Totals for the whole session: time, distance, pace and heart rate in big fields, then time in zones. */
@Composable
fun DataPage(s: SessionState, bpm: Int?) {
    val snap = s.snapshot
    val hrColor = bpmColor(bpm, s.zones, s.target)
    val zoned = snap.timeBelowMs + snap.timeInMs + snap.timeAboveMs
    val third = if (s.target != null) {
        Field("In target", if (zoned > 0) "${snap.timeInMs * 100 / zoned}%" else "--")
    } else {
        Field("Zone", snap.hrZone?.let { zoneName(it) } ?: "--", color = hrColor)
    }
    val fields = buildList {
        add(Field("Time", formatDuration(snap.activeDurationMs)))
        if (s.hasGps()) {
            add(Field("Distance", km(s.distanceM), "km"))
            add(Field(s.type.paceLabel, s.type.formatPaceOrSpeed(s.paceSecPerKm), s.type.paceUnit))
            add(Field("Avg ${s.type.paceLabel.lowercase()}", s.type.formatPaceOrSpeed(s.avgPaceSecPerKm), s.type.paceUnit))
        }
        add(Field("Heart rate", bpm?.toString() ?: "--", "bpm", hrColor))
        add(Field("Avg HR", s.avgBpm?.toString() ?: "--", "bpm", bpmColor(s.avgBpm, s.zones, s.target)))
        add(Field("Max HR", s.maxBpm?.toString() ?: "--", "bpm", bpmColor(s.maxBpm, s.zones, s.target)))
        add(third)
        if (s.steps != null) {
            add(Field("Cadence", s.cadenceSpm?.toString() ?: "--", "spm"))
            add(Field("Avg cadence", s.avgCadenceSpm?.toString() ?: "--", "spm · ${s.steps} steps"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldGrid(fields)
        if (s.target != null) {
            Row(Modifier.fillMaxWidth()) {
                SmallStat("Above", formatDuration(snap.timeAboveMs), Modifier.weight(1f))
                SmallStat("Below", formatDuration(snap.timeBelowMs), Modifier.weight(1f))
                SmallStat("Alerts", snap.alertCount.toString(), Modifier.weight(1f))
            }
        }
        if (s.zones != null && snap.zoneMs.sum() > 0) {
            Text("Time in zones", color = ZoneColors.muted, fontSize = 13.sp)
            HrZoneBars(snap.zoneMs, s.zones)
        }
        if (snap.dataInterruptions > 0) {
            Text("Data gaps: ${snap.dataInterruptions} (${formatDuration(snap.timeStaleMs)})", color = ZoneColors.muted)
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RollingText(value, style = MaterialTheme.typography.titleLarge)
        Text(label.uppercase(), color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

/** The running lap in big fields, then every finished lap (newest first). */
@Composable
fun LapsPage(s: SessionState) {
    val lap = s.currentLap
    val colorOf = { b: Int? -> bpmColor(b, s.zones, s.target) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lap == null) {
            GlowCard(accent = Palette.accent) {
                CardLabel("${s.type.lapWord}s", Palette.accent)
                Text(
                    "Press ${s.type.lapWord} to start a new ${s.type.lapWord.lowercase()}." + if (s.hasGps()) " Auto-lap is set in Settings → GPS." else "",
                    color = ZoneColors.muted,
                )
            }
        } else {
            CardLabel("${s.type.lapWord} ${lap.number}${lap.label?.let { " · $it" } ?: ""}", Palette.accent)
            FieldGrid(
                buildList {
                    val w = s.type.lapWord
                    add(Field("$w time", formatDuration(lap.durationMs)))
                    if (s.hasGps()) {
                        add(Field("$w distance", km(lap.distanceM), "km"))
                        add(Field("$w ${s.type.paceLabel.lowercase()}", s.type.formatPaceOrSpeed(lap.paceSecPerKm), s.type.paceUnit))
                    }
                    add(Field("$w avg HR", lap.avgBpm?.toString() ?: "--", "bpm", colorOf(lap.avgBpm)))
                },
            )
        }
        if (s.laps.isNotEmpty()) {
            Text(if (s.workoutName != null) "Finished steps" else "Finished ${s.type.lapWord.lowercase()}s", fontWeight = FontWeight.SemiBold)
            LapsTable(s.laps.reversed(), colorOf, s.type)
        }
    }
}

/** Key numbers on top, then a large map. */
@Composable
fun MapPage(s: SessionState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SmallStat("Time", formatDuration(s.snapshot.activeDurationMs), Modifier.weight(1f))
            SmallStat(if (s.distanceEstimated) "Km · est." else "Km", km(s.distanceM), Modifier.weight(1f))
            SmallStat("${s.type.paceLabel} ${s.type.paceUnit}", s.type.formatPaceOrSpeed(s.paceSecPerKm), Modifier.weight(1f))
        }
        LiveMapCard(s.route, mapHeight = 330.dp, planned = s.plannedRoute?.points)
        s.plannedRoute?.let { RouteLine(it) }
        val (color, line) = s.gpsLine()
        Text(
            line,
            color = color,
            fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}
