package com.rameez.fit3zone.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.rameez.fit3zone.core.GraphRouter
import com.rameez.fit3zone.core.MatchedPoint
import com.rameez.fit3zone.core.StrokeMatcher
import com.rameez.fit3zone.core.OsmData
import com.rameez.fit3zone.core.Overpass
import com.rameez.fit3zone.core.PieceKind
import com.rameez.fit3zone.core.RouteDraft
import com.rameez.fit3zone.core.RouteGraph
import com.rameez.fit3zone.core.RoutePoint
import com.rameez.fit3zone.core.RouteTracker
import com.rameez.fit3zone.core.WaypointState
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.OsmArea
import com.rameez.fit3zone.data.RouteSource
import com.rameez.fit3zone.data.RouteStore
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2

/**
 * The route being drawn, kept for the whole app process (like [LoopPlanning]) so leaving the screen keeps the draft and
 * the downloaded paths. Edits run one at a time off the main thread; map data is fetched with [OsmArea] around taps
 * that fall outside what's loaded, and pieces waiting for it snap when it arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object DrawRouting {
    /** Paths are downloaded this far around a tap (OsmArea adds its 25 % margin). */
    private const val AREA_M = 2_500.0
    /** A tap this close to the edge of the loaded circle fetches the next area (paths beyond the edge are missing). */
    private const val EDGE_M = 300.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private val _draft = MutableStateFlow(RouteDraft())
    val draft: StateFlow<RouteDraft> = _draft.asStateFlow()
    private val _snap = MutableStateFlow(true)
    /** Snap new waypoints to paths (else straight lines, for unmapped tracks, beaches, parks). */
    val snap: StateFlow<Boolean> = _snap.asStateFlow()
    private val _loading = MutableStateFlow(0)
    /** Downloads running. */
    val loading: StateFlow<Int> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _matching = MutableStateFlow(false)
    /** A freehand line is being matched to paths. */
    val matching: StateFlow<Boolean> = _matching.asStateFlow()
    private val _freehand = MutableStateFlow(false)
    /** Draw mode: the finger draws a line on the map (instead of tapping waypoints and moving the map). */
    val freehand: StateFlow<Boolean> = _freehand.asStateFlow()

    /** Where the map was, so coming back shows the same place. */
    var camera: CameraPosition? = null

    // Touched only on [worker].
    private var areas = listOf<OsmArea.Area>()
    private var graph: RouteGraph? = null
    /** Running downloads by centre; each completes when its area is merged in (or failed). */
    private val downloading = HashMap<Pair<Double, Double>, CompletableDeferred<Unit>>()
    @Volatile private var preferParks = true
    @Volatile private var appContext: Context? = null

    fun begin(context: Context) {
        appContext = context.applicationContext
        preferParks = SettingsStore.get(context).loopPreferParks.value
    }

    fun setSnap(on: Boolean) { _snap.value = on }
    fun setFreehand(on: Boolean) { _freehand.value = on }

    private fun router() = GraphRouter(graph, preferParks) { lat, lon -> areas.any { it.covers(lat, lon, EDGE_M) } }

    private fun edit(op: (RouteDraft, GraphRouter) -> RouteDraft) {
        scope.launch(worker) {
            val d = op(_draft.value, router())
            _draft.value = d
            fetchPending(d)
        }
    }

    fun tap(at: LatLng) { val s = _snap.value; edit { d, r -> d.add(RoutePoint(at.latitude, at.longitude), s, r) } }
    fun move(i: Int, to: LatLng) = edit { d, r -> if (i < d.waypoints.size) d.move(i, RoutePoint(to.latitude, to.longitude), r) else d }
    fun remove(i: Int) = edit { d, r -> if (i < d.waypoints.size) d.remove(i, r) else d }
    fun undo() = edit { d, r -> d.undo().refresh(r) }
    fun closeLoop() = edit { d, r -> d.closeLoop(r) }
    fun outAndBack() = edit { d, _ -> d.outAndBack() }
    fun retry() { _error.value = null; edit { d, r -> d.refresh(r) } }

    /** Loads the paths around [lat], [lon] ahead of the first tap (where the map opens). */
    fun prefetch(lat: Double, lon: Double) { scope.launch(worker) { fetch(lat, lon) } }

    /** Starts over; the downloaded paths stay for the next draft. */
    fun clear() {
        scope.launch(worker) {
            _draft.value = RouteDraft()
            _error.value = null
        }
    }

    private fun fetchPending(d: RouteDraft) {
        for (w in d.waypoints) if (w.state == WaypointState.PENDING) fetch(w.tapped.lat, w.tapped.lon)
    }

    /**
     * On [worker]: starts a download unless the place is loaded or a running download will cover it. Returns what to
     * wait for (null = already loaded).
     */
    private fun fetch(lat: Double, lon: Double): Deferred<Unit>? {
        if (areas.any { it.covers(lat, lon, EDGE_M) }) return null
        downloading.entries.firstOrNull { (c, _) -> OsmArea.Area(c.first, c.second, AREA_M, EMPTY).covers(lat, lon, EDGE_M) }?.let { return it.value }
        val context = appContext ?: return null
        val done = CompletableDeferred<Unit>()
        downloading[lat to lon] = done
        _loading.value = downloading.size
        scope.launch {
            val began = System.currentTimeMillis()
            val area = try {
                OsmArea(context).loadArea(lat, lon, AREA_M) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HeartRateRepository.log.add("draw", "map data failed: $e")
                _error.value = (e as? OsmArea.BusyException)?.message ?: "Couldn't load the paths here: ${e.message ?: e.javaClass.simpleName}"
                null
            }
            withContext(worker) {
                downloading.remove(lat to lon)
                _loading.value = downloading.size
                done.complete(Unit)
                if (area != null) {
                    areas = areas + area
                    val t0 = System.currentTimeMillis()
                    graph = try {
                        RouteGraph.build(OsmData.merge(areas.map { it.data }))
                    } catch (e: OutOfMemoryError) {
                        // Too many areas for the phone: keep only the newest.
                        areas = listOf(area)
                        RouteGraph.build(area.data)
                    }
                    HeartRateRepository.log.add("draw", "area ${areas.size} loaded in ${(t0 - began) / 1000} s, graph ${graph?.nodeCount} nodes " +
                        "/ ${graph?.edgeCount} edges in ${System.currentTimeMillis() - t0} ms")
                    _error.value = null
                    val d = _draft.value.refresh(router())
                    _draft.value = d
                    fetchPending(d) // taps further out than this area
                }
            }
        }
        return done
    }

    /**
     * A line drawn freehand (Draw mode): loads the paths along it, matches it to them ([StrokeMatcher]) and adds the
     * result as one edit. [toleranceM] ≈ a finger's width on screen at the zoom it was drawn.
     */
    fun stroke(line: List<LatLng>, toleranceM: Double) {
        if (line.size < 2) return
        val pts = line.map { RoutePoint(it.latitude, it.longitude) }
        scope.launch {
            _matching.value = true
            try {
                // Every place the line passes needs its paths first (an area is ~5 km across).
                for (s in StrokeMatcher.resample(pts, 500.0)) withContext(worker) { fetch(s.lat, s.lon) }?.await()
                withContext(worker) {
                    val t0 = System.currentTimeMillis()
                    val g = graph
                    val matched = if (g == null) pts.map { MatchedPoint(it, false) }
                    else StrokeMatcher.match(g, pts, toleranceM, preferParks)
                    val d = _draft.value.addMatched(matched, router())
                    _draft.value = d
                    HeartRateRepository.log.add("draw", "stroke ${pts.size} points (tolerance ${toleranceM.toInt()} m) → ${matched.size} waypoints, " +
                        "${matched.count { !it.onPath }} off paths, ${d.distanceM.toInt()} m total in ${System.currentTimeMillis() - t0} ms")
                }
            } finally {
                _matching.value = false
            }
        }
    }

    /** Saves the draft as a route (source DRAWN) and returns its id. */
    suspend fun save(context: Context, name: String): Long {
        val route = _draft.value.toRoute(name).simplified()
        val id = withContext(SessionHistory.dispatcher) { RouteStore.get(context).save(route, RouteSource.DRAWN) }
        HeartRateRepository.log.add("draw", "saved ${route.name} (${route.distanceM.toInt()} m, ${_draft.value.waypoints.size} waypoints) as route $id")
        // Done drawing: free the paths' memory (they stay cached on disk).
        withContext(worker) {
            _draft.value = RouteDraft()
            _error.value = null
            if (downloading.isEmpty()) { areas = emptyList(); graph = null }
        }
        return id
    }

    private val EMPTY = OsmData(emptyList(), emptyList())
}

/**
 * Draw a route on the map: tap waypoints (each piece follows real paths from the last one, or a straight line when
 * snapping is off), or switch to Draw and sketch it with one finger (two fingers move the map) — the line is
 * matched to paths. Waypoints can be
 * dragged (long-press) or tapped to delete. Saving stores a normal route.
 */
@Composable
fun DrawRouteScreen(modifier: Modifier, onSaved: (id: Long, use: Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    DrawRouting.begin(context) // cheap, idempotent
    val draft by DrawRouting.draft.collectAsStateWithLifecycle()
    val snap by DrawRouting.snap.collectAsStateWithLifecycle()
    val loading by DrawRouting.loading.collectAsStateWithLifecycle()
    val error by DrawRouting.error.collectAsStateWithLifecycle()
    val matching by DrawRouting.matching.collectAsStateWithLifecycle()
    val freehand by DrawRouting.freehand.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf(hasLocation(context)) }
    val camera = rememberCameraPositionState { position = DrawRouting.camera ?: CameraPosition.fromLatLngZoom(LatLng(20.5, 78.9), 4f) }

    // First open: centre on the phone's position and start loading the paths there.
    val askLocation = rememberLocationAsk { granted -> located = granted }
    LaunchedEffect(Unit) { if (!located) askLocation() }
    LaunchedEffect(located) {
        if (!located || DrawRouting.camera != null || !draft.isEmpty) return@LaunchedEffect
        val here = currentLocation(context.applicationContext) ?: return@LaunchedEffect
        camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(here.latitude, here.longitude), 15.5f))
        DrawRouting.prefetch(here.latitude, here.longitude)
    }
    LaunchedEffect(Unit) { snapshotFlow { camera.position }.collect { DrawRouting.camera = it } }
    // Hint after a tap that couldn't snap.
    LaunchedEffect(Unit) {
        DrawRouting.draft.drop(1).collect { d ->
            val last = d.waypoints.lastOrNull()
            hint = if (last?.state == WaypointState.NO_PATH) "No path here, drawn straight" else null
        }
    }
    if (selected != null && selected!! >= draft.waypoints.size) selected = null

    if (!hasMapsKey(context)) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextAction("‹ Routes", onBack)
            Text("Drawing a route needs Google Maps, which isn't set up in this build.", color = ZoneColors.muted)
        }
        return
    }

    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = rememberMapProperties().copy(isMyLocationEnabled = located),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false, myLocationButtonEnabled = located,
                rotationGesturesEnabled = false, tiltGesturesEnabled = false,
            ),
            contentPadding = PaddingValues(top = 64.dp, bottom = 190.dp),
            onMapClick = { at ->
                selected = null
                if (camera.position.zoom < MIN_DRAW_ZOOM) hint = "Zoom in to draw" else DrawRouting.tap(at)
            },
        ) {
            draft.pieces.forEach { piece ->
                val line = piece.points.map { LatLng(it.lat, it.lon) }
                if (line.size < 2) return@forEach
                when (piece.kind) {
                    PieceKind.SNAPPED -> {
                        Polyline(points = line, color = Palette.accent2.copy(alpha = 0.30f), width = 24f)
                        Polyline(points = line, color = Palette.accent2, width = 10f)
                    }
                    PieceKind.STRAIGHT -> Polyline(points = line, color = Palette.accent2, width = 7f)
                    PieceKind.PENDING -> Polyline(points = line, color = Palette.accent2.copy(alpha = 0.8f), width = 7f, pattern = listOf(Dash(24f), Gap(16f)))
                }
            }
            val startIcon = remember { dotIcon(ZoneColors.inZone, with(density) { 22.dp.toPx() }) }
            val endIcon = remember { dotIcon(ZoneColors.above, with(density) { 22.dp.toPx() }) }
            val midIcon = remember { dotIcon(Palette.accent2, with(density) { 16.dp.toPx() }) }
            val pickedIcon = remember { dotIcon(Color.White, with(density) { 24.dp.toPx() }) }
            val closed = draft.isClosed
            draft.waypoints.forEachIndexed { i, w ->
                // The finish of a closed loop sits on the start: draw only the start.
                if (closed && i == draft.waypoints.size - 1) return@forEachIndexed
                key(i, w.point) {
                    val state = remember { MarkerState(LatLng(w.point.lat, w.point.lon)) }
                    LaunchedEffect(state) {
                        snapshotFlow { state.isDragging }.drop(1).collect { dragging -> if (!dragging) DrawRouting.move(i, state.position) }
                    }
                    Marker(
                        state = state,
                        icon = when {
                            selected == i -> pickedIcon
                            i == 0 -> startIcon
                            i == draft.waypoints.size - 1 -> endIcon
                            else -> midIcon
                        },
                        anchor = Offset(0.5f, 0.5f),
                        draggable = true,
                        zIndex = if (i == 0) 2f else 1f,
                        onClick = { selected = if (selected == i) null else i; true },
                    )
                }
            }
        }

        // Draw mode: the finger draws on top of the map; the line is matched to paths on release.
        if (freehand) FreehandLayer(
            enabled = !matching,
            onTooShort = { hint = "Draw a line with your finger" },
            onLine = { screen ->
                val proj = camera.projection ?: return@FreehandLayer
                if (camera.position.zoom < MIN_DRAW_ZOOM) { hint = "Zoom in to draw"; return@FreehandLayer }
                val line = screen.map { proj.fromScreenLocation(android.graphics.Point(it.x.toInt(), it.y.toInt())) }
                // How far a finger's width (~24 dp) is on the ground at this zoom.
                val a = proj.fromScreenLocation(android.graphics.Point(0, 0))
                val b = proj.fromScreenLocation(android.graphics.Point(with(density) { 24.dp.roundToPx() }, 0))
                val tol = RouteTracker.haversineM(a.latitude, a.longitude, b.latitude, b.longitude).coerceIn(10.0, 150.0)
                hint = null
                DrawRouting.stroke(line, tol)
            },
            onTransform = { pan, zoom, focus ->
                camera.move(CameraUpdateFactory.scrollBy(-pan.x, -pan.y))
                if (zoom != 1f) camera.move(CameraUpdateFactory.zoomBy(log2(zoom), android.graphics.Point(focus.x.toInt(), focus.y.toInt())))
            },
        )

        // Top: back + distance.
        Row(
            Modifier.fillMaxWidth().padding(12.dp).background(Palette.surface.copy(alpha = 0.94f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction("‹ Routes", onBack)
            Text("Draw a route", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(formatKm(draft.distanceM), fontFamily = Numeric, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.accent2, modifier = Modifier.padding(end = 12.dp))
        }

        // Bottom: status, tools, save.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                .background(Palette.surface.copy(alpha = 0.96f), RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val status = when {
                error != null -> error
                matching && loading > 0 -> "Loading paths along your line…"
                matching -> "Fitting your line to paths…"
                loading > 0 -> "Loading paths…"
                hint != null -> hint
                freehand -> if (draft.isEmpty) "Draw your route with one finger; it snaps to paths. Two fingers move and zoom the map."
                else "Draw more to carry on from the end. Two fingers move the map."
                draft.isEmpty -> "Tap the map to set the start, or switch to Draw."
                draft.waypoints.size == 1 -> "Tap where to go next."
                else -> null
            }
            if (status != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, color = if (error != null) ZoneColors.above else ZoneColors.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (error != null) TextAction("Retry", DrawRouting::retry)
            }
            val sel = selected
            if (sel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (sel == 0) "Start" else if (sel == draft.waypoints.size - 1) "Finish" else "Waypoint ${sel + 1}",
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    TextAction("Delete", { DrawRouting.remove(sel); selected = null }, color = ZoneColors.above)
                    TextAction("Close", { selected = null }, color = ZoneColors.muted)
                }
                Text("Long-press a waypoint and drag to move it.", color = Palette.faint, fontSize = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillChip("Undo", selected = false, color = Palette.accent2, enabled = draft.canUndo, compact = true) { DrawRouting.undo() }
                    PillChip("Back to start", selected = false, color = Palette.accent2, enabled = draft.waypoints.size >= 2 && !draft.isClosed, compact = true) { DrawRouting.closeLoop() }
                    PillChip("Out & back", selected = false, color = Palette.accent2, enabled = draft.waypoints.size >= 2 && !draft.isClosed, compact = true) { DrawRouting.outAndBack() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillChip("Tap", selected = !freehand, color = Palette.accent, compact = true) { DrawRouting.setFreehand(false) }
                    PillChip("Draw", selected = freehand, color = Palette.accent, compact = true) { DrawRouting.setFreehand(true); selected = null }
                    if (!freehand) PillChip(if (snap) "Snap to paths" else "Straight lines", selected = !snap, color = Palette.accent2, compact = true) { DrawRouting.setSnap(!snap) }
                    Box(Modifier.weight(1f))
                    TextAction(
                        if (confirmClear) "Tap to clear" else "Clear",
                        { if (confirmClear) { DrawRouting.clear(); confirmClear = false } else confirmClear = true },
                        color = if (confirmClear) ZoneColors.above else ZoneColors.muted,
                        enabled = !draft.isEmpty,
                    )
                }
                GradientButton("Save route", { saving = true }, enabled = draft.waypoints.size >= 2 && loading == 0 && !matching, height = 52.dp)
            }
            Text(Overpass.ATTRIBUTION, color = Palette.faint, fontSize = 11.sp)
        }
    }

    if (saving) SaveRouteDialog(
        draft.defaultName(),
        pending = draft.hasPending,
        onSave = { name, use ->
            DrawRouting.save(context, name).also { saving = false; onSaved(it, use) }
        },
        onDismiss = { saving = false },
    )
}

private const val MIN_DRAW_ZOOM = 13f

@Composable
private fun SaveRouteDialog(defaultName: String, pending: Boolean, onSave: suspend (name: String, use: Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlowCard(accent = Palette.accent2, contentPadding = 16.dp) {
            CardLabel("Save route", Palette.accent2)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(80) }, label = { Text("Name") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            if (pending) Text("Some pieces are still straight lines (paths not loaded).", color = ZoneColors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Cancel", onDismiss, color = ZoneColors.muted, enabled = !busy)
                TextAction("Save", {
                    busy = true
                    scope.launch { onSave(name.trim().ifEmpty { defaultName }, false) }
                }, enabled = !busy)
                TextAction(if (busy) "Saving…" else "Save and use", {
                    busy = true
                    scope.launch { onSave(name.trim().ifEmpty { defaultName }, true) }
                }, enabled = !busy)
            }
            Text("\"Save and use\" follows it on your next GPS run.", color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A filled circle with a white ring, as a marker icon (only valid once the map has initialised Maps). */
private fun dotIcon(color: Color, sizePx: Float): BitmapDescriptor {
    val size = sizePx.toInt().coerceAtLeast(8)
    val bmp = createBitmap(size, size)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color.toArgb()
    c.drawCircle(size / 2f, size / 2f, size / 2f - size * 0.16f, p)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

/**
 * Over the map in Draw mode: one finger draws a line ([onLine] gets it in screen pixels when the finger lifts); two
 * fingers move and zoom the map ([onTransform]: pan in px, zoom factor, focus point) — putting a second finger down
 * mid-line drops that line. Drawing is ignored while not [enabled] (a line is being matched); moving the map works.
 */
@Composable
private fun FreehandLayer(
    enabled: Boolean,
    onTooShort: () -> Unit,
    onLine: (List<Offset>) -> Unit,
    onTransform: (pan: Offset, zoom: Float, focus: Offset) -> Unit,
) {
    var line by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val canDraw by rememberUpdatedState(enabled)
    val color = Palette.accent
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var points = listOf(down.position)
                var drawing = canDraw
                var moved = false
                var lastCount = 1
                var lastCentroid = down.position
                var lastSpan = 0f
                if (drawing) line = points
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2 && drawing) { drawing = false; line = emptyList() }
                    if (!drawing && (pressed.size >= 2 || moved || !canDraw)) {
                        // Move / zoom the map with however many fingers are down (re-anchor when that changes).
                        val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.size.toFloat()
                        val span = if (pressed.size >= 2) pressed.map { (it.position - centroid).getDistance() }.average().toFloat() else 0f
                        if (pressed.size == lastCount) {
                            val zoom = if (pressed.size >= 2 && lastSpan > 0f && span > 0f) span / lastSpan else 1f
                            onTransform(centroid - lastCentroid, zoom, centroid)
                        }
                        moved = true
                        lastCount = pressed.size
                        lastCentroid = centroid
                        lastSpan = span
                    } else if (drawing) {
                        val p = pressed[0].position
                        // A point every ~3 px is plenty and keeps long lines light.
                        if ((p - points.last()).getDistance() >= 3f) { points = points + p; line = points }
                    }
                    event.changes.forEach { it.consume() }
                }
                if (drawing) {
                    line = emptyList()
                    if (points.size >= 2) onLine(points) else onTooShort()
                }
            }
        },
    ) {
        if (line.size >= 2) {
            val path = Path().apply { moveTo(line[0].x, line[0].y); for (k in 1 until line.size) lineTo(line[k].x, line[k].y) }
            drawPath(path, color.copy(alpha = 0.35f), style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
