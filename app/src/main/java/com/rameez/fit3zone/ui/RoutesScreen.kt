package com.rameez.fit3zone.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.Gpx
import com.rameez.fit3zone.core.Overpass
import com.rameez.fit3zone.core.Route
import com.rameez.fit3zone.data.RouteProgress
import com.rameez.fit3zone.data.RouteSource
import com.rameez.fit3zone.data.RouteStore
import com.rameez.fit3zone.data.SavedRoute
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.service.HeartRateForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saved routes: plan a loop, draw one or import a GPX file; pick one to follow on the next GPS run (or none), share
 * or delete. The chosen route stays chosen for later runs until changed — the Run tab always shows it.
 */
@Composable
fun RoutesScreen(modifier: Modifier, selectedId: Long?, onSelect: (Long?) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { RouteStore.get(context) }
    val routes by store.routes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var newLoop by remember { mutableStateOf(false) }
    var draw by remember { mutableStateOf(false) }
    /** A route just saved "for later": its card says so. */
    var savedNote by remember { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(SessionHistory.dispatcher) { runCatching { store.importGpx(uri) } }
            result.onSuccess { id -> onSelect(id); message = null }
                .onFailure { message = (it as? Gpx.FormatException)?.message ?: "Couldn't read this file" }
        }
    }
    if (newLoop) {
        NewLoopScreen(modifier, onSaved = { id, use -> newLoop = false; if (use) onSelect(id) else savedNote = id }, onBack = { newLoop = false })
        return
    }
    if (draw) {
        DrawRouteScreen(modifier, onSaved = { id, use -> draw = false; if (use) onSelect(id) else savedNote = id }, onBack = { draw = false })
        return
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextAction("‹ Run", onBack)
        Text("Routes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        val chosen = routes.firstOrNull { it.id == selectedId }
        if (chosen != null) {
            GlowCard(accent = ZoneColors.inZone, contentPadding = 14.dp) {
                CardLabel("Next GPS run follows", ZoneColors.inZone)
                Text(chosen.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(routeSummary(chosen.route), color = ZoneColors.muted, fontSize = 13.sp)
                GhostButton("Don't follow a route", { onSelect(null) }, Modifier.fillMaxWidth().padding(top = 8.dp), height = 48.dp)
            }
        }
        Text(
            "Follow a route on a GPS run: Taal buzzes and says \"Off route\" when you leave it. Plan a loop from where you " +
                "are, draw your own on the map, or import a GPX file from Strava, Komoot or any route planner (it stays on this phone).",
            color = ZoneColors.muted,
        )
        GradientButton("New loop", { newLoop = true })
        GhostButton("Draw a route", { draw = true }, Modifier.fillMaxWidth(), height = 56.dp)
        // GPX files come with many types depending on the app that saved them, so any file is allowed.
        GhostButton("Import GPX file", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), height = 56.dp)
        message?.let { Text(it, color = ZoneColors.above, fontSize = 14.sp) }
        if (routes.isEmpty()) {
            Text("No routes yet.", color = ZoneColors.muted, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = TextAlign.Center)
        } else {
            Text("Saved routes", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
        routes.forEach { saved ->
            val selected = saved.id == selectedId
            GlowCard(
                Modifier.let { if (selected) it.border(2.dp, ZoneColors.inZone, RoundedCornerShape(24.dp)) else it },
                accent = if (selected) ZoneColors.inZone else Palette.accent2,
                onClick = { onSelect(saved.id) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(saved.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (selected) Text("SELECTED", color = ZoneColors.inZone, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(routeSummary(saved.route), color = ZoneColors.muted, fontSize = 13.sp)
                    if (saved.id == savedNote && !selected) Text("Saved · tap Use to follow it on your next run", color = ZoneColors.inZone, fontSize = 13.sp)
                    RouteSketch(saved.route.asGeoPoints(), emptyList(), { Palette.accent2 }, Modifier.height(120.dp), animate = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selected) TextAction("Stop using", { onSelect(null) }, color = ZoneColors.muted)
                        else TextAction("Use", { onSelect(saved.id) })
                        TextAction("Share GPX", { shareRoute(context, saved) })
                        TextAction(
                            if (confirmDelete == saved.id) "Tap to delete" else "Delete",
                            {
                                if (confirmDelete == saved.id) {
                                    if (selected) onSelect(null)
                                    scope.launch(SessionHistory.dispatcher) { store.delete(saved.id) }
                                    confirmDelete = null
                                } else confirmDelete = saved.id
                            },
                            color = if (confirmDelete == saved.id) ZoneColors.above else ZoneColors.muted,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

/** Before a GPS run: the route to follow (or none). Tap → [RoutesScreen]; "No route" clears it right here. */
@Composable
fun RouteCard(route: SavedRoute?, onOpen: () -> Unit, onClear: () -> Unit) {
    GlowCard(accent = Palette.accent2, onClick = onOpen, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardLabel("Route", Palette.accent2)
            if (route == null) {
                Text("No route · tap to plan, draw or import one", color = ZoneColors.muted, fontSize = 14.sp)
            } else {
                Text(route.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(routeSummary(route.route), color = ZoneColors.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextAction("Change", onOpen)
                    TextAction("✕ No route", onClear, color = ZoneColors.muted)
                }
            }
        }
    }
}

/**
 * During a run: distance left, or how far off the route the runner is, with "Stop following" (two taps, like Stop)
 * for when the runner leaves the route on purpose: the off-route alerts stop, the run goes on.
 */
@Composable
fun RouteLine(p: RouteProgress) {
    val context = LocalContext.current
    val (text, color) = when {
        !p.following -> "Not following ${p.name}" to ZoneColors.muted
        p.complete -> "Route complete" to ZoneColors.inZone
        !p.started -> "Route: head to the start" + (p.awayM?.let { " · ${distanceText(it)} away" } ?: "") to ZoneColors.muted
        p.offRoute -> "OFF ROUTE" + (p.awayM?.let { " · ${distanceText(it)} away" } ?: "") to ZoneColors.above
        else -> "${p.name} · ${formatKm(p.remainingM)} left" to Palette.accent2
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text, color = color, fontWeight = if (p.offRoute && p.following) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2,
        )
        if (p.following && !p.complete) {
            var confirm by remember { mutableStateOf(false) }
            LaunchedEffect(confirm) { if (confirm) { delay(3_000); confirm = false } }
            TextAction(
                if (confirm) "Tap again to stop following" else "Stop following",
                { if (confirm) { HeartRateForegroundService.stopFollowingRoute(context); confirm = false } else confirm = true },
                color = if (confirm) ZoneColors.above else ZoneColors.muted,
            )
        }
    }
}

private fun distanceText(m: Double) = if (m < 1000) "${(m / 10).toInt() * 10} m" else formatKm(m)

fun routeSummary(r: Route): String = buildList {
    add(formatKm(r.distanceM))
    r.ascentM?.let { add("↑ ${it.toInt()} m") }
    if (r.isLoop) add("loop")
}.joinToString(" · ")

/** Route points as GeoPoints for [RouteSketch] (time = order). */
fun Route.asGeoPoints(): List<GeoPoint> = points.mapIndexed { i, p -> GeoPoint(i * 1000L, p.lat, p.lon, 5f, p.eleM) }

private fun shareRoute(context: Context, saved: SavedRoute) =
    shareGpx(context, Gpx.write(saved.route, Overpass.ATTRIBUTION.takeIf { saved.source != RouteSource.GPX }), saved.name, "Share route")

/**
 * Saves [xml] as `Download/Taal/<name>.gpx` (MediaStore, no permission needed; a clash gets " (1)" added by Android)
 * and returns the shown path, e.g. "Downloads/Taal/Easy run 2026-10-20.gpx".
 */
fun saveGpxToDownloads(context: Context, xml: String, name: String): String {
    val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "route" }.take(60)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, "$safe.gpx")
        put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Taal")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads isn't available")
    try {
        resolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) } ?: error("Couldn't open the file")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    val shown = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "$safe.gpx"
    return "Downloads/Taal/$shown"
}

/** Writes [xml] to the cache and opens the share sheet; only leaves the phone if the user picks a target. */
fun shareGpx(context: Context, xml: String, name: String, title: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
    val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "route" }.take(60)
    val file = File(dir, "$safe.gpx").apply { writeText(xml) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}
