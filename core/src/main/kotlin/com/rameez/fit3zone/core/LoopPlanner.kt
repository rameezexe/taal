package com.rameez.fit3zone.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What to plan: a loop of about [targetM] starting and ending at the start point. */
data class LoopRequest(
    val startLat: Double,
    val startLon: Double,
    val targetM: Double,
    val preferParks: Boolean = false,
    val count: Int = 3,
    val seed: Long = 1L,
)

/** One planned loop. Shares are of its length: [overlapShare] run twice (or right beside itself), [footShare] on footpaths or in parks, [busyShare] on bigger roads. */
class Loop(
    val route: Route,
    val bearingDeg: Double,
    val overlapShare: Double,
    val footShare: Double,
    val busyShare: Double,
    internal val edgeIds: Set<Int> = emptySet(),
) {
    val lengthM: Double get() = route.distanceM

    /** Share of the smaller loop's graph edges that [other] uses too (1 = the same loop). */
    internal fun sharedWith(other: Loop): Double {
        val n = minOf(edgeIds.size, other.edgeIds.size)
        return if (n == 0) 0.0 else edgeIds.count { it in other.edgeIds }.toDouble() / n
    }
}

/**
 * Plans running loops on a [RouteGraph], all on the phone. For each of several directions it puts 3 waypoints on a
 * circle through the start, joins start → W1 → W2 → W3 → start with shortest paths (paths already used in this loop
 * cost [REUSE_FACTOR] ×, so it comes back a different way), drops immediate U-turns, and grows or shrinks the circle
 * until the length is within [GOOD_ERROR]. The best loops by length error, repeats and busy roads, in different
 * directions, are returned (best first).
 */
object LoopPlanner {
    const val REUSE_FACTOR = 3.0
    const val GOOD_ERROR = 0.03
    private const val DIRECTIONS = 24
    private const val TRIES_PER_DIRECTION = 8
    /** Roads are ~1.25 × longer than the straight lines between the waypoints. */
    private const val DETOUR = 1.25
    /** No start within this of a path → no loop. */
    const val MAX_SNAP_M = 400.0
    /** Two loops sharing more of their paths than this count as the same loop. */
    private const val MAX_SHARED = 0.6
    private const val DOUBLED_M = 15.0
    private const val DOUBLED_GAP_M = 80.0
    private const val SAMPLE_M = 10.0

    fun plan(graph: RouteGraph, req: LoopRequest): List<Loop> {
        require(req.targetM > 0) { "target length must be positive" }
        if (graph.nodeCount == 0) return emptyList()
        val sx = graph.x(req.startLon)
        val sy = graph.y(req.startLat)
        // Start on a network big enough for the loop (not a lone footpath next to the start).
        val bigEnough = req.targetM * 1.5
        val largest = graph.componentLengthM.indices.maxBy { graph.componentLengthM[it] }
        val start = graph.nearestNode(sx, sy, MAX_SNAP_M) { graph.componentLengthM[graph.component[it]] >= bigEnough }
            ?: graph.nearestNode(sx, sy, MAX_SNAP_M) { graph.component[it] == largest }
            ?: return emptyList()
        val comp = graph.component[start]
        val search = PathSearch(graph, graph.edgeCosts(req.preferParks), if (req.preferParks) RouteGraph.PARK_FACTOR else 1.0)

        val offset = Random(req.seed).nextDouble(0.0, 2 * PI / DIRECTIONS)
        val found = ArrayList<Pair<Double, Loop>>()
        for (d in 0 until DIRECTIONS) for (clockwise in listOf(true, false)) {
            val theta = offset + d * 2 * PI / DIRECTIONS
            var r = req.targetM / (4 * sqrt(2.0) * DETOUR)
            // Length jumps as waypoints snap to other junctions, so scale r until the target is bracketed, then bisect.
            // Every try is kept: a slightly worse length may be a much nicer loop.
            var tooShort = 0.0
            var tooLong = Double.MAX_VALUE
            for (t in 0 until TRIES_PER_DIRECTION) {
                val loop = build(graph, search, start, comp, req, theta, r, clockwise) ?: break
                found += score(loop, req) to loop
                val err = loop.lengthM / req.targetM - 1
                if (abs(err) <= GOOD_ERROR) break
                if (err < 0) tooShort = maxOf(tooShort, r) else tooLong = minOf(tooLong, r)
                r = if (tooShort > 0 && tooLong < Double.MAX_VALUE) (tooShort + tooLong) / 2
                else r * (req.targetM / loop.lengthM).coerceIn(0.6, 1.6)
            }
        }
        return pickDiverse(found.sortedBy { it.first }.map { it.second }, req.count, req.targetM)
    }

    private fun build(
        graph: RouteGraph, search: PathSearch, start: Int, comp: Int, req: LoopRequest,
        theta: Double, r: Double, clockwise: Boolean,
    ): Loop? {
        val sx = graph.nodeX[start]
        val sy = graph.nodeY[start]
        // Circle of radius r whose centre lies in direction theta (0 = north, clockwise); the start is on it.
        val cx = sx + r * sin(theta)
        val cy = sy + r * cos(theta)
        val phi0 = atan2(sx - cx, sy - cy)
        val sign = if (clockwise) 1 else -1
        val stops = IntList().apply { add(start) }
        for (k in 1..3) {
            val phi = phi0 + sign * k * PI / 2
            val w = graph.nearestNode(cx + r * sin(phi), cy + r * cos(phi)) { graph.component[it] == comp } ?: return null
            if (w != stops.last()) stops.add(w)
        }
        if (stops.size < 3) return null
        stops.add(start)

        search.clearUsed()
        val edges = IntList()
        for (i in 1 until stops.size) {
            val leg = search.path(stops[i - 1], stops[i]) ?: return null
            for (k in 0 until leg.size) { edges.add(leg[k]); search.markUsed(leg[k]) }
        }
        val trimmed = dropUTurns(edges)
        if (trimmed.size == 0) return null
        return toLoop(graph, trimmed, start, req, theta)
    }

    /** Removes immediate back-and-forths (u → v → u on the same edge), e.g. a spur to a waypoint on a dead end. */
    private fun dropUTurns(edges: IntList): IntList {
        val out = IntList()
        for (k in 0 until edges.size) {
            val e = edges[k]
            if (out.size > 0 && out.last() == e) out.removeLast() else out.add(e)
        }
        return out
    }

    private fun toLoop(graph: RouteGraph, edges: IntList, start: Int, req: LoopRequest, theta: Double): Loop {
        val points = ArrayList<RoutePoint>()
        points += RoutePoint(req.startLat, req.startLon)
        var node = start
        var total = 0.0
        var foot = 0.0
        var busy = 0.0
        var repeated = 0.0
        val seen = HashSet<Int>()
        for (k in 0 until edges.size) {
            val e = edges[k]
            val len = graph.edgeLenM[e]
            total += len
            val kind = graph.edgeKind[e]
            if (kind.onFoot || graph.edgeInPark[e]) foot += len
            if (kind.busy) busy += len
            if (!seen.add(e)) repeated += len
            graph.appendEdge(points, e, node)
            node = graph.other(e, node)
        }
        points += RoutePoint(req.startLat, req.startLon)
        val line = Route("Loop", points)
        val route = Route(String.format(Locale.US, "Loop %.1f km", line.distanceM / 1000), line.points)
        val bearing = Math.toDegrees(theta).let { ((it % 360) + 360) % 360 }
        return Loop(route, bearing, maxOf(repeated / total, doubledShare(route)), foot / total, busy / total, seen)
    }

    /**
     * Share of the loop that runs within [DOUBLED_M] of another part of itself, e.g. out along a promenade and back on
     * the road beside it (different OSM ways, so not caught by edge reuse). Points [DOUBLED_GAP_M] apart along the loop
     * (either way round, so the start and finish meeting doesn't count) are compared, sampled every [SAMPLE_M].
     */
    internal fun doubledShare(route: Route): Double {
        val total = route.distanceM
        if (total < 2 * DOUBLED_GAP_M) return 0.0
        val n = (total / SAMPLE_M).toInt()
        val lat0 = route.points.first().lat
        val kx = cos(Math.toRadians(lat0)) * 111_320.0
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        var seg = 1
        for (i in 0 until n) {
            val along = i * SAMPLE_M
            while (seg < route.cumulativeM.size - 1 && route.cumulativeM[seg] < along) seg++
            val a = route.points[seg - 1]
            val b = route.points[seg]
            val span = route.cumulativeM[seg] - route.cumulativeM[seg - 1]
            val t = if (span <= 0) 0.0 else ((along - route.cumulativeM[seg - 1]) / span).coerceIn(0.0, 1.0)
            xs[i] = (a.lon + (b.lon - a.lon) * t - route.points.first().lon) * kx
            ys[i] = (a.lat + (b.lat - a.lat) * t - lat0) * 111_320.0
        }
        val cells = HashMap<Long, IntList>()
        fun key(cx: Int, cy: Int) = cx.toLong() * 1_000_003L + cy
        for (i in 0 until n) cells.getOrPut(key(floor(xs[i] / DOUBLED_M).toInt(), floor(ys[i] / DOUBLED_M).toInt())) { IntList() }.add(i)
        val gapSamples = (DOUBLED_GAP_M / SAMPLE_M).toInt()
        var doubled = 0
        for (i in 0 until n) {
            val cx = floor(xs[i] / DOUBLED_M).toInt()
            val cy = floor(ys[i] / DOUBLED_M).toInt()
            var hit = false
            loop@ for (dx in -1..1) for (dy in -1..1) {
                val list = cells[key(cx + dx, cy + dy)] ?: continue
                for (k in 0 until list.size) {
                    val j = list[k]
                    val apart = abs(i - j).let { minOf(it, n - it) }
                    if (apart < gapSamples) continue
                    val ddx = xs[i] - xs[j]
                    val ddy = ys[i] - ys[j]
                    if (ddx * ddx + ddy * ddy <= DOUBLED_M * DOUBLED_M) { hit = true; break@loop }
                }
            }
            if (hit) doubled++
        }
        return doubled.toDouble() / n
    }

    /** Lower is better: length error (steeply beyond 5 %), repeats, busy roads, and streets instead of footpaths/parks. */
    private fun score(loop: Loop, req: LoopRequest): Double {
        val err = abs(loop.lengthM / req.targetM - 1)
        val notGreen = (1 - loop.footShare) * if (req.preferParks) 0.6 else 0.15
        return err * 3 + (err - 0.05).coerceAtLeast(0.0) * 10 + loop.overlapShare + loop.busyShare * 0.4 + notGreen
    }

    /**
     * Best loops whose directions are ≥ 60° apart (then 30°, then any), taking clean loops (≤ 15 % doubled) within 5 %
     * of the length first, then clean ones within 10 %, then anything. Never two that mostly share their paths.
     */
    private fun pickDiverse(sorted: List<Loop>, count: Int, targetM: Double): List<Loop> {
        val picked = ArrayList<Loop>()
        fun clean(l: Loop, maxErr: Double) = abs(l.lengthM / targetM - 1) <= maxErr && l.overlapShare <= 0.15
        for (pool in listOf(sorted.filter { clean(it, 0.05) }, sorted.filter { clean(it, 0.10) }, sorted)) for (minGap in listOf(60.0, 30.0, 0.0)) {
            for (l in pool) {
                if (picked.size == count) return picked
                if (l !in picked && picked.all { angleGap(it.bearingDeg, l.bearingDeg) >= minGap && it.sharedWith(l) <= MAX_SHARED }) picked += l
            }
        }
        return picked
    }

    private fun angleGap(a: Double, b: Double): Double = abs(a - b).let { if (it > 180) 360 - it else it }
}

/**
 * A* on the graph with reusable arrays (one search per loop leg, so no allocation per search). Edge cost =
 * [baseCost] × [LoopPlanner.REUSE_FACTOR] once the edge was used earlier in the same loop. The straight-line
 * heuristic is scaled by the cheapest weight ([minWeight]) so it never overestimates.
 */
internal class PathSearch(private val g: RouteGraph, val baseCost: DoubleArray, minWeight: Double) {
    private val hScale = minWeight * 0.995
    private val dist = DoubleArray(g.nodeCount)
    private val parentEdge = IntArray(g.nodeCount)
    private val seenGen = IntArray(g.nodeCount)
    private val doneGen = IntArray(g.nodeCount)
    private var gen = 0
    private val used = IntArray(g.edgeCount)
    private val usedList = IntList()
    private val heap = MinHeap()

    fun clearUsed() { for (k in 0 until usedList.size) used[usedList[k]] = 0; usedList.clear() }
    fun markUsed(e: Int) { if (used[e]++ == 0) usedList.add(e) }

    /** Edges from [src] to [dst] in order, or null if they aren't connected. */
    fun path(src: Int, dst: Int): IntList? {
        if (src == dst) return IntList()
        gen++
        heap.clear()
        val tx = g.nodeX[dst]
        val ty = g.nodeY[dst]
        fun h(n: Int): Double { val dx = g.nodeX[n] - tx; val dy = g.nodeY[n] - ty; return sqrt(dx * dx + dy * dy) * hScale }
        dist[src] = 0.0; seenGen[src] = gen; parentEdge[src] = -1
        heap.push(h(src), src)
        while (heap.size > 0) {
            val u = heap.popNode()
            if (doneGen[u] == gen) continue
            doneGen[u] = gen
            if (u == dst) break
            val du = dist[u]
            for (k in g.adjFrom(u) until g.adjFrom(u + 1)) {
                val e = g.adjAt(k)
                val v = g.other(e, u)
                if (doneGen[v] == gen) continue
                val nd = du + baseCost[e] * (if (used[e] > 0) LoopPlanner.REUSE_FACTOR else 1.0)
                if (seenGen[v] != gen || nd < dist[v]) {
                    seenGen[v] = gen; dist[v] = nd; parentEdge[v] = e
                    heap.push(nd + h(v), v)
                }
            }
        }
        if (doneGen[dst] != gen) return null
        val rev = IntList()
        var n = dst
        while (n != src) { val e = parentEdge[n]; rev.add(e); n = g.other(e, n) }
        val out = IntList()
        for (k in rev.size - 1 downTo 0) out.add(rev[k])
        return out
    }
}

/** Binary min-heap of (key, node) with lazy deletion (stale entries are skipped by the caller). */
internal class MinHeap {
    private var keys = DoubleArray(1024)
    private var nodes = IntArray(1024)
    var size = 0; private set

    fun clear() { size = 0 }

    fun push(key: Double, node: Int) {
        if (size == keys.size) { keys = keys.copyOf(size * 2); nodes = nodes.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (keys[p] <= key) break
            keys[i] = keys[p]; nodes[i] = nodes[p]; i = p
        }
        keys[i] = key; nodes[i] = node
    }

    fun popNode(): Int {
        val top = nodes[0]
        size--
        if (size > 0) {
            val key = keys[size]
            val node = nodes[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && keys[c + 1] < keys[c]) c++
                if (keys[c] >= key) break
                keys[i] = keys[c]; nodes[i] = nodes[c]; i = c
            }
            keys[i] = key; nodes[i] = node
        }
        return top
    }
}
