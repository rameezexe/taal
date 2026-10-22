package com.rameez.fit3zone.core

import java.util.Locale

/** Where a waypoint of a [RouteDraft] sits: on a path, free (straight lines wanted), off any path, or waiting for map data. */
enum class WaypointState { SNAPPED, FREE, NO_PATH, PENDING }

/** A tapped waypoint: [point] is where it sits (on the path when snapped), [tapped] where the finger was. */
data class Waypoint(val point: RoutePoint, val tapped: RoutePoint, val state: WaypointState) {
    /** Snapping was wanted for it (any state but [WaypointState.FREE]). */
    val wantsSnap: Boolean get() = state != WaypointState.FREE
}

/** How a piece between two waypoints was drawn: along paths, as a straight line, or straight until map data arrives. */
enum class PieceKind { SNAPPED, STRAIGHT, PENDING }

class DraftPiece(val points: List<RoutePoint>, val kind: PieceKind) {
    val lengthM: Double by lazy { (1 until points.size).sumOf { Route.metres(points[it - 1], points[it]) } }

    fun reversed() = DraftPiece(points.asReversed().toList(), kind)
}

/** Result of snapping a tap to the path network. */
sealed interface Snap {
    class At(val point: RoutePoint) : Snap
    data object NoPath : Snap
    /** No map data for that place yet. */
    data object Pending : Snap
}

/** Finds paths for a [RouteDraft]; [path] is only asked between two snapped points. */
interface DraftRouter {
    fun snap(p: RoutePoint): Snap
    fun path(from: RoutePoint, to: RoutePoint): List<RoutePoint>?
}

/**
 * A [DraftRouter] on [graph] (null = no map data at all); [loaded] tells whether a place is inside the downloaded areas,
 * so taps outside them wait ([Snap.Pending]) instead of snapping to a far-off path at the edge of the data.
 */
class GraphRouter(
    private val graph: RouteGraph?,
    private val preferParks: Boolean,
    private val loaded: (lat: Double, lon: Double) -> Boolean = { _, _ -> graph != null },
) : DraftRouter {
    override fun snap(p: RoutePoint): Snap {
        if (!loaded(p.lat, p.lon)) return Snap.Pending
        val at = graph?.snap(p.lat, p.lon, RouteDraft.MAX_SNAP_M) ?: return Snap.NoPath
        return Snap.At(RoutePoint(at.lat, at.lon))
    }

    override fun path(from: RoutePoint, to: RoutePoint): List<RoutePoint>? =
        graph?.route(from.lat, from.lon, to.lat, to.lon, preferParks, maxSnapM = 5.0)
}

/**
 * A route being drawn by tapping waypoints on a map: piece i joins waypoint i to i + 1. Immutable — every edit returns
 * a new draft that remembers this one for [undo]; only the pieces next to a changed waypoint are routed again.
 * Pieces whose map data hasn't arrived are straight [PieceKind.PENDING] placeholders until [refresh].
 */
class RouteDraft private constructor(
    val waypoints: List<Waypoint>,
    val pieces: List<DraftPiece>,
    private val previous: RouteDraft?,
) {
    constructor() : this(emptyList(), emptyList(), null)

    init { require(pieces.size == maxOf(0, waypoints.size - 1)) { "one piece between each two waypoints" } }

    val distanceM: Double get() = pieces.sumOf { it.lengthM }
    val canUndo: Boolean get() = previous != null
    val isEmpty: Boolean get() = waypoints.isEmpty()
    val hasPending: Boolean get() = waypoints.any { it.state == WaypointState.PENDING } || pieces.any { it.kind == PieceKind.PENDING }
    /** Ends at its start, so "Back to start" has nothing to do. */
    val isClosed: Boolean get() = waypoints.size > 2 && Route.metres(waypoints.first().point, waypoints.last().point) < 1.0

    /** All points start to finish (at least the waypoints themselves). */
    val points: List<RoutePoint> get() =
        if (pieces.isEmpty()) waypoints.map { it.point } else Route.dropDuplicates(pieces.flatMap { it.points })

    fun undo(): RouteDraft = previous ?: this

    /** Adds a waypoint at [tap], snapped to a path when [snap] (else straight from the last one). */
    fun add(tap: RoutePoint, snap: Boolean, router: DraftRouter): RouteDraft {
        val w = waypoint(tap, snap, router)
        val ps = if (waypoints.isEmpty()) pieces else pieces + piece(waypoints.last(), w, router)
        return RouteDraft(waypoints + w, ps, this)
    }

    /**
     * Adds a freehand stroke matched by [StrokeMatcher] as one edit (one undo step): points on a path become snapped
     * waypoints, the others free ones; it carries on from the last waypoint.
     */
    fun addMatched(points: List<MatchedPoint>, router: DraftRouter): RouteDraft {
        if (points.isEmpty()) return this
        val ws = waypoints.toMutableList()
        val ps = pieces.toMutableList()
        for (m in points) {
            val w = Waypoint(m.point, m.point, if (m.onPath) WaypointState.SNAPPED else WaypointState.FREE)
            if (ws.isNotEmpty()) {
                if (Route.metres(ws.last().point, w.point) < 1.0) continue
                ps += piece(ws.last(), w, router)
            }
            ws += w
        }
        return RouteDraft(ws, ps, this)
    }

    /**
     * Moves waypoint [i] to [tap] (keeping whether it snaps); its two pieces are routed again. Moving the start or
     * finish of a closed loop moves both, so it stays a loop.
     */
    fun move(i: Int, tap: RoutePoint, router: DraftRouter): RouteDraft {
        val last = waypoints.size - 1
        val moved = if (isClosed && (i == 0 || i == last)) listOf(0, last) else listOf(i)
        val w = waypoint(tap, waypoints[i].wantsSnap, router)
        val ws = waypoints.toMutableList()
        for (k in moved) ws[k] = w
        val ps = pieces.toMutableList()
        for (k in moved) {
            if (k > 0) ps[k - 1] = piece(ws[k - 1], ws[k], router)
            if (k < ps.size) ps[k] = piece(ws[k], ws[k + 1], router)
        }
        return RouteDraft(ws, ps, this)
    }

    /**
     * Deletes waypoint [i]; its neighbours are joined by one new piece. Deleting the start of a closed loop deletes
     * its finish (the way back), so the loop opens and the start can be deleted next.
     */
    fun remove(i: Int, router: DraftRouter): RouteDraft {
        if (isClosed && i == 0) return remove(waypoints.size - 1, router)
        val ws = waypoints.toMutableList().apply { removeAt(i) }
        val ps = pieces.toMutableList()
        when {
            ps.isEmpty() -> {}
            i == 0 -> ps.removeAt(0)
            i == waypoints.size - 1 -> ps.removeAt(ps.size - 1)
            else -> { ps.removeAt(i); ps[i - 1] = piece(ws[i - 1], ws[i], router) }
        }
        return RouteDraft(ws, ps, this)
    }

    /** "Back to start": the best way from the last waypoint to the first. */
    fun closeLoop(router: DraftRouter): RouteDraft {
        if (waypoints.size < 2 || isClosed) return this
        val start = waypoints.first()
        return RouteDraft(waypoints + start, pieces + piece(waypoints.last(), start, router), this)
    }

    /** "Out & back": the same way back to the start. */
    fun outAndBack(): RouteDraft {
        if (waypoints.size < 2) return this
        return RouteDraft(waypoints + waypoints.asReversed().drop(1), pieces + pieces.asReversed().map { it.reversed() }, this)
    }

    /**
     * Retries what was waiting for map data (after a download): pending waypoints are snapped and pending pieces, or
     * pieces next to a waypoint that changed, routed. Not an undo step; returns this draft when nothing changed.
     */
    fun refresh(router: DraftRouter): RouteDraft {
        if (!hasPending) return this
        val changed = BooleanArray(waypoints.size)
        val ws = waypoints.mapIndexed { i, w ->
            if (w.state != WaypointState.PENDING) w
            else waypoint(w.tapped, true, router).also { changed[i] = it.state != WaypointState.PENDING }
        }
        var any = changed.any { it }
        val ps = pieces.mapIndexed { i, p ->
            if (p.kind != PieceKind.PENDING && !changed[i] && !changed[i + 1]) p
            else piece(ws[i], ws[i + 1], router).also { if (it.kind != PieceKind.PENDING) any = true }
        }
        return if (any) RouteDraft(ws, ps, previous) else this
    }

    fun defaultName(): String = String.format(Locale.US, "Route %.1f km", distanceM / 1000)

    fun toRoute(name: String = defaultName()): Route = Route(name.ifBlank { defaultName() }, points)

    private fun waypoint(tap: RoutePoint, snap: Boolean, router: DraftRouter): Waypoint {
        if (!snap) return Waypoint(tap, tap, WaypointState.FREE)
        return when (val s = router.snap(tap)) {
            is Snap.At -> Waypoint(s.point, tap, WaypointState.SNAPPED)
            Snap.NoPath -> Waypoint(tap, tap, WaypointState.NO_PATH)
            Snap.Pending -> Waypoint(tap, tap, WaypointState.PENDING)
        }
    }

    private fun piece(a: Waypoint, b: Waypoint, router: DraftRouter): DraftPiece {
        val straight = listOf(a.point, b.point)
        if (a.state == WaypointState.PENDING || b.state == WaypointState.PENDING) return DraftPiece(straight, PieceKind.PENDING)
        if (a.state != WaypointState.SNAPPED || b.state != WaypointState.SNAPPED) return DraftPiece(straight, PieceKind.STRAIGHT)
        val path = router.path(a.point, b.point) ?: return DraftPiece(straight, PieceKind.STRAIGHT)
        return DraftPiece(path, PieceKind.SNAPPED)
    }

    companion object {
        /** A tap further than this from any path is drawn straight ("No path here"). */
        const val MAX_SNAP_M = 150.0
    }
}
