package com.rameez.fit3zone.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.APP_NAME

/**
 * Phone families with their own background-app killers. Most Indian phones are Xiaomi / Redmi / POCO, Oppo / Realme /
 * OnePlus (ColorOS) or Vivo / iQOO; each needs extra steps beyond Android's "Unrestricted" battery setting, or the
 * run stops when the screen goes off.
 */
enum class PhoneFamily(
    val label: String,
    /** dontkillmyapp.com page with screenshots, maintained by the community. */
    val helpPage: String,
    /** Autostart / background-start screens to try, in order (names differ between OS versions). */
    val autostart: List<ComponentName> = emptyList(),
) {
    SAMSUNG("Samsung", "samsung"),
    XIAOMI(
        "Xiaomi / Redmi / POCO", "xiaomi",
        listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    ),
    COLOROS(
        "Oppo / Realme / OnePlus", "oneplus",
        listOf(
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
    ),
    VIVO(
        "Vivo / iQOO", "vivo",
        listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ),
    ),
    OTHER("Android", "google");

    /** What to do on this phone, beyond the "Allow" button (which covers Android's own battery optimisation). */
    val steps: List<String>
        get() = when (this) {
            SAMSUNG -> listOf(
                "Settings → Battery → Background usage limits: $APP_NAME must not be in Sleeping or Deep sleeping apps.",
                "Turn Power saving off before GPS runs (it switches GPS off with the screen off).",
            )
            XIAOMI -> listOf(
                "Turn Autostart on for $APP_NAME (button below, or App info → Autostart).",
                "App info → Battery saver → No restrictions.",
                "Open Recents, long-press $APP_NAME and tap the lock, so clearing Recents doesn't close it.",
                "Turn Battery saver off before GPS runs.",
            )
            COLOROS -> listOf(
                "App info → Battery usage: allow background activity, auto launch and foreground activity (whichever are shown).",
                "Open Recents, tap ⋮ or long-press $APP_NAME and choose Lock.",
                "Turn Power saving / Battery saver off before GPS runs.",
            )
            VIVO -> listOf(
                "Settings → Battery → Background power consumption management → $APP_NAME → Allow high background power consumption (or Don't restrict).",
                "Turn Autostart on for $APP_NAME (button below, or i Manager → App manager → Autostart).",
                "Open Recents, pull $APP_NAME down or long-press it and lock it.",
                "Turn Power saving off before GPS runs.",
            )
            OTHER -> listOf("Turn Battery saver off before GPS runs.")
        }

    companion object {
        fun of(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): PhoneFamily {
            val m = "$manufacturer $brand".lowercase()
            return when {
                "samsung" in m -> SAMSUNG
                listOf("xiaomi", "redmi", "poco").any { it in m } -> XIAOMI
                listOf("oppo", "realme", "oneplus").any { it in m } -> COLOROS
                listOf("vivo", "iqoo").any { it in m } -> VIVO
                else -> OTHER
            }
        }
    }
}

fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Android's own "Let app always run in background?" dialog; falls back to the app's settings page. */
@SuppressLint("BatteryLife") // Sideloaded fitness app with a foreground service: exactly the allowed use.
fun requestUnrestricted(context: Context) {
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
    if (!context.tryStart(ask)) openAppDetails(context)
}

fun openAppDetails(context: Context) {
    context.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

/** The brand's Autostart screen if one of the known ones opens, else the app's settings page. */
fun openAutostart(context: Context, family: PhoneFamily) {
    val opened = family.autostart.any { context.tryStart(Intent().setComponent(it)) }
    if (!opened) openAppDetails(context)
}

private fun Context.tryStart(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/**
 * Everything that keeps runs going with the screen off on this phone: Android's battery setting (checked live),
 * the brand's own steps, and a link to dontkillmyapp.com for screenshots. Setup flow + Device → Background running.
 */
@Composable
fun BackgroundGuide() {
    val context = LocalContext.current
    val family = remember { PhoneFamily.of() }
    var unrestricted by remember { mutableStateOf(ignoresBatteryOptimizations(context)) }
    RefreshOnResume { unrestricted = ignoresBatteryOptimizations(context) }

    GlowCard(accent = if (unrestricted) ZoneColors.inZone else ZoneColors.pending) {
        CardLabel(if (unrestricted) "Battery: Unrestricted" else "Battery: Optimized", if (unrestricted) ZoneColors.inZone else ZoneColors.pending)
        Text(
            if (unrestricted) "Android won't pause $APP_NAME during runs."
            else "Android pauses apps in the background to save battery, which stops alerts and GPS mid-run.",
            color = ZoneColors.muted, fontSize = 14.sp,
        )
        if (!unrestricted) GradientButton("Allow background use", { requestUnrestricted(context) })
    }
    GlowCard(accent = Palette.accent) {
        CardLabel("${family.label}: also do this", Palette.accent)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            family.steps.forEachIndexed { i, step ->
                Row {
                    Text("${i + 1}.", Modifier.width(22.dp), color = Palette.accent, fontSize = 14.sp)
                    Text(step, fontSize = 14.sp)
                }
            }
        }
        if (family.autostart.isNotEmpty()) GhostButton("Open Autostart settings", { openAutostart(context, family) }, Modifier.fillMaxWidth(), height = 52.dp)
        GhostButton("Open $APP_NAME's app info", { openAppDetails(context) }, Modifier.fillMaxWidth(), height = 52.dp)
        TextAction("Guide with screenshots ›", {
            context.tryStart(Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com/${family.helpPage}".toUri()))
        })
    }
    PowerSaverWarning()
}
