package com.rameez.fit3zone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.ble.BleScanner
import com.rameez.fit3zone.ble.FoundDevice
import com.rameez.fit3zone.data.BandData
import com.rameez.fit3zone.data.DeviceKind
import com.rameez.fit3zone.data.DevicePrefs
import com.rameez.fit3zone.data.SavedDevice
import com.rameez.fit3zone.service.HeartRateForegroundService

/** Device tab → choose the heart-rate sensor. */
@Composable
fun DevicesScreen(modifier: Modifier, onSelected: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Heart-rate sensor", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SensorPicker(onSelected)
    }
}

/**
 * Pick what kind of sensor, follow its setup hint, scan, tap it. Choosing one saves it and connects.
 * Works with anything that speaks the standard Bluetooth Heart Rate profile; the kind only changes the help text.
 */
@Composable
fun SensorPicker(onSelected: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val prefs = remember { DevicePrefs(context) }
    val devices by scanner.devices.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(prefs.device?.kind ?: DeviceKind.STRAP) }
    var showAll by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        scanner.loadBonded()
        onDispose { scanner.stopScan() }
    }
    LaunchedEffect(kind) { scanner.startScan() }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillChip("Strap / armband", kind == DeviceKind.STRAP, Palette.accent) { kind = DeviceKind.STRAP }
        PillChip("Watch", kind == DeviceKind.WATCH, Palette.accent) { kind = DeviceKind.WATCH }
        PillChip("Galaxy Fit3", kind == DeviceKind.FIT3, Palette.accent) { kind = DeviceKind.FIT3 }
    }

    GlowCard(accent = Palette.accent2) {
        CardLabel("Before you scan", Palette.accent2)
        setupHint(kind).forEach { Text(it, color = ZoneColors.muted, fontSize = 14.sp) }
    }
    if (kind == DeviceKind.FIT3) BandDataCard()

    val shown = devices.filter {
        showAll || when (kind) {
            DeviceKind.FIT3 -> it.looksLikeFit3
            // Only what is broadcasting heart rate right now; paired earbuds etc. would just confuse.
            else -> it.advertisesHeartRate
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (scanning) "Scanning…" else "${shown.size} found", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextAction(if (scanning) "Scanning" else "Scan again", { scanner.startScan() }, enabled = !scanning)
    }
    if (shown.isEmpty() && !scanning) {
        Text(
            if (kind == DeviceKind.FIT3) "No Fit3 found. Make sure it's on, nearby and paired with Galaxy Wearable."
            else "Nothing broadcasting heart rate found. Check the steps above, then tap Scan again.",
            color = ZoneColors.muted,
        )
    }
    shown.forEach { d ->
        DeviceRow(d) {
            prefs.device = SavedDevice(d.address, d.name, kind)
            HeartRateForegroundService.connect(context, d.address)
            onSelected()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show all Bluetooth devices", Modifier.weight(1f), color = ZoneColors.muted)
        Switch(checked = showAll, onCheckedChange = { showAll = it })
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, onClick: () -> Unit) {
    GlowCard(accent = Palette.accent, onClick = onClick, contentPadding = 14.dp) {
        Text(d.name ?: "(no name)", style = MaterialTheme.typography.titleMedium)
        val tags = listOfNotNull(
            "heart rate".takeIf { d.advertisesHeartRate },
            "paired".takeIf { d.bonded },
            d.rssi?.let { if (it > -70) "close" else if (it > -85) "nearby" else "far" },
        )
        Text(tags.joinToString(" · ").ifEmpty { d.address }, color = ZoneColors.muted, fontSize = 13.sp)
    }
}

private fun setupHint(kind: DeviceKind): List<String> = when (kind) {
    DeviceKind.STRAP -> listOf(
        "1. Wet the strap's sensor pads and put it on: most straps only wake up when they touch skin.",
        "2. Don't pair it in Android's Bluetooth settings; it shows up here directly.",
        "3. If another app is connected to it, close that app if it doesn't appear.",
        "Works with any strap or armband using standard Bluetooth heart rate (Polar, Garmin HRM, Wahoo, Coros, Decathlon and others).",
    )
    DeviceKind.WATCH -> listOf(
        "Turn on heart-rate broadcast on the watch, then scan:",
        "• Garmin: heart-rate settings → Broadcast Heart Rate (or during an activity).",
        "• Polar, Coros, Suunto, Amazfit: look for \"HR broadcast\" or \"Share heart rate\" in the watch or its app.",
        "Galaxy Watch and Apple Watch don't broadcast heart rate on their own, so they can't be used yet.",
    )
    DeviceKind.FIT3 -> listOf(
        "Keep the band paired with Galaxy Wearable: $APP_NAME connects alongside it.",
        "Live heart rate only streams while a workout runs on the band or continuous heart rate is on.",
    )
}

/** Opt-in switch for reading the Fit3's own health data (steps, sleep, SpO2 …) as it syncs. */
@Composable
fun BandDataCard() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { BandData.load(context) }
    val band by BandData.state.collectAsStateWithLifecycle()
    GlowCard(accent = Palette.steps) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardLabel("Band data · unofficial · experimental", Palette.steps)
                Text("Show the band's steps, sleep, heart rate and blood oxygen on My Day")
            }
            Switch(checked = band.enabled, onCheckedChange = { BandData.setEnabled(context, it) })
        }
        Text(
            "$APP_NAME only listens to what the band already sends to your phone when Galaxy Wearable syncs; it never " +
                "writes to the band. This isn't an official Samsung feature, may stop working after a band update, and " +
                "isn't affiliated with or endorsed by Samsung. Tested only on band software R390XXU0AZE2.",
            color = ZoneColors.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(),
        )
    }
}
