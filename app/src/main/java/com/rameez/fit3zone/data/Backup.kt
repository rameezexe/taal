package com.rameez.fit3zone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rameez.fit3zone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup file contains (its manifest.json). */
data class BackupInfo(val exportedAt: Long, val versionName: String, val activities: Int, val sessionsDbVersion: Int)

class BackupException(message: String) : Exception(message)

/**
 * All of the user's data in one zip, to survive a reinstall or move to another phone: activities (sessions.db),
 * band data (band.db) and settings (profile, zones, alerts, workouts + calendar, sensor, setup). Not included:
 * Strava login (encrypted with this phone's keystore) and the Health Connect / Strava sync state — those are
 * connected again after an import. Blocking: call off the main thread.
 */
object Backup {
    private const val FORMAT = 1
    private const val APP = "taal"
    private val PREFS = listOf("zone2", "workouts", "device", "band_data", "setup")

    fun export(context: Context, out: OutputStream): BackupInfo {
        val tmp = File(context.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
        try {
            val sessions = File(tmp, "sessions.db")
            SessionHistory.get(context).snapshotTo(sessions)
            val band = File(tmp, "band.db").takeIf { BandData.snapshotTo(context, it) }
            val info = BackupInfo(
                System.currentTimeMillis(), BuildConfig.VERSION_NAME,
                SessionHistory.get(context).sessions.value.count { !it.simulated }, SessionHistory.DB_VERSION,
            )
            ZipOutputStream(out.buffered()).use { zip ->
                fun entry(name: String, write: (OutputStream) -> Unit) {
                    zip.putNextEntry(ZipEntry(name)); write(zip); zip.closeEntry()
                }
                entry("manifest.json") {
                    it.write(
                        JSONObject().put("app", APP).put("format", FORMAT).put("exportedAt", info.exportedAt)
                            .put("versionName", info.versionName).put("versionCode", BuildConfig.VERSION_CODE)
                            .put("activities", info.activities).put("sessionsDbVersion", info.sessionsDbVersion)
                            .toString(2).toByteArray(),
                    )
                }
                entry("sessions.db") { sessions.inputStream().use { input -> input.copyTo(it) } }
                band?.let { f -> entry("band.db") { f.inputStream().use { input -> input.copyTo(it) } } }
                for (name in PREFS) {
                    val json = prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE))
                    entry("prefs/$name.json") { it.write(json.toString(2).toByteArray()) }
                }
            }
            return info
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Unpacks and checks a backup into a staging folder; [import] then applies it. */
    fun read(context: Context, input: InputStream): Pair<BackupInfo, File> {
        val dir = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val allowed = e.name in setOf("manifest.json", "sessions.db", "band.db") ||
                    (e.name.startsWith("prefs/") && e.name.removePrefix("prefs/").removeSuffix(".json") in PREFS)
                if (e.isDirectory || !allowed) continue
                val f = File(dir, e.name).apply { parentFile?.mkdirs() }
                f.outputStream().use { zip.copyTo(it) }
            }
        }
        val manifest = File(dir, "manifest.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            ?: throw BackupException("This isn't a backup file from this app.")
        if (manifest.optString("app") != APP || manifest.optInt("format") != FORMAT) throw BackupException("This isn't a backup file from this app.")
        val info = BackupInfo(
            manifest.getLong("exportedAt"), manifest.optString("versionName"), manifest.optInt("activities"), manifest.getInt("sessionsDbVersion"),
        )
        if (info.sessionsDbVersion > SessionHistory.DB_VERSION) throw BackupException("This backup is from a newer version (${info.versionName}). Update the app first.")
        if (!File(dir, "sessions.db").exists()) throw BackupException("The backup has no activities file.")
        return info to dir
    }

    /**
     * Replaces everything on this phone with the staged backup. The app must restart right after (the caller exits
     * the process), so nothing still holding the old databases or settings writes them back.
     */
    fun import(context: Context, staged: File) {
        if (SessionRepository.session.value?.isActive == true) throw BackupException("Stop the running activity first.")
        SessionHistory.get(context).close()
        BandData.close()
        val dbDir = context.getDatabasePath("sessions.db").parentFile!!
        for (name in listOf("sessions.db", "band.db")) {
            val src = File(staged, name)
            if (name == "band.db" && !src.exists()) continue
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbDir, name + it).delete() }
            src.copyTo(File(dbDir, name), overwrite = true)
        }
        for (name in PREFS) {
            val f = File(staged, "prefs/$name.json").takeIf { it.exists() } ?: continue
            jsonToPrefs(JSONObject(f.readText()), context.getSharedPreferences(name, Context.MODE_PRIVATE))
        }
        // Sync state belonged to the old install's records: start clean (connect Strava / Samsung Health again).
        // commit (not apply): the process exits right after.
        context.getSharedPreferences("strava", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        context.getSharedPreferences("healthExport", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        staged.deleteRecursively()
    }

    private fun prefsToJson(prefs: SharedPreferences): JSONObject = JSONObject().apply {
        for ((k, v) in prefs.all) {
            val typed = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            put(k, typed)
        }
    }

    private fun jsonToPrefs(json: JSONObject, prefs: SharedPreferences) = prefs.edit(commit = true) {
        val e = clear()
        for (k in json.keys()) {
            val o = json.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                "ss" -> o.getJSONArray("v").let { a -> e.putStringSet(k, (0 until a.length()).map(a::getString).toSet()) }
            }
        }
    }
}
