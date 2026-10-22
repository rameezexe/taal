package com.rameez.fit3zone.core

import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A point of a planned route; [eleM] in metres above sea level when the source had it. */
data class RoutePoint(val lat: Double, val lon: Double, val eleM: Double? = null)

/** A planned route (imported GPX or a generated loop): the line to follow, with its length and climb. */
class Route(val name: String, points: List<RoutePoint>) {
    val points: List<RoutePoint> = dropDuplicates(points)

    /** Distance from the start to each point, metres. */
    val cumulativeM: DoubleArray = DoubleArray(this.points.size).also { c ->
        for (i in 1 until c.size) c[i] = c[i - 1] + metres(this.points[i - 1], this.points[i])
    }

    val distanceM: Double get() = if (cumulativeM.isEmpty()) 0.0 else cumulativeM.last()

    /** Ends near where it starts (a loop), so "Route complete" needs the whole length, not just reaching the start. */
    val isLoop: Boolean get() = points.size > 2 && metres(points.first(), points.last()) <= LOOP_CLOSE_M && distanceM > 2 * LOOP_CLOSE_M

    /** Climb and descent from point elevations, smoothed over ~[ELE_WINDOW_M] with a 3 m hysteresis like [ElevationProfile]. */
    val ascentM: Double? get() = climb.first
    val descentM: Double? get() = climb.second

    /** (distance m, elevation m) for an elevation chart; empty without elevations. */
    val elevationProfile: List<Pair<Double, Double>> by lazy {
        if (points.count { it.eleM != null } < 2) emptyList()
        else smoothedEle().mapIndexedNotNull { i, e -> e?.let { cumulativeM[i] to it } }
    }

    private val climb: Pair<Double?, Double?> by lazy {
        val prof = elevationProfile
        if (prof.size < 2) return@lazy null to null
        var gain = 0.0
        var loss = 0.0
        var ref = prof.first().second
        for ((_, h) in prof) {
            if (h - ref >= 3.0) { gain += h - ref; ref = h } else if (ref - h >= 3.0) { loss += ref - h; ref = h }
        }
        gain to loss
    }

    private fun smoothedEle(): List<Double?> {
        var lo = 0
        var hi = 0
        var sum = 0.0
        var n = 0
        return points.indices.map { i ->
            while (hi < points.size && cumulativeM[hi] <= cumulativeM[i] + ELE_WINDOW_M / 2) {
                points[hi].eleM?.let { sum += it; n++ }; hi++
            }
            while (cumulativeM[lo] < cumulativeM[i] - ELE_WINDOW_M / 2) {
                points[lo].eleM?.let { sum -= it; n-- }; lo++
            }
            if (points[i].eleM == null || n == 0) null else sum / n
        }
    }

    /** The same route with at most [maxPoints] points (Douglas–Peucker, tolerance grown until it fits). */
    fun simplified(maxPoints: Int = MAX_POINTS): Route {
        if (points.size <= maxPoints) return this
        var tol = 1.0
        var kept = douglasPeucker(points, tol)
        while (kept.size > maxPoints) { tol *= 1.5; kept = douglasPeucker(points, tol) }
        return Route(name, kept)
    }

    companion object {
        const val MAX_POINTS = 2_000
        const val LOOP_CLOSE_M = 100.0
        private const val ELE_WINDOW_M = 60.0

        fun metres(a: RoutePoint, b: RoutePoint): Double = RouteTracker.haversineM(a.lat, a.lon, b.lat, b.lon)

        internal fun dropDuplicates(points: List<RoutePoint>): List<RoutePoint> {
            val out = ArrayList<RoutePoint>(points.size)
            for (p in points) if (out.isEmpty() || metres(out.last(), p) >= 0.5) out += p
            return out
        }

        private fun douglasPeucker(points: List<RoutePoint>, tolM: Double): List<RoutePoint> {
            if (points.size < 3) return points
            val keep = BooleanArray(points.size).also { it[0] = true; it[it.size - 1] = true }
            val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.size - 1) }
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var worst = -1
                var worstD = tolM
                for (i in a + 1 until b) {
                    val d = Geo.distanceToSegmentM(points[i].lat, points[i].lon, points[a], points[b]).first
                    if (d > worstD) { worstD = d; worst = i }
                }
                if (worst >= 0) { keep[worst] = true; stack.add(a to worst); stack.add(worst to b) }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }
    }
}

/** Flat-earth geometry around a point; plenty accurate for route segments of a few hundred metres. */
internal object Geo {
    private const val M_PER_DEG = 111_320.0

    /** Distance from a point to the route position [alongM] metres from its start. */
    fun distanceAt(lat: Double, lon: Double, route: Route, alongM: Double): Double {
        val c = route.cumulativeM
        var i = c.indexOfFirst { it >= alongM }.let { if (it <= 0) 1 else it }
        if (i >= c.size) i = c.size - 1
        val a = route.points[i - 1]
        val b = route.points[i]
        val t = if (c[i] == c[i - 1]) 0.0 else ((alongM - c[i - 1]) / (c[i] - c[i - 1])).coerceIn(0.0, 1.0)
        return RouteTracker.haversineM(lat, lon, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** (distance from the point to segment a–b in metres, position along the segment 0..1). */
    fun distanceToSegmentM(lat: Double, lon: Double, a: RoutePoint, b: RoutePoint): Pair<Double, Double> {
        val k = cos(Math.toRadians(lat)) * M_PER_DEG
        val bx = (b.lon - a.lon) * k
        val by = (b.lat - a.lat) * M_PER_DEG
        val px = (lon - a.lon) * k
        val py = (lat - a.lat) * M_PER_DEG
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy) to t
    }
}

/**
 * GPX 1.0 / 1.1 reading and writing. Reading is a tolerant scan for track, route or waypoint points (in that order
 * of preference: Strava and Komoot export tracks, some planners routes, GPS-art sites sometimes only waypoints);
 * extensions and namespaces are ignored. No XML parser, so no DTD or external-entity handling to worry about.
 */
object Gpx {
    private val POINT = Regex("""<(?:\w+:)?(trkpt|rtept|wpt)\b([^>]*?)(/>|>(.*?)</(?:\w+:)?\1\s*>)""", RegexOption.DOT_MATCHES_ALL)
    private val LAT = Regex("""\blat\s*=\s*["']([-+0-9.eE]+)["']""")
    private val LON = Regex("""\blon\s*=\s*["']([-+0-9.eE]+)["']""")
    private val ELE = Regex("""<(?:\w+:)?ele\s*>\s*([-+0-9.eE]+)\s*<""")
    private val NAME = Regex("""<(?:\w+:)?name\s*>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</(?:\w+:)?name\s*>""", RegexOption.DOT_MATCHES_ALL)
    private val TRK_NAME = Regex("""<(?:\w+:)?(?:trk|rte)\b[^>]*>\s*<(?:\w+:)?name\s*>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</""", RegexOption.DOT_MATCHES_ALL)

    class FormatException(message: String) : Exception(message)

    /** Reads the route from a GPX file; [fallbackName] (e.g. the file name) when the file has none. */
    fun read(xml: String, fallbackName: String = "Imported route"): Route {
        if (!xml.contains("<gpx", ignoreCase = true)) throw FormatException("Not a GPX file")
        val byKind = POINT.findAll(xml).mapNotNull { m ->
            val attrs = m.groupValues[2]
            val lat = LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            if (abs(lat) > 90 || abs(lon) > 180) return@mapNotNull null
            val ele = ELE.find(m.groupValues[4])?.groupValues?.get(1)?.toDoubleOrNull()
            m.groupValues[1] to RoutePoint(lat, lon, ele)
        }.groupBy({ it.first }, { it.second })
        val points = byKind["trkpt"]?.takeIf { it.size >= 2 } ?: byKind["rtept"]?.takeIf { it.size >= 2 }
            ?: byKind["wpt"]?.takeIf { it.size >= 2 } ?: throw FormatException("No route in this GPX file")
        val name = (TRK_NAME.find(xml) ?: NAME.find(xml))?.groupValues?.get(1)?.let(::unescape)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: fallbackName
        val route = Route(name.take(80), points).simplified()
        if (route.distanceM < 50) throw FormatException("This route is too short")
        return route
    }

    /** A planned route as a GPX track (what Strava, Komoot and watches import as a route). */
    fun write(route: Route, attribution: String? = null): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Taal" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>").append(escape(route.name)).append("</name>")
        attribution?.let { append("<copyright author=\"").append(escape(it)).append("\"/>") }
        append("</metadata>\n  <trk><name>").append(escape(route.name)).append("</name><trkseg>\n")
        for (p in route.points) {
            append("    <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
            p.eleM?.let { append("<ele>").append(num(it)).append("</ele>") }
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    /** A recorded activity: one track segment per resumed stretch, time, elevation and heart rate (Garmin extension). */
    fun writeActivity(s: ExportSession, name: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Taal" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""").append('\n')
        append("  <metadata><name>").append(escape(name)).append("</name><time>").append(time(s.startedAtMs)).append("</time></metadata>\n")
        append("  <trk><name>").append(escape(name)).append("</name><type>").append(TcxWriter.sport(s.type).lowercase()).append("</type>\n")
        val breaks = s.segmentBreaksMs.sorted()
        val segments = s.points.groupBy { p -> breaks.count { it <= p.offsetMs } }.toSortedMap().values
        for (seg in segments) {
            append("    <trkseg>\n")
            for (p in seg) {
                append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
                p.altitudeM?.let { append("<ele>").append(num(it)).append("</ele>") }
                append("<time>").append(time(s.startedAtMs + p.offsetMs)).append("</time>")
                nearestBpm(s.samples, p.offsetMs)?.let {
                    append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it)
                        .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                }
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private fun nearestBpm(samples: List<HrSample>, offsetMs: Long): Int? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (samples[mid].offsetMs < offsetMs) lo = mid + 1 else hi = mid }
        val best = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1)).minBy { abs(it.offsetMs - offsetMs) }
        return best.bpm.takeIf { abs(best.offsetMs - offsetMs) <= TcxWriter.MATCH_WINDOW_MS }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun time(epochMs: Long) = Instant.ofEpochMilli(epochMs - epochMs % 1000).toString()
    private fun coord(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun num(v: Double) = String.format(Locale.US, "%.1f", v)
}

/** What [RouteFollower.update] reports; the session turns these into vibration and voice cues. */
sealed interface RouteEvent {
    /** Left the route for [RouteFollower.confirmOffMs]; [repeat] = still off, reminder. */
    data class OffRoute(val distanceM: Double, val repeat: Boolean) : RouteEvent
    data object BackOnRoute : RouteEvent
    data object Complete : RouteEvent
}

/**
 * Follows a [Route] from accepted GPS fixes during a run.
 *
 * Progress is searched **forward from the last progress** within a window ([aheadM], widened while off route by the
 * distance moved since), so routes that repeat themselves (4 laps of a park loop, out-and-back) don't jump to the
 * wrong lap. Before the runner first reaches the route ([acquired]) the whole route is searched and the earliest
 * close match wins (start of lap 1), and there are no off-route alerts — walking to the start isn't "off route".
 *
 * Off route = farther than [offRouteM] for [confirmOffMs] of continuous fixes (a fix gap longer than [maxFixGapMs]
 * restarts the count: no alerts across a GPS gap). Back on = within [backOnM] for [confirmBackMs]. While off,
 * a reminder repeats every [repeatMs]. Complete = progress ≥ 98 % and within [finishM] of the end.
 */
class RouteFollower(
    val route: Route,
    val offRouteM: Double = 35.0,
    val backOnM: Double = 20.0,
    val confirmOffMs: Long = 12_000,
    val confirmBackMs: Long = 3_000,
    val repeatMs: Long = 60_000,
    val aheadM: Double = 300.0,
    val behindM: Double = 50.0,
    val finishM: Double = 50.0,
    val maxFixGapMs: Long = 5_000,
) {
    var acquired = false; private set
    var offRoute = false; private set
    var complete = false; private set
    /** Distance along the route of the latest match, metres. */
    var progressM = 0.0; private set
    /** Distance from the latest fix to the route, metres (null before the first fix). */
    var distanceFromRouteM: Double? = null; private set
    /** Route metres passed while on the route (for "95 % followed"). */
    var followedM = 0.0; private set

    val remainingM: Double get() = (route.distanceM - progressM).coerceAtLeast(0.0)
    val followedFraction: Double get() = if (route.distanceM <= 0) 0.0 else (followedM / route.distanceM).coerceIn(0.0, 1.0)

    private var lastFix: Triple<Long, Double, Double>? = null
    private var offSince: Long? = null
    private var backSince: Long? = null
    private var lastOffAlertMs = 0L
    private var movedWhileOffM = 0.0
    private var lastOnRouteProgressM = 0.0
    private var runDistanceAtLastMatchM: Double? = null

    /**
     * Feed every accepted fix. [runDistanceM] = the session's distance so far (incl. GPS gaps bridged by steps): after
     * a gap it tells how far along the route the runner should be, so a lap route picks the right lap.
     */
    fun update(offsetMs: Long, lat: Double, lon: Double, runDistanceM: Double? = null): RouteEvent? {
        if (route.points.size < 2) return null
        val prev = lastFix
        val gap = prev == null || offsetMs - prev.first > maxFixGapMs
        if (prev != null && offRoute) movedWhileOffM += RouteTracker.haversineM(prev.second, prev.third, lat, lon)
        lastFix = Triple(offsetMs, lat, lon)

        val movedM = runDistanceM?.let { d -> runDistanceAtLastMatchM?.let { (d - it).coerceAtLeast(0.0) } }
        val (dist, along) = match(lat, lon, movedM)
        distanceFromRouteM = dist
        if (!acquired) {
            if (dist > backOnM) return null
            acquired = true
            runDistanceAtLastMatchM = runDistanceM
            progressM = along
            lastOnRouteProgressM = along
            return null
        }
        // Progress only moves forward, and only from fixes near the route (a parallel street doesn't count).
        if (dist <= offRouteM) {
            progressM = max(progressM, along)
            runDistanceAtLastMatchM = runDistanceM
        }

        if (dist <= offRouteM && !offRoute) {
            // On route: credit the stretch since the last on-route match (not a jump across a shortcut).
            if (progressM > lastOnRouteProgressM && progressM - lastOnRouteProgressM <= aheadM) followedM += progressM - lastOnRouteProgressM
            lastOnRouteProgressM = max(lastOnRouteProgressM, progressM)
        }

        var event: RouteEvent? = null
        if (!offRoute) {
            offSince = if (dist > offRouteM) (if (gap) offsetMs else offSince ?: offsetMs) else null
            val since = offSince
            if (since != null && offsetMs - since >= confirmOffMs) {
                offRoute = true
                movedWhileOffM = 0.0
                lastOffAlertMs = offsetMs
                backSince = null
                event = RouteEvent.OffRoute(dist, repeat = false)
            }
        } else {
            backSince = if (dist <= backOnM) (if (gap) offsetMs else backSince ?: offsetMs) else null
            val since = backSince
            if (since != null && offsetMs - since >= confirmBackMs) {
                offRoute = false
                offSince = null
                lastOnRouteProgressM = progressM
                event = RouteEvent.BackOnRoute
            } else if (dist > offRouteM && offsetMs - lastOffAlertMs >= repeatMs && !gap) {
                lastOffAlertMs = offsetMs
                event = RouteEvent.OffRoute(dist, repeat = true)
            }
        }
        if (!complete && !offRoute && progressM >= route.distanceM * 0.98) {
            val end = route.points.last()
            if (RouteTracker.haversineM(lat, lon, end.lat, end.lon) <= finishM) {
                complete = true
                progressM = route.distanceM
                followedM = min(route.distanceM, followedM + (route.distanceM - lastOnRouteProgressM).coerceIn(0.0, aheadM))
                return event ?: RouteEvent.Complete
            }
        }
        return event
    }

    /** (distance to the route, distance along it) of the best segment in the search window. */
    private fun match(lat: Double, lon: Double, movedM: Double?): Pair<Double, Double> {
        val pts = route.points
        val cum = route.cumulativeM
        val from: Double
        val to: Double
        if (!acquired) { from = 0.0; to = route.distanceM } else {
            from = progressM - behindM
            to = progressM + aheadM + max(if (offRoute) movedWhileOffM * 1.5 else 0.0, (movedM ?: 0.0) * 1.2)
        }
        // Where the runner should be by distance run; on a lap route several laps pass close by, so the one nearest
        // this wins among close candidates.
        val expected = if (acquired && movedM != null) progressM + movedM else progressM
        var closeAlong: Double? = null
        var bestD = Double.MAX_VALUE
        var bestAlong = progressM
        for (i in 0 until pts.size - 1) {
            if (cum[i + 1] < from) continue
            if (cum[i] > to) break
            val (d, t) = Geo.distanceToSegmentM(lat, lon, pts[i], pts[i + 1])
            val along = cum[i] + t * (cum[i + 1] - cum[i])
            // Before acquiring: the earliest segment that is close enough wins (lap 1, not a later lap).
            if (!acquired && d <= backOnM) return d to along
            if (d < bestD - 0.5 || (abs(d - bestD) <= 0.5 && abs(along - expected) < abs(bestAlong - expected))) {
                bestD = d; bestAlong = along
            }
            if (d <= backOnM && (closeAlong == null || abs(along - expected) < abs(closeAlong - expected))) closeAlong = along
        }
        // Prefer the close candidate nearest the expected position over a marginally closer one on another lap.
        closeAlong?.let { a -> if (abs(a - bestAlong) > aheadM / 2) return Geo.distanceAt(lat, lon, route, a) to a }
        return bestD to bestAlong
    }
}
