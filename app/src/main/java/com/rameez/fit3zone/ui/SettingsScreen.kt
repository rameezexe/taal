package com.rameez.fit3zone.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.core.AlertTiming
import com.rameez.fit3zone.core.HeartRateProfile
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.MaxHrSource
import com.rameez.fit3zone.core.ReadingSource
import com.rameez.fit3zone.core.RestingHrMeasurement
import com.rameez.fit3zone.core.RestingHrSource
import com.rameez.fit3zone.data.AlertSettings
import com.rameez.fit3zone.data.HealthData
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SettingsStore
import com.rameez.fit3zone.data.VibrationPattern
import com.rameez.fit3zone.service.ZoneAlertManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val alertManager = remember { ZoneAlertManager(context) }
    // Snapshot once: these only seed the editable form fields.
    val savedProfile = remember { store.profile.value }
    val saved = remember { store.timing.value }
    val savedAlerts = remember { store.alerts.value }

    var age by remember { mutableStateOf(savedProfile?.age?.toString() ?: "") }
    var maxHr by remember { mutableStateOf(savedProfile?.maxHr?.toString() ?: "") }
    var maxSource by remember { mutableStateOf(savedProfile?.maxHrSource) }
    var resting by remember { mutableStateOf(savedProfile?.restingHr?.toString() ?: "") }
    var restingSource by remember { mutableStateOf(savedProfile?.restingSource) }
    val savedRange = remember { store.bandRestingRange.value }
    var bandMin by remember { mutableStateOf(savedRange?.first?.toString() ?: "") }
    var bandMax by remember { mutableStateOf(savedRange?.last?.toString() ?: "") }
    fun bandRange(): IntRange? {
        val lo = bandMin.trim().toIntOrNull()
        val hi = bandMax.trim().toIntOrNull()
        if (lo == null && hi == null) return null
        val l = lo ?: hi!!
        val h = hi ?: lo!!
        require(l in 30..120 && h in 30..120 && l <= h) { "band resting limits must be 30–120 with min ≤ max" }
        return l..h
    }

    var confirmS by remember { mutableStateOf((saved.confirmMs / 1000).toString()) }
    var belowConfirmS by remember { mutableStateOf((saved.belowConfirmMs / 1000).toString()) }
    var repeatS by remember { mutableStateOf((saved.repeatMs / 1000).toString()) }
    var cooldownS by remember { mutableStateOf((saved.cooldownMs / 1000).toString()) }
    var recoveryS by remember { mutableStateOf((saved.recoveryMs / 1000).toString()) }
    var staleS by remember { mutableStateOf((saved.staleTimeoutMs / 1000).toString()) }
    var backCue by remember { mutableStateOf(saved.backInZoneCue) }
    var alertBelow by remember { mutableStateOf(saved.alertBelow) }
    var pattern by remember { mutableStateOf(savedAlerts.pattern) }
    var belowPattern by remember { mutableStateOf(savedAlerts.belowPattern) }
    var intensity by remember { mutableFloatStateOf(savedAlerts.intensity.toFloat()) }
    var lengthS by remember { mutableFloatStateOf(savedAlerts.durationMs / 1000f) }
    fun alertSettings() = AlertSettings(pattern, belowPattern, intensity.toInt(), (lengthS * 1000).toLong())
    var message by remember { mutableStateOf<String?>(null) }
    var sampleInterval by remember { mutableStateOf(store.sampleIntervalMs.value) }
    var gpsOn by remember { mutableStateOf(store.gpsEnabled.value) }
    var autoPauseOn by remember { mutableStateOf(store.autoPause.value) }
    var audioOn by remember { mutableStateOf(store.audioCues.value) }
    var autoLapM by remember { mutableIntStateOf(store.autoLapM.value) }

    fun seconds(v: String, name: String) = (v.trim().toLongOrNull() ?: error("$name must be a number")) * 1000

    fun buildTiming() = AlertTiming(
        confirmMs = seconds(confirmS, "confirmation"),
        belowConfirmMs = seconds(belowConfirmS, "below confirmation"),
        repeatMs = seconds(repeatS, "repeat"),
        cooldownMs = seconds(cooldownS, "cooldown"),
        recoveryMs = seconds(recoveryS, "recovery"),
        staleTimeoutMs = seconds(staleS, "stale timeout"),
        backInZoneCue = backCue,
        alertBelow = alertBelow,
    )

    /** Null when nothing is entered (zones stay unset); throws on invalid input. */
    fun buildProfile(): HeartRateProfile? {
        val a = age.trim().toIntOrNull()
        val m = maxHr.trim().toIntOrNull() ?: a?.let { HeartRateProfile.ageEstimate(it) } ?: return null
        val source = when {
            maxHr.isBlank() -> MaxHrSource.AGE_ESTIMATE
            else -> maxSource ?: MaxHrSource.MANUAL
        }
        val r = resting.trim().toIntOrNull()
        return HeartRateProfile(a, m, source, r, if (r == null) null else restingSource ?: RestingHrSource.MANUAL)
    }

    val preview = remember(age, maxHr, resting) { runCatching { buildProfile() } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Heart-rate zones", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Five zones from heart-rate reserve (Karvonen): resting + 50/60/70/80/90% of (max − resting). " +
                "Without a resting HR, zones use % of max HR.",
            color = ZoneColors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Age", age, { age = it; if (maxSource == MaxHrSource.AGE_ESTIMATE) maxHr = "" }, Modifier.weight(1f))
            NumberField("Max HR", maxHr, { maxHr = it; maxSource = MaxHrSource.MANUAL }, Modifier.weight(1f))
        }
        val a = age.toIntOrNull()?.takeIf { it in 10..100 }
        Text(
            when {
                maxHr.isBlank() && a != null -> "Max HR will be estimated: 208 − 0.7 × $a = ${HeartRateProfile.ageEstimate(a)} (Tanaka). " +
                    "Runs that reach higher raise it automatically."
                maxSource == MaxHrSource.AGE_ESTIMATE -> "Estimated from age. Raised automatically when a run goes higher."
                maxSource == MaxHrSource.DETECTED -> "Detected on a run (highest 10-second average). Raised again if exceeded."
                maxSource == MaxHrSource.MANUAL -> "Entered by you: never changed automatically."
                else -> "Enter your age (or a tested max HR)."
            },
            color = ZoneColors.muted, fontSize = 13.sp,
        )
        if (a != null && maxHr.isNotBlank() && maxSource != MaxHrSource.AGE_ESTIMATE) {
            TextButton(onClick = { maxHr = ""; maxSource = MaxHrSource.AGE_ESTIMATE }) {
                Text("Use age estimate (${HeartRateProfile.ageEstimate(a)}) with auto-detect")
            }
        }

        NumberField("Resting HR", resting, { resting = it; restingSource = RestingHrSource.MANUAL }, Modifier.fillMaxWidth())
        RestingHrTools(
            limits = runCatching { bandRange() }.getOrNull(),
            onResult = { bpm, source -> resting = bpm.toString(); restingSource = source },
        )
        if (restingSource == RestingHrSource.BAND && resting.isNotBlank()) {
            Text(
                "From the band: lowest 30-minute average of each day (usually asleep), median of the last 14 days, " +
                    "kept within your limits below. Updates automatically as new band data arrives; typing a value switches to manual.",
                color = ZoneColors.muted, fontSize = 13.sp,
            )
        }
        Text("Band resting HR limits (optional)", color = ZoneColors.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Lowest", bandMin, { bandMin = it }, Modifier.weight(1f))
            NumberField("Highest", bandMax, { bandMax = it }, Modifier.weight(1f))
        }

        preview.fold(
            onSuccess = { p -> p?.let { ZonesTable(it) } },
            onFailure = { Text("Zones: ${it.message}", color = ZoneColors.above) },
        )

        Text("Alert timing", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Above: confirm (s)", confirmS, { confirmS = it }, Modifier.weight(1f))
            NumberField("Below: confirm (s)", belowConfirmS, { belowConfirmS = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Repeat (s)", repeatS, { repeatS = it }, Modifier.weight(1f))
            NumberField("Cooldown (s)", cooldownS, { cooldownS = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Recovery (s)", recoveryS, { recoveryS = it }, Modifier.weight(1f))
            NumberField("Stale after (s)", staleS, { staleS = it }, Modifier.weight(1f))
        }
        Text(
            "Confirm: time outside the target before the first alert. Repeat: interval while still outside. " +
                "Cooldown: minimum gap between alerts. Recovery: time back inside before the back-in-zone buzz. " +
                "Stale: no reading for this long pauses alerts.",
            color = ZoneColors.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alert when below the target (after you've first reached it)", Modifier.weight(1f))
            Switch(checked = alertBelow, onCheckedChange = { alertBelow = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Short buzz when back in the target", Modifier.weight(1f))
            Switch(checked = backCue, onCheckedChange = { backCue = it })
        }

        Text("Vibration", style = MaterialTheme.typography.titleLarge)
        Text("Above target (slow down)", fontWeight = FontWeight.SemiBold)
        PatternChips(pattern) { pattern = it }
        Text("Below target (speed up)", fontWeight = FontWeight.SemiBold)
        PatternChips(belowPattern) { belowPattern = it }
        if (pattern == belowPattern) Text("Use different patterns so you can tell them apart in a pocket.", color = ZoneColors.pending, fontSize = 13.sp)
        Text("Alert length: %.1f s".format(lengthS))
        Slider(
            value = lengthS,
            onValueChange = { lengthS = (it * 2).roundToInt() / 2f },
            valueRange = AlertSettings.MIN_DURATION_MS / 1000f..AlertSettings.MAX_DURATION_MS / 1000f,
        )
        if (alertManager.hasAmplitudeControl) {
            Text("Intensity")
            Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 40f..255f)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { alertManager.aboveZone(alertSettings()) }) { Text("Test above") }
            OutlinedButton(onClick = { alertManager.belowZone(alertSettings()) }) { Text("Test below") }
            OutlinedButton(onClick = { alertManager.backInZone(alertSettings()) }) { Text("Test back-in") }
        }

        Text("GPS", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Record route, distance and pace with the phone's GPS during runs", Modifier.weight(1f))
            Switch(checked = gpsOn, onCheckedChange = { gpsOn = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-pause")
                Text("Pauses when GPS shows you've stopped (traffic lights), resumes when you run again.", color = ZoneColors.muted, fontSize = 13.sp)
            }
            Switch(checked = autoPauseOn, onCheckedChange = { autoPauseOn = it }, enabled = gpsOn)
        }
        Text("Auto-lap")
        Text("A lap every set distance on GPS runs (short buzz + spoken lap). Workouts use their steps as laps instead.", color = ZoneColors.muted, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 400 to "400 m", 1_000 to "1 km", 2_000 to "2 km").forEach { (m, label) ->
                FilterChip(selected = autoLapM == m, onClick = { autoLapM = m }, label = { Text(label) }, enabled = gpsOn)
            }
        }

        Text("Voice", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spoken cues")
                Text(
                    "Each km or auto-lap (pace, heart rate), workout steps, laps and zone alerts (\"slow down\" / \"speed up\"), " +
                        "through headphones or the speaker. Music is lowered while a cue plays.",
                    color = ZoneColors.muted, fontSize = 13.sp,
                )
            }
            Switch(checked = audioOn, onCheckedChange = { audioOn = it })
        }

        Text("Session history", style = MaterialTheme.typography.titleLarge)
        Text("Heart-rate samples stored per session (on this phone only)", color = ZoneColors.muted)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(null to "Off", 1_000L to "Every 1 s", 5_000L to "Every 5 s", 15_000L to "Every 15 s").forEach { (ms, label) ->
                FilterChip(selected = sampleInterval == ms, onClick = { sampleInterval = ms }, label = { Text(label) })
            }
        }

        Button(onClick = {
            message = runCatching {
                val timing = buildTiming()
                require(lengthS * 1000 < timing.repeatMs) { "alert length must be shorter than the repeat interval" }
                val profile = buildProfile()
                store.saveTiming(timing)
                store.saveAlerts(alertSettings())
                store.saveSampleInterval(sampleInterval)
                store.saveGpsEnabled(gpsOn)
                store.saveAutoPause(autoPauseOn)
                store.saveAutoLap(autoLapM)
                store.saveAudioCues(audioOn)
                store.saveBandRestingRange(bandRange())
                profile?.let(store::saveProfile)
                profile
            }.fold(
                { p -> if (p == null) "Saved (no zones yet: enter your age or max HR)" else "Saved: max HR ${p.maxHr}, zones ${p.zones().floors.joinToString("/")}" },
                { "Not saved: ${it.message}" },
            )
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        message?.let { Text(it, color = if (it.startsWith("Saved")) ZoneColors.inZone else ZoneColors.above) }
    }
}

@Composable
private fun PatternChips(selected: VibrationPattern, onSelect: (VibrationPattern) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VibrationPattern.entries.forEach { p ->
            FilterChip(selected = selected == p, onClick = { onSelect(p) }, label = { Text(p.label) })
        }
    }
}

@Composable
private fun ZonesTable(p: HeartRateProfile) {
    val zones = p.zones()
    GlowCard(accent = Palette.heart, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (p.usesReserve) "HEART-RATE RESERVE · max ${p.maxHr} · resting ${p.restingHr}" else "% OF MAX HR · max ${p.maxHr} (add resting HR for reserve)",
                color = ZoneColors.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            val names = listOf("Warm up", "Easy", "Aerobic", "Threshold", "Maximum")
            for (z in HrZones.COUNT downTo 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Z$z", Modifier.width(40.dp), color = ZoneColors.hrZone(z), fontWeight = FontWeight.Bold)
                    Text(names[z - 1], Modifier.weight(1f))
                    val r = zones.range(z)
                    Text("${r.first}–${r.last} BPM", fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Unsaved changes show here first; tap Save to use them.", color = ZoneColors.muted, fontSize = 12.sp)
        }
    }
}

/** Resting HR from a 2-minute still measurement with the band, or today's value from Health Connect. */
@Composable
private fun RestingHrTools(limits: IntRange?, onResult: (Int, RestingHrSource) -> Unit) {
    val context = LocalContext.current
    var measuring by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    val healthResting by androidx.compose.runtime.produceState<Int?>(null) {
        value = runCatching { HealthData(context).takeIf { it.availability() == HealthData.Availability.AVAILABLE }?.readToday()?.restingHr }.getOrNull()
    }
    val bandResting by androidx.compose.runtime.produceState<com.rameez.fit3zone.core.RestingEstimate?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.rameez.fit3zone.data.BandData.restingEstimate(context) }.getOrNull()
        }
    }

    if (measuring) {
        LaunchedEffect(Unit) {
            val m = RestingHrMeasurement()
            val job = launch {
                HeartRateRepository.latest.filterNotNull().collect { r ->
                    if (r.source == ReadingSource.BLE) m.add(r.elapsedMs, r.bpm)
                }
            }
            while (true) {
                val now = SystemClock.elapsedRealtime()
                progress = m.progress(now)
                if (progress == 0f && HeartRateRepository.latest.value?.let { now - it.elapsedMs > 10_000 } != false) {
                    status = "Waiting for live heart rate from the band (start a workout on the band)…"
                } else {
                    status = "Sit or lie still… ${(progress * 120).toInt()} / 120 s"
                }
                if (m.isDone(now)) break
                delay(500)
            }
            job.cancel()
            val result = m.result
            status = if (result != null) "Measured resting HR: $result BPM (tap Save to keep it)" else "Not enough readings; try again."
            result?.let { onResult(it, RestingHrSource.MEASURED) }
            measuring = false
        }
    }
    bandResting?.let { e ->
        val use = e.within(limits)
        OutlinedButton(onClick = { onResult(use, RestingHrSource.BAND) }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Use band: $use BPM" + (if (use != e.bpm) " (measured ${e.bpm}, kept in ${limits!!.first}–${limits.last})" else "") +
                    " · ${e.daysUsed} ${if (e.daysUsed == 1) "night" else "nights"}, auto-updates",
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { status = null; measuring = !measuring }) { Text(if (measuring) "Cancel" else "Measure (2 min)") }
        healthResting?.let { hr ->
            OutlinedButton(onClick = { onResult(hr, RestingHrSource.HEALTH_CONNECT) }) { Text("Use Health Connect ($hr)") }
        }
    }
    if (measuring) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    status?.let { Text(it, color = ZoneColors.muted, fontSize = 13.sp) }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
