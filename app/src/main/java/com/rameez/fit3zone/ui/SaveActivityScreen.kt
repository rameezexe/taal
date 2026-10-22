package com.rameez.fit3zone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.data.AUTO_SYNC_MIN_MS
import com.rameez.fit3zone.data.AchievementStore
import com.rameez.fit3zone.data.HealthExport
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SYNC_HEALTH
import com.rameez.fit3zone.data.SYNC_STRAVA
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionSummary
import com.rameez.fit3zone.data.Strava
import com.rameez.fit3zone.data.effortWord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** "Morning Run", "Evening Walk": the name the save screen suggests (a workout keeps its own name). */
fun suggestedName(s: SessionSummary): String {
    s.workoutName?.let { return it }
    val hour = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()).hour
    val part = when (hour) { in 5..11 -> "Morning"; in 12..16 -> "Afternoon"; in 17..20 -> "Evening"; else -> "Night" }
    return "$part ${s.type.label}"
}

/**
 * Shown after an activity ends, before anything leaves the phone (Strava style): name it, say how it went and
 * how hard it felt, see what it achieved, choose where it goes. Discard deletes it.
 */
@Composable
fun SaveActivityScreen(s: SessionSummary) {
    val context = LocalContext.current
    val app = context.applicationContext as Fit3ZoneApp
    val history = remember { SessionHistory.get(context) }
    val strava = remember { Strava.get(context) }
    val health = remember { HealthExport.get(context) }
    val stravaState by strava.state.collectAsStateWithLifecycle()
    val healthState by health.state.collectAsStateWithLifecycle()
    val badges = AchievementStore.get(context).byId.collectAsStateWithLifecycle().value[s.id].orEmpty()

    var name by rememberSaveable(s.id) { mutableStateOf(s.name ?: suggestedName(s)) }
    var note by rememberSaveable(s.id) { mutableStateOf(s.note.orEmpty()) }
    var effort by rememberSaveable(s.id) { mutableStateOf(s.effort) }
    val long = s.activeMs >= AUTO_SYNC_MIN_MS
    val canStrava = stravaState.connected && strava.uploadable(s)
    val canHealth = healthState.enabled && health.exportable(s)
    var toStrava by rememberSaveable(s.id) { mutableStateOf(stravaState.autoUpload && long) }
    var toHealth by rememberSaveable(s.id) { mutableStateOf(long) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDiscard) { if (confirmDiscard) { delay(3_000); confirmDiscard = false } }
    // Back keeps the screen: the activity has to be saved or discarded.
    BackHandler {}

    fun save() {
        if (saving) return
        saving = true
        val skip = buildSet {
            if (!(canStrava && toStrava)) add(SYNC_STRAVA)
            if (!(canHealth && toHealth)) add(SYNC_HEALTH)
        }
        app.appScope.launch {
            val saved = withContext(SessionHistory.dispatcher) {
                history.review(s.id, name, note, effort, skip)
                history.sessions.value.firstOrNull { it.id == s.id }
            } ?: return@launch
            HeartRateRepository.log.add("save", "session ${s.id} saved: effort=${effort ?: "-"} strava=${SYNC_STRAVA !in skip} health=${SYNC_HEALTH !in skip}")
            // Auto-upload / auto-export pick up longer activities by themselves; anything else goes now.
            if (SYNC_STRAVA !in skip && !(stravaState.autoUpload && long)) launch { strava.upload(saved) }
            if (SYNC_HEALTH !in skip && !long) launch { health.write(saved) }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(if (confirmDiscard) "Tap again to discard" else "Discard", {
                if (!confirmDiscard) confirmDiscard = true
                else app.appScope.launch {
                    withContext(SessionHistory.dispatcher) { history.delete(s.id) }
                    HeartRateRepository.log.add("save", "session ${s.id} discarded")
                }
            }, color = ZoneColors.above)
            Text("Save activity", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            // Balances the Discard action so the title stays centred.
            Spacer(Modifier.weight(0.45f))
        }
        HorizontalDivider(color = Palette.hairline)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.surface).border(1.dp, Palette.hairline, RoundedCornerShape(14.dp))) {
                if (s.hasGps) ActivityMapThumb(s, 170.dp)
                HeadlineStats(s, Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            }

            OutlinedTextField(
                name, { name = it.take(60) }, label = { Text("Title") }, singleLine = true,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            OutlinedTextField(
                note, { note = it.take(500) }, label = { Text("How did it go?") }, minLines = 2,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            EffortPicker(effort) { effort = if (effort == it) null else it }

            if (badges.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.accent2.copy(alpha = 0.08f))
                        .border(1.dp, Palette.accent2.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(14.dp),
                ) { AchievementList(badges) }
            }

            if (canStrava || canHealth) {
                Column {
                    if (canStrava) SyncSwitch("Upload to Strava", toStrava) { toStrava = it }
                    if (canStrava && canHealth) HorizontalDivider(color = Palette.hairline)
                    if (canHealth) SyncSwitch("Send to Samsung Health", toHealth) { toHealth = it }
                }
            }
            if (s.simulated) Text("Simulated session: it stays on this phone.", color = Palette.muted, fontSize = 13.sp)
        }

        HorizontalDivider(color = Palette.hairline)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            GradientButton(if (saving) "Saving…" else "Save activity", ::save, enabled = !saving, height = 56.dp)
        }
    }
}

/** Distance · pace · time for GPS activities; time · avg HR · cadence (or max HR) indoors. */
@Composable
fun HeadlineStats(s: SessionSummary, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        if (s.hasGps && s.distanceM > 0) {
            BigStat("%.2f".format(s.distanceM / 1000), "Distance", Modifier.weight(1f), unit = "km")
            BigStat(s.avgPaceSecPerKm?.let { s.type.formatPaceOrSpeed(it) } ?: "--", "Avg ${s.type.paceLabel.lowercase()}", Modifier.weight(1f))
            BigStat(formatDuration(s.activeMs), "Time", Modifier.weight(1f))
        } else {
            BigStat(formatDuration(s.activeMs), "Time", Modifier.weight(1f))
            BigStat(s.avgBpm?.toString() ?: "--", "Avg HR", Modifier.weight(1f), unit = "bpm")
            if (s.avgCadenceSpm != null) BigStat(s.avgCadenceSpm.toString(), "Cadence", Modifier.weight(1f), unit = "spm")
            else BigStat(s.maxBpm?.toString() ?: "--", "Max HR", Modifier.weight(1f), unit = "bpm")
        }
    }
}

/** 1–10 bar, green to red; tapping the chosen value again clears it. */
@Composable
private fun EffortPicker(effort: Int?, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("HOW HARD DID IT FEEL?", Modifier.weight(1f), color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(effort?.let { "$it · ${effortWord(it)}" } ?: "Optional", color = if (effort != null) Palette.text else Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..10) {
                val c = effortColor(i)
                val on = effort != null && i <= effort
                Box(
                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) c else Palette.surfaceHigh)
                        .let { if (i == effort) it.border(2.dp, Palette.text, RoundedCornerShape(6.dp)) else it }
                        .semantics { contentDescription = "Effort $i, ${effortWord(i)}" }
                        .clickable { onPick(i) },
                )
            }
        }
    }
}

private fun effortColor(i: Int): Color = when (i) {
    in 1..2 -> ZoneColors.inZone
    in 3..4 -> Color(0xFFB9D65C)
    in 5..6 -> Palette.accent2
    in 7..8 -> Palette.accent
    else -> ZoneColors.above
}

@Composable
private fun SyncSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Switch(
            on, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.onAccent),
        )
    }
}
