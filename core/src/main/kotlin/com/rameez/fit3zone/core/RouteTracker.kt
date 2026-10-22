package com.rameez.fit3zone.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS fix at [offsetMs] from session start. */
data class GeoPoint(
    val offsetMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    /** Metres above sea level when the fix had one (stored since DB v6). */
    val altitudeM: Double? = null,
)

/** One completed kilometre (or the final partial one when [distanceM] < 1000). */
data class Split(
    val index: Int,
    val distanceM: Double,
    val durationMs: Long,
    val avgBpm: Int?,
) {
    /** Seconds per km, scaled for partial splits. */
    val paceSecPerKm: Double? get() = if (distanceM < 50) null else durationMs / 1000.0 / (distanceM / 1000.0)
}

/**
 * Turns raw GPS fixes into distance, splits and pace. Pure and deterministic so the same code
 * computes live stats during a run and re-derives splits from stored points afterwards.
 *
 * Noise handling:
 *  - fixes worse than [maxAccuracyM] are dropped;
 *  - a fix implying more than [maxSpeedMps] from the last accepted fix is a GPS jump and is dropped;
 *  - distance is measured on a smoothed track: each position is the accuracy-weighted average of [smoothFixes]
 *    fixes centred on it (within 4.5 s each side). Raw 1 Hz fixes zig-zag around the real path and overcount
 *    by 2–5 % (measured on a ~405 m loop: raw 418 m per lap, smoothed 406–412 m). This delays distance by
 *    about 3 s while running; [finish] counts the last fixes;
 *  - distance only advances once the position has moved [minStepM] from the anchor, so standing
 *    still doesn't accumulate jitter.
 *
 * GPS gaps: phones can go minutes without a usable fix (Power saving with the screen off, trees, pocket). A straight
 * line across the gap badly undercounts, especially on a loop, so when steps are known ([addSteps]) a gap longer than
 * [gapBridgeMs] counts steps × stride instead when that's longer. The stride is calibrated during the run on the
 * smoothed distance of stretches with good, continuous GPS ([calibratedStrideM]), over windows of at least 10 s;
 * until then [defaultStrideM] (e.g. from earlier runs) is used, if given.
 */
class RouteTracker(
    private val maxAccuracyM: Float = 25f,
    private val maxSpeedMps: Double = 12.0,
    private val minStepM: Double = 5.0,
    private val splitM: Double = 1_000.0,
    private val defaultStrideM: Double? = null,
    private val gapBridgeMs: Long = 5_000,
    /**
     * Fixes averaged per position. 1 = [LEGACY]: exactly how activities recorded before smoothing were measured
     * (raw fixes, stride from straight lines over every 10 s window), so their saved distance, laps and splits
     * stay as they were.
     */
    private val smoothFixes: Int = SMOOTH_FIXES,
    /** Always bridge with [defaultStrideM] (a replay that already knows the whole activity's stride). */
    private val lockStride: Boolean = false,
) {
    private val _points = mutableListOf<GeoPoint>()
    /** Accepted fixes, including ones too close to advance distance (kept for drawing the route). */
    val points: List<GeoPoint> get() = _points

    private val legacy = smoothFixes <= LEGACY
    private var legacyCalibrationStart: GeoPoint? = null

    private val hr = mutableListOf<HrSample>()
    /** Last accepted raw fix of the current segment (for the jump check); null at a segment start. */
    private var lastRaw: GeoPoint? = null
    /** Raw fixes of the current segment still needed for smoothing; [nextCentre] is the next one to smooth. */
    private val window = ArrayDeque<GeoPoint>()
    private var nextCentre = 0

    /** Last smoothed position that advanced distance. */
    private var anchor: GeoPoint? = null
    private var segmentStart = true

    var distanceM = 0.0
        private set

    private val splits = mutableListOf<Split>()
    /** (time, cumulative distance) whenever distance advances, per segment — for best efforts. */
    private val trace = mutableListOf<MutableList<Pair<Long, Double>>>()
    private var splitStartMs = 0L
    /** Time of the last smoothed position counted (advanced or not). */
    private var lastCountedMs = 0L

    /** Time of the last accepted fix (raw), or null before the first one. */
    var lastFixMs: Long? = null
        private set

    /** (time, cumulative steps), increasing; empty when the activity has no step data. */
    private val stepTimeline = mutableListOf<Pair<Long, Long>>()
    private var calibrationM = 0.0
    private var calibrationSteps = 0.0
    /** (time, distance) where the current calibration window started: continuous good GPS since then. */
    private var calibrationStart: Pair<Long, Double>? = null

    /** Distance credited from steps across GPS gaps (included in [distanceM]). */
    var bridgedM = 0.0
        private set

    /** The smoothed positions distance was measured on (one per accepted fix). */
    val smoothedTrack = mutableListOf<GeoPoint>()

    /** (fix time, cumulative distance) for every accepted fix, e.g. for per-trackpoint distance in exports. */
    val fixDistances = mutableListOf<Pair<Long, Double>>()

    /** Fixes dropped so far, by reason (for diagnostics). */
    var rejectedAccuracy = 0
        private set
    var rejectedJump = 0
        private set

    /** Steps taken so far ([cumulativeSteps]) at [offsetMs]; call in time order (each step, or a periodic count). */
    fun addSteps(offsetMs: Long, cumulativeSteps: Long) {
        val last = stepTimeline.lastOrNull()
        if (last != null && (offsetMs <= last.first || cumulativeSteps < last.second)) return
        stepTimeline += offsetMs to cumulativeSteps
    }

    /** Metres per step measured on good GPS during this activity; null until about 150 steps were measured. */
    val calibratedStrideM: Double?
        get() = if (calibrationSteps >= MIN_CALIBRATION_STEPS) calibrationM / calibrationSteps else null

    private val strideM: Double? get() = if (lockStride) defaultStrideM else calibratedStrideM ?: defaultStrideM

    /** Steps between two times, interpolated on the step timeline; null without step data covering them. */
    private fun stepsBetween(fromMs: Long, toMs: Long): Double? {
        if (stepTimeline.size < 2 || toMs <= fromMs) return null
        if (fromMs < stepTimeline.first().first - STEP_EDGE_MS || toMs > stepTimeline.last().first + STEP_EDGE_MS) return null
        return (stepsAt(toMs) - stepsAt(fromMs)).coerceAtLeast(0.0)
    }

    private fun stepsAt(t: Long): Double {
        val i = stepTimeline.binarySearch { it.first.compareTo(t) }
        if (i >= 0) return stepTimeline[i].second.toDouble()
        val idx = -i - 1
        if (idx == 0) return stepTimeline.first().second.toDouble()
        if (idx >= stepTimeline.size) return stepTimeline.last().second.toDouble()
        val (ta, sa) = stepTimeline[idx - 1]
        val (tb, sb) = stepTimeline[idx]
        return sa + (sb - sa) * (t - ta).toDouble() / (tb - ta)
    }

    /** True while GPS has been gone longer than [gapBridgeMs] in a segment, i.e. [distanceAt] is adding steps. */
    fun inGap(offsetMs: Long): Boolean {
        val last = lastFixMs ?: return false
        return !segmentStart && lastRaw != null && offsetMs - last > gapBridgeMs
    }

    /** Distance now, including the steps since the last fix when GPS has been gone longer than [gapBridgeMs]. */
    fun distanceAt(offsetMs: Long): Double {
        val a = anchor ?: return distanceM
        if (!inGap(offsetMs) || segmentStart) return distanceM
        val stride = strideM ?: return distanceM
        val steps = stepsBetween(a.offsetMs, offsetMs) ?: return distanceM
        return distanceM + (steps * stride).coerceAtMost(maxSpeedMps * (offsetMs - a.offsetMs) / 1000.0)
    }

    /** Returns true if the fix was accepted. */
    fun addPoint(p: GeoPoint): Boolean {
        if (p.accuracyM > maxAccuracyM || p.accuracyM <= 0f) { rejectedAccuracy++; return false }
        // Legacy: jumps were judged from the last position that advanced distance.
        val last = if (legacy && !segmentStart) anchor else lastRaw
        if (last != null) {
            if (p.offsetMs <= last.offsetMs) return false
            val dtS = (p.offsetMs - last.offsetMs) / 1000.0
            if (haversineM(last.lat, last.lon, p.lat, p.lon) / dtS > maxSpeedMps) { rejectedJump++; return false }
        }
        lastRaw = p
        lastFixMs = p.offsetMs
        _points += p
        window += p
        smoothPending(final = false)
        return true
    }

    /** Counts the fixes still waiting for smoothing; call at the end of the activity (replay does). */
    fun finish() = smoothPending(final = true)

    /** Smooths every fix whose neighbours are all known (or all of them when [final]) and counts it. */
    private fun smoothPending(final: Boolean) {
        val half = smoothFixes / 2
        while (nextCentre < window.size) {
            val c = window[nextCentre]
            val after = window.size - 1 - nextCentre
            if (!final && after < half && window.last().offsetMs - c.offsetMs <= SMOOTH_SPAN_MS) return
            count(smoothed(nextCentre, half))
            nextCentre++
            while (nextCentre > half) { window.removeFirst(); nextCentre-- }
        }
    }

    /**
     * Accuracy-weighted average of the fix at [i] and up to [half] neighbours on each side. The window is kept
     * symmetric (as many fixes before as after, each within 4.5 s), so a segment's ends and the edges of a GPS gap
     * aren't pulled inwards.
     */
    private fun smoothed(i: Int, half: Int): GeoPoint {
        val c = window[i]
        var k = 0
        while (k < half && i - k - 1 >= 0 && i + k + 1 <= window.lastIndex &&
            c.offsetMs - window[i - k - 1].offsetMs <= SMOOTH_SPAN_MS && window[i + k + 1].offsetMs - c.offsetMs <= SMOOTH_SPAN_MS
        ) k++
        if (k == 0) return c
        var w = 0.0; var lat = 0.0; var lon = 0.0
        for (j in i - k..i + k) {
            val q = window[j]
            val wq = 1.0 / (q.accuracyM.toDouble() * q.accuracyM)
            w += wq; lat += q.lat * wq; lon += q.lon * wq
        }
        return c.copy(lat = lat / w, lon = lon / w)
    }

    /** Distance, splits and stride calibration from one smoothed position. */
    private fun count(p: GeoPoint) {
        smoothedTrack += p
        val a = anchor
        if (a == null || segmentStart) {
            if (a == null && splits.isEmpty() && distanceM == 0.0) splitStartMs = p.offsetMs
            anchor = p
            segmentStart = false
            trace += mutableListOf(p.offsetMs to distanceM)
            lastCountedMs = p.offsetMs
            fixDistances += p.offsetMs to distanceM
            calibrationStart = (p.offsetMs to distanceM).takeIf { p.accuracyM <= CALIBRATION_ACCURACY_M }
            legacyCalibrationStart = p.takeIf { it.accuracyM <= CALIBRATION_ACCURACY_M }
            return
        }
        val straight = haversineM(a.lat, a.lon, p.lat, p.lon)
        val dtS = (p.offsetMs - a.offsetMs) / 1000.0
        val sinceLastMs = p.offsetMs - lastCountedMs
        val gap = sinceLastMs > gapBridgeMs
        lastCountedMs = p.offsetMs
        if (legacy) legacyCalibrate(p, continuous = sinceLastMs <= CALIBRATION_MAX_DT_MS)
        val bridged = if (gap) strideM?.let { stride ->
            stepsBetween(a.offsetMs, p.offsetMs)?.let { (it * stride).coerceAtMost(maxSpeedMps * dtS) }
        } else null
        val d = if (bridged != null && bridged > straight) bridged else straight
        val continuous = sinceLastMs <= CALIBRATION_MAX_DT_MS && p.accuracyM <= CALIBRATION_ACCURACY_M
        if (!continuous) calibrationStart = null
        if (d < minStepM) { fixDistances += p.offsetMs to distanceM; return }
        if (d > straight) bridgedM += d - straight

        val before = distanceM
        distanceM += d
        trace.last() += p.offsetMs to distanceM
        // Close every split boundary crossed by this step, interpolating the crossing time.
        var boundary = (splits.size + 1) * splitM
        while (distanceM >= boundary) {
            val frac = (boundary - before) / d
            val crossMs = a.offsetMs + ((p.offsetMs - a.offsetMs) * frac).toLong()
            splits += Split(splits.size + 1, splitM, crossMs - splitStartMs, avgBpm(splitStartMs, crossMs))
            splitStartMs = crossMs
            boundary += splitM
        }
        anchor = p
        fixDistances += p.offsetMs to distanceM
        if (!legacy) calibrate(p)
    }

    /** Stride calibration of activities recorded before smoothing: straight line vs steps over 10 s windows. */
    private fun legacyCalibrate(p: GeoPoint, continuous: Boolean) {
        val start = legacyCalibrationStart
        if (!continuous || p.accuracyM > CALIBRATION_ACCURACY_M || start == null) {
            legacyCalibrationStart = p.takeIf { it.accuracyM <= CALIBRATION_ACCURACY_M }
            return
        }
        if (p.offsetMs - start.offsetMs < CALIBRATION_WINDOW_MS) return
        stepsBetween(start.offsetMs, p.offsetMs)?.let { steps ->
            if (steps > 0) { calibrationM += haversineM(start.lat, start.lon, p.lat, p.lon); calibrationSteps += steps }
        }
        legacyCalibrationStart = p
    }

    /** Stride calibration: smoothed distance vs steps over windows of continuous good GPS. */
    private fun calibrate(p: GeoPoint) {
        val start = calibrationStart
        if (start == null) {
            if (p.accuracyM <= CALIBRATION_ACCURACY_M) calibrationStart = p.offsetMs to distanceM
            return
        }
        val dtMs = p.offsetMs - start.first
        if (dtMs < CALIBRATION_WINDOW_MS) return
        stepsBetween(start.first, p.offsetMs)?.let { steps ->
            val metres = distanceM - start.second
            // Only real walking / running: GPS drift while standing or shuffling would make the stride huge.
            if (steps * 60_000.0 / dtMs >= MIN_CALIBRATION_SPM && metres / steps in PLAUSIBLE_STRIDE_M) {
                calibrationM += metres; calibrationSteps += steps
            }
        }
        calibrationStart = p.offsetMs to distanceM
    }

    fun addHeartRate(offsetMs: Long, bpm: Int) {
        hr += HrSample(offsetMs, bpm)
    }

    /** Call after a pause: the next fix starts a new segment, so movement during the pause isn't counted. */
    fun breakSegment() {
        finish()
        window.clear()
        nextCentre = 0
        lastRaw = null
        segmentStart = true
        calibrationStart = null
        legacyCalibrationStart = null
    }

    /**
     * Fastest time in ms to cover [metres] anywhere in the route (within one segment, so a pause never
     * counts), interpolating the start point; null if no segment is that long.
     */
    fun bestEffortMs(metres: Double): Long? {
        var best: Double? = null
        for (seg in trace) {
            var i = 0
            for (j in seg.indices) {
                val (tj, dj) = seg[j]
                val startD = dj - metres
                if (startD < seg[0].second) continue
                while (i + 1 < j && seg[i + 1].second <= startD) i++
                val (ti, di) = seg[i]
                val (tn, dn) = seg[i + 1]
                val startT = if (dn > di) ti + (tn - ti) * (startD - di) / (dn - di) else ti.toDouble()
                val dt = tj - startT
                if (best == null || dt < best) best = dt
            }
        }
        return best?.toLong()
    }

    /**
     * Pace over time for charts: at each point where distance advanced, seconds per km over the last
     * [windowMs] of the same segment (pauses never count). Points with < 20 m in the window are skipped.
     */
    fun paceSeries(windowMs: Long = 30_000): List<Pair<Long, Double>> = buildList {
        for (seg in trace) {
            var i = 0
            for (j in seg.indices) {
                val (tj, dj) = seg[j]
                while (i < j && tj - seg[i + 1].first >= windowMs) i++
                val (ti, di) = seg[i]
                val dd = dj - di
                if (dd >= 20 && tj > ti) add(tj to (tj - ti) / 1000.0 / (dd / 1000.0))
            }
        }
    }

    /** Completed splits. */
    fun completedSplits(): List<Split> = splits.toList()

    /** Completed splits plus the current partial one. */
    fun allSplits(): List<Split> {
        val partialM = distanceM - splits.size * splitM
        if (partialM < 1) return completedSplits()
        return splits + Split(splits.size + 1, partialM, lastCountedMs - splitStartMs, avgBpm(splitStartMs, lastCountedMs))
    }

    /** Pace over the last [windowMs] of movement (smoothed track), in seconds per km; null if too little movement. */
    fun currentPaceSecPerKm(windowMs: Long = 30_000): Double? {
        val seg = trace.lastOrNull() ?: return null
        val since = lastCountedMs - windowMs
        val from = seg.indexOfLast { it.first <= since }
        val (t0, d0) = if (from < 0) seg.first() else seg[from]
        val startMs = maxOf(since, t0)
        val d = distanceM - d0
        val t = (lastCountedMs - startMs) / 1000.0
        if (d < 20 || t <= 0) return null
        return t / (d / 1000.0)
    }

    private fun avgBpm(fromMs: Long, toMs: Long): Int? {
        val inRange = hr.filter { it.offsetMs in fromMs..toMs }
        if (inRange.isEmpty()) return null
        return (inRange.sumOf { it.bpm }.toDouble() / inRange.size).let { kotlin.math.round(it).toInt() }
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val MIN_CALIBRATION_STEPS = 150.0
        private const val CALIBRATION_MAX_DT_MS = 3_000L
        private const val CALIBRATION_WINDOW_MS = 10_000L
        private const val CALIBRATION_ACCURACY_M = 15f
        private const val MIN_CALIBRATION_SPM = 60.0
        private val PLAUSIBLE_STRIDE_M = 0.3..2.0
        /** Step data may start/end a little after/before the fixes (first step, last 5 s sample). */
        private const val STEP_EDGE_MS = 10_000L
        /** Fixes averaged per position (≈ 7 s at 1 Hz), and how far in time a neighbour may be from the centre. */
        const val SMOOTH_FIXES = 7
        /** [smoothFixes] for activities recorded before smoothing (see the constructor). */
        const val LEGACY = 1
        private const val SMOOTH_SPAN_MS = 4_500L

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /**
         * Rebuilds a tracker from stored points and HR samples (e.g. for an activity's splits). With [steps]
         * (time, cumulative steps), GPS gaps are bridged; the stride is first calibrated over the whole activity,
         * so gaps early on count the same as later ones.
         */
        fun replay(
            points: List<GeoPoint>, samples: List<HrSample>, segmentBreaksMs: List<Long> = emptyList(),
            steps: List<Pair<Long, Long>> = emptyList(), defaultStrideM: Double? = null, smoothFixes: Int = SMOOTH_FIXES,
        ): RouteTracker {
            val stride = if (steps.isEmpty()) null else run(points, emptyList(), segmentBreaksMs, steps, defaultStrideM, smoothFixes, lock = false).calibratedStrideM ?: defaultStrideM
            return run(points, samples, segmentBreaksMs, steps, stride, smoothFixes, lock = stride != null && smoothFixes > LEGACY)
        }

        private fun run(
            points: List<GeoPoint>, samples: List<HrSample>, segmentBreaksMs: List<Long>, steps: List<Pair<Long, Long>>, stride: Double?,
            smoothFixes: Int, lock: Boolean,
        ): RouteTracker {
            val t = RouteTracker(defaultStrideM = stride, smoothFixes = smoothFixes, lockStride = lock)
            samples.forEach { t.addHeartRate(it.offsetMs, it.bpm) }
            steps.forEach { (ms, n) -> t.addSteps(ms, n) }
            val breaks = segmentBreaksMs.sorted().toMutableList()
            for (p in points) {
                while (breaks.isNotEmpty() && breaks.first() <= p.offsetMs) {
                    breaks.removeAt(0)
                    t.breakSegment()
                }
                t.addPoint(p)
            }
            t.finish()
            return t
        }
    }
}

/** "5:42" style pace. */
fun formatPace(secPerKm: Double?): String {
    if (secPerKm == null || secPerKm.isNaN() || secPerKm > 60 * 60) return "--:--"
    val s = kotlin.math.round(secPerKm).toLong()
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Splits a route into pieces that can be drawn as lines: a new piece starts after a stretch of more than [maxGapMs]
 * without a fix (GPS lost, or fixes dropped as inaccurate) and at each resume after a pause ([breaksMs]), so the map
 * doesn't draw a straight line across ground that wasn't recorded.
 */
fun List<GeoPoint>.drawablePieces(breaksMs: List<Long> = emptyList(), maxGapMs: Long = 20_000): List<List<GeoPoint>> {
    if (isEmpty()) return emptyList()
    val pieces = mutableListOf(mutableListOf(first()))
    for (i in 1 until size) {
        val prev = this[i - 1]
        val p = this[i]
        val gap = p.offsetMs - prev.offsetMs > maxGapMs || breaksMs.any { it > prev.offsetMs && it <= p.offsetMs }
        if (gap) pieces += mutableListOf(p) else pieces.last() += p
    }
    return pieces
}
