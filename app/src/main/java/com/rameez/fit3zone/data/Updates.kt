package com.rameez.fit3zone.data

import android.content.Context
import androidx.core.content.edit
import com.rameez.fit3zone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer version announced in version.json. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val url: String, val notes: String)

/** Shown once after updating to this version (null = nothing to say). Keep the newest first. */
object ReleaseNotes {
    private val notes = mapOf(
        7 to "New name: Taal. Distance is more accurate (smoothed GPS, and your steps fill in when GPS drops out); " +
            "you hear \"GPS lost\" and see a warning when Power saving limits GPS. Setup shows the background steps " +
            "for your phone brand. New: Device → Your data, to back up and restore everything.",
    )

    fun of(versionCode: Int): String? = notes[versionCode]
}

/**
 * Testers install the APK from a link, so there are no store updates: twice a day the app fetches a small
 * version.json from [BuildConfig.UPDATE_URL] (nothing is sent but the request itself) and shows a card when a newer
 * version exists. Format: {"versionCode": 8, "versionName": "1.1.1-beta", "url": "https://…/taal.apk", "notes": "…"}.
 * Empty UPDATE_URL = no checks. Prefs "updates".
 */
object Updates {
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** A newer version the user hasn't dismissed. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _whatsNew = MutableStateFlow<String?>(null)
    /** Release notes of the version just installed, until dismissed. */
    val whatsNew: StateFlow<String?> = _whatsNew.asStateFlow()

    fun load(context: Context) {
        val p = prefs(context)
        val seen = p.getInt(KEY_SEEN, 0)
        if (seen < BuildConfig.VERSION_CODE) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val freshInstall = info.firstInstallTime == info.lastUpdateTime
            if (freshInstall) p.edit { putInt(KEY_SEEN, BuildConfig.VERSION_CODE) }
            else _whatsNew.value = ReleaseNotes.of(BuildConfig.VERSION_CODE).also { if (it == null) p.edit { putInt(KEY_SEEN, BuildConfig.VERSION_CODE) } }
        }
        _available.value = p.getString(KEY_LATEST, null)?.let(::parse)?.takeIf { it.isNewer(p) }
    }

    fun dismissWhatsNew(context: Context) {
        prefs(context).edit { putInt(KEY_SEEN, BuildConfig.VERSION_CODE) }
        _whatsNew.value = null
    }

    /** "Later": hides this version's card (a newer one shows again). */
    fun dismissUpdate(context: Context) {
        _available.value?.let { prefs(context).edit { putInt(KEY_DISMISSED, it.versionCode) } }
        _available.value = null
    }

    /** Fetches version.json if the last check is older than 12 h; failures are quiet (offline is normal). */
    suspend fun check(context: Context, force: Boolean = false) {
        val url = BuildConfig.UPDATE_URL.takeIf { it.isNotBlank() } ?: return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(KEY_CHECKED_AT, 0) < CHECK_EVERY_MS) return
        val body = withContext(Dispatchers.IO) {
            runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Cache-Control", "no-cache")
                    try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
                }
            }.getOrNull()
        } ?: return
        val info = parse(body) ?: return
        p.edit { putLong(KEY_CHECKED_AT, now).putString(KEY_LATEST, body) }
        _available.value = info.takeIf { it.isNewer(p) }
        if (info.versionCode > BuildConfig.VERSION_CODE) HeartRateRepository.log.add("update", "available: ${info.versionName} (${info.versionCode})")
    }

    private fun UpdateInfo.isNewer(p: android.content.SharedPreferences) =
        versionCode > BuildConfig.VERSION_CODE && versionCode > p.getInt(KEY_DISMISSED, 0)

    private fun parse(json: String): UpdateInfo? = runCatching {
        val o = JSONObject(json)
        UpdateInfo(o.getInt("versionCode"), o.getString("versionName"), o.getString("url"), o.optString("notes"))
    }.getOrNull()?.takeIf { it.url.startsWith("https://") }

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private const val KEY_SEEN = "seenVersion"
    private const val KEY_LATEST = "latest"
    private const val KEY_CHECKED_AT = "checkedAt"
    private const val KEY_DISMISSED = "dismissed"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
}
