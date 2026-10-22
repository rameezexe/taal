package com.rameez.fit3zone.core

import java.util.Locale

/** One OSM way as Overpass returns it with `out geom tags`: node ids with their positions, and the tags we use. */
class OsmWay(val id: Long, val nodeIds: LongArray, val lats: DoubleArray, val lons: DoubleArray, val tags: Map<String, String>) {
    init { require(nodeIds.size == lats.size && lats.size == lons.size) { "way $id: ids and geometry differ" } }
    val isClosed: Boolean get() = nodeIds.size > 2 && nodeIds.first() == nodeIds.last()
}

/** Paths to walk on ([ways], tagged `highway`) and green areas ([parks], closed `leisure` ways) of one download. */
class OsmData(val ways: List<OsmWay>, val parks: List<OsmWay>) {
    companion object {
        /**
         * Several downloads as one (a way in two of them is kept once). Ways from different downloads join where they
         * share a vertex position (see [positionId]), so a graph built from the result is seamless at the borders.
         */
        fun merge(areas: List<OsmData>): OsmData =
            if (areas.size == 1) areas[0]
            else OsmData(areas.flatMap { it.ways }.distinctBy { it.id }, areas.flatMap { it.parks }.distinctBy { it.id })
    }
}

/**
 * The public OpenStreetMap Overpass API: the query for the walkable paths around a start point, and a streaming
 * reader for its JSON answer (no JSON library in core, and no object per vertex, so a 10 km circle in a city fits).
 */
object Overpass {
    /** Public instances, asked at once (each was busy or hung for minutes at times on 2026-10-21). */
    val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    const val ATTRIBUTION = "Map data © OpenStreetMap contributors (ODbL)"

    private const val HIGHWAYS = "footway|path|pedestrian|living_street|residential|service|unclassified|tertiary|" +
        "secondary|primary|track|steps|cycleway|trunk_link|primary_link|secondary_link|tertiary_link|road"
    private val KEEP_TAGS = setOf("highway", "foot", "access", "area", "leisure", "name", "surface", "lit")

    /**
     * Download radius for a loop of [targetM]: waypoints sit up to ~0.3 × the length from the start (see
     * [LoopPlanner]), plus room for the length correction.
     */
    fun radiusFor(targetM: Double): Double = (targetM * 0.35).coerceIn(1_500.0, 9_000.0)

    fun query(lat: Double, lon: Double, radiusM: Double): String {
        val at = String.format(Locale.US, "around:%.0f,%.6f,%.6f", radiusM, lat, lon)
        return "[out:json][timeout:90][maxsize:268435456];(" +
            "way($at)[highway~\"^($HIGHWAYS)$\"][access!~\"^(private|no)$\"][foot!~\"^(no)$\"];" +
            "way($at)[leisure~\"^(park|garden|track)$\"];" +
            ");out geom tags;"
    }

    class FormatException(message: String) : Exception(message)

    /** Reads an Overpass JSON answer. Ways without usable geometry are skipped; an error answer throws. */
    fun parse(json: CharSequence): OsmData {
        val r = JsonPull(json)
        val ways = ArrayList<OsmWay>()
        val parks = ArrayList<OsmWay>()
        var remark: String? = null
        var sawElements = false
        r.obj { key ->
            when (key) {
                "elements" -> { sawElements = true; r.arr { readElement(r)?.let { w -> if (w.tags.containsKey("highway")) ways += w else if (w.isClosed) parks += w } } }
                "remark" -> remark = r.stringOrNull()
                else -> r.skip()
            }
        }
        // Overpass reports timeouts and out-of-memory as a "remark" with whatever elements it had (often none).
        remark?.let { if (it.contains("error", ignoreCase = true)) throw FormatException(it.trim()) }
        if (!sawElements) throw FormatException("Not an Overpass answer")
        return OsmData(ways, parks)
    }

    private fun readElement(r: JsonPull): OsmWay? {
        var type: String? = null
        var id = 0L
        val ids = LongList()
        val lats = DoubleList()
        val lons = DoubleList()
        val tags = HashMap<String, String>()
        r.obj { key ->
            when (key) {
                "type" -> type = r.stringOrNull()
                "id" -> id = r.long()
                "nodes" -> r.arr { ids.add(r.long()) }
                "geometry" -> r.arr {
                    // A vertex outside a bbox filter comes back as null; keep the slot so ids and points line up.
                    var lat = Double.NaN
                    var lon = Double.NaN
                    if (r.isNull()) r.skip() else r.obj { k -> when (k) { "lat" -> lat = r.double(); "lon" -> lon = r.double(); else -> r.skip() } }
                    lats.add(lat); lons.add(lon)
                }
                "tags" -> r.obj { k -> if (k in KEEP_TAGS) r.stringOrNull()?.let { tags[k] = it } else r.skip() }
                else -> r.skip()
            }
        }
        if (type != "way" || lats.size < 2) return null
        // `out geom tags` has no node ids; ways share a node exactly where they print the same position.
        val nodeIds = if (ids.size == lats.size) ids.toArray() else LongArray(lats.size) { k -> positionId(lats[k], lons[k], id, k) }
        return OsmWay(id, nodeIds, lats.toArray(), lons.toArray(), tags)
    }
}

/** A node id from a position at OSM's 1e-7° resolution (unique per point; a negative per-way id for a missing one). */
internal fun positionId(lat: Double, lon: Double, wayId: Long, k: Int): Long {
    if (lat.isNaN() || lon.isNaN()) return -(wayId * 100_000 + k + 1)
    val la = Math.round((lat + 90) * 1e7)
    val lo = Math.round((lon + 180) * 1e7)
    return la * 3_600_000_001L + lo
}

internal class LongList {
    private var a = LongArray(16)
    var size = 0; private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray(): LongArray = a.copyOf(size)
}

internal class DoubleList {
    private var a = DoubleArray(16)
    var size = 0; private set
    fun add(v: Double) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun toArray(): DoubleArray = a.copyOf(size)
}

internal class IntList {
    private var a = IntArray(16)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun removeLast(): Int = a[--size]
    fun last(): Int = a[size - 1]
    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}

/**
 * Minimal pull reader for JSON: each callback of [obj] / [arr] must consume exactly one value (read it or [skip]).
 * Strict enough to reject garbage (an HTML error page), lenient about nothing else we need.
 */
internal class JsonPull(private val s: CharSequence) {
    private var i = 0

    private fun ws() { while (i < s.length && s[i].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++ }
    private fun peek(): Char { ws(); if (i >= s.length) fail("unexpected end"); return s[i] }
    private fun expect(c: Char) { if (peek() != c) fail("expected '$c'"); i++ }
    private fun fail(what: String): Nothing = throw Overpass.FormatException("Bad JSON at $i: $what")

    fun isNull(): Boolean = peek() == 'n'

    inline fun obj(onField: (String) -> Unit) {
        expectChar('{')
        if (peekChar() == '}') { advance(); return }
        while (true) {
            val key = string()
            expectChar(':')
            onField(key)
            when (peekChar()) { ',' -> advance(); '}' -> { advance(); return }; else -> failAt("expected ',' or '}'") }
        }
    }

    inline fun arr(onItem: () -> Unit) {
        expectChar('[')
        if (peekChar() == ']') { advance(); return }
        while (true) {
            onItem()
            when (peekChar()) { ',' -> advance(); ']' -> { advance(); return }; else -> failAt("expected ',' or ']'") }
        }
    }

    // Public wrappers so the inline functions above can use the private helpers.
    fun peekChar(): Char = peek()
    fun expectChar(c: Char) = expect(c)
    fun advance() { i++ }
    fun failAt(what: String): Nothing = fail(what)

    fun stringOrNull(): String? = when (peek()) {
        '"' -> string()
        else -> { skip(); null }
    }

    fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad \\u escape")
                            sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape")); i += 4
                        }
                        else -> fail("bad escape")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun numberText(): String {
        ws()
        val start = i
        while (i < s.length && s[i].let { it.isDigit() || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' }) i++
        if (start == i) fail("expected a number")
        return s.substring(start, i)
    }

    fun double(): Double = numberText().toDoubleOrNull() ?: fail("bad number")
    fun long(): Long = numberText().let { t -> t.toLongOrNull() ?: t.toDoubleOrNull()?.toLong() ?: fail("bad number") }

    fun skip() {
        when (peek()) {
            '{' -> obj { skip() }
            '[' -> arr { skip() }
            '"' -> string()
            't' -> literal("true"); 'f' -> literal("false"); 'n' -> literal("null")
            else -> numberText()
        }
    }

    private fun literal(word: String) {
        if (i + word.length > s.length || s.substring(i, i + word.length) != word) fail("expected $word")
        i += word.length
    }
}
