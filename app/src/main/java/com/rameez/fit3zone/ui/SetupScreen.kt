package com.rameez.fit3zone.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.R
import com.rameez.fit3zone.core.HeartRateProfile
import com.rameez.fit3zone.core.MaxHrSource
import com.rameez.fit3zone.core.RestingHrSource
import com.rameez.fit3zone.data.DevicePrefs
import com.rameez.fit3zone.data.SettingsStore

/** First-run setup is done (or the app was set up before the setup flow existed). */
object Setup {
    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    fun isDone(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.contains("done")) return prefs.getBoolean("done", false)
        // Installs from before the setup flow: a saved profile means the app is already set up.
        val existing = SettingsStore.get(context).profile.value != null
        prefs.edit { putBoolean("done", existing) }
        return existing
    }

    fun finish(context: Context) = prefs(context).edit { putBoolean("done", true) }
}

private enum class Step { WELCOME, PERMISSIONS, PROFILE, SENSOR, BACKGROUND }

@Composable
fun SetupFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    if (step != Step.WELCOME) BackHandler { step = Step.entries[step.ordinal - 1] }
    fun next() {
        if (step == Step.BACKGROUND) { Setup.finish(context); onDone() } else step = Step.entries[step.ordinal + 1]
    }

    Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            StepDots(step)
            AnimatedContent(
                targetState = step, modifier = Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(300)) + slideInHorizontally(tween(380)) { it / 5 * dir }).togetherWith(fadeOut(tween(150)))
                },
                label = "setup",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (s) {
                        Step.WELCOME -> WelcomeStep(::next)
                        Step.PERMISSIONS -> PermissionsStep(::next)
                        Step.PROFILE -> ProfileStep(::next)
                        Step.SENSOR -> SensorStep(::next)
                        Step.BACKGROUND -> BackgroundStep(::next)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Step) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Step.entries.forEach {
            Box(Modifier.size(if (it == step) 22.dp else 8.dp, 8.dp).background(if (it.ordinal <= step.ordinal) Palette.accent else Palette.hairline, CircleShape))
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    sub?.let { Text(it, color = ZoneColors.muted) }
}

@Composable
private fun WelcomeStep(next: () -> Unit) {
    Image(
        painterResource(R.drawable.taal_wordmark), contentDescription = APP_NAME,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).height(72.dp),
    )
    Title("Welcome to $APP_NAME", "Heart-rate zone training with your phone in your pocket.")
    GlowCard(accent = Palette.heart) {
        listOf(
            "Live heart rate from a strap, watch or Galaxy Fit3",
            "Vibration alerts when you leave your zone, even with the screen off",
            "GPS runs, structured workouts, race pacer and voice cues",
            "Training load, recovery, VO2max and race predictions",
            "Optional upload to Strava and Samsung Health",
        ).forEach { Text("•  $it") }
    }
    GlowCard(accent = ZoneColors.pending) {
        CardLabel("Please read", ZoneColors.pending)
        Text(
            "$APP_NAME is not a medical device. Zones, alerts and training numbers are estimates for fitness use. " +
                "Talk to a doctor before starting hard training, especially with a heart condition, and stop if you feel unwell.",
            color = ZoneColors.muted, fontSize = 14.sp,
        )
        Text(
            "Your data stays on this phone. It only goes to Strava or Health Connect if you turn that on, " +
                "and to anyone else only when you share it yourself.",
            color = ZoneColors.muted, fontSize = 14.sp,
        )
    }
    GradientButton("I understand, let's start", next)
}

@Composable
private fun PermissionsStep(next: () -> Unit) {
    val context = LocalContext.current
    fun missing() = REQUIRED_PERMISSIONS.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    var denied by remember { mutableStateOf(missing()) }
    var asked by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { denied = missing(); asked++ }
    RefreshOnResume { denied = missing() }
    val bluetoothOk = denied.none { it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT }
    val notificationsOk = denied.none { it == Manifest.permission.POST_NOTIFICATIONS }

    Title("Permissions", "Two now; location and physical activity are asked when you start your first run.")
    PermissionRow("Nearby devices", "Connect to your heart-rate sensor over Bluetooth. Required.", bluetoothOk)
    PermissionRow("Notifications", "Live heart rate and run controls while the screen is off. Strongly recommended.", notificationsOk)
    when {
        denied.isEmpty() -> GradientButton("Continue", next)
        asked >= 2 && !bluetoothOk -> {
            Text("Android won't ask again. Allow Nearby devices in the app's settings.", color = ZoneColors.above)
            GhostButton("Open app settings", { openAppSettings(context) }, Modifier.fillMaxWidth(), height = 56.dp)
        }
        else -> GradientButton("Allow", { launcher.launch(REQUIRED_PERMISSIONS) })
    }
    if (bluetoothOk && !notificationsOk) TextAction("Continue without notifications", next)
}

@Composable
private fun PermissionRow(name: String, why: String, granted: Boolean) {
    GlowCard(accent = if (granted) ZoneColors.inZone else Palette.accent, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(why, color = ZoneColors.muted, fontSize = 13.sp)
            }
            Text(if (granted) "Allowed" else "Not yet", color = if (granted) ZoneColors.inZone else ZoneColors.muted, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProfileStep(next: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val saved = remember { store.profile.value }
    var age by rememberSaveable { mutableStateOf(saved?.age?.toString() ?: "") }
    var resting by rememberSaveable { mutableStateOf(saved?.restingHr?.toString() ?: "") }
    var max by rememberSaveable { mutableStateOf(if (saved?.maxHrSource == MaxHrSource.MANUAL) saved.maxHr.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }

    Title("Your heart-rate zones", "Zones are worked out from your age, or from your own numbers if you know them.")
    NumberField("Age", age, { age = it; error = null }, Modifier.fillMaxWidth())
    NumberField("Resting heart rate (optional)", resting, { resting = it; error = null }, Modifier.fillMaxWidth())
    Text("Your lowest heart rate, e.g. just after waking up. Makes the zones fit you better.", color = ZoneColors.muted, fontSize = 13.sp)
    NumberField("Max heart rate (optional)", max, { max = it; error = null }, Modifier.fillMaxWidth())
    Text("Leave empty to estimate it from your age; it's raised automatically if a run shows a higher one.", color = ZoneColors.muted, fontSize = 13.sp)

    val preview = runCatching { build(age, resting, max) }.getOrNull()
    preview?.zones()?.let { z ->
        GlowCard(accent = Palette.accent) {
            CardLabel("Your zones", Palette.accent)
            Text(
                (1..5).joinToString("  ·  ") { i -> "Z$i ${z.floors[i - 1]}" } + "  ·  max ${preview.maxHr}",
                fontSize = 14.sp,
            )
        }
    }
    error?.let { Text(it, color = ZoneColors.above) }
    GradientButton("Save and continue", {
        runCatching { build(age, resting, max) ?: error("Enter your age (or a max heart rate)") }
            .onSuccess {
                // Same numbers as before: keep the saved profile (and where its values came from, e.g. the band).
                val unchanged = saved != null && it.age == saved.age && it.restingHr == saved.restingHr &&
                    (it.maxHr == saved.maxHr || max.isBlank())
                if (!unchanged) store.saveProfile(it)
                next()
            }
            .onFailure { error = it.message }
    })
}

private fun build(age: String, resting: String, max: String): HeartRateProfile? {
    val a = age.trim().toIntOrNull()
    val m = max.trim().toIntOrNull()
    val maxHr = m ?: a?.let(HeartRateProfile::ageEstimate) ?: return null
    val r = resting.trim().toIntOrNull()
    return HeartRateProfile(a, maxHr, if (m != null) MaxHrSource.MANUAL else MaxHrSource.AGE_ESTIMATE, r, r?.let { RestingHrSource.MANUAL })
}

@Composable
private fun SensorStep(next: () -> Unit) {
    val context = LocalContext.current
    Title("Heart-rate sensor", "Choose what you'll wear. You can change it later under Device.")
    SensorPicker(onSelected = next)
    TextAction(if (DevicePrefs(context).device != null) "Keep the current sensor" else "Skip for now", next)
}

@Composable
private fun BackgroundStep(next: () -> Unit) {
    Title("Keep it running", "So alerts and GPS keep going with the screen off.")
    BackgroundGuide()
    GlowCard(accent = Palette.accent2) {
        CardLabel("Later, if you like", Palette.accent2)
        Text("Device → Strava: upload your runs.  Device → Samsung Health: send them to Health Connect.  My Day: read steps and sleep from Health Connect.", color = ZoneColors.muted, fontSize = 14.sp)
    }
    GradientButton("Start using $APP_NAME", next)
}

/** Runs [block] each time the screen comes back (e.g. after the user changed something in system settings). */
@Composable
fun RefreshOnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { block() } }
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}
