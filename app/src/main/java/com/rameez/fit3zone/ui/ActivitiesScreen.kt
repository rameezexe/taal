package com.rameez.fit3zone.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.core.Achievement
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.core.ElevationProfile
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.HrSample
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.Lap
import com.rameez.fit3zone.core.Split
import com.rameez.fit3zone.core.drawablePieces
import com.rameez.fit3zone.core.formatPace
import com.rameez.fit3zone.core.formatRaceTime
import com.rameez.fit3zone.data.AchievementStore
import com.rameez.fit3zone.data.HealthExport
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionStatus
import com.rameez.fit3zone.data.SessionSummary
import com.rameez.fit3zone.data.Strava
import com.rameez.fit3zone.data.effortWord
import com.rameez.fit3zone.data.replayTrack
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ActivitiesScreen(modifier: Modifier, openId: Long? = null, onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val history = remember { SessionHistory.get(context) }
    val sessions by history.sessions.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) } // ActivityType name; null = all

    LaunchedEffect(Unit) { withContext(SessionHistory.dispatcher) { history.refresh() } }
    LaunchedEffect(openId) {
        if (openId != null) { selectedId = openId; onOpened() }
    }

    val selected = sessions.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null }
        ActivityDetail(modifier, selected, history, onBack = { selectedId = null })
        return
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Activities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (sessions.isEmpty()) Text("No activities yet. Finished activities are saved on this phone only.", color = ZoneColors.muted)
        // Type filter, only when there is more than one type to choose from.
        val types = sessions.map { it.type }.distinct().sortedBy { it.ordinal }
        // A filter whose last activity was deleted (or retyped) would leave an empty list with no chips to undo it.
        val active = filter?.takeIf { types.size > 1 && types.any { t -> t.name == it } }
        if (types.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip("All", active == null, Palette.accent, compact = true) { filter = null }
                types.forEach { t -> PillChip(t.label, active == t.name, Palette.accent, compact = true) { filter = t.name } }
            }
        }
        if (sessions.isNotEmpty()) WeekLine(sessions)
        val shown = sessions.filter { active == null || it.type.name == active }
        val achievements by AchievementStore.get(context).byId.collectAsStateWithLifecycle()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(shown, key = { it.id }) { s -> ActivityRow(s, achievements[s.id].orEmpty()) { selectedId = s.id } }
        }
    }
}

@Composable
private fun ActivityRow(s: SessionSummary, badges: List<Achievement>, onClick: () -> Unit) {
    GlowCard(onClick = onClick, contentPadding = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Long workout names shorten instead of squeezing the badge.
                Text(s.title, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                badge(s)?.let { (label, color) ->
                    Text(
                        label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text("${friendlyDate(s.startedAt)} · ${s.type.label}", color = Palette.muted, fontSize = 13.sp)
        }
        HeadlineStats(s, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        if (s.hasGps && s.distanceM > 0) ActivityMapThumb(s, 170.dp)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AchievementLine(badges)
            if (s.hasZoneTimes) HrZoneStrip(s.zoneMs) else ZoneTimeBar(s.belowMs, s.inMs, s.aboveMs, inLabel = s.targetLabel ?: "In target")
            zoneLine(s)?.let { Text(it, color = Palette.muted, fontSize = 13.sp) }
        }
    }
}

/** "68 % in Zone 2 · avg 149 bpm": the target result, else the zone it spent most time in. */
internal fun zoneLine(s: SessionSummary): String? {
    val avg = s.avgBpm?.let { " · avg $it bpm" }.orEmpty()
    s.target?.let { return "${s.inZonePercent} % in ${it.label}$avg" }
    if (!s.hasZoneTimes) return s.avgBpm?.let { "Avg $it bpm" }
    val total = s.zoneMs.drop(1).sum().takeIf { it > 0 } ?: return null
    val z = (1..HrZones.COUNT).maxBy { s.zoneMs[it] }
    return "${s.zoneMs[z] * 100 / total} % in Zone $z$avg"
}

/** "Today at 19:05", "Yesterday at 07:10", "Sun 18 Oct at 18:10", with the year once it isn't this year. */
fun friendlyDate(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val time = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    val day = when (t.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> t.format(DateTimeFormatter.ofPattern(if (t.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()))
    }
    return "$day at $time"
}

/** This week (from Monday) so far: activity count, distance, time. */
@Composable
private fun WeekLine(sessions: List<SessionSummary>) {
    val zone = ZoneId.systemDefault()
    val monday = LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
    val week = sessions.filter { !it.simulated && it.status != SessionStatus.IN_PROGRESS && it.startedAt >= from }
    val km = week.sumOf { it.distanceM } / 1000
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("This week", Modifier.weight(1f), color = Palette.muted, fontSize = 13.sp)
        Text(
            "${week.size} ${if (week.size == 1) "activity" else "activities"} · ${"%.1f".format(km)} km · ${formatDuration(week.sumOf { it.activeMs })}",
            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        )
    }
}

/**
 * Applies [change] to the stored session, then updates the activity on Strava and in Health Connect when it was
 * sent there, reporting a short result line for under the edited field through [onNote]. Saving unchanged text still syncs, which
 * retries an update that failed earlier.
 */
private fun saveAndSync(context: Context, history: SessionHistory, id: Long, onNote: (String?) -> Unit, change: SessionHistory.() -> Unit) {
    val app = context.applicationContext as Fit3ZoneApp
    app.appScope.launch {
        val updated = withContext(SessionHistory.dispatcher) {
            history.change()
            history.sessions.value.firstOrNull { it.id == id }
        } ?: return@launch
        onNote("Updating Strava / Samsung Health…")
        val strava = Strava.get(context).syncDetails(updated)
        val health = HealthExport.get(context).rewriteIfWritten(updated)
        onNote(
            buildList {
                strava.onSuccess { if (it) add("Updated on Strava") }.onFailure { add("Strava update failed: ${it.message?.take(80)}") }
                health.onSuccess { if (it) add("updated in Samsung Health") }.onFailure { add("Health Connect update failed: ${it.message?.take(80)}") }
            }.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null },
        )
    }
}

/** Title with a Rename action; the new name goes to Strava and Health Connect too. */
@Composable
private fun ActivityName(s: SessionSummary, history: SessionHistory) {
    val context = LocalContext.current
    var editing by remember(s.id) { mutableStateOf(false) }
    var text by remember(s.id) { mutableStateOf(s.title) }
    var syncNote by remember(s.id) { mutableStateOf<String?>(null) }
    val save = { name: String? ->
        editing = false
        saveAndSync(context, history, s.id, { syncNote = it }) { setName(s.id, name) }
    }

    if (editing) {
        OutlinedTextField(
            text, { text = it.take(60) }, label = { Text("Name") }, singleLine = true,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save(text) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextAction("Save", { save(text) }, enabled = text.isNotBlank())
            TextAction("Cancel", { editing = false; text = s.title }, color = ZoneColors.muted)
            if (s.name != null) TextAction("Reset name", { text = s.defaultTitle; save(null) }, color = ZoneColors.muted)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextAction("Rename", { text = s.title; editing = true })
        }
    }
    syncNote?.let { Text(it, color = ZoneColors.muted, fontSize = 13.sp) }
}

/**
 * The user's own note on the activity (how it felt, shoes, weather…). Sent as the first lines of the
 * Strava description and the Health Connect notes.
 */
@Composable
private fun ActivityNote(s: SessionSummary, history: SessionHistory) {
    val context = LocalContext.current
    var editing by remember(s.id) { mutableStateOf(false) }
    var text by remember(s.id) { mutableStateOf(s.note.orEmpty()) }
    var syncNote by remember(s.id) { mutableStateOf<String?>(null) }
    val save = { note: String? ->
        editing = false
        saveAndSync(context, history, s.id, { syncNote = it }) { setNote(s.id, note) }
    }
    if (editing) {
        OutlinedTextField(
            text, { text = it.take(500) }, label = { Text("Note") }, placeholder = { Text("How did it feel?") },
            minLines = 2, maxLines = 6, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextAction("Save", { save(text) }, enabled = text.isNotBlank() || s.note != null)
            TextAction("Cancel", { editing = false; text = s.note.orEmpty() }, color = ZoneColors.muted)
            if (s.note != null) TextAction("Delete note", { text = ""; save(null) }, color = ZoneColors.muted)
        }
    } else if (s.note != null) {
        GlowCard(accent = Palette.accent2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardLabel("Note", Palette.accent2)
                Spacer(Modifier.weight(1f))
                TextAction("Edit", { text = s.note; editing = true })
            }
            Text(s.note, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        TextAction("+ Add a note", { text = ""; editing = true })
    }
    syncNote?.let { Text(it, color = ZoneColors.muted, fontSize = 13.sp) }
}

private class ActivityData(
    val samples: List<HrSample>,
    val points: List<GeoPoint>,
    val splits: List<Split>,
    val laps: List<Lap>,
    val pace: List<Pair<Long, Double>>,
    val cadence: List<Pair<Long, Int>>,
    val elevation: ElevationProfile?,
    /** Fastest 1 / 5 / 10 km inside this activity (only distances it covered). */
    val bestEfforts: List<Pair<String, Long>>,
    /** The planned route followed, when it still exists. */
    val planned: com.rameez.fit3zone.core.Route? = null,
)

@Composable
private fun ActivityDetail(modifier: Modifier, s: SessionSummary, history: SessionHistory, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by produceState<ActivityData?>(null, s.id) {
        value = withContext(SessionHistory.dispatcher) {
            val samples = history.samples(s.id)
            val points = history.points(s.id)
            val track = if (points.size >= 2) replayTrack(s, points, samples, history.stepTimeline(s.id)) else null
            ActivityData(
                samples, points, track?.allSplits().orEmpty(), history.laps(s.id),
                pace = track?.paceSeries().orEmpty().filter { it.second < 30 * 60 },
                cadence = history.cadence(s.id),
                elevation = ElevationProfile.of(points),
                bestEfforts = if (track != null && s.type != ActivityType.CYCLE) {
                    listOf("1 km" to 1_000.0, "5 km" to 5_000.0, "10 km" to 10_000.0)
                        .mapNotNull { (label, m) -> track.bestEffortMs(m)?.let { label to it } }
                } else emptyList(),
                planned = com.rameez.fit3zone.data.RouteStore.get(context).get(s.routeId)?.route,
            )
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val target = s.target
    val type = s.type
    // Colour by the zones recorded with the session; older sessions colour by their target.
    val colorOf: (Int?) -> Color = remember(s.id) { { bpm -> bpmColor(bpm, s.zones, target) } }

    val badges = AchievementStore.get(context).byId.collectAsStateWithLifecycle().value[s.id].orEmpty()
    val d = data

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    // Map first, edge to edge, with the way back on top of it (Strava style).
    if (d != null && d.points.size >= 2) {
        Box {
            if (hasMapsKey(context)) RouteMap(d.points, d.samples, colorOf, s.segmentBreaksMs, d.planned, height = 300.dp, rounded = false)
            else RouteSketch(d.points, d.samples, colorOf, Modifier.height(300.dp).background(Palette.surface), breaksMs = s.segmentBreaksMs)
            Box(
                Modifier.padding(12.dp).size(44.dp).clip(CircleShape).background(Palette.bg.copy(alpha = 0.8f)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).height(44.dp).clip(CircleShape)
                    .background(Palette.bg.copy(alpha = 0.8f)).clickable { sharing = true }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Share", fontWeight = FontWeight.SemiBold, color = Palette.accent) }
        }
    }
    if (sharing && d != null) ShareImageDialog(s, d.points, onDismiss = { sharing = false })
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (d == null || d.points.size < 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("‹ Activities", onBack)
                Spacer(Modifier.weight(1f))
                if (d != null) TextAction("Share", { sharing = true })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                listOfNotNull(friendlyDate(s.startedAt), type.label, s.effort?.let { "Effort $it · ${effortWord(it)}" }).joinToString(" · "),
                color = ZoneColors.muted, fontSize = 13.sp,
            )
            badge(s)?.let { (label, color) -> Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        ActivityName(s, history)
        ActivityNote(s, history)
        HeadlineStats(s)
        if (s.hasGps && d != null && d.points.size < 2) Text("No GPS fix was recorded for this activity.", color = ZoneColors.muted)
        if (badges.isNotEmpty()) {
            GlowCard {
                CardLabel("Achievements", Palette.accent2)
                Spacer(Modifier.height(4.dp))
                AchievementList(badges)
            }
        }
        s.routeName?.let { name ->
            Text(
                "Route: $name" + (s.routeFollowed?.let { " · ${(it * 100).toInt()} % followed" } ?: ""),
                color = Palette.accent2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            )
        }

        // Summary: every number this activity has, three per row.
        val stats = buildList {
            if (s.distanceM > 0) add("Distance" to formatKm(s.distanceM))
            add("Time" to formatDuration(s.activeMs))
            s.avgPaceSecPerKm?.let { add("Avg ${type.paceLabel.lowercase()}" to "${type.formatPaceOrSpeed(it)} ${type.paceUnit}") }
            add("Avg HR" to (s.avgBpm?.toString() ?: "--"))
            add("Max HR" to (s.maxBpm?.toString() ?: "--"))
            if (target != null || s.workoutName != null) add("Alerts" to "${s.alerts - s.belowAlerts}↑ ${s.belowAlerts}↓")
            s.avgCadenceSpm?.let { add("Avg cadence" to "$it spm") }
            s.steps?.let { add("Steps" to "%,d".format(it)) }
            if (s.steps != null && s.steps > 0 && s.distanceM > 0) add("Avg stride" to "%.2f m".format(s.distanceM / s.steps))
            d?.elevation?.let { e ->
                add("Elev. gain" to "${e.gainM.roundToInt()} m")
                add("Elev. loss" to "${e.lossM.roundToInt()} m")
            }
        }
        GlowCard(accent = Palette.accent) {
            CardLabel("Summary", Palette.accent)
            stats.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, value) -> DetailStat(label, value, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            s.estimatedPercent?.let {
                Text("$it % of the distance is estimated from your steps: GPS was off for a while.", color = ZoneColors.muted, fontSize = 13.sp)
            }
            if (s.interruptions > 0) Text("Data gaps: ${s.interruptions} (${formatDuration(s.staleMs)})", color = ZoneColors.muted, fontSize = 13.sp)
        }

        if (!s.simulated && s.status != SessionStatus.IN_PROGRESS && s.activeMs >= 60_000) EffectCard(s)

        GlowCard(accent = Palette.heart) {
            CardLabel("Heart rate", Palette.heart)
            when {
                d == null -> Text("Loading…", color = ZoneColors.muted)
                d.samples.size >= 2 -> HrChart(d.samples, s.zones, target)
                else -> Text(if (s.sampleIntervalMs == null) "Sample recording was off." else "Not enough samples for a chart.", color = ZoneColors.muted)
            }
            if (s.hasZoneTimes) {
                Spacer(Modifier.height(4.dp))
                Text("Time in zones", color = ZoneColors.muted, fontSize = 13.sp)
                HrZoneBars(s.zoneMs, s.zones)
            }
            if (s.workoutName != null) {
                // Targets changed per step; the stored limits are only the last step's.
                Text("Workout targets", color = ZoneColors.muted, fontSize = 13.sp)
                ZoneTimeBar(s.belowMs, s.inMs, s.aboveMs)
            } else if (target != null) {
                Text("Target ${target.label}: ${target.lowerBpm}–${target.upperBpm} BPM", color = ZoneColors.muted, fontSize = 13.sp)
                ZoneTimeBar(s.belowMs, s.inMs, s.aboveMs, inLabel = target.label)
            }
        }

        if (d != null && d.pace.size >= 2) {
            GlowCard(accent = Palette.accent) {
                CardLabel(type.paceLabel, Palette.accent)
                if (type.showsSpeed) {
                    LineChart(
                        d.pace.map { it.first to (3600 / it.second).toFloat() }, Palette.accent, { "%.0f km/h".format(it) },
                        avg = s.avgPaceSecPerKm?.let { (3600 / it).toFloat() }, minSpan = 4f,
                    )
                } else {
                    // Faster is higher, like Garmin's pace chart.
                    LineChart(
                        d.pace.map { it.first to it.second.toFloat() }, Palette.accent, { formatPace(it.toDouble()) },
                        invert = true, avg = s.avgPaceSecPerKm?.toFloat(), minSpan = 60f,
                    )
                }
                Text("Dashed: average ${type.formatPaceOrSpeed(s.avgPaceSecPerKm)} ${type.paceUnit}", color = ZoneColors.muted, fontSize = 12.sp)
            }
        }

        if (d != null && d.cadence.size >= 2) {
            GlowCard(accent = Palette.steps) {
                CardLabel("Cadence", Palette.steps)
                LineChart(d.cadence.map { it.first to it.second.toFloat() }, Palette.steps, { "${it.roundToInt()} spm" }, avg = s.avgCadenceSpm?.toFloat(), minSpan = 20f)
            }
        }

        d?.elevation?.let { e ->
            GlowCard(accent = ZoneColors.inZone) {
                CardLabel("Elevation", ZoneColors.inZone)
                LineChart(e.samples.map { it.first to it.second.toFloat() }, ZoneColors.inZone, { "${it.roundToInt()} m" }, minSpan = 10f, robust = false)
                Text(
                    "Gain ${e.gainM.roundToInt()} m · loss ${e.lossM.roundToInt()} m · ${e.minM.roundToInt()} to ${e.maxM.roundToInt()} m (GPS, smoothed)".replace("-", "−"),
                    color = ZoneColors.muted, fontSize = 12.sp,
                )
            }
        }

        if (d != null && d.bestEfforts.isNotEmpty()) {
            GlowCard(accent = ZoneColors.pending) {
                CardLabel("Best efforts", ZoneColors.pending)
                d.bestEfforts.forEach { (label, ms) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, Modifier.weight(1f))
                        Text(formatRaceTime(ms / 1000.0), Modifier.weight(1f), fontFamily = Numeric, fontWeight = FontWeight.SemiBold)
                        val m = label.substringBefore(" ").toDouble() * 1000
                        Text("${type.formatPaceOrSpeed(ms / 1000.0 / (m / 1000))} ${type.paceUnit}", Modifier.weight(1f), color = ZoneColors.muted)
                    }
                }
            }
        }

        if (d != null && d.laps.isNotEmpty()) {
            GlowCard(accent = Palette.accent2) {
                CardLabel(if (s.workoutName != null) "Steps" else "${type.lapWord}s", Palette.accent2)
                LapsTable(d.laps, colorOf, type)
            }
        }

        if (d != null && d.splits.isNotEmpty()) {
            GlowCard(accent = Palette.accent) {
                CardLabel("Splits", Palette.accent)
                SplitsTable(d.splits, colorOf, type)
            }
        }

        StravaUploadRow(s, deleting = confirmDelete)

        // Fix the type afterwards (e.g. a walk recorded as a run).
        Text("Activity type", color = ZoneColors.muted, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityType.entries.forEach { t ->
                PillChip(t.label, type == t, Palette.accent, compact = true) {
                    if (type != t) scope.launch { withContext(SessionHistory.dispatcher) { history.setType(s.id, t) } }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Export CSV", { scope.launch { shareCsv(context, history, s) } }, Modifier.weight(1f), height = 52.dp)
            if (s.hasGps && (d?.points?.size ?: 0) >= 2) {
                GhostButton("Export GPX", { scope.launch { downloadActivityGpx(context, history, s) } }, Modifier.weight(1f), height = 52.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                if (confirmDelete) "Tap to confirm" else "Delete",
                {
                    if (confirmDelete) scope.launch {
                        HealthExport.get(context).remove(s)
                        withContext(SessionHistory.dispatcher) { history.delete(s.id) }
                        onBack()
                    } else confirmDelete = true
                },
                Modifier.weight(1f), height = 52.dp,
            )
        }
        Spacer(Modifier.height(80.dp)) // clear the floating tab bar
    }
    }
}

@Composable
private fun RouteMap(
    points: List<GeoPoint>, samples: List<HrSample>, colorOf: (Int?) -> Color, breaksMs: List<Long>,
    planned: com.rameez.fit3zone.core.Route? = null, height: Dp = 260.dp, rounded: Boolean = true,
) {
    val camera = rememberCameraPositionState()
    val pieces = remember(points, breaksMs) { points.drawablePieces(breaksMs) }
    // Within each recorded piece, consecutive points with the same zone become one coloured polyline.
    val segments = remember(pieces, samples) {
        val out = mutableListOf<Pair<Color, MutableList<LatLng>>>()
        pieces.forEach { piece ->
            var pieceStart = true
            piece.forEach { p ->
                val c = colorOf(bpmAt(p.offsetMs, samples))
                val ll = LatLng(p.lat, p.lon)
                val last = out.lastOrNull()
                if (!pieceStart && last != null && last.first == c) last.second += ll
                else out += c to mutableListOf<LatLng>().apply { if (!pieceStart) last?.second?.lastOrNull()?.let { add(it) }; add(ll) }
                pieceStart = false
            }
        }
        out
    }
    // Where GPS was lost: a faint dashed line between the pieces instead of a route line.
    val gaps = remember(pieces) { pieces.zipWithNext { a, b -> listOf(LatLng(a.last().lat, a.last().lon), LatLng(b.first().lat, b.first().lon)) } }
    GoogleMap(
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(if (rounded) 12.dp else 0.dp)),
        cameraPositionState = camera,
        properties = rememberMapProperties(),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        onMapLoaded = {
            val bounds = LatLngBounds.builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        },
    ) {
        // The planned route, faint and wide under the recorded track.
        planned?.let { r -> Polyline(points = r.points.map { LatLng(it.lat, it.lon) }, color = Palette.accent2.copy(alpha = 0.35f), width = 22f) }
        gaps.forEach { Polyline(points = it, color = Palette.faint, width = 6f, pattern = listOf(Dash(18f), Gap(14f))) }
        segments.filter { it.second.size >= 2 }.forEach { (color, line) -> Polyline(points = line, color = color, width = 14f) }
        Circle(center = LatLng(points.first().lat, points.first().lon), radius = 8.0, fillColor = ZoneColors.inZone, strokeColor = Color.White)
        Circle(center = LatLng(points.last().lat, points.last().lon), radius = 8.0, fillColor = ZoneColors.above, strokeColor = Color.White)
    }
}

@Composable
private fun SplitsTable(splits: List<Split>, colorOf: (Int?) -> Color, type: ActivityType) {
    // Bars show speed relative to the fastest full split; the fastest one is gold.
    val full = splits.filter { it.distanceM >= 999 && it.paceSecPerKm != null }
    val fastest = full.minOfOrNull { it.paceSecPerKm!! }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("KM", Modifier.width(44.dp), color = ZoneColors.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(type.paceLabel, Modifier.width(72.dp), color = ZoneColors.muted, fontSize = 12.sp, textAlign = TextAlign.End)
            Text("HR", Modifier.width(48.dp), color = ZoneColors.muted, fontSize = 12.sp, textAlign = TextAlign.End)
        }
        splits.forEach { sp ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val km = if (sp.distanceM < 999) "%.2f".format(sp.distanceM / 1000) else sp.index.toString()
                Text(km, Modifier.width(44.dp), color = ZoneColors.muted)
                Box(Modifier.weight(1f).height(10.dp)) {
                    val pace = sp.paceSecPerKm
                    if (pace != null && fastest != null) {
                        val best = sp.distanceM >= 999 && pace == fastest && full.size > 1
                        Box(
                            Modifier.fillMaxWidth((fastest / pace).toFloat().coerceIn(0.15f, 1f)).fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp)).background(if (best) Palette.accent2 else Palette.accent),
                        )
                    }
                }
                Text(type.formatPaceOrSpeed(sp.paceSecPerKm), Modifier.width(72.dp), fontFamily = Numeric, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text(sp.avgBpm?.toString() ?: "--", Modifier.width(48.dp), color = colorOf(sp.avgBpm), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
fun LapsTable(laps: List<Lap>, colorOf: (Int?) -> Color, type: ActivityType = ActivityType.RUN) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(type.lapWord, Modifier.weight(1.6f), color = ZoneColors.muted, fontSize = 12.sp)
            listOf("Time", "Dist", type.paceLabel, "HR").forEach { Text(it, Modifier.weight(1f), color = ZoneColors.muted, fontSize = 12.sp) }
        }
        HorizontalDivider(color = Color(0xFF2A2E33))
        laps.forEach { l ->
            Row(Modifier.fillMaxWidth()) {
                Text(l.label ?: l.number.toString(), Modifier.weight(1.6f), fontSize = 14.sp, maxLines = 1)
                Text(formatDuration(l.durationMs), Modifier.weight(1f), fontSize = 14.sp)
                Text(if (l.distanceM >= 10) "%.2f".format(l.distanceM / 1000) else "--", Modifier.weight(1f), fontSize = 14.sp)
                Text(type.formatPaceOrSpeed(l.paceSecPerKm), Modifier.weight(1f), fontSize = 14.sp)
                Text(l.avgBpm?.toString() ?: "--", Modifier.weight(1f), color = colorOf(l.avgBpm), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Line chart of stored samples over the session. With zones, the background is banded in zone
 * colours (Garmin style); the target, if any, is outlined.
 */
@Composable
private fun HrChart(samples: List<HrSample>, zones: HrZones?, target: com.rameez.fit3zone.core.ZoneTarget?) {
    val minY = minOf(samples.minOf { it.bpm }, target?.lowerBpm ?: Int.MAX_VALUE) - 10f
    val maxY = maxOf(samples.maxOf { it.bpm }, target?.upperBpm ?: 0) + 10f
    val maxX = samples.last().offsetMs.coerceAtLeast(1).toFloat()
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        fun y(bpm: Float) = size.height - (bpm - minY) / (maxY - minY) * size.height
        fun x(ms: Long) = ms / maxX * size.width
        fun band(lo: Float, hi: Float, c: Color) {
            val top = y(hi.coerceAtMost(maxY))
            val bottom = y(lo.coerceAtLeast(minY))
            if (bottom > top) drawRect(c, Offset(0f, top), Size(size.width, bottom - top))
        }
        if (zones != null) {
            val edges = listOf(minY) + zones.floors.map { it.toFloat() } + maxOf(maxY, zones.maxHr.toFloat())
            for (z in 0..HrZones.COUNT) band(edges[z], edges[z + 1], ZoneColors.hrZone(z).copy(alpha = 0.22f))
        }
        target?.let {
            if (zones == null) band(it.lowerBpm.toFloat(), it.upperBpm.toFloat(), ZoneColors.inZone.copy(alpha = 0.18f))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(0f, y(it.upperBpm.toFloat())), Offset(size.width, y(it.upperBpm.toFloat())), 2f)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(0f, y(it.lowerBpm.toFloat())), Offset(size.width, y(it.lowerBpm.toFloat())), 2f)
        }
        // Downsample to roughly one point per pixel column so long sessions stay cheap to draw.
        val step = (samples.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
        val path = Path()
        samples.filterIndexed { i, _ -> i % step == 0 }.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.offsetMs), y(p.bpm.toFloat())) else path.lineTo(x(p.offsetMs), y(p.bpm.toFloat()))
        }
        drawPath(path, Color.White, style = Stroke(width = 3f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0:00", color = ZoneColors.muted, fontSize = 12.sp)
        Text("${minY.toInt()}–${maxY.toInt()} BPM", color = ZoneColors.muted, fontSize = 12.sp)
        Text(formatDuration(samples.last().offsetMs), color = ZoneColors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = Numeric, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Text(label.uppercase(), color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun badge(s: SessionSummary): Pair<String, Color>? = when {
    s.status == SessionStatus.INTERRUPTED -> "INTERRUPTED" to ZoneColors.pending
    s.status == SessionStatus.IN_PROGRESS -> "IN PROGRESS" to ZoneColors.inZone
    s.simulated -> "SIMULATED" to ZoneColors.pending
    else -> null
}

/** GPX of the recorded track (time, elevation, heart rate) for other apps, saved to Downloads/Taal on this phone. */
private suspend fun downloadActivityGpx(context: Context, history: SessionHistory, s: SessionSummary) {
    val xml = withContext(SessionHistory.dispatcher) {
        com.rameez.fit3zone.core.Gpx.writeActivity(
            com.rameez.fit3zone.core.ExportSession(
                startedAtMs = s.startedAt, type = s.type, activeMs = s.activeMs, distanceM = s.distanceM, avgBpm = s.avgBpm,
                maxBpm = s.maxBpm, samples = history.samples(s.id), points = history.points(s.id), segmentBreaksMs = s.segmentBreaksMs,
            ),
            s.title,
        )
    }
    val name = "${s.title} ${java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(s.startedAt))}"
    val message = runCatching { withContext(Dispatchers.IO) { saveGpxToDownloads(context, xml, name) } }.fold(
        { "Saved to $it" },
        { HeartRateRepository.log.add("export", "GPX save failed: $it"); "Couldn't save the GPX: ${it.message}" },
    )
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

/** Only leaves the phone when the user picks a target in the share sheet. */
private suspend fun shareCsv(context: Context, history: SessionHistory, s: SessionSummary) {
    val csv = withContext(SessionHistory.dispatcher) { history.toCsv(s) }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, "$APP_NAME activity ${dateFormat().format(Date(s.startedAt))}")
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    context.startActivity(Intent.createChooser(send, "Export activity"))
}

fun dateFormat() = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())
