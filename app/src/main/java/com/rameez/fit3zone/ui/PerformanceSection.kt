package com.rameez.fit3zone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.core.ActivityRecord
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.core.LoadBand
import com.rameez.fit3zone.core.Performance
import com.rameez.fit3zone.data.replayTrack
import com.rameez.fit3zone.core.TrainingStats
import com.rameez.fit3zone.core.formatPace
import com.rameez.fit3zone.core.formatRaceTime
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionStatus
import com.rameez.fit3zone.data.SessionSummary
import com.rameez.fit3zone.data.SettingsStore
import kotlinx.coroutines.withContext
import java.time.ZoneId

private fun bandColor(b: LoadBand): Color = when (b) {
    LoadBand.BASELINE -> Palette.accent
    LoadBand.LOW -> ZoneColors.below
    LoadBand.OPTIMAL -> ZoneColors.inZone
    LoadBand.HIGH -> ZoneColors.pending
    LoadBand.VERY_HIGH -> ZoneColors.above
}

private fun shortDate(ms: Long, zone: ZoneId): String =
    java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().let { "${it.dayOfMonth}/${it.monthValue}" }

/**
 * Training → Performance: load status, recovery, VO2max, race predictions, intensity minutes and
 * best efforts. All are labelled estimates from published formulas (see core Performance).
 */
@Composable
fun PerformanceSection(acts: List<ActivityRecord>, sessions: List<SessionSummary>, zone: ZoneId, onOpenActivity: (Long) -> Unit) {
    val context = LocalContext.current
    val profile by SettingsStore.get(context).profile.collectAsStateWithLifecycle()
    val now = remember(acts) { System.currentTimeMillis() }
    val status = remember(acts) { Performance.loadStatus(acts, now) }
    val recovery = remember(acts) { Performance.recoveryRemainingHours(acts, now) }
    val vo2 = remember(acts, profile) { profile?.let { Performance.vo2maxEstimate(acts, now, it.maxHr, it.restingHr) } }

    Text("Performance", fontWeight = FontWeight.Bold, fontSize = 18.sp)

    // Training status: acute vs chronic load
    val daily = remember(acts) { Performance.dailyLoads(acts, now, zone) }
    MetricCard(
        "Training status (estimate)", status.band.label,
        detail = "7-day load ${status.acute} · 4-week average ${status.chronicWeekly}/week" +
            (status.ratio?.let { " · ratio %.2f".format(it) } ?: " · needs 2 weeks of activities") +
            ". Optimal 0.8–1.3; above 1.5 is a sharp jump.",
        accent = bandColor(status.band),
    ) {
        BarChart(
            daily.map { it.second.toFloat() },
            daily.mapIndexed { i, (d, _) -> if (i % 7 == 3) "${d.dayOfMonth}/${d.monthValue}" else "" },
            bandColor(status.band), highlightLast = false,
        )
    }

    MetricCard(
        "Recovery (estimate)", if (recovery > 0) "$recovery h" else "Recovered",
        detail = if (recovery > 0) "Until you're ready for another hard session, from your recent training effect." else "Ready for a hard session.",
        accent = if (recovery > 24) ZoneColors.pending else ZoneColors.inZone,
    )

    // VO2max + race predictions
    if (profile == null) {
        MetricCard("VO2 max", "--", detail = "Set your heart-rate profile in Settings first.", accent = Palette.oxygen)
    } else if (vo2 == null) {
        MetricCard(
            "VO2 max (estimate)", "--",
            detail = "Needs a GPS run of at least 10 min and 1.6 km at a steady effort (≥ 50 % heart-rate reserve).",
            accent = Palette.oxygen,
        )
    } else {
        val hist = vo2.history.takeLast(8)
        MetricCard(
            "VO2 max (estimate)", "%.1f".format(vo2.vo2max),
            detail = "ml/kg/min · average of your last ${vo2.runsUsed} qualifying ${if (vo2.runsUsed == 1) "run" else "runs"} (pace vs heart rate). Not a lab test.",
            accent = Palette.oxygen,
            onClick = { onOpenActivity(vo2.history.last().activityId) },
        ) {
            if (hist.size > 1) BarChart(hist.map { it.vo2max.toFloat() }, hist.map { shortDate(it.startedAt, zone) }, Palette.oxygen)
        }
        // Second opinion from what you've actually run: Riegel from your fastest run of at least 1 km.
        val fastest = remember(acts) { TrainingStats.records(acts).fastestAvgPace?.first }
        GlowCard(accent = Palette.accent) {
            CardLabel("Race predictions (estimate)", Palette.accent)
            Row(Modifier.fillMaxWidth()) {
                Text("", Modifier.weight(1.3f))
                Text("VO2 max", Modifier.weight(1f), color = ZoneColors.muted, fontSize = 12.sp, textAlign = TextAlign.End)
                if (fastest != null) Text("Your runs", Modifier.weight(1f), color = ZoneColors.muted, fontSize = 12.sp, textAlign = TextAlign.End)
            }
            Performance.RACES.forEach { (name, metres) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(name, Modifier.weight(1.3f))
                    Text(
                        formatRaceTime(Performance.predictRaceSec(vo2.vo2max, metres)), Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold, fontFamily = Numeric, textAlign = TextAlign.End,
                    )
                    if (fastest != null) Text(
                        formatRaceTime(Performance.riegelSec(fastest.activeMs / 1000.0, fastest.distanceM, metres)), Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold, fontFamily = Numeric, textAlign = TextAlign.End, color = ZoneColors.pending,
                    )
                }
            }
            Text(
                "VO2 max column: Daniels VDOT, i.e. what that fitness allows when trained for the distance; heart-rate estimates " +
                    "from easy runs tend to be optimistic." +
                    (fastest?.let { " Your runs: Riegel from your fastest run (%.2f km in ${formatDuration(it.activeMs)}).".format(it.distanceM / 1000) } ?: ""),
                color = ZoneColors.muted, fontSize = 12.sp,
            )
        }
    }

    // Intensity minutes per week vs the 150 min goal
    val weekly = remember(acts) {
        val weeks = TrainingStats.weekly(acts, now, zone)
        weeks.map { w -> w to acts.filter { TrainingStats.weekStart(it.startedAt, zone) == w.weekStart }.sumOf { Performance.intensityMinutes(it) } }
    }
    val thisWeek = weekly.last().second
    MetricCard(
        "Intensity minutes · week", "$thisWeek / ${Performance.INTENSITY_GOAL_MIN}",
        detail = "Z1 counts once, Z2 and above count double (WHO goal: 150 a week).",
        accent = ZoneColors.inZone,
    ) {
        BarChart(
            weekly.map { it.second.toFloat() }, weekly.map { (w, _) -> "${w.weekStart.dayOfMonth}/${w.weekStart.monthValue}" },
            ZoneColors.inZone, target = Performance.INTENSITY_GOAL_MIN.toFloat(),
        )
    }

    BestEfforts(sessions, onOpenActivity)
}

private val EFFORTS = listOf("1 km" to 1_000.0, "5 km" to 5_000.0, "10 km" to 10_000.0)

/** Fastest 1 / 5 / 10 km inside any real GPS run (a pause never counts). */
@Composable
private fun BestEfforts(sessions: List<SessionSummary>, onOpenActivity: (Long) -> Unit) {
    val context = LocalContext.current
    val history = remember { SessionHistory.get(context) }
    val runs = remember(sessions) {
        sessions.filter { !it.simulated && it.status != SessionStatus.IN_PROGRESS && it.type == ActivityType.RUN && it.hasGps && it.distanceM >= 1_000 }
    }
    val best by produceState<List<Pair<String, Pair<SessionSummary, Long>?>>?>(null, runs) {
        value = withContext(SessionHistory.dispatcher) {
            val tracks = runs.map { it to replayTrack(it, history.points(it.id)) }
            EFFORTS.map { (label, m) ->
                label to tracks.mapNotNull { (s, t) -> t.bestEffortMs(m)?.let { s to it } }.minByOrNull { it.second }
            }
        }
    }
    val found = best?.filter { it.second != null }.orEmpty()
    if (found.isEmpty()) return
    GlowCard(accent = ZoneColors.pending) {
        CardLabel("Best efforts (runs)", ZoneColors.pending)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            found.forEach { (label, hit) ->
                val (s, ms) = hit!!
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f))
                    Text(formatRaceTime(ms / 1000.0), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontFamily = Numeric, textAlign = TextAlign.End)
                    TextAction(dateFormat().format(java.util.Date(s.startedAt)).substringBefore(","), { onOpenActivity(s.id) }, color = ZoneColors.muted)
                }
            }
        }
    }
}

/** Activity detail: what this session did for you. */
@Composable
fun EffectCard(s: SessionSummary) {
    val context = LocalContext.current
    val profile by SettingsStore.get(context).profile.collectAsStateWithLifecycle()
    val r = remember(s) { s.toRecord() }
    val te = remember(r) { Performance.trainingEffect(r) }
    val vo2 = profile?.takeIf { s.type == ActivityType.RUN }?.let { Performance.vo2maxFromRun(s.distanceM, s.activeMs, s.avgBpm, it.maxHr, it.restingHr) }
    GlowCard(accent = Palette.accent2) {
        CardLabel("Training effect (estimate)", Palette.accent2)
        Row(Modifier.fillMaxWidth()) {
            EffectStat("Aerobic", te.aerobic, Modifier.weight(1f))
            EffectStat("Anaerobic", te.anaerobic, Modifier.weight(1f))
        }
        Text(
            "Load ${TrainingStats.load(r)} · recovery ${Performance.recoveryHours(te)} h · ${Performance.intensityMinutes(r)} intensity min" +
                (vo2?.let { " · VO2 max %.1f".format(it) } ?: ""),
            color = ZoneColors.muted, fontSize = 13.sp,
        )
    }
}

@Composable
private fun EffectStat(label: String, value: Double, modifier: Modifier) {
    Column(modifier) {
        Text("%.1f".format(value), fontFamily = Numeric, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
        Text("${label.uppercase()} · ${Performance.effectLabel(value)}", color = ZoneColors.muted, fontSize = 12.sp)
    }
}
