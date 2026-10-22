package com.rameez.fit3zone.core

import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything a recorded session needs for an activity file. Offsets are ms since [startedAtMs] (wall time, pauses included). */
data class ExportSession(
    val startedAtMs: Long,
    val type: ActivityType,
    val activeMs: Long,
    val distanceM: Double,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val samples: List<HrSample>,
    val points: List<GeoPoint>,
    /** (offset ms, steps per minute) every 5 s. */
    val cadence: List<Pair<Long, Int>> = emptyList(),
    /** Offsets where the session was resumed after a pause; each starts a new track. */
    val segmentBreaksMs: List<Long> = emptyList(),
    /** Recorded laps / workout steps (start and duration in active time); fewer than two = one lap for the session. */
    val laps: List<Lap> = emptyList(),
    /**
     * Cumulative distance at each GPS fix (fix offset → metres), e.g. [RouteTracker.fixDistances]; written as
     * per-trackpoint DistanceMeters so Strava uses this distance (incl. GPS gaps bridged by steps).
     */
    val pointDistancesM: Map<Long, Double> = emptyMap(),
)

/**
 * Garmin Training Center (TCX) file for uploading to Strava and other services. TCX rather than GPX
 * because it carries heart rate and cadence without GPS too (treadmill, strength). One TCX lap per recorded
 * lap or workout step (Strava shows them as laps), else one for the whole session; each paused stretch is its
 * own track so the pause isn't counted as moving time.
 */
object TcxWriter {
    /** A heart-rate / cadence value is attached to a trackpoint only when it was recorded this close in time. */
    const val MATCH_WINDOW_MS = 10_000L

    fun write(s: ExportSession): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" """ +
                """xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">""",
        )
        appendLine("<Activities>")
        appendLine("""<Activity Sport="${sport(s.type)}">""")
        val start = time(s.startedAtMs)
        appendLine("<Id>$start</Id>")
        val points = points(s)
        if (s.laps.size < 2) {
            appendLap(s, s.startedAtMs, s.activeMs, s.distanceM, s.avgBpm, s.maxBpm, points)
        } else {
            val active = ActiveClock(s, points)
            val laps = s.laps.sortedBy { it.startMs }
            laps.forEachIndexed { i, lap ->
                val last = i == laps.lastIndex
                // The first lap also takes anything recorded before its start, the last anything after its end.
                val inLap = points.filter { p ->
                    val a = active.at(p.offsetMs)
                    (i == 0 || a >= lap.startMs) && (last || a < laps[i + 1].startMs)
                }
                val startMs = s.startedAtMs + (inLap.firstOrNull()?.offsetMs ?: active.wallAt(lap.startMs))
                appendLap(s, startMs, lap.durationMs, lap.distanceM, lap.avgBpm, lap.maxBpm, inLap)
            }
        }
        appendLine("<Creator><Name>Taal</Name></Creator>")
        appendLine("</Activity>")
        appendLine("</Activities>")
        appendLine("</TrainingCenterDatabase>")
    }

    /** TCX knows only these three sports; Strava's own type is set separately ([stravaSportType]). */
    fun sport(type: ActivityType): String = when (type) {
        ActivityType.RUN, ActivityType.TREADMILL, ActivityType.WALK -> "Running"
        ActivityType.CYCLE -> "Biking"
        ActivityType.STRENGTH, ActivityType.OTHER -> "Other"
    }

    private fun StringBuilder.appendLap(
        s: ExportSession, startMs: Long, durationMs: Long, distanceM: Double, avgBpm: Int?, maxBpm: Int?, points: List<Point>,
    ) {
        appendLine("""<Lap StartTime="${time(startMs)}">""")
        appendLine("<TotalTimeSeconds>${num(durationMs / 1000.0)}</TotalTimeSeconds>")
        appendLine("<DistanceMeters>${num(distanceM)}</DistanceMeters>")
        appendLine("<Calories>0</Calories>")
        avgBpm?.let { appendLine("<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>") }
        maxBpm?.let { appendLine("<MaximumHeartRateBpm><Value>$it</Value></MaximumHeartRateBpm>") }
        appendLine("<Intensity>Active</Intensity>")
        appendLine("<TriggerMethod>Manual</TriggerMethod>")
        for (track in tracks(points, s.segmentBreaksMs)) {
            appendLine("<Track>")
            track.forEach { appendTrackpoint(it, s) }
            appendLine("</Track>")
        }
        appendLine("</Lap>")
    }

    private data class Point(val offsetMs: Long, val geo: GeoPoint?)

    /** Trackpoints at the GPS fixes, or at the heart-rate / cadence samples when there is no route. */
    private fun points(s: ExportSession): List<Point> =
        if (s.points.isNotEmpty()) s.points.map { Point(it.offsetMs, it) }
        else (s.samples.map { it.offsetMs } + s.cadence.map { it.first }).distinct().sorted().map { Point(it, null) }

    /** [points] split at each resume. */
    private fun tracks(points: List<Point>, segmentBreaksMs: List<Long>): List<List<Point>> {
        if (points.isEmpty()) return emptyList()
        val breaks = segmentBreaksMs.sorted()
        return points.groupBy { p -> breaks.count { it <= p.offsetMs } }.toSortedMap().values.toList()
    }

    /**
     * Converts wall offsets (pauses included) to active time, which laps are measured in. Only resume times are
     * stored, so each pause is taken to start at the last thing recorded before its resume (within a few seconds:
     * nothing is recorded while paused).
     */
    private class ActiveClock(s: ExportSession, points: List<Point>) {
        private val recorded = (points.map { it.offsetMs } + s.samples.map { it.offsetMs } + s.cadence.map { it.first }).sorted()
        /** (resume wall offset, pause length), in order. */
        private val pauses = s.segmentBreaksMs.sorted().map { b ->
            val before = recorded.lastOrNull { it < b } ?: b
            b to (b - before).coerceAtLeast(0)
        }

        fun at(wallMs: Long): Long = wallMs - pauses.filter { it.first <= wallMs }.sumOf { it.second }

        fun wallAt(activeMs: Long): Long {
            var wall = activeMs
            for ((resume, length) in pauses) if (resume - length <= wall) wall += length
            return wall
        }
    }

    private fun StringBuilder.appendTrackpoint(p: Point, s: ExportSession) {
        append("<Trackpoint><Time>").append(time(s.startedAtMs + p.offsetMs)).append("</Time>")
        p.geo?.let { g ->
            append("<Position><LatitudeDegrees>").append(coord(g.lat)).append("</LatitudeDegrees>")
            append("<LongitudeDegrees>").append(coord(g.lon)).append("</LongitudeDegrees></Position>")
            g.altitudeM?.let { append("<AltitudeMeters>").append(num(it)).append("</AltitudeMeters>") }
            s.pointDistancesM[p.offsetMs]?.let { append("<DistanceMeters>").append(num(it)).append("</DistanceMeters>") }
        }
        nearest(s.samples, p.offsetMs, { it.offsetMs })?.let {
            append("<HeartRateBpm><Value>").append(it.bpm).append("</Value></HeartRateBpm>")
        }
        // Running cadence in TCX counts one foot (Garmin convention); Strava doubles it back to steps per minute.
        if (s.type.tracksCadence) nearest(s.cadence, p.offsetMs, { it.first })?.let { (_, spm) ->
            append("<Extensions><ns3:TPX><ns3:RunCadence>").append((spm / 2.0).roundToInt()).append("</ns3:RunCadence></ns3:TPX></Extensions>")
        }
        append("</Trackpoint>\n")
    }

    /** The value recorded closest to [offsetMs], if within [MATCH_WINDOW_MS]. [list] is sorted by offset. */
    private fun <T> nearest(list: List<T>, offsetMs: Long, offsetOf: (T) -> Long): T? {
        if (list.isEmpty()) return null
        var lo = 0
        var hi = list.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (offsetOf(list[mid]) < offsetMs) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(list.getOrNull(lo - 1), list[lo]).minBy { abs(offsetOf(it) - offsetMs) }
        return best.takeIf { abs(offsetOf(it) - offsetMs) <= MATCH_WINDOW_MS }
    }

    private fun time(epochMs: Long) = Instant.ofEpochMilli(epochMs - epochMs % 1000).toString()
    private fun coord(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun num(v: Double) = String.format(Locale.US, "%.1f", v)
}

/** Strava's sport_type for each activity type (set after upload, since TCX only knows run / bike / other). */
fun ActivityType.stravaSportType(): String = when (this) {
    ActivityType.RUN, ActivityType.TREADMILL -> "Run"
    ActivityType.WALK -> "Walk"
    ActivityType.CYCLE -> "Ride"
    ActivityType.STRENGTH -> "WeightTraining"
    ActivityType.OTHER -> "Workout"
}

/** Strava's "trainer" flag: indoors on a machine. */
val ActivityType.stravaTrainer: Boolean get() = this == ActivityType.TREADMILL
