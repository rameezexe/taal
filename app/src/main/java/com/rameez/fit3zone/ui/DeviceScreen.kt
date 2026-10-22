package com.rameez.fit3zone.ui

import com.rameez.fit3zone.APP_NAME
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import com.rameez.fit3zone.data.DevMode
import com.rameez.fit3zone.BuildConfig
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.data.BleConnectionState
import com.rameez.fit3zone.data.DevicePrefs
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.service.HeartRateForegroundService

private enum class DevicePage { HOME, BAND, SETTINGS, STRAVA, HEALTH, DEBUG, BACKGROUND, DATA }

/** The "Device" area: the heart-rate sensor, connected services, app settings and diagnostics. */
@Composable
fun DeviceScreen(modifier: Modifier) {
    var page by rememberSaveable { mutableStateOf(DevicePage.HOME) }
    if (page != DevicePage.HOME) BackHandler { page = DevicePage.HOME }

    Column(modifier.fillMaxSize()) {
        if (page != DevicePage.HOME) TextButton(onClick = { page = DevicePage.HOME }, Modifier.padding(start = 8.dp)) { Text("‹ Device") }
        val inner = Modifier.weight(1f)
        when (page) {
            DevicePage.HOME -> DeviceHome(inner) { page = it }
            DevicePage.BAND -> DevicesScreen(inner, onSelected = { page = DevicePage.HOME })
            DevicePage.SETTINGS -> SettingsScreen(inner)
            DevicePage.STRAVA -> StravaScreen(inner)
            DevicePage.HEALTH -> HealthExportScreen(inner)
            DevicePage.DEBUG -> DiagnosticsScreen(inner)
            DevicePage.BACKGROUND -> Column(
                inner.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Background running", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                BackgroundGuide()
            }
            DevicePage.DATA -> DataScreen(inner)
        }
    }
}

@Composable
private fun DeviceHome(modifier: Modifier, open: (DevicePage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by HeartRateRepository.connection.collectAsStateWithLifecycle()
    val info by HeartRateRepository.deviceInfo.collectAsStateWithLifecycle()
    val now = rememberElapsedNow()
    val fresh = HeartRateRepository.isFresh(now)
    val saved = remember(connection) { DevicePrefs(context).device }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val unrestricted = remember(now / 5_000) { power.isIgnoringBatteryOptimizations(context.packageName) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        val (dot, status) = when {
            connection == BleConnectionState.Subscribed -> ZoneColors.inZone to "Connected"
            connection == BleConnectionState.Idle -> ZoneColors.noData to "Not connected"
            else -> ZoneColors.pending to HeartRateForegroundService.describe(connection)
        }
        MetricCard(
            saved?.kind?.title ?: "Heart-rate sensor", saved?.name ?: "None chosen yet",
            detail = listOfNotNull(
                info.firmwareRevision?.let { "firmware rev $it" },
                info.softwareRevision?.let { "software rev $it" },
                saved?.address,
            ).joinToString(" · ").ifEmpty { null },
            onClick = { open(DevicePage.BAND) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Text(status)
            }
            Row {
                if (saved != null) TextButton(onClick = {
                    if (connection == BleConnectionState.Idle) HeartRateForegroundService.connect(context, saved.address)
                    else HeartRateForegroundService.disconnect(context)
                }) { Text(if (connection == BleConnectionState.Idle) "Connect" else "Disconnect") }
                TextButton(onClick = { open(DevicePage.BAND) }) { Text(if (saved == null) "Choose sensor" else "Change sensor") }
            }
        }

        MetricCard(
            "Background running", if (unrestricted) "Unrestricted" else "Optimized",
            detail = (if (unrestricted) "Android won't pause $APP_NAME during runs." else "Android may pause the app with the screen off.") +
                " Steps for your phone (${PhoneFamily.of().label}) →",
            accent = if (unrestricted) ZoneColors.inZone else ZoneColors.pending,
            onClick = { open(DevicePage.BACKGROUND) },
        )

        val strava by com.rameez.fit3zone.data.Strava.get(context).state.collectAsStateWithLifecycle()
        MetricCard(
            "Strava", if (strava.connected) strava.athleteName ?: "Connected" else "Not connected",
            detail = if (strava.connected) "Auto-upload ${if (strava.autoUpload) "on" else "off"} · upload past activities" else "Upload your activities to Strava",
            accent = StravaOrange, onClick = { open(DevicePage.STRAVA) },
        )
        val healthExport by com.rameez.fit3zone.data.HealthExport.get(context).state.collectAsStateWithLifecycle()
        MetricCard(
            "Samsung Health", if (healthExport.enabled) "Sending" else "Off",
            detail = if (healthExport.enabled) "${healthExport.written.size} activities sent via Health Connect" else "Show your activities in Samsung Health",
            accent = Palette.steps, onClick = { open(DevicePage.HEALTH) },
        )
        MetricCard("Your data", "Export · import", detail = "Save all activities and settings to a file, or restore them after a reinstall", onClick = { open(DevicePage.DATA) })
        MetricCard("Settings", "Zones, alerts, GPS", detail = "Heart-rate zones, vibration, recording", onClick = { open(DevicePage.SETTINGS) })
        MetricCard(
            "Feedback", "Report a problem or idea",
            detail = "Opens your email or chat app with phone details and the app log attached. You see everything before sending.",
            accent = Palette.accent2, onClick = { scope.launch { sendFeedback(context) } },
        )
        val dev by DevMode.on.collectAsStateWithLifecycle()
        if (dev) MetricCard("Diagnostics", "Bluetooth & logs", detail = "Packets, services, simulator, copyable log", onClick = { open(DevicePage.DEBUG) })
        VersionLine(dev)
    }
}

/** "Taal 1.1.0-beta (7)"; 7 taps switch the developer tools on or off. */
@Composable
private fun VersionLine(dev: Boolean) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    Text(
        "$APP_NAME ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (dev) " · developer tools on" else "",
        color = ZoneColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) {
            if (++taps >= 7) {
                taps = 0
                DevMode.set(context, !dev)
                Toast.makeText(context, if (dev) "Developer tools off" else "Developer tools on", Toast.LENGTH_SHORT).show()
            }
        }.padding(vertical = 8.dp),
    )
}
