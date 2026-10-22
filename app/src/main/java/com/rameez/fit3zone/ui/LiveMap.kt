package com.rameez.fit3zone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.rameez.fit3zone.core.drawablePieces
import com.google.maps.android.compose.rememberCameraPositionState
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.RoutePoint

/**
 * Route so far during a GPS session. Follows the runner by default; a tap switches to the whole
 * route and back. Gestures are off so the Run screen still scrolls over it. A [planned] route is drawn
 * underneath in violet.
 */
@Composable
fun LiveMapCard(points: List<GeoPoint>, mapHeight: Dp = 260.dp, planned: List<RoutePoint>? = null) {
    val context = LocalContext.current
    var follow by rememberSaveable { mutableStateOf(true) }
    GlowCard(accent = Palette.accent, onClick = { follow = !follow }, contentPadding = 14.dp) {
        CardLabel(if (follow) "Map · following" else "Map · whole route", Palette.accent)
        when {
            points.isEmpty() && planned.isNullOrEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("The map starts with the first good GPS fix.", color = ZoneColors.muted, fontSize = 13.sp)
            }
            hasMapsKey(context) -> LiveGoogleMap(points, follow, mapHeight, planned.orEmpty()) { follow = !follow }
            points.isEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("The map starts with the first good GPS fix.", color = ZoneColors.muted, fontSize = 13.sp)
            }
            else -> RouteSketch(points, emptyList(), { Palette.accent }, Modifier.height(mapHeight), animate = false)
        }
        Text(if (follow) "Tap to see the whole route" else "Tap to follow your position", color = ZoneColors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun LiveGoogleMap(points: List<GeoPoint>, follow: Boolean, height: Dp, planned: List<RoutePoint>, onTap: () -> Unit) {
    val camera = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    val line = remember(points) { points.map { LatLng(it.lat, it.lon) } }
    val pieces = remember(points) { points.drawablePieces().map { piece -> piece.map { LatLng(it.lat, it.lon) } } }
    val plannedLine = remember(planned) { planned.map { LatLng(it.lat, it.lon) } }
    // Before the first fix, show the planned route's start.
    val here = line.lastOrNull() ?: plannedLine.first()
    // Move the camera on each new fix (follow) or when the route grows outside the view (overview).
    LaunchedEffect(loaded, follow, line.size) {
        if (!loaded) return@LaunchedEffect
        runCatching {
            if (follow || line.size < 2) {
                camera.animate(CameraUpdateFactory.newLatLngZoom(here, 17f), 700)
            } else {
                val bounds = LatLngBounds.builder().apply { (line + plannedLine).forEach { include(it) } }.build()
                camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 90), 700)
            }
        }
    }
    GoogleMap(
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)),
        cameraPositionState = camera,
        properties = rememberMapProperties(),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false, myLocationButtonEnabled = false,
            scrollGesturesEnabled = false, zoomGesturesEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false,
        ),
        onMapLoaded = {
            camera.move(CameraUpdateFactory.newLatLngZoom(here, 17f))
            loaded = true
        },
        onMapClick = { onTap() },
    ) {
        if (plannedLine.size >= 2) {
            Polyline(points = plannedLine, color = Palette.accent2.copy(alpha = 0.30f), width = 30f)
            Polyline(points = plannedLine, color = Palette.accent2.copy(alpha = 0.85f), width = 8f)
        }
        // GPS gaps (no fix for 20 s+) are faint dashes, not part of the route line.
        pieces.zipWithNext { a, b -> Polyline(points = listOf(a.last(), b.first()), color = Palette.faint, width = 6f, pattern = listOf(Dash(18f), Gap(14f))) }
        pieces.filter { it.size >= 2 }.forEach { piece ->
            Polyline(points = piece, color = Palette.accent.copy(alpha = 0.35f), width = 26f)
            Polyline(points = piece, color = Palette.accent, width = 12f)
        }
        line.firstOrNull()?.let { Circle(center = it, radius = 5.0, fillColor = ZoneColors.inZone, strokeColor = Color.White, strokeWidth = 4f) }
        if (line.isNotEmpty()) Circle(center = here, radius = 7.0, fillColor = Palette.heart, strokeColor = Color.White, strokeWidth = 6f)
    }
}

/** A planned route on its own (New loop results): the whole line in view, start marked; a sketch without a Maps key. */
@Composable
fun PlannedRouteMap(route: com.rameez.fit3zone.core.Route, height: Dp = 240.dp) {
    val context = LocalContext.current
    if (!hasMapsKey(context)) {
        RouteSketch(route.asGeoPoints(), emptyList(), { Palette.accent2 }, Modifier.height(height), animate = false)
        return
    }
    val camera = rememberCameraPositionState()
    val line = remember(route) { route.points.map { LatLng(it.lat, it.lon) } }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(loaded, line) {
        if (!loaded || line.size < 2) return@LaunchedEffect
        runCatching {
            val bounds = LatLngBounds.builder().apply { line.forEach { include(it) } }.build()
            camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        }
    }
    GoogleMap(
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)),
        cameraPositionState = camera,
        properties = rememberMapProperties(),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false, myLocationButtonEnabled = false,
            scrollGesturesEnabled = false, zoomGesturesEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false,
        ),
        onMapLoaded = { loaded = true },
    ) {
        if (line.size >= 2) {
            Polyline(points = line, color = Palette.accent2.copy(alpha = 0.30f), width = 26f)
            Polyline(points = line, color = Palette.accent2, width = 9f)
            Circle(center = line.first(), radius = 8.0, fillColor = ZoneColors.inZone, strokeColor = Color.White, strokeWidth = 5f)
        }
    }
}
