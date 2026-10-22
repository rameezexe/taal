package com.rameez.fit3zone.core

import kotlin.math.abs

/** A waypoint from a matched stroke: on a path ([onPath]), or the drawn point itself where no path fits. */
class MatchedPoint(val point: RoutePoint, val onPath: Boolean)

/**
 * Turns a line drawn freehand on the map into the paths it most likely means ("map matching"), like Strava's
 * freehand route drawing. The line is sampled every few tens of metres; each sample gets the nearest point of up to
 * [CANDIDATES] nearby paths. A hidden Markov model (Viterbi) picks one per sample: close to the finger is good
 * (emission), and the path between consecutive picks shouldn't be longer than the drawn step (transition) — so a
 * wobble doesn't become a detour into a side street and back. A short last turn at either end is trimmed. The chosen chain is then thinned to the fewest
 * waypoints whose best paths ([RouteGraph.route] costs) give the same route, so the result stays editable.
 */
object StrokeMatcher {
    private const val CANDIDATES = 6

    /**
     * [toleranceM] = how far off a finger stroke can be at the zoom it was drawn (~ a finger's width on screen).
     * Samples with no path within reach come back as drawn points (`onPath = false`).
     */
    fun match(graph: RouteGraph, stroke: List<RoutePoint>, toleranceM: Double, preferParks: Boolean): List<MatchedPoint> {
        if (stroke.isEmpty()) return emptyList()
        val spacing = toleranceM.coerceIn(15.0, 100.0)
        val reach = (toleranceM * 2.5).coerceIn(40.0, 300.0)
        val sigma = toleranceM * 0.6
        val beta = spacing * 0.4 + 5.0
        val samples = resample(stroke, spacing)
        val cands = samples.map { graph.candidates(it.lat, it.lon, reach, CANDIDATES) }
        val search = BoundedSearch(graph, graph.edgeLenM)

        // Viterbi over runs of samples that have candidates and can be joined; a run breaks where nothing connects.
        val out = ArrayList<MatchedPoint>()
        val chains = ArrayList<Pair<List<RouteGraph.EdgePoint>, DoubleArray>>()
        var t = 0
        fun flushChains() {
            for ((chain, legLen) in chains) thin(graph, chain, legLen, preferParks).forEach { out += MatchedPoint(RoutePoint(it.lat, it.lon), true) }
            chains.clear()
        }
        while (t < samples.size) {
            if (cands[t].isEmpty()) {
                flushChains()
                // Off the network (a lawn, a beach): keep the drawn points, every 4th of a run plus its ends.
                val s = t
                while (t < samples.size && cands[t].isEmpty()) t++
                for (k in s until t) if ((k - s) % 4 == 0 || k == t - 1) out += MatchedPoint(samples[k], false)
                continue
            }
            val start = t
            var score = DoubleArray(cands[t].size) { emission(cands[t][it], sigma) }
            val back = ArrayList<IntArray>()
            val legs = ArrayList<DoubleArray>()
            back += IntArray(cands[t].size) { -1 }
            legs += DoubleArray(cands[t].size)
            t++
            while (t < samples.size && cands[t].isNotEmpty()) {
                val gc = Route.metres(samples[t - 1], samples[t])
                val prev = cands[t - 1]
                val cur = cands[t]
                val next = DoubleArray(cur.size) { Double.NEGATIVE_INFINITY }
                val from = IntArray(cur.size) { -1 }
                val leg = DoubleArray(cur.size)
                for (p in prev.indices) {
                    if (score[p] == Double.NEGATIVE_INFINITY) continue
                    search.run(prev[p], gc * 3 + 2 * reach + 50)
                    for (c in cur.indices) {
                        val len = search.lengthTo(cur[c]) ?: continue
                        val s = score[p] - transitionCost(len, gc, beta) + emission(cur[c], sigma)
                        if (s > next[c]) { next[c] = s; from[c] = p; leg[c] = len }
                    }
                }
                if (next.all { it == Double.NEGATIVE_INFINITY }) break // nothing joins: start a new run here
                score = next
                back += from
                legs += leg
                t++
            }
            // Backtrack the best chain of this run.
            var k = score.indices.maxBy { score[it] }
            val chain = ArrayList<RouteGraph.EdgePoint>()
            val legLen = DoubleArray(back.size)
            for (i in back.size - 1 downTo 0) {
                chain += cands[start + i][k]
                legLen[i] = legs[i][k]
                if (i > 0) k = back[i][k]
            }
            chain.reverse()
            if (chain.size >= 2) {
                trimEnd(chain, legLen, chain.size - 1, cands[start + chain.size - 1], search, toleranceM)
                trimEnd(chain, legLen, 0, cands[start], search, toleranceM)
            }
            chains += chain to legLen
        }
        flushChains()
        return out
    }

    /**
     * An end of the stroke often lands just round a corner (the finger stopped on the cross street): if another
     * candidate of that end sample lies on the way to the chosen one, within [toleranceM] of it, end there instead.
     */
    private fun trimEnd(
        chain: MutableList<RouteGraph.EdgePoint>, legLen: DoubleArray, end: Int, options: List<RouteGraph.EdgePoint>,
        search: BoundedSearch, toleranceM: Double,
    ) {
        val inner = if (end == 0) 1 else end - 1
        val chosen = chain[end]
        search.run(chain[inner], legLen[maxOf(end, inner)] + toleranceM + 50)
        val toChosen = search.lengthTo(chosen) ?: return
        var best: RouteGraph.EdgePoint? = null
        var bestLen = toChosen
        for (a in options) {
            if (a === chosen || a.distM > toleranceM) continue
            val toA = search.lengthTo(a) ?: continue
            val stub = Route.metres(RoutePoint(a.lat, a.lon), RoutePoint(chosen.lat, chosen.lon))
            if (stub <= toleranceM && abs(toA + stub - toChosen) < 2.0 && toA < bestLen) { best = a; bestLen = toA }
        }
        best ?: return
        chain[end] = best
        legLen[maxOf(end, inner)] = bestLen
    }

    /**
     * A path longer than the drawn step is a detour (a spur into a side street and back): costly. Shorter is normal —
     * a shaky finger draws longer than the street — so it costs a third as much.
     */
    private fun transitionCost(len: Double, gc: Double, beta: Double): Double =
        if (len >= gc) (len - gc) / beta else (gc - len) / (3 * beta)

    private fun emission(c: RouteGraph.EdgePoint, sigma: Double): Double { val z = c.distM / sigma; return -0.5 * z * z }

    /**
     * The fewest points of [chain] (first and last kept) such that the best path between consecutive ones has the
     * matched length: from each kept point, go as far along the chain as the best path still agrees.
     */
    private fun thin(graph: RouteGraph, chain: List<RouteGraph.EdgePoint>, legLen: DoubleArray, preferParks: Boolean): List<RouteGraph.EdgePoint> {
        if (chain.size <= 2) return chain
        val cum = DoubleArray(chain.size)
        for (i in 1 until chain.size) cum[i] = cum[i - 1] + legLen[i]
        val search = BoundedSearch(graph, graph.edgeCosts(preferParks))
        val kept = arrayListOf(chain[0])
        var i = 0
        while (i < chain.size - 1) {
            val horizon = minOf(chain.size - 1, i + 60)
            search.run(chain[i], (cum[horizon] - cum[i]) * PathKind.TRUNK.weight + 100)
            var good = i + 1
            for (j in i + 1..horizon) {
                val matched = cum[j] - cum[i]
                val len = search.lengthTo(chain[j]) ?: break
                if (abs(len - matched) > 3 + matched * 0.01) break
                good = j
            }
            kept += chain[good]
            i = good
        }
        return kept
    }

    /** Points every [spacing] m along the line, plus its last point. */
    fun resample(line: List<RoutePoint>, spacing: Double): List<RoutePoint> {
        val out = arrayListOf(line.first())
        var carry = 0.0
        for (k in 1 until line.size) {
            val a = line[k - 1]
            val b = line[k]
            val d = Route.metres(a, b)
            var at = spacing - carry
            while (at <= d) {
                val f = at / d
                out += RoutePoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                at += spacing
            }
            carry = (carry + d) % spacing
        }
        if (Route.metres(out.last(), line.last()) > spacing * 0.3) out += line.last() else out[out.size - 1] = line.last()
        return out
    }
}

/**
 * Dijkstra by [cost] from a point on an edge, up to a cost limit, remembering the length (m) along the best path to
 * each node. Reuses its arrays between runs.
 */
internal class BoundedSearch(private val g: RouteGraph, private val cost: DoubleArray) {
    private val dist = DoubleArray(g.nodeCount)
    private val len = DoubleArray(g.nodeCount)
    private val seenGen = IntArray(g.nodeCount)
    private val doneGen = IntArray(g.nodeCount)
    private var gen = 0
    private val heap = MinHeap()
    private var src: RouteGraph.EdgePoint? = null

    private fun perM(e: Int) = if (g.edgeLenM[e] > 0) cost[e] / g.edgeLenM[e] else 1.0

    fun run(from: RouteGraph.EdgePoint, maxCost: Double) {
        gen++
        heap.clear()
        src = from
        val e = from.edge
        seed(g.edgeFrom[e], from.alongM, perM(e))
        seed(g.edgeTo[e], g.edgeLenM[e] - from.alongM, perM(e))
        while (heap.size > 0) {
            val u = heap.popNode()
            if (doneGen[u] == gen) continue
            doneGen[u] = gen
            if (dist[u] > maxCost) break
            for (k in g.adjFrom(u) until g.adjFrom(u + 1)) {
                val edge = g.adjAt(k)
                val v = g.other(edge, u)
                if (doneGen[v] == gen) continue
                val nd = dist[u] + cost[edge]
                if (seenGen[v] != gen || nd < dist[v]) {
                    seenGen[v] = gen; dist[v] = nd; len[v] = len[u] + g.edgeLenM[edge]
                    heap.push(nd, v)
                }
            }
        }
    }

    private fun seed(n: Int, m: Double, perM: Double) {
        val c = m * perM
        if (seenGen[n] == gen && dist[n] <= c) return
        seenGen[n] = gen; dist[n] = c; len[n] = m
        heap.push(c, n)
    }

    /** Length along the cheapest path found from the last [run]'s start to [to], or null if not reached. */
    fun lengthTo(to: RouteGraph.EdgePoint): Double? {
        val from = src ?: return null
        var bestCost = Double.MAX_VALUE
        var bestLen: Double? = null
        if (from.edge == to.edge) { val d = abs(from.alongM - to.alongM); bestCost = d * perM(to.edge); bestLen = d }
        val e = to.edge
        for ((n, part) in listOf(g.edgeFrom[e] to to.alongM, g.edgeTo[e] to g.edgeLenM[e] - to.alongM)) {
            if (seenGen[n] != gen) continue
            val c = dist[n] + part * perM(e)
            if (c < bestCost) { bestCost = c; bestLen = len[n] + part }
        }
        return bestLen
    }
}
