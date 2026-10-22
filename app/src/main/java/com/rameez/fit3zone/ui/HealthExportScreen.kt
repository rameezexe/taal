package com.rameez.fit3zone.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.data.HealthData
import com.rameez.fit3zone.data.HealthExport
import com.rameez.fit3zone.data.SessionHistory
import kotlinx.coroutines.launch

/** Device → Samsung Health: send finished activities to Health Connect, where Samsung Health reads them. */
@Composable
fun HealthExportScreen(modifier: Modifier) {
    val context = LocalContext.current
    val export = remember { HealthExport.get(context) }
    val state by export.state.collectAsStateWithLifecycle()
    val sessions by SessionHistory.get(context).sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val available = remember { HealthData(context).availability() == HealthData.Availability.AVAILABLE }
    var confirmOff by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { export.enable() }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Samsung Health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        GlowCard(accent = Palette.steps) {
            CardLabel(if (state.enabled) "Sending" else "Off", Palette.steps)
            Text("Activities → Health Connect → Samsung Health", style = MaterialTheme.typography.titleMedium)
            Text(
                "Each finished activity is written to Health Connect: the workout, GPS route, heart rate, distance and elevation gain. " +
                    "Samsung Health (and other apps you allow) read it from there. Steps are not sent, so they aren't counted twice.",
                color = ZoneColors.muted, fontSize = 13.sp,
            )
        }

        when {
            !available -> Text("Health Connect isn't available on this phone.", color = ZoneColors.above)
            !state.enabled -> GradientButton(
                "Start sending",
                { launcher.launch(HealthExport.PERMISSIONS) },
                colors = listOf(Palette.steps, Palette.accent), contentColor = androidx.compose.ui.graphics.Color(0xFF0A0D10),
            )
            else -> {
                val pending = export.notWritten(sessions)
                GlowCard(accent = Palette.accent) {
                    CardLabel("History", Palette.accent)
                    val done = sessions.count { it.id in state.written }
                    Text("$done sent · ${pending.size} not sent", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "New activities of 5 min or more are sent when you finish them; shorter ones (over a minute) only with the button below. Sending one again replaces it. Deleting an activity here removes it there too.",
                        color = ZoneColors.muted, fontSize = 13.sp,
                    )
                    state.progress?.let { Text(it, color = Palette.steps, fontWeight = FontWeight.SemiBold) }
                }
                GhostButton(
                    if (pending.isEmpty()) "Everything is sent" else "Send ${pending.size} past ${if (pending.size == 1) "activity" else "activities"}",
                    { (context.applicationContext as Fit3ZoneApp).appScope.launch { export.writeHistory(sessions) } },
                    Modifier.fillMaxWidth(), enabled = pending.isNotEmpty() && state.progress == null, height = 56.dp,
                )
                GhostButton(
                    if (confirmOff) "Tap again to stop sending" else "Stop sending",
                    { if (confirmOff) { export.disable(); confirmOff = false } else confirmOff = true },
                    Modifier.fillMaxWidth(), height = 56.dp,
                )
                Text(
                    "Already sent activities stay in Health Connect; delete them there (Health Connect → Data and access) if needed. " +
                        "If Samsung Health also syncs to Strava, check Strava for a second copy of the same run.",
                    color = ZoneColors.muted, fontSize = 12.sp,
                )
            }
        }
        state.message?.let { Text(it, color = if (it.startsWith("Sent")) ZoneColors.inZone else ZoneColors.pending) }
    }
}
