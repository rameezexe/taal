package com.rameez.fit3zone.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rameez.fit3zone.data.GpsStatus
import com.rameez.fit3zone.data.SessionState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * True when Power saving is on and its location rule limits GPS (on Samsung: GPS off whenever the screen is off),
 * which leaves most of a pocket run without fixes. "Unrestricted" battery for the app doesn't override it.
 */
fun powerSaverLimitsGps(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isPowerSaveMode && pm.locationPowerSaveMode != PowerManager.LOCATION_MODE_NO_CHANGE
}

/** GPS line under the live distance (Heart and Map pages): problems first, then the fix accuracy. */
fun SessionState.gpsLine(): Pair<Color, String> = when {
    gps == GpsStatus.NO_PERMISSION -> ZoneColors.above to "GPS unavailable: location permission off"
    gpsLost -> ZoneColors.above to (if (distanceEstimated) "GPS lost · distance from your steps" else "GPS lost")
    powerSaver -> ZoneColors.above to "Power saving is on: GPS stops with the screen off"
    gps == GpsStatus.OK -> ZoneColors.inZone to "GPS ready (±${gpsAccuracyM?.toInt()} m)"
    gps == GpsStatus.SEARCHING -> ZoneColors.pending to "Searching for GPS…" + (gpsAccuracyM?.let { " (±${it.toInt()} m)" } ?: "")
    else -> ZoneColors.noData to ""
}

/** Run screen, before a GPS activity: tells the user to turn Power saving off, with a button to its settings. */
@Composable
fun PowerSaverWarning() {
    val context = LocalContext.current
    var limited by remember { mutableStateOf(powerSaverLimitsGps(context)) }
    RefreshOnResume { limited = powerSaverLimitsGps(context) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); limited = powerSaverLimitsGps(context) } }
    if (!limited) return
    GlowCard(accent = ZoneColors.above) {
        CardLabel("Power saving is on", ZoneColors.above)
        Text(
            "It switches GPS off whenever the screen is off, so most of a run in your pocket isn't tracked. " +
                "Turn Power saving off before you start.",
            fontSize = 14.sp,
        )
        GhostButton("Open Power saving settings", {
            context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, Modifier.fillMaxWidth(), height = 52.dp)
    }
}
