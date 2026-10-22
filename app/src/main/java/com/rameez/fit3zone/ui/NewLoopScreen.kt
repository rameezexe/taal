package com.rameez.fit3zone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.rameez.fit3zone.core.Loop
import com.rameez.fit3zone.core.LoopPlanner
import com.rameez.fit3zone.core.LoopRequest
import com.rameez.fit3zone.core.Overpass
import com.rameez.fit3zone.core.RouteGraph
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.OsmArea
import com.rameez.fit3zone.data.RouteSource
import com.rameez.fit3zone.data.RouteStore
import com.rameez.fit3zone.data.SessionHistory
import com.rameez.fit3zone.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private val LOOP_KM = listOf(3.0, 5.0, 8.0, 10.0, 12.0, 15.0, 21.1, 23.0)

internal sealed interface LoopPhase {
    data object Idle : LoopPhase
    data class Working(val status: String, val sinceMs: Long) : LoopPhase
    data class Done(val loops: List<Loop>, val targetM: Double) : LoopPhase
    data class Failed(val message: String) : LoopPhase
}

/**
 * The loop being planned, kept for the whole app process so leaving the screen (e.g. while a busy map server takes
 * a minute) doesn't throw the work away; coming back shows the progress or the loops.
 */
internal object LoopPlanning {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _phase = MutableStateFlow<LoopPhase>(LoopPhase.Idle)
    val phase: StateFlow<LoopPhase> = _phase.asStateFlow()

    fun cancel() { job?.cancel(); job = null; _phase.value = LoopPhase.Idle }
    fun clear() { if (_phase.value !is LoopPhase.Working) _phase.value = LoopPhase.Idle }
    fun fail(message: String) { _phase.value = LoopPhase.Failed(message) }

    fun start(appContext: Context, targetM: Int, preferParks: Boolean) {
        job?.cancel()
        val began = System.currentTimeMillis()
        fun status(text: String) { _phase.value = LoopPhase.Working(text, began) }
        job = scope.launch {
            status("Finding your location…")
            val here = currentLocation(appContext)
            if (here == null) {
                fail("Couldn't get your location. Turn on Location (GPS) and try again outdoors or by a window.")
                return@launch
            }
            try {
                val radius = Overpass.radiusFor(targetM.toDouble())
                val data = OsmArea(appContext).load(here.latitude, here.longitude, radius) { scope.launch { if (job?.isActive == true) status(it) } }
                status("Planning loops…")
                val started = System.currentTimeMillis()
                val loops = withContext(Dispatchers.Default) {
                    val graph = RouteGraph.build(data)
                    val built = System.currentTimeMillis()
                    LoopPlanner.plan(graph, LoopRequest(here.latitude, here.longitude, targetM.toDouble(), preferParks)).also {
                        HeartRateRepository.log.add("loop", "graph ${graph.nodeCount} nodes / ${graph.edgeCount} edges in ${built - started} ms, " +
                            "planned ${it.size} loops for ${targetM} m in ${System.currentTimeMillis() - built} ms: " +
                            it.joinToString { l -> "${l.lengthM.toInt()} m" } + " (total ${(System.currentTimeMillis() - began) / 1000} s)")
                    }
                }
                _phase.value = if (loops.isEmpty()) LoopPhase.Failed("No loop found here. Try another length, or start closer to streets or paths.")
                else LoopPhase.Done(loops, targetM.toDouble())
            } catch (e: CancellationException) {
                throw e
            } catch (e: OsmArea.BusyException) {
                fail(e.message ?: "The map data service is busy. Try again in a few minutes.")
            } catch (e: OutOfMemoryError) {
                HeartRateRepository.log.add("loop", "out of memory for ${targetM} m")
                fail("This area is too big for the phone's memory. Try a shorter loop.")
            } catch (e: Exception) {
                HeartRateRepository.log.add("loop", "failed: $e")
                fail("Couldn't plan a loop: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

/**
 * Plans running loops from where the phone is: downloads the paths around it from OpenStreetMap (cached) and plans
 * them on the phone with [LoopPlanner]. Saving stores the loop as a route (source LOOP) and selects it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewLoopScreen(modifier: Modifier, onSaved: (id: Long, use: Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val savedTarget by settings.loopTargetM.collectAsStateWithLifecycle()
    val savedParks by settings.loopPreferParks.collectAsStateWithLifecycle()
    var targetM by remember { mutableIntStateOf(savedTarget) }
    var preferParks by remember { mutableStateOf(savedParks) }
    var custom by remember { mutableStateOf(if (LOOP_KM.any { (it * 1000).toInt() == savedTarget }) "" else km(savedTarget.toDouble())) }
    val phase by LoopPlanning.phase.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    fun generate() {
        settings.saveLoopOptions(targetM, preferParks)
        LoopPlanning.start(context.applicationContext, targetM, preferParks)
    }

    val askLocation = rememberLocationAsk { granted ->
        if (granted) generate() else LoopPlanning.fail("Allow location so Taal can plan a loop from where you are.")
    }
    val working = phase is LoopPhase.Working
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextAction("‹ Routes", onBack)
        Text("New loop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A loop from where you are now, back to the same spot.", color = ZoneColors.muted)

        GlowCard(accent = Palette.accent2) {
            CardLabel("Distance", Palette.accent2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LOOP_KM.forEach { k ->
                    val m = (k * 1000).toInt()
                    PillChip(km(k * 1000), selected = custom.isEmpty() && targetM == m, color = Palette.accent2, enabled = !working) {
                        targetM = m; custom = ""
                    }
                }
            }
            OutlinedTextField(
                value = custom,
                onValueChange = { v ->
                    custom = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
                    custom.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..42.2 }?.let { targetM = (it * 1000).toInt() }
                },
                label = { Text("Other (km)") },
                singleLine = true,
                enabled = !working,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prefer parks and footpaths", fontWeight = FontWeight.SemiBold)
                    Text("Small detours to stay off the streets", color = ZoneColors.muted, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = preferParks, onCheckedChange = { preferParks = it }, enabled = !working)
            }
        }

        GradientButton(
            if (working) "Planning…" else "Plan loops · ${km(targetM.toDouble())}",
            { if (!working) askLocation() },
            enabled = !working,
            shimmer = working,
        )

        when (val p = phase) {
            is LoopPhase.Working -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(p.sinceMs) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                Text("${p.status} ${(now - p.sinceMs) / 1000} s", color = ZoneColors.muted)
                Text("You can leave this screen; the loops will be here when you come back.", color = Palette.faint, fontSize = 12.sp)
                TextAction("Cancel", LoopPlanning::cancel, color = ZoneColors.muted)
            }
            is LoopPhase.Failed -> Text(p.message, color = ZoneColors.above)
            is LoopPhase.Done -> p.loops.forEachIndexed { i, loop -> LoopResult(i, loop, p.targetM, onSaved) }
            LoopPhase.Idle -> Unit
        }

        Text(
            "Planning a loop sends the area around your start point (not your name or account) to the public " +
                "OpenStreetMap Overpass service. ${Overpass.ATTRIBUTION}.",
            color = Palette.faint, fontSize = 12.sp,
        )
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun LoopResult(index: Int, loop: Loop, targetM: Double, onSaved: (id: Long, use: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    GlowCard(accent = Palette.accent2) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loop ${index + 1}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(km(loop.lengthM), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Palette.accent2)
            }
            Text(loopFacts(loop, targetM), color = ZoneColors.muted, fontSize = 13.sp)
            PlannedRouteMap(loop.route)
            fun save(use: Boolean) {
                if (saving) return
                saving = true
                scope.launch {
                    val id = withContext(SessionHistory.dispatcher) { RouteStore.get(context).save(loop.route, RouteSource.LOOP) }
                    HeartRateRepository.log.add("loop", "saved ${loop.route.name} as route $id${if (use) ", selected" else ""}")
                    LoopPlanning.clear()
                    onSaved(id, use)
                }
            }
            GradientButton(if (saving) "Saving…" else "Save and use", { save(true) }, height = 52.dp)
            TextAction("Save for later", { save(false) }, color = ZoneColors.muted, enabled = !saving)
        }
    }
}

private fun loopFacts(loop: Loop, targetM: Double): String = buildList {
    val off = (loop.lengthM / targetM - 1) * 100
    if (kotlin.math.abs(off) >= 3) add(String.format(Locale.US, "%+.0f %% vs asked", off))
    add("${(loop.footShare * 100).toInt()} % footpaths & parks")
    if (loop.busyShare >= 0.05) add("${(loop.busyShare * 100).toInt()} % busy roads")
    if (loop.overlapShare >= 0.05) add("${(loop.overlapShare * 100).toInt()} % run twice")
}.joinToString(" · ")

private fun km(m: Double): String =
    if (m % 1000.0 == 0.0) "${(m / 1000).toInt()} km" else String.format(Locale.US, "%.1f km", m / 1000)

/** The phone's position now (high accuracy, ≤ 20 s), else its last known one; null without permission or a fix. */
@SuppressLint("MissingPermission")
internal suspend fun currentLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val cts = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { cts.cancel() }
        }
    }
    if (fresh != null) return fresh
    return suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}

/** Asks for location (needed to plan from here) when it isn't granted yet; [onResult] gets whether it is now. */
@Composable
fun rememberLocationAsk(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> onResult(r.values.any { it }) }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) onResult(true) else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
