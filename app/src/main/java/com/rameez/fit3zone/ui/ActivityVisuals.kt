package com.rameez.fit3zone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.rameez.fit3zone.R
import com.rameez.fit3zone.core.Achievement
import com.rameez.fit3zone.core.AchievementKind
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.drawablePieces
import com.rameez.fit3zone.core.formatRaceTime
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SessionSummary
import kotlinx.coroutines.withContext

/** Warm dark map tiles that match the Kesar palette. */
@Composable
fun rememberMapProperties(): MapProperties {
    val context = LocalContext.current
    return remember { MapProperties(mapStyleOptions = MapStyleOptions.loadRawResourceStyle(context, R.raw.map_kesar)) }
}

/** The activity's route as a static (lite) map for lists and the save screen; a sketch without a Maps key. */
@Composable
fun ActivityMapThumb(s: SessionSummary, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val points by produceState<List<GeoPoint>?>(null, s.id, s.distanceM) {
        value = withContext(SessionHistory.dispatcher) { SessionHistory.get(context).points(s.id) }
    }
    val p = points ?: return Box(modifier.fillMaxWidth().height(height).background(Palette.surfaceHigh))
    if (p.size < 2) return
    if (!hasMapsKey(context)) {
        RouteSketch(p, emptyList(), { Palette.accent }, modifier.height(height), animate = false, breaksMs = s.segmentBreaksMs)
        return
    }
    val pieces = remember(p) { p.drawablePieces(s.segmentBreaksMs).map { piece -> piece.map { LatLng(it.lat, it.lon) } } }
    val camera = rememberCameraPositionState()
    var placed by remember { mutableStateOf(false) }
    GoogleMap(
        modifier = modifier.fillMaxWidth().height(height),
        cameraPositionState = camera,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
        properties = rememberMapProperties(),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false, myLocationButtonEnabled = false,
            scrollGesturesEnabled = false, zoomGesturesEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false,
        ),
        onMapLoaded = {
            if (!placed) runCatching {
                val bounds = LatLngBounds.builder().apply { p.forEach { include(LatLng(it.lat, it.lon)) } }.build()
                camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 60))
                placed = true
            }
        },
    ) {
        pieces.filter { it.size >= 2 }.forEach { Polyline(points = it, color = Palette.accent, width = 10f) }
        Circle(center = LatLng(p.first().lat, p.first().lon), radius = 6.0, fillColor = ZoneColors.inZone, strokeColor = Palette.bg, strokeWidth = 3f)
    }
}

/** One line under a card: the best badge and how many there are. */
@Composable
fun AchievementLine(list: List<Achievement>, modifier: Modifier = Modifier) {
    val first = list.firstOrNull() ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, tint = Palette.accent2, modifier = Modifier.size(18.dp))
        Text(first.label, color = Palette.accent2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (list.size > 1) Text("· ${list.size} achievements", color = Palette.muted, fontSize = 14.sp)
    }
}

/** Badges listed with their times, for the detail and save screens. */
@Composable
fun AchievementList(list: List<Achievement>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val tint = if (a.isRecord) Palette.accent2 else Palette.muted
                Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    achievementDetail(a)?.let { Text(it, color = Palette.muted, fontSize = 13.sp) }
                }
            }
        }
    }
}

private fun achievementDetail(a: Achievement): String? = when (a.kind) {
    AchievementKind.EFFORT -> a.ms?.let { ms ->
        formatRaceTime(ms / 1000.0) + (a.bestMs?.let { " · best is ${formatRaceTime(it / 1000.0)}" } ?: " · personal record")
    }
    AchievementKind.LONGEST -> a.distanceM?.let { formatKm(it) }
}

/** Big number with a small unit and a grey label under it, Strava-style. */
@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier, unit: String? = null, color: Color = Palette.text) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = Numeric, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = color, maxLines = 1)
            unit?.let { Text(" $it", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        Text(label, color = Palette.muted, fontSize = 12.sp)
    }
}
