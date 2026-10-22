package com.rameez.fit3zone.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.database.getDoubleOrNull
import com.rameez.fit3zone.core.Gpx
import com.rameez.fit3zone.core.Route
import com.rameez.fit3zone.core.RoutePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Where a saved route came from. Stored as text; an older build reads a source it doesn't know as GPX. */
enum class RouteSource { GPX, LOOP, DRAWN }

data class SavedRoute(val id: Long, val createdAt: Long, val source: RouteSource, val route: Route) {
    val name: String get() = route.name
}

/**
 * Planned routes, kept in sessions.db (table routes, v11) so backups include them. All methods block; call them on
 * [SessionHistory.dispatcher]. Imported GPX files never leave the phone.
 */
class RouteStore private constructor(context: Context) {
    private val history = SessionHistory.get(context)
    private val resolver = context.contentResolver

    private val _routes = MutableStateFlow<List<SavedRoute>>(emptyList())
    /** Newest first. */
    val routes: StateFlow<List<SavedRoute>> = _routes.asStateFlow()

    fun get(id: Long?): SavedRoute? = id?.let { i -> _routes.value.firstOrNull { it.id == i } ?: load(i) }

    fun refresh() {
        _routes.value = history.readableDatabase.rawQuery("SELECT id, name, created_at, source, points FROM routes ORDER BY created_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(row(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4))) } }
    }

    private fun load(id: Long): SavedRoute? =
        history.readableDatabase.rawQuery("SELECT id, name, created_at, source, points FROM routes WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToNext()) row(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4)) else null }

    private fun row(id: Long, name: String, createdAt: Long, source: String, points: String) =
        SavedRoute(id, createdAt, runCatching { RouteSource.valueOf(source) }.getOrDefault(RouteSource.GPX), Route(name, decode(points)))

    fun save(route: Route, source: RouteSource): Long {
        val id = history.writableDatabase.insertOrThrow("routes", null, ContentValues().apply {
            put("name", route.name)
            put("created_at", System.currentTimeMillis())
            put("source", source.name)
            put("distance_m", route.distanceM)
            put("ascent_m", route.ascentM)
            put("points", encode(route.points))
        })
        refresh()
        return id
    }

    fun rename(id: Long, name: String) {
        val clean = name.trim().take(80).ifEmpty { return }
        history.writableDatabase.update("routes", ContentValues().apply { put("name", clean) }, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    fun delete(id: Long) {
        history.writableDatabase.delete("routes", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /** Reads a GPX file the user picked; throws [Gpx.FormatException] with a message to show. */
    fun importGpx(uri: Uri): Long {
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            if (!c.isNull(1) && c.getLong(1) > MAX_GPX_BYTES) throw Gpx.FormatException("This file is too large for a route (over 20 MB)")
            c.getString(0)
        }
        val xml = resolver.openInputStream(uri)?.use { input ->
            // Bounded read (InputStream.readNBytes needs Android 13; testers may have 12).
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_GPX_BYTES) throw Gpx.FormatException("This file is too large for a route (over 20 MB)")
            }
            out.toString(Charsets.UTF_8.name())
        } ?: throw Gpx.FormatException("Couldn't open the file")
        val fallback = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported route"
        return save(Gpx.read(xml, fallback), RouteSource.GPX)
    }

    companion object {
        private const val MAX_GPX_BYTES = 20 * 1024 * 1024

        /** "lat,lon[,ele];…" with 6 decimals (~0.1 m). */
        fun encode(points: List<RoutePoint>): String = points.joinToString(";") { p ->
            String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon) + (p.eleM?.let { String.format(Locale.US, ",%.1f", it) } ?: "")
        }

        fun decode(s: String): List<RoutePoint> = s.split(';').mapNotNull { part ->
            val v = part.split(',')
            val lat = v.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = v.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            RoutePoint(lat, lon, v.getOrNull(2)?.toDoubleOrNull())
        }

        @Volatile private var instance: RouteStore? = null
        fun get(context: Context): RouteStore =
            instance ?: synchronized(this) { instance ?: RouteStore(context.applicationContext).also { instance = it } }
    }
}
