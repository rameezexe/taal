package com.rameez.fit3zone.ui

import com.rameez.fit3zone.APP_NAME
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionStatus
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rameez.fit3zone.R

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var setupDone by remember { mutableStateOf(Setup.isDone(this)) }
                if (setupDone) PermissionGate { AppScaffold() } else SetupFlow(onDone = { setupDone = true })
            }
        }
    }
}

val REQUIRED_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun missing() = REQUIRED_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    var denied by remember { mutableStateOf(missing()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        denied = missing()
    }
    // Notifications are strongly recommended but not required to read HR; Bluetooth is required.
    val bluetoothMissing = denied.any { it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT }
    if (!bluetoothMissing) {
        content()
        return
    }
    Column(
        Modifier.fillMaxSize().background(Palette.bg).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("Nearby devices permission", style = MaterialTheme.typography.headlineSmall)
        Text(
            "$APP_NAME needs the Nearby devices permission to connect to your heart-rate strap, watch or band over Bluetooth, " +
                "and the notification permission to show live heart rate while the screen is off. " +
                "It does not use your location.",
        )
        Button(onClick = { launcher.launch(REQUIRED_PERMISSIONS) }) { Text("Grant permissions") }
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open app settings") }
    }
}

private enum class Tab(val label: String, val icon: Int) {
    MY_DAY("My Day", R.drawable.ic_today),
    ACTIVITIES("Activities", R.drawable.ic_list),
    RUN("Run", R.drawable.ic_heart),
    TRAINING("Training", R.drawable.ic_chart),
    DEVICE("Device", R.drawable.ic_watch),
}

@Composable
private fun AppScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.MY_DAY) }
    var openActivityId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openActivity: (Long) -> Unit = { openActivityId = it; tab = Tab.ACTIVITIES }
    val context = LocalContext.current
    val sessions by remember { SessionHistory.get(context) }.sessions.collectAsStateWithLifecycle()
    // A finished activity waits on the save screen before anything else (and before it syncs anywhere).
    val unsaved = sessions.firstOrNull { it.status == SessionStatus.COMPLETED && !it.reviewed }
    if (unsaved != null) {
        SaveActivityScreen(unsaved)
        return
    }
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { BottomBar(tab) { tab = it } },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(320, delayMillis = 60)) + slideInHorizontally(tween(420, easing = Emphasized)) { it / 6 * dir })
                        .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(320, easing = Emphasized)) { -it / 10 * dir })
                },
                label = "tabs",
            ) { t ->
                CompositionLocalProvider(LocalAppearOrder provides remember(t) { AppearOrder() }) {
                    when (t) {
                        Tab.MY_DAY -> MyDayScreen(modifier, onOpenActivity = openActivity, onStartRun = { tab = Tab.RUN })
                        Tab.ACTIVITIES -> ActivitiesScreen(modifier, openId = openActivityId, onOpened = { openActivityId = null })
                        Tab.RUN -> SessionScreen(modifier, onPickDevice = { tab = Tab.DEVICE }, onOpenSettings = { tab = Tab.DEVICE })
                        Tab.TRAINING -> TrainingScreen(modifier, onOpenActivity = openActivity)
                        Tab.DEVICE -> DeviceScreen(modifier)
                    }
                }
            }
        }
    }
}

/** Floating pill bar; a highlight slides to the selected tab and its icon pops. */
@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val index by animateFloatAsState(
        selected.ordinal.toFloat(), spring(dampingRatio = 0.72f, stiffness = 420f), label = "indicator",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .height(68.dp)
            .clip(shape)
            .background(Palette.surface.copy(alpha = 0.96f))
            .border(1.dp, Palette.hairline, shape),
    ) {
        Canvas(Modifier.matchParentSize().padding(6.dp)) {
            val slot = size.width / Tab.entries.size
            drawRoundRect(
                Palette.accent.copy(alpha = 0.16f),
                Offset(slot * index, 0f), Size(slot, size.height), CornerRadius(size.height / 2),
            )
        }
        Row(Modifier.matchParentSize()) {
            Tab.entries.forEach { t ->
                val on = t == selected
                val tint by animateColorAsState(if (on) Palette.accent else Palette.muted, tween(250), label = "tint")
                val pop by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
                val source = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(source, 0.9f)
                        .clickable(interactionSource = source, indication = null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painterResource(t.icon), contentDescription = t.label, tint = tint,
                        modifier = Modifier.size(22.dp).graphicsLayer { scaleX = pop; scaleY = pop },
                    )
                    Text(
                        t.label, color = tint, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
