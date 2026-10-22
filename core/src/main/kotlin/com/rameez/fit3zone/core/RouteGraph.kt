package com.rameez.fit3zone.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How pleasant a path is to run on; [weight] multiplies its length in route costs (1.0 = a footpath). */
enum class PathKind(val weight: Double, val onFoot: Boolean, val busy: Boolean) {
    FOOT(1.0, true, false),
    STEPS(1.5, true, false),
    MINOR(1.1, false, false),
    OTHER(1.3, false, false),
    TERTIARY(1.4, false, true),
    SECONDARY(1.8, false, true),
    PRIMARY(2.5, false, true),
    TRUNK(3.0, false, true);

    companion object {
        fun of(highway: String?): PathKind = when (highway) {
            "footway", "path", "pedestrian", "track", "living_street", "cycleway" -> FOOT
            "steps" -> STEPS
            "residential", "service", "unclassified" -> MINOR
            "tertiary", "tertiary_link" -> TERTIARY
            "secondary", "secondary_link" -> SECONDARY
            "primary", "primary_link" -> PRIMARY
            "trunk", "trunk_link" -> TRUNK
            else -> OTHER
        }
    }
}

/**
 * The walkable network of one [OsmData] download: graph nodes are way ends and vertices shared by ways (junctions),
 * edges the way pieces between them with their geometry. Undirected (one-way streets are fine on foot). Positions are
 * also kept as metres east/north of the area's centre ([nodeX], [nodeY]) for fast distance estimates.
 */
class RouteGraph private constructor(
    val nodeLat: DoubleArray,
    val nodeLon: DoubleArray,
    val edgeFrom: IntArray,
    val edgeTo: IntArray,
    val edgeLenM: DoubleArray,
    val edgeKind: Array<PathKind>,
    val edgeInPark: BooleanArray,
    /** Geometry of edge e (from → to, both ends included) is [geomLat]/[geomLon] at geomStart[e] until geomStart[e + 1]. */
    val geomStart: IntArray,
    val geomLat: DoubleArray,
    val geomLon: DoubleArray,
    private val adjStart: IntArray,
    private val adjEdge: IntArray,
    private val originLat: Double,
    private val originLon: Double,
) {
    val nodeCount: Int get() = nodeLat.size
    val edgeCount: Int get() = edgeFrom.size

    private val kx = cos(Math.toRadians(originLat)) * M_PER_DEG
    val nodeX = DoubleArray(nodeCount) { (nodeLon[it] - originLon) * kx }
    val nodeY = DoubleArray(nodeCount) { (nodeLat[it] - originLat) * M_PER_DEG }

    fun x(lon: Double) = (lon - originLon) * kx
    fun y(lat: Double) = (lat - originLat) * M_PER_DEG
    fun lat(y: Double) = originLat + y / M_PER_DEG
    fun lon(x: Double) = originLon + x / kx

    /** Connected component of each node, and the total path length of each component. */
    val component: IntArray
    val componentLengthM: DoubleArray

    init {
        component = IntArray(nodeCount) { -1 }
        val lengths = DoubleList()
        val stack = IntList()
        var c = 0
        for (s in 0 until nodeCount) {
            if (component[s] >= 0) continue
            component[s] = c
            stack.add(s)
            var total = 0.0
            while (stack.size > 0) {
                val u = stack.removeLast()
                forEachEdge(u) { e ->
                    val v = other(e, u)
                    if (edgeFrom[e] == u) total += edgeLenM[e]
                    if (component[v] < 0) { component[v] = c; stack.add(v) }
                }
            }
            lengths.add(total)
            c++
        }
        componentLengthM = lengths.toArray()
    }

    inline fun forEachEdge(node: Int, action: (Int) -> Unit) {
        for (k in adjFrom(node) until adjFrom(node + 1)) action(adjAt(k))
    }

    fun adjFrom(node: Int): Int = adjStart[node]
    fun adjAt(k: Int): Int = adjEdge[k]
    fun degree(node: Int): Int = adjStart[node + 1] - adjStart[node]
    fun other(edge: Int, node: Int): Int = if (edgeFrom[edge] == node) edgeTo[edge] else edgeFrom[edge]

    private val grid: NodeGrid by lazy { NodeGrid(nodeX, nodeY, GRID_CELL_M) }

    /** Nearest node to (x, y) among those [accept]s, and its distance; null if there is none within [maxM]. */
    fun nearestNode(x: Double, y: Double, maxM: Double = Double.MAX_VALUE, accept: (Int) -> Boolean = { true }): Int? =
        grid.nearest(x, y, maxM, accept)

    /** Route cost of each edge: length × [PathKind.weight], × [PARK_FACTOR] inside parks when [preferParks]. */
    fun edgeCosts(preferParks: Boolean) = DoubleArray(edgeCount) { e ->
        val parkFactor = if (preferParks && edgeInPark[e]) PARK_FACTOR else 1.0
        edgeLenM[e] * edgeKind[e].weight * parkFactor
    }

    /** Appends edge [e]'s geometry walked from node [from] to [points] (both ends included). */
    fun appendEdge(points: MutableList<RoutePoint>, e: Int, from: Int) {
        val a = geomStart[e]
        val b = geomStart[e + 1] - 1
        if (edgeFrom[e] == from) for (j in a..b) points += RoutePoint(geomLat[j], geomLon[j])
        else for (j in b downTo a) points += RoutePoint(geomLat[j], geomLon[j])
    }

    /**
     * A point on edge [edge]: on its geometry piece from vertex [seg] to [seg] + 1 at fraction [t], [distM] from the
     * point asked for, [alongM] metres from the edge's `from` end.
     */
    class EdgePoint(val edge: Int, val seg: Int, val t: Double, val lat: Double, val lon: Double, val distM: Double, val alongM: Double)

    private val segments: SegmentGrid by lazy { SegmentGrid(this, SEGMENT_CELL_M) }

    /**
     * The nearest point on a path to (lat, lon), or null if no path is within [maxM]. Paths on a network of at least
     * [minNetworkM] (all connected paths together) win over closer bits of path that lead nowhere, e.g. a 5 m footway
     * mapped on its own; those are only used when nothing else is in reach.
     */
    fun snap(lat: Double, lon: Double, maxM: Double, minNetworkM: Double = MIN_NETWORK_M): EdgePoint? =
        segments.nearest(x(lon), y(lat), maxM) { componentLengthM[component[edgeFrom[it]]] >= minNetworkM }
            ?: segments.nearest(x(lon), y(lat), maxM) { true }

    /** The nearest point of each path within [maxM] of (lat, lon), nearest first, at most [limit] (for matching a drawn line). */
    fun candidates(lat: Double, lon: Double, maxM: Double, limit: Int): List<EdgePoint> =
        segments.candidates(x(lon), y(lat), maxM, limit) { componentLengthM[component[edgeFrom[it]]] >= MIN_NETWORK_M }

    private val searches = arrayOfNulls<PathSearch>(2)

    /**
     * The best path (by [edgeCosts]) between the points on the network nearest to (fromLat, fromLon) and (toLat, toLon),
     * from the first snapped point to the second; null if either is more than [maxSnapM] from a path or they aren't
     * connected. Not thread-safe across callers, hence synchronized (the searches reuse arrays).
     */
    @Synchronized
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, preferParks: Boolean, maxSnapM: Double = 150.0): List<RoutePoint>? {
        val a = snap(fromLat, fromLon, maxSnapM) ?: return null
        val b = snap(toLat, toLon, maxSnapM) ?: return null
        val k = if (preferParks) 1 else 0
        val search = searches[k] ?: PathSearch(this, edgeCosts(preferParks), if (preferParks) PARK_FACTOR else 1.0).also { searches[k] = it }
        fun perM(e: Int) = search.baseCost[e] / edgeLenM[e]
        fun toEnd(p: EdgePoint, n: Int) = (if (n == edgeFrom[p.edge]) p.alongM else edgeLenM[p.edge] - p.alongM) * perM(p.edge)

        // Along the shared edge, or from an end of a's edge to an end of b's (4 searches on a small graph: a few ms).
        var bestCost = if (a.edge == b.edge) abs(a.alongM - b.alongM) * perM(a.edge) else Double.MAX_VALUE
        var bestPath: IntList? = null
        var bestA = -1
        var bestB = -1
        for (na in intArrayOf(edgeFrom[a.edge], edgeTo[a.edge])) for (nb in intArrayOf(edgeFrom[b.edge], edgeTo[b.edge])) {
            val p = search.path(na, nb) ?: continue
            var cost = toEnd(a, na) + toEnd(b, nb)
            for (i in 0 until p.size) cost += search.baseCost[p[i]]
            if (cost < bestCost) { bestCost = cost; bestPath = p; bestA = na; bestB = nb }
        }
        if (bestCost == Double.MAX_VALUE) return null

        val out = ArrayList<RoutePoint>()
        out += RoutePoint(a.lat, a.lon)
        val path = bestPath
        if (path == null) {
            // Same edge: the vertices between the two points, in the direction of travel.
            if (a.alongM <= b.alongM) for (j in a.seg + 1..b.seg) out += vertex(j) else for (j in a.seg downTo b.seg + 1) out += vertex(j)
        } else {
            walkToEnd(out, a, bestA)
            var node = bestA
            for (i in 0 until path.size) { appendEdge(out, path[i], node); node = other(path[i], node) }
            val back = ArrayList<RoutePoint>()
            walkToEnd(back, b, bestB)
            for (i in back.size - 1 downTo 1) out += back[i]
        }
        out += RoutePoint(b.lat, b.lon)
        return Route.dropDuplicates(out)
    }

    private fun vertex(j: Int) = RoutePoint(geomLat[j], geomLon[j])

    /** Vertices from point [p] along its edge to node [n] (the point itself excluded, the node included). */
    private fun walkToEnd(out: MutableList<RoutePoint>, p: EdgePoint, n: Int) {
        val e = p.edge
        if (n == edgeFrom[e]) for (j in p.seg downTo geomStart[e]) out += vertex(j)
        else for (j in p.seg + 1 until geomStart[e + 1]) out += vertex(j)
    }

    companion object {
        private const val M_PER_DEG = 111_320.0
        private const val GRID_CELL_M = 250.0
        private const val SEGMENT_CELL_M = 100.0
        const val MIN_NETWORK_M = 1_000.0
        /** "Prefer parks and footpaths" multiplies edges inside a park by this. */
        const val PARK_FACTOR = 0.8

        fun build(data: OsmData): RouteGraph {
            val ways = data.ways.mapNotNull(::withoutGaps).flatten()
            // A vertex becomes a graph node when it ends a way or is used more than once (a junction). A closed way
            // also gets its middle vertex as a node, so a ring hanging off one junction is a real loop, not a self-edge.
            val uses = LongIntMap()
            for (w in ways) {
                for (id in w.nodeIds) uses.add(id, 1)
                uses.add(w.nodeIds.first(), 2)
                uses.add(w.nodeIds.last(), 2)
                if (w.isClosed) uses.add(w.nodeIds[w.nodeIds.size / 2], 2)
            }
            val index = LongIntMap()
            val nLat = DoubleList()
            val nLon = DoubleList()
            fun node(w: OsmWay, k: Int): Int {
                val id = w.nodeIds[k]
                index.get(id).let { if (it >= 0) return it }
                index.put(id, nLat.size)
                nLat.add(w.lats[k]); nLon.add(w.lons[k])
                return nLat.size - 1
            }
            val from = IntList()
            val to = IntList()
            val len = DoubleList()
            val kinds = ArrayList<PathKind>()
            val gStart = IntList()
            val gLat = DoubleList()
            val gLon = DoubleList()
            for (w in ways) {
                val kind = PathKind.of(w.tags["highway"])
                var startK = 0
                var acc = 0.0
                for (k in 1 until w.nodeIds.size) {
                    acc += RouteTracker.haversineM(w.lats[k - 1], w.lons[k - 1], w.lats[k], w.lons[k])
                    if (uses.get(w.nodeIds[k]) < 2 && k < w.nodeIds.size - 1) continue
                    val a = node(w, startK)
                    val b = node(w, k)
                    if (a != b && acc > 0.0) {
                        from.add(a); to.add(b); len.add(acc); kinds += kind
                        gStart.add(gLat.size)
                        for (j in startK..k) { gLat.add(w.lats[j]); gLon.add(w.lons[j]) }
                    }
                    startK = k
                    acc = 0.0
                }
            }
            gStart.add(gLat.size)
            val n = nLat.size
            val eFrom = from.toArray()
            val eTo = to.toArray()
            val adjStart = IntArray(n + 1)
            for (e in eFrom.indices) { adjStart[eFrom[e] + 1]++; adjStart[eTo[e] + 1]++ }
            for (i in 0 until n) adjStart[i + 1] += adjStart[i]
            val fill = adjStart.copyOf()
            val adj = IntArray(eFrom.size * 2)
            for (e in eFrom.indices) { adj[fill[eFrom[e]]++] = e; adj[fill[eTo[e]]++] = e }

            val latArr = nLat.toArray()
            val lonArr = nLon.toArray()
            val oLat = if (n == 0) 0.0 else (latArr.min() + latArr.max()) / 2
            val oLon = if (n == 0) 0.0 else (lonArr.min() + lonArr.max()) / 2
            val geomStart = gStart.toArray()
            val geomLat = gLat.toArray()
            val geomLon = gLon.toArray()
            val parks = data.parks.map(::Polygon)
            val inPark = BooleanArray(eFrom.size) { e ->
                val mid = (geomStart[e] + geomStart[e + 1] - 1) / 2
                val lat = (geomLat[geomStart[e]] + geomLat[geomStart[e + 1] - 1]) / 2 * 0.5 + geomLat[mid] * 0.5
                val lon = (geomLon[geomStart[e]] + geomLon[geomStart[e + 1] - 1]) / 2 * 0.5 + geomLon[mid] * 0.5
                parks.any { it.contains(lat, lon) }
            }
            return RouteGraph(
                latArr, lonArr, eFrom, eTo, len.toArray(), kinds.toTypedArray(), inPark,
                geomStart, geomLat, geomLon, adjStart, adj, oLat, oLon,
            )
        }

        /** Splits a way where Overpass left out vertex positions (null geometry), dropping pieces under 2 vertices. */
        private fun withoutGaps(w: OsmWay): List<OsmWay>? {
            if (w.lats.none { it.isNaN() } && w.lons.none { it.isNaN() }) return listOf(w)
            val out = ArrayList<OsmWay>()
            var s = 0
            for (k in 0..w.nodeIds.size) {
                if (k == w.nodeIds.size || w.lats[k].isNaN() || w.lons[k].isNaN()) {
                    if (k - s >= 2) out += OsmWay(w.id, w.nodeIds.copyOfRange(s, k), w.lats.copyOfRange(s, k), w.lons.copyOfRange(s, k), w.tags)
                    s = k + 1
                }
            }
            return out.ifEmpty { null }
        }
    }

    /** A closed way as a polygon, with a bounding box to skip most point tests. */
    private class Polygon(w: OsmWay) {
        private val lats = w.lats
        private val lons = w.lons
        private val minLat = lats.min(); private val maxLat = lats.max()
        private val minLon = lons.min(); private val maxLon = lons.max()

        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
            var inside = false
            var j = lats.size - 1
            for (i in lats.indices) {
                if ((lats[i] > lat) != (lats[j] > lat) &&
                    lon < (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i]) + lons[i]) inside = !inside
                j = i
            }
            return inside
        }
    }
}

/** Buckets the geometry pieces of all edges into square cells for nearest-point-on-a-path lookups. */
private class SegmentGrid(private val g: RouteGraph, private val cell: Double) {
    /** Edge of the piece starting at geometry vertex j, or -1 at an edge's last vertex. */
    private val edgeOf = IntArray(g.geomLat.size) { -1 }
    private val xs = DoubleArray(g.geomLat.size) { g.x(g.geomLon[it]) }
    private val ys = DoubleArray(g.geomLat.size) { g.y(g.geomLat[it]) }
    private val cells = HashMap<Long, IntList>()

    init {
        for (e in 0 until g.edgeCount) for (j in g.geomStart[e] until g.geomStart[e + 1] - 1) {
            edgeOf[j] = e
            for (c in cellOf(min(xs[j], xs[j + 1]))..cellOf(max(xs[j], xs[j + 1])))
                for (r in cellOf(min(ys[j], ys[j + 1]))..cellOf(max(ys[j], ys[j + 1])))
                    cells.getOrPut(key(c, r)) { IntList() }.add(j)
        }
    }

    private fun cellOf(v: Double) = floor(v / cell).toInt()
    private fun key(c: Int, r: Int) = c.toLong() * 4_294_967_311L + r

    fun nearest(x: Double, y: Double, maxM: Double, acceptEdge: (Int) -> Boolean): RouteGraph.EdgePoint? =
        candidates(x, y, maxM, 1, acceptEdge).firstOrNull()

    /** The nearest point of each edge within [maxM], nearest first, at most [limit]. */
    fun candidates(x: Double, y: Double, maxM: Double, limit: Int, acceptEdge: (Int) -> Boolean): List<RouteGraph.EdgePoint> {
        val reach = ceil(maxM / cell).toInt()
        val c0 = cellOf(x)
        val r0 = cellOf(y)
        val maxD2 = maxM * maxM
        // Per edge: best piece j, fraction t and squared distance.
        val bestJ = HashMap<Int, Int>()
        val bestT = HashMap<Int, Double>()
        val bestD2 = HashMap<Int, Double>()
        for (c in c0 - reach..c0 + reach) for (r in r0 - reach..r0 + reach) {
            val list = cells[key(c, r)] ?: continue
            for (k in 0 until list.size) {
                val j = list[k]
                val e = edgeOf[j]
                if (!acceptEdge(e)) continue
                val dx = xs[j + 1] - xs[j]
                val dy = ys[j + 1] - ys[j]
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((x - xs[j]) * dx + (y - ys[j]) * dy) / len2).coerceIn(0.0, 1.0)
                val px = xs[j] + dx * t - x
                val py = ys[j] + dy * t - y
                val d2 = px * px + py * py
                if (d2 <= maxD2 && d2 < (bestD2[e] ?: Double.MAX_VALUE)) { bestD2[e] = d2; bestJ[e] = j; bestT[e] = t }
            }
        }
        return bestD2.entries.sortedBy { it.value }.take(limit).map { (e, d2) -> edgePoint(e, bestJ.getValue(e), bestT.getValue(e), sqrt(d2)) }
    }

    private fun edgePoint(e: Int, j: Int, t: Double, dist: Double): RouteGraph.EdgePoint {
        var along = 0.0
        for (k in g.geomStart[e] until j) along += RouteTracker.haversineM(g.geomLat[k], g.geomLon[k], g.geomLat[k + 1], g.geomLon[k + 1])
        along += t * RouteTracker.haversineM(g.geomLat[j], g.geomLon[j], g.geomLat[j + 1], g.geomLon[j + 1])
        val lat = g.geomLat[j] + (g.geomLat[j + 1] - g.geomLat[j]) * t
        val lon = g.geomLon[j] + (g.geomLon[j + 1] - g.geomLon[j]) * t
        return RouteGraph.EdgePoint(e, j, t, lat, lon, dist, along)
    }
}

/** Buckets nodes into square cells for nearest-node lookups. */
private class NodeGrid(private val xs: DoubleArray, private val ys: DoubleArray, private val cell: Double) {
    private val minX = xs.minOrNull() ?: 0.0
    private val minY = ys.minOrNull() ?: 0.0
    private val cols = (((xs.maxOrNull() ?: 0.0) - minX) / cell).toInt() + 1
    private val rows = (((ys.maxOrNull() ?: 0.0) - minY) / cell).toInt() + 1
    private val start = IntArray(cols * rows + 1)
    private val items = IntArray(xs.size)

    init {
        val cellOf = IntArray(xs.size) { cellIndex(col(xs[it]), row(ys[it])) }
        for (c in cellOf) start[c + 1]++
        for (i in 0 until cols * rows) start[i + 1] += start[i]
        val fill = start.copyOf()
        for (n in cellOf.indices) items[fill[cellOf[n]]++] = n
    }

    private fun col(x: Double) = floor((x - minX) / cell).toInt().coerceIn(0, cols - 1)
    private fun row(y: Double) = floor((y - minY) / cell).toInt().coerceIn(0, rows - 1)
    private fun cellIndex(c: Int, r: Int) = r * cols + c

    fun nearest(x: Double, y: Double, maxM: Double, accept: (Int) -> Boolean): Int? {
        if (xs.isEmpty()) return null
        val c0 = col(x)
        val r0 = row(y)
        var best = -1
        var bestD2 = maxM * maxM
        fun scan(c: Int, r: Int) {
            if (c < 0 || c >= cols || r < 0 || r >= rows) return
            val cellIdx = cellIndex(c, r)
            for (k in start[cellIdx] until start[cellIdx + 1]) {
                val n = items[k]
                val dx = xs[n] - x
                val dy = ys[n] - y
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2 && accept(n)) { bestD2 = d2; best = n }
            }
        }
        for (ring in 0..max(cols, rows)) {
            // Every cell of this ring is at least (ring - 1) cells away; stop once that can't beat the best.
            val minD = (ring - 1) * cell
            if (minD > 0 && minD * minD > bestD2) break
            if (ring == 0) { scan(c0, r0); continue }
            for (c in c0 - ring..c0 + ring) { scan(c, r0 - ring); scan(c, r0 + ring) }
            for (r in r0 - ring + 1 until r0 + ring) { scan(c0 - ring, r); scan(c0 + ring, r) }
        }
        return best.takeIf { it >= 0 }
    }
}

/** Open-addressing Long → Int map (no boxing); [get] returns -1 for a missing key. */
internal class LongIntMap(capacity: Int = 1 shl 12) {
    private var keys = LongArray(capacity)
    private var vals = IntArray(capacity)
    private var used = BooleanArray(capacity)
    private var size = 0

    private fun slot(k: Long, mask: Int): Int {
        var h = (k * -0x61c8864680b583ebL).let { (it xor (it ushr 32)).toInt() } and mask
        while (used[h] && keys[h] != k) h = (h + 1) and mask
        return h
    }

    fun get(k: Long): Int { val s = slot(k, keys.size - 1); return if (used[s]) vals[s] else -1 }

    fun put(k: Long, v: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val s = slot(k, keys.size - 1)
        if (!used[s]) { used[s] = true; keys[s] = k; size++ }
        vals[s] = v
    }

    /** Adds [d] to the value (missing keys count as 0). */
    fun add(k: Long, d: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val s = slot(k, keys.size - 1)
        if (!used[s]) { used[s] = true; keys[s] = k; vals[s] = 0; size++ }
        vals[s] += d
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = IntArray(ok.size * 2); used = BooleanArray(ok.size * 2)
        val mask = keys.size - 1
        for (i in ok.indices) if (ou[i]) { val s = slot(ok[i], mask); used[s] = true; keys[s] = ok[i]; vals[s] = ov[i] }
    }
}
