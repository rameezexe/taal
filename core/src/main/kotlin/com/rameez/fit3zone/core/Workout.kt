package com.rameez.fit3zone.core

import kotlin.math.roundToLong

enum class StepKind(val code: Char, val label: String) {
    WARMUP('W', "Warm-up"), RUN('R', "Run"), RECOVER('V', "Recover"), COOLDOWN('C', "Cool-down");

    companion object {
        fun of(code: Char): StepKind = entries.first { it.code == code }
    }
}

/** How a step ends. Time and distance count active (unpaused) time and GPS distance. */
sealed interface StepEnd {
    data class Time(val ms: Long) : StepEnd {
        init { require(ms in 5_000..6 * 3_600_000L) { "step time must be 5 s–6 h" } }
    }
    data class Distance(val m: Double) : StepEnd {
        init { require(m in 50.0..100_000.0) { "step distance must be 50 m–100 km" } }
    }
    data object LapPress : StepEnd
}

data class WorkoutStep(val kind: StepKind, val end: StepEnd, val target: TargetChoice = TargetChoice.Free)

/** A group of steps done [repeats] times in a row; a plain step is a block with one step and one repeat. */
data class WorkoutBlock(val steps: List<WorkoutStep>, val repeats: Int = 1) {
    init {
        require(steps.isNotEmpty()) { "a block needs at least one step" }
        require(repeats in 1..50) { "repeats must be 1–50" }
    }
}

/** One step of a workout as it will be run, with its target resolved against the current zones. */
data class PlannedStep(
    /** 0-based position in the flattened workout. */
    val index: Int,
    val step: WorkoutStep,
    /** 1-based repeat number and the block's repeat count (1/1 for plain steps). */
    val repeat: Int,
    val repeats: Int,
    val target: ZoneTarget?,
) {
    /** "Run 2/4", "Warm-up". */
    val title: String get() = if (repeats > 1) "${step.kind.label} $repeat/$repeats" else step.kind.label

    /** "800 m", "4:00", "Lap button". */
    val goal: String get() = describe(step.end)

    companion object {
        fun describe(end: StepEnd): String = when (end) {
            is StepEnd.Time -> formatClockDuration(end.ms)
            is StepEnd.Distance ->
                if (end.m >= 1000) "%.2f".format(java.util.Locale.US, end.m / 1000).trimEnd('0').trimEnd('.') + " km"
                else "${end.m.roundToLong()} m"
            StepEnd.LapPress -> "Lap button"
        }
    }
}

/**
 * A structured workout. Stored as a compact text form ([encodeSteps]), e.g.
 * `1x[W,t600,zones:1-2]/4x[R,d800,zones:4-4;V,t120,zones:1-2]/1x[C,lap,free]`.
 */
data class Workout(val name: String, val blocks: List<WorkoutBlock>) {
    init { require(blocks.isNotEmpty()) { "a workout needs at least one step" } }

    val needsZones: Boolean get() = blocks.any { b -> b.steps.any { it.target is TargetChoice.Zones } }
    val usesDistance: Boolean get() = blocks.any { b -> b.steps.any { it.end is StepEnd.Distance } }
    val stepCount: Int get() = blocks.sumOf { it.steps.size * it.repeats }

    /** Sum of timed steps; distance and lap steps are not included. */
    val timedMs: Long get() = blocks.sumOf { b -> b.repeats * b.steps.sumOf { (it.end as? StepEnd.Time)?.ms ?: 0L } }
    val distanceM: Double get() = blocks.sumOf { b -> b.repeats * b.steps.sumOf { (it.end as? StepEnd.Distance)?.m ?: 0.0 } }

    /** Flattens repeats and resolves targets. Throws if a step needs zones and [zones] is null. */
    fun plan(zones: HrZones?): List<PlannedStep> {
        val out = mutableListOf<PlannedStep>()
        for (b in blocks) for (r in 1..b.repeats) for (s in b.steps) {
            val target = when (val t = s.target) {
                TargetChoice.Free -> null
                is TargetChoice.Zones -> t.resolve(requireNotNull(zones) { "workout uses zones; set up heart-rate zones first" })
                is TargetChoice.Custom -> ZoneTarget(t.lowerBpm, t.upperBpm, "Custom")
            }
            out += PlannedStep(out.size, s, r, b.repeats, target)
        }
        return out
    }

    fun encodeSteps(): String = blocks.joinToString("/") { b ->
        "${b.repeats}x[" + b.steps.joinToString(";") { s ->
            val end = when (val e = s.end) {
                is StepEnd.Time -> "t${e.ms / 1000}"
                is StepEnd.Distance -> "d${e.m.roundToLong()}"
                StepEnd.LapPress -> "lap"
            }
            "${s.kind.code},$end,${s.target.encode()}"
        } + "]"
    }

    companion object {
        fun decode(name: String, steps: String): Workout? = runCatching {
            Workout(name, steps.split("/").map { block ->
                val x = block.indexOf("x[")
                require(x > 0 && block.endsWith("]"))
                WorkoutBlock(
                    block.substring(x + 2, block.length - 1).split(";").map { s ->
                        val (kind, end, target) = s.split(",").also { require(it.size == 3) }
                        WorkoutStep(
                            StepKind.of(kind.single()),
                            when {
                                end == "lap" -> StepEnd.LapPress
                                end.startsWith("t") -> StepEnd.Time(end.drop(1).toLong() * 1000)
                                end.startsWith("d") -> StepEnd.Distance(end.drop(1).toDouble())
                                else -> error("bad end $end")
                            },
                            TargetChoice.decode(target)!!,
                        )
                    },
                    block.substring(0, x).toInt(),
                )
            })
        }.getOrNull()
    }
}

/** Progress through the current step, for the Run screen and notification. */
data class WorkoutProgress(
    val step: PlannedStep,
    val total: Int,
    val stepElapsedMs: Long,
    val stepCoveredM: Double,
    /** Left in a timed step / distance step; null for the other kinds. */
    val remainingMs: Long?,
    val remainingM: Double?,
    /** 0..1 for timed and distance steps, null for lap-press steps. */
    val fraction: Float?,
    val next: PlannedStep?,
    val finished: Boolean,
)

sealed interface WorkoutEvent {
    /** [previous] is null for the first step; [byLap] when the lap button ended the previous step. */
    data class StepStarted(val step: PlannedStep, val previous: PlannedStep?, val byLap: Boolean) : WorkoutEvent
    data class Finished(val last: PlannedStep, val byLap: Boolean) : WorkoutEvent
}

/**
 * Deterministic workout state machine. Callers pass the session's active time (pauses excluded) and
 * GPS distance; no clocks inside. Timed steps end exactly on their boundary (the next step starts
 * where the previous one should have ended, not at the tick that noticed), so steps don't drift.
 * The lap button ends the current step whatever its kind.
 */
class WorkoutRunner(val steps: List<PlannedStep>) {
    init { require(steps.isNotEmpty()) }

    var index = 0
        private set
    var finished = false
        private set
    private var started = false
    private var stepStartMs = 0L
    private var stepStartM = 0.0

    val current: PlannedStep get() = steps[index]

    fun start(activeMs: Long, distanceM: Double): WorkoutEvent.StepStarted {
        check(!started)
        started = true
        stepStartMs = activeMs
        stepStartM = distanceM
        return WorkoutEvent.StepStarted(steps[0], null, byLap = false)
    }

    fun update(activeMs: Long, distanceM: Double): List<WorkoutEvent> {
        if (!started || finished) return emptyList()
        val out = mutableListOf<WorkoutEvent>()
        while (!finished) {
            val end = current.step.end
            when {
                end is StepEnd.Time && activeMs - stepStartMs >= end.ms ->
                    out += advance(stepStartMs + end.ms, distanceM, byLap = false)
                end is StepEnd.Distance && distanceM - stepStartM >= end.m ->
                    out += advance(activeMs, stepStartM + end.m, byLap = false)
                else -> break
            }
        }
        return out
    }

    fun lap(activeMs: Long, distanceM: Double): List<WorkoutEvent> {
        if (!started || finished) return emptyList()
        return listOf(advance(activeMs, distanceM, byLap = true))
    }

    private fun advance(atMs: Long, atM: Double, byLap: Boolean): WorkoutEvent {
        val previous = current
        if (index == steps.lastIndex) {
            finished = true
            return WorkoutEvent.Finished(previous, byLap)
        }
        index++
        stepStartMs = atMs
        stepStartM = atM
        return WorkoutEvent.StepStarted(current, previous, byLap)
    }

    fun progress(activeMs: Long, distanceM: Double): WorkoutProgress? {
        if (!started) return null
        val elapsed = (activeMs - stepStartMs).coerceAtLeast(0)
        val covered = (distanceM - stepStartM).coerceAtLeast(0.0)
        val end = current.step.end
        val remainingMs = (end as? StepEnd.Time)?.let { (it.ms - elapsed).coerceAtLeast(0) }
        val remainingM = (end as? StepEnd.Distance)?.let { (it.m - covered).coerceAtLeast(0.0) }
        val fraction = when (end) {
            is StepEnd.Time -> elapsed.toFloat() / end.ms
            is StepEnd.Distance -> (covered / end.m).toFloat()
            StepEnd.LapPress -> null
        }?.coerceIn(0f, 1f)
        return WorkoutProgress(
            current, steps.size, elapsed, covered, remainingMs, remainingM,
            if (finished) 1f else fraction, steps.getOrNull(index + 1).takeIf { !finished }, finished,
        )
    }
}

/** "4:00", "1:02:30". */
fun formatClockDuration(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Ready-made workouts offered on first use. */
object WorkoutTemplates {
    private fun z(from: Int, to: Int = from) = TargetChoice.Zones(from, to)
    private fun min(m: Int) = StepEnd.Time(m * 60_000L)

    val all: List<Workout> = listOf(
        Workout("Zone 2 easy · 40 min", listOf(
            WorkoutBlock(listOf(WorkoutStep(StepKind.WARMUP, min(10), z(1)))),
            WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, min(25), z(2)))),
            WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, min(5), z(1)))),
        )),
        Workout("4 × 4 min Zone 4", listOf(
            WorkoutBlock(listOf(WorkoutStep(StepKind.WARMUP, min(10), z(1, 2)))),
            WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, min(4), z(4)), WorkoutStep(StepKind.RECOVER, min(3), z(1, 2))), repeats = 4),
            WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, min(5), z(1)))),
        )),
        Workout("8 × 400 m", listOf(
            WorkoutBlock(listOf(WorkoutStep(StepKind.WARMUP, min(10), z(1, 2)))),
            WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, StepEnd.Distance(400.0), z(4, 5)), WorkoutStep(StepKind.RECOVER, StepEnd.Time(90_000), z(1, 2))), repeats = 8),
            WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, StepEnd.LapPress, z(1)))),
        )),
        Workout("Tempo · 20 min Zone 3", listOf(
            WorkoutBlock(listOf(WorkoutStep(StepKind.WARMUP, min(10), z(1, 2)))),
            WorkoutBlock(listOf(WorkoutStep(StepKind.RUN, min(20), z(3)))),
            WorkoutBlock(listOf(WorkoutStep(StepKind.COOLDOWN, min(10), z(1)))),
        )),
    )
}
