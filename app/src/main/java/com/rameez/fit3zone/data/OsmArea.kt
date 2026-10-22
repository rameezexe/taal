package com.rameez.fit3zone.data

import android.content.Context
import com.rameez.fit3zone.BuildConfig
import com.rameez.fit3zone.core.OsmData
import com.rameez.fit3zone.core.Overpass
import com.rameez.fit3zone.core.RouteTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenStreetMap paths around a start point for the loop planner, from the public Overpass API (each phone asks for
 * itself: no server of ours, no shared quota). Only the circle (centre + radius) is sent. Answers are cached
 * gzipped in files/osm/ for [MAX_AGE_MS]; a cached circle that covers the request is reused.
 */
class OsmArea(context: Context) {
    private val dir = File(context.filesDir, "osm").apply { mkdirs() }

    class BusyException(message: String) : Exception(message)

    /**
     * The paths within [radiusM] of the point. [onStatus] gets short texts for the screen. Both servers are asked at
     * once and the first good answer wins (one public server often hangs for minutes); the download covers
     * [MARGIN] × the radius so planning again nearby (GPS drift, another length) reuses it. Throws
     * [BusyException] when every server is still busy or unreachable after [DEADLINE_MS].
     */
    suspend fun load(lat: Double, lon: Double, radiusM: Double, onStatus: (String) -> Unit): OsmData =
        loadArea(lat, lon, radiusM, onStatus).data

    /** Downloaded paths and the circle they cover (at least the one asked for). */
    class Area(val lat: Double, val lon: Double, val radiusM: Double, val data: OsmData) {
        /** (lat, lon) is at least [marginM] inside the circle, so the paths around it are all there. */
        fun covers(lat: Double, lon: Double, marginM: Double = 0.0) = RouteTracker.haversineM(lat, lon, this.lat, this.lon) <= radiusM - marginM
    }

    /** Like [load], also telling which circle the data covers (a saved download may be bigger than asked). */
    suspend fun loadArea(lat: Double, lon: Double, radiusM: Double, onStatus: (String) -> Unit): Area = withContext(Dispatchers.IO) {
        cached(lat, lon, radiusM)?.let { (file, circle) ->
            onStatus("Using saved map data for this area…")
            runCatching { return@withContext Area(circle.first, circle.second, circle.third, Overpass.parse(readGz(file))) }
                .onFailure { HeartRateRepository.log.add("loop", "cache ${file.name} unreadable, downloading: $it"); file.delete() }
        }
        val fetchR = radiusM * MARGIN
        onStatus("Downloading map data for this area…")
        when (val r = race(Overpass.query(lat, lon, fetchR), fetchR) { onStatus("Map data service busy, still trying…") }) {
            is Fetched -> { save(lat, lon, fetchR, r.json); Area(lat, lon, fetchR, r.data) }
            is AllFailed -> throw BusyException("The free map data service is busy right now (${r.problem}). Try again in a few minutes.")
        }
    }

    private sealed interface RaceResult
    private class Fetched(val json: String, val data: OsmData) : RaceResult
    private class AllFailed(val problem: String) : RaceResult

    /**
     * Asks every server at once; the first parsed answer wins and the other requests are cut off. Each server
     * retries on its own after [RETRY_MS] (a 502 is often gone seconds later, while another server may hang for
     * its whole timeout), until [DEADLINE_MS] has passed.
     */
    private suspend fun race(query: String, fetchR: Double, onRetry: () -> Unit): RaceResult = coroutineScope {
        val winner = CompletableDeferred<RaceResult>()
        val begin = System.currentTimeMillis()
        var gaveUp = 0
        var problem = "no answer"
        val jobs = Overpass.SERVERS.map { server ->
            launch {
                var attempt = 0
                while (true) {
                    val started = System.currentTimeMillis()
                    try {
                        val json = postCancellable(server, query)
                        val data = Overpass.parse(json)
                        HeartRateRepository.log.add("loop", "overpass ${host(server)} ok: ${json.length / 1024} kB, ${data.ways.size} ways, " +
                            "${System.currentTimeMillis() - started} ms (r=${fetchR.toInt()} m, try ${attempt + 1}, ${(System.currentTimeMillis() - begin) / 1000} s in)")
                        winner.complete(Fetched(json, data))
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val why = if (e is SocketTimeoutException) "timed out" else e.message ?: e.javaClass.simpleName
                        HeartRateRepository.log.add("loop", "overpass ${host(server)} failed after ${(System.currentTimeMillis() - started) / 1000} s: $why")
                        problem = why
                    }
                    onRetry()
                    val wait = RETRY_MS[minOf(attempt, RETRY_MS.size - 1)]
                    if (System.currentTimeMillis() + wait - begin > DEADLINE_MS) break
                    delay(wait)
                    attempt++
                }
                synchronized(this@OsmArea) { if (++gaveUp == Overpass.SERVERS.size) winner.complete(AllFailed(problem)) }
            }
        }
        winner.await().also { jobs.forEach { it.cancel() } }
    }

    private class Busy(message: String) : IOException(message)

    /** [post] on its own thread; cancelling the coroutine disconnects the request. */
    private suspend fun postCancellable(server: String, query: String): String = suspendCancellableCoroutine { cont ->
        val conn = URL(server).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { conn.disconnect() }
        thread(name = "overpass-${host(server)}") {
            try {
                val body = post(conn, query)
                if (cont.isActive) cont.resume(body)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    private fun post(conn: HttpURLConnection, query: String): String {
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "Taal/${BuildConfig.VERSION_NAME} (running app; route planner)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code == 429 || code in 500..599) throw Busy("HTTP $code")
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // A full server sometimes answers 200 with an HTML page.
            if (!body.trimStart().startsWith("{")) throw Busy("not JSON")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** A fresh cached file whose circle contains the requested one. Name: "<lat>_<lon>_<radius m>_<saved ms>.json.gz". */
    private fun cached(lat: Double, lon: Double, radiusM: Double): Pair<File, Triple<Double, Double, Double>>? {
        val now = System.currentTimeMillis()
        return dir.listFiles().orEmpty().mapNotNull { f ->
            val p = f.name.removeSuffix(".json.gz").split('_')
            if (p.size != 4) return@mapNotNull null
            val cLat = p[0].toDoubleOrNull() ?: return@mapNotNull null
            val cLon = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val cR = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val at = p[3].toLongOrNull() ?: return@mapNotNull null
            if (now - at > MAX_AGE_MS) { f.delete(); return@mapNotNull null }
            if (RouteTracker.haversineM(lat, lon, cLat, cLon) + radiusM > cR) return@mapNotNull null
            f to Triple(cLat, cLon, cR)
        }.minByOrNull { it.second.third } // the smallest covering area parses fastest
    }

    private fun save(lat: Double, lon: Double, radiusM: Double, json: String) {
        runCatching {
            val name = String.format(Locale.US, "%.5f_%.5f_%.0f_%d.json.gz", lat, lon, radiusM, System.currentTimeMillis())
            val tmp = File(dir, "$name.tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(json.toByteArray()) }
            tmp.renameTo(File(dir, name))
            // Keep the cache under MAX_CACHE_BYTES, oldest first.
            val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json.gz") }.sortedBy { it.lastModified() }.toMutableList()
            var total = files.sumOf { it.length() }
            while (total > MAX_CACHE_BYTES && files.size > 1) { val f = files.removeAt(0); total -= f.length(); f.delete() }
        }.onFailure { HeartRateRepository.log.add("loop", "couldn't cache map data: $it") }
    }

    private fun readGz(f: File): String = GZIPInputStream(f.inputStream().buffered()).bufferedReader().use { it.readText() }

    private fun host(url: String) = url.substringAfter("://").substringBefore('/')

    companion object {
        /** Download this much more than the loop needs, so nearby plans reuse it. */
        private const val MARGIN = 1.25
        private val RETRY_MS = longArrayOf(5_000, 15_000, 30_000)
        /** No new try starts after this (a try already running may take up to its 120 s timeout). */
        private const val DEADLINE_MS = 150_000L
        private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val MAX_CACHE_BYTES = 50L * 1024 * 1024
    }
}
