package com.rameez.fit3zone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.PlannedStep
import com.rameez.fit3zone.core.StepEnd
import com.rameez.fit3zone.core.StepKind
import com.rameez.fit3zone.core.TargetChoice
import com.rameez.fit3zone.core.Workout
import com.rameez.fit3zone.core.WorkoutBlock
import com.rameez.fit3zone.core.WorkoutStep
import com.rameez.fit3zone.core.formatClockDuration
import com.rameez.fit3zone.data.SavedWorkout
import com.rameez.fit3zone.data.WorkoutStore
import java.time.LocalDate

/** Workout library: pick one for the next run, or create / edit / duplicate / delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutsScreen(
    modifier: Modifier,
    selectedId: String?,
    zones: HrZones?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { WorkoutStore.get(context) }
    val workouts by store.workouts.collectAsStateWithLifecycle()
    val schedule by store.schedule.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var scheduling by remember { mutableStateOf<SavedWorkout?>(null) }
    var editing by remember { mutableStateOf<SavedWorkout?>(null) }
    var creating by remember { mutableStateOf(false) }

    if (editing != null || creating) {
        val original = editing
        BackHandler { editing = null; creating = false }
        WorkoutEditor(
            modifier, original?.workout, zones,
            onSave = { w -> store.save(w, original?.id); editing = null; creating = false },
            onCancel = { editing = null; creating = false },
        )
        return
    }
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    scheduling?.let { sw ->
        ScheduleDialog(
            sw.id, sw.workout.name, schedule, nameOf = { id -> store.get(id)?.workout?.name }, today = today,
            onPlan = { store.plan(it, sw.id) }, onUnplan = { store.unplan(it) }, onDismiss = { scheduling = null },
        )
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextAction("‹ Run", onBack)
        Text("Workouts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Tap a workout to use it for your next run.", color = ZoneColors.muted)
        workouts.forEach { saved ->
            val w = saved.workout
            val selected = saved.id == selectedId
            GlowCard(
                Modifier.let { if (selected) it.border(2.dp, ZoneColors.inZone, RoundedCornerShape(24.dp)) else it },
                accent = if (selected) ZoneColors.inZone else Palette.accent2,
                onClick = { onSelect(saved.id) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (selected) Text("SELECTED", color = ZoneColors.inZone, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(workoutSummary(w), color = ZoneColors.muted, fontSize = 13.sp)
                    StepList(w, zones)
                    val upcoming = schedule.upcoming(saved.id, today)
                    if (upcoming.isNotEmpty()) {
                        Text(
                            "Planned: " + upcoming.take(3).joinToString(" · ") { shortDay(it) } + if (upcoming.size > 3) " +${upcoming.size - 3}" else "",
                            color = Palette.accent2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    FlowRow(Modifier.offset(x = (-12).dp)) {
                        TextAction("Schedule", { scheduling = saved }, color = Palette.accent2)
                        TextAction("Edit", { editing = saved })
                        TextAction("Duplicate", { store.duplicate(saved.id) })
                        TextAction(
                            if (confirmDelete == saved.id) "Tap again to delete" else "Delete",
                            { if (confirmDelete == saved.id) { store.delete(saved.id); confirmDelete = null } else confirmDelete = saved.id },
                            color = ZoneColors.above,
                        )
                    }
                }
            }
        }
        GradientButton("New workout", { creating = true }, colors = listOf(Palette.accent2, Palette.accent), height = 56.dp)
    }
}

/** "10 steps · 38 min + 3.2 km". */
fun workoutSummary(w: Workout): String {
    val parts = buildList {
        if (w.timedMs > 0) add("${(w.timedMs + 59_999) / 60_000} min")
        if (w.distanceM > 0) add(PlannedStep.describe(StepEnd.Distance(w.distanceM)))
    }
    return "${w.stepCount} steps" + if (parts.isEmpty()) "" else " · " + parts.joinToString(" + ")
}

/** Compact list of a workout's blocks, e.g. "4× Run 800 m Z4 · Recover 2:00 Z1–2". */
@Composable
fun StepList(w: Workout, zones: HrZones?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        w.blocks.forEach { b ->
            val text = b.steps.joinToString(" · ") { s -> "${s.kind.label} ${PlannedStep.describe(s.end)} ${targetShort(s.target, zones)}".trim() }
            Text((if (b.repeats > 1) "${b.repeats}× " else "") + text, fontSize = 13.sp)
        }
    }
}

private fun targetShort(t: TargetChoice, zones: HrZones?): String = when (t) {
    TargetChoice.Free -> ""
    is TargetChoice.Zones -> (if (t.from == t.to) "Z${t.from}" else "Z${t.from}–${t.to}") +
        (zones?.let { " (${it.range(t.from).first}–${it.range(t.to).last})" } ?: "")
    is TargetChoice.Custom -> "${t.lowerBpm}–${t.upperBpm}"
}

// ---- editor -------------------------------------------------------------------------------

private val FieldShape = RoundedCornerShape(14.dp)

private enum class EndType(val label: String) { TIME("Time"), DISTANCE("Distance"), LAP("Lap button") }

private class StepDraft(step: WorkoutStep) {
    var kind by mutableStateOf(step.kind)
    var endType by mutableStateOf(
        when (step.end) { is StepEnd.Time -> EndType.TIME; is StepEnd.Distance -> EndType.DISTANCE; StepEnd.LapPress -> EndType.LAP },
    )
    var time by mutableStateOf((step.end as? StepEnd.Time)?.let { formatClockDuration(it.ms) } ?: "5:00")
    var distance by mutableStateOf((step.end as? StepEnd.Distance)?.m?.toInt()?.toString() ?: "1000")
    var target by mutableStateOf(step.target)

    fun build(): WorkoutStep = WorkoutStep(
        kind,
        when (endType) {
            EndType.TIME -> StepEnd.Time(parseTime(time))
            EndType.DISTANCE -> StepEnd.Distance(distance.trim().toDoubleOrNull() ?: error("distance must be metres"))
            EndType.LAP -> StepEnd.LapPress
        },
        target,
    )
}

private class BlockDraft(block: WorkoutBlock) {
    var repeats by mutableIntStateOf(block.repeats)
    val steps: SnapshotStateList<StepDraft> = mutableStateListOf<StepDraft>().apply { addAll(block.steps.map(::StepDraft)) }
}

/** "4:30" → 270 000 ms; a bare number is minutes. */
private fun parseTime(s: String): Long {
    val t = s.trim()
    val parts = t.split(":")
    val seconds = when (parts.size) {
        1 -> (t.toDoubleOrNull() ?: error("time must be mm:ss or minutes")) * 60
        2 -> (parts[0].toIntOrNull() ?: error("bad time $t")) * 60.0 + (parts[1].toIntOrNull() ?: error("bad time $t"))
        3 -> (parts[0].toIntOrNull() ?: error("bad time $t")) * 3600.0 + parts[1].toInt() * 60 + parts[2].toInt()
        else -> error("bad time $t")
    }
    return (seconds * 1000).toLong()
}

@Composable
private fun WorkoutEditor(modifier: Modifier, initial: Workout?, zones: HrZones?, onSave: (Workout) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "My workout") }
    val blocks = remember {
        mutableStateListOf<BlockDraft>().apply {
            addAll(
                (initial?.blocks ?: listOf(
                    WorkoutBlock(listOf(WorkoutStep(StepKind.WARMUP, StepEnd.Time(600_000), TargetChoice.Zones(1, 1)))),
                    WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Time(1_200_000), TargetChoice.Zones(2, 2)))),
                    WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, StepEnd.Time(300_000), TargetChoice.Zones(1, 1)))),
                )).map(::BlockDraft),
            )
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextAction("‹ Workouts", onCancel)
        Text(if (initial == null) "New workout" else "Edit workout", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth())

        blocks.forEachIndexed { bi, block ->
            val repeat = block.repeats > 1 || block.steps.size > 1
            GlowCard(accent = if (repeat) Palette.accent2 else Palette.faint, contentPadding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (repeat) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Repeat", fontWeight = FontWeight.SemiBold)
                            GhostButton("−", { if (block.repeats > 1) block.repeats-- }, Modifier.width(52.dp), height = 44.dp)
                            Text("${block.repeats}×", style = MaterialTheme.typography.titleLarge, fontFamily = Numeric)
                            GhostButton("+", { if (block.repeats < 50) block.repeats++ }, Modifier.width(52.dp), height = 44.dp)
                        }
                    }
                    block.steps.forEachIndexed { si, step ->
                        StepEditor(step, zones, onDelete = {
                            block.steps.removeAt(si)
                            if (block.steps.isEmpty()) blocks.removeAt(bi)
                        })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (repeat) TextAction("+ Step in repeat", {
                            block.steps += StepDraft(WorkoutStep(StepKind.RECOVER, StepEnd.Time(120_000), TargetChoice.Zones(1, 2)))
                        }, color = Palette.accent2)
                        if (bi > 0) TextAction("Move up", { blocks.add(bi - 1, blocks.removeAt(bi)) })
                        if (bi < blocks.lastIndex) TextAction("Move down", { blocks.add(bi + 1, blocks.removeAt(bi)) })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("+ Step", {
                blocks += BlockDraft(WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Time(300_000), TargetChoice.Zones(2, 2)))))
            }, Modifier.weight(1f), height = 52.dp)
            GhostButton("+ Repeat", {
                blocks += BlockDraft(
                    WorkoutBlock(
                        listOf(
                            WorkoutStep(StepKind.RUN, StepEnd.Time(240_000), TargetChoice.Zones(4, 4)),
                            WorkoutStep(StepKind.RECOVER, StepEnd.Time(180_000), TargetChoice.Zones(1, 2)),
                        ),
                        repeats = 4,
                    ),
                )
            }, Modifier.weight(1f), height = 52.dp)
        }
        error?.let { Text(it, color = ZoneColors.above) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton("Save", {
                runCatching {
                    Workout(name.trim().ifEmpty { "Workout" }, blocks.map { b -> WorkoutBlock(b.steps.map { it.build() }, b.repeats) })
                }.onSuccess(onSave).onFailure { error = "Not saved: ${it.message}" }
            }, Modifier.weight(1f), height = 56.dp)
            GhostButton("Cancel", onCancel, Modifier.weight(1f), height = 56.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepEditor(step: StepDraft, zones: HrZones?, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.bg.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepKind.entries.forEach { k -> PillChip(k.label, step.kind == k, Palette.accent, compact = true) { step.kind = k } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EndType.entries.forEach { e -> PillChip(e.label, step.endType == e, Palette.accent2, compact = true) { step.endType = e } }
        }
        when (step.endType) {
            EndType.TIME -> OutlinedTextField(
                step.time, { step.time = it.filter { c -> c.isDigit() || c == ':' }.take(8) },
                label = { Text("Time (mm:ss)") }, singleLine = true, shape = FieldShape, modifier = Modifier.width(160.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            EndType.DISTANCE -> OutlinedTextField(
                step.distance, { step.distance = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Distance (m)") }, singleLine = true, shape = FieldShape, modifier = Modifier.width(160.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            EndType.LAP -> Text("Ends when you press Lap.", color = ZoneColors.muted, fontSize = 13.sp)
        }
        Text("Target", color = ZoneColors.muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillChip("Free", step.target is TargetChoice.Free, Palette.accent, compact = true) { step.target = TargetChoice.Free }
            val sel = step.target as? TargetChoice.Zones
            for (z in 1..HrZones.COUNT) {
                PillChip("Z$z", sel != null && z in sel.from..sel.to, ZoneColors.hrZone(z), compact = true) {
                        step.target = when {
                            sel == null -> TargetChoice.Zones(z, z)
                            sel.from != sel.to && z in sel.from..sel.to -> TargetChoice.Zones(z, z)
                            z == sel.to + 1 -> TargetChoice.Zones(sel.from, z)
                            z == sel.from - 1 -> TargetChoice.Zones(z, sel.to)
                            else -> TargetChoice.Zones(z, z)
                        }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(targetShort(step.target, zones).ifEmpty { "No target: no zone alerts in this step" }, color = ZoneColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextAction("Remove", onDelete, color = ZoneColors.above)
        }
    }
}
