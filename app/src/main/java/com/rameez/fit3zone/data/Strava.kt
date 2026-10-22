package com.rameez.fit3zone.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import com.rameez.fit3zone.BuildConfig
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.ActivityType
import com.rameez.fit3zone.core.ExportSession
import com.rameez.fit3zone.core.TcxWriter
import com.rameez.fit3zone.core.stravaSportType
import com.rameez.fit3zone.core.stravaTrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class UploadStatus { UPLOADING, DONE, FAILED }

data class StravaUpload(val status: UploadStatus, val activityId: Long? = null, val error: String? = null) {
    val url: String? get() = activityId?.let { "https://www.strava.com/activities/$it" }
}

data class StravaState(
    val configured: Boolean,
    val connected: Boolean = false,
    val athleteName: String? = null,
    val autoUpload: Boolean = true,
    /** Per session id; sessions never tried are missing. */
    val uploads: Map<Long, StravaUpload> = emptyMap(),
    /** Connect / disconnect / history problem to show once. */
    val message: String? = null,
    /** "Uploading 3 of 7" while the history upload runs. */
    val progress: String? = null,
    /** Granted read access too, which Strava needs before it lets us rename an activity. Older logins lack it. */
    val canEdit: Boolean = false,
)

/**
 * Strava connection: OAuth login through the Strava app or browser, tokens encrypted with an Android Keystore
 * key, and activity uploads as TCX files. Only sessions the user uploads (or, with auto-upload on, runs that
 * finish after connecting) leave the phone, and only to Strava.
 *
 * Token requests go through the token Worker (strava-worker/, BuildConfig.STRAVA_TOKEN_URL), which adds the
 * client secret. Debug builds without a Worker URL send the secret from BuildConfig themselves (personal use only).
 */
class Strava private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("strava", Context.MODE_PRIVATE)
    private val history = SessionHistory.get(context)
    private val devices = DevicePrefs(context)
    private val vault = TokenVault()
    private val uploadLock = Mutex()
    private val tokenLock = Mutex()

    private val _state = MutableStateFlow(
        StravaState(
            configured = BuildConfig.STRAVA_CLIENT_ID.isNotBlank() &&
                (BuildConfig.STRAVA_TOKEN_URL.isNotBlank() || BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()),
            connected = prefs.contains(KEY_REFRESH),
            athleteName = prefs.getString("athlete", null),
            autoUpload = prefs.getBoolean("autoUpload", true),
            uploads = readUploads(),
            canEdit = canEdit(prefs.getString("scope", null)),
        ),
    )
    val state: StateFlow<StravaState> = _state.asStateFlow()

    // ---- login ----

    /** Opens in the Strava app when installed (it handles this link), otherwise in the browser. */
    fun authorizeUri(): Uri {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val oauthState = Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit { putString("oauthState", oauthState) }
        return "https://www.strava.com/oauth/mobile/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", BuildConfig.STRAVA_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", oauthState)
            .build()
    }

    /** Handles taal://localhost/strava?code=…&scope=…&state=… from the login page. */
    suspend fun completeAuthorization(redirect: Uri) = withContext(Dispatchers.IO) {
        val result = runCatching {
            redirect.getQueryParameter("error")?.let { error(if (it == "access_denied") "Access was not allowed" else it) }
            val expected = prefs.getString("oauthState", null)
            require(expected != null && redirect.getQueryParameter("state") == expected) { "Login reply didn't match; try again" }
            val scope = redirect.getQueryParameter("scope").orEmpty()
            require("activity:write" in scope.split(",")) { "Tick \"Upload your activities\" on the Strava page so runs can be uploaded" }
            val code = requireNotNull(redirect.getQueryParameter("code")) { "No login code from Strava" }
            val json = token(mapOf("code" to code, "grant_type" to "authorization_code"))
            saveTokens(json)
            val athlete = json.optJSONObject("athlete")
            val name = athlete?.let { listOf(it.optString("firstname"), it.optString("lastname")).filter(String::isNotBlank).joinToString(" ") }
            prefs.edit {
                remove("oauthState")
                putString("athlete", name)
                putString("scope", scope)
                // Auto-upload only covers sessions that end from now on; older ones go through "Upload history".
                putLong("connectedAt", System.currentTimeMillis())
            }
            name
        }
        result.onSuccess { name ->
            _state.update { it.copy(connected = true, athleteName = name, message = null, canEdit = canEdit(prefs.getString("scope", null))) }
            HeartRateRepository.log.add("strava", "connected as ${name ?: "?"}")
        }.onFailure { e ->
            _state.update { it.copy(message = "Not connected: ${e.message}") }
            HeartRateRepository.log.add("strava", "connect failed: ${e.message}")
        }
    }

    /** Removes the app's access at Strava (best effort) and forgets the tokens. Upload history is kept. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (prefs.contains(KEY_REFRESH)) runCatching {
            post("https://www.strava.com/oauth/deauthorize", mapOf("access_token" to accessToken()))
        }.onFailure { HeartRateRepository.log.add("strava", "deauthorize failed (tokens forgotten anyway): ${it.message}") }
        prefs.edit { remove(KEY_ACCESS); remove(KEY_REFRESH); remove("expiresAt"); remove("athlete"); remove("connectedAt"); remove("scope") }
        _state.update { it.copy(connected = false, athleteName = null, message = null, canEdit = false) }
        HeartRateRepository.log.add("strava", "disconnected")
    }

    fun setAutoUpload(on: Boolean) {
        prefs.edit { putBoolean("autoUpload", on) }
        _state.update { it.copy(autoUpload = on) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Token exchange / refresh: through the Worker when there is one, else straight to Strava with the secret.
     * STRAVA_TOKEN_URL may be the Worker's base URL or its /token endpoint.
     */
    private fun token(params: Map<String, String>): JSONObject =
        if (BuildConfig.STRAVA_TOKEN_URL.isNotBlank()) post(BuildConfig.STRAVA_TOKEN_URL.trimEnd('/').removeSuffix("/token") + "/token", params)
        else post(
            "https://www.strava.com/oauth/token",
            params + mapOf("client_id" to BuildConfig.STRAVA_CLIENT_ID, "client_secret" to BuildConfig.STRAVA_CLIENT_SECRET),
        )

    private fun saveTokens(json: JSONObject) {
        prefs.edit {
            putString(KEY_ACCESS, vault.encrypt(json.getString("access_token")))
            putString(KEY_REFRESH, vault.encrypt(json.getString("refresh_token")))
            putLong("expiresAt", json.getLong("expires_at"))
        }
    }

    /** A valid access token, refreshed when it has less than 5 minutes left (they last 6 hours). */
    private suspend fun accessToken(): String = tokenLock.withLock {
        val refresh = prefs.getString(KEY_REFRESH, null)?.let(vault::decrypt) ?: throw NotConnected()
        val access = prefs.getString(KEY_ACCESS, null)?.let(vault::decrypt)
        if (access != null && prefs.getLong("expiresAt", 0) - 300 > System.currentTimeMillis() / 1000) return access
        val json = try {
            token(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
        } catch (e: HttpError) {
            // Revoked on strava.com (Settings → My Apps): forget the tokens so the screen offers Connect again.
            if (e.code == 400 || e.code == 401) {
                prefs.edit { remove(KEY_ACCESS); remove(KEY_REFRESH); remove("expiresAt") }
                _state.update { it.copy(connected = false, message = "Strava access was removed; connect again") }
                throw NotConnected()
            }
            throw e
        }
        saveTokens(json) // Strava may hand out a new refresh token: always keep the latest.
        json.getString("access_token")
    }

    // ---- uploads ----

    /** Can this session go to Strava at all. */
    fun uploadable(s: SessionSummary): Boolean =
        !s.simulated && s.status != SessionStatus.IN_PROGRESS && s.activeMs >= 60_000

    /** Uploads one session and waits for Strava to process it. Safe to call again after a failure. */
    suspend fun upload(s: SessionSummary): StravaUpload = uploadLock.withLock {
        _state.value.uploads[s.id]?.takeIf { it.status == UploadStatus.DONE }?.let { return it }
        setUpload(s.id, StravaUpload(UploadStatus.UPLOADING), persist = false)
        val result = try {
            StravaUpload(UploadStatus.DONE, activityId = withContext(Dispatchers.IO) { send(s) })
        } catch (e: NotConnected) {
            StravaUpload(UploadStatus.FAILED, error = "Not connected to Strava")
        } catch (e: HttpError) {
            StravaUpload(UploadStatus.FAILED, error = "Strava refused the upload (${e.message})")
        } catch (e: IOException) {
            StravaUpload(UploadStatus.FAILED, error = "No connection to Strava (${e.message ?: e.javaClass.simpleName})")
        } catch (e: Exception) {
            StravaUpload(UploadStatus.FAILED, error = e.message ?: e.javaClass.simpleName)
        }
        // Network trouble isn't remembered across restarts, so auto-upload tries again next time.
        val persist = result.status == UploadStatus.DONE || result.error?.startsWith("No connection") == false
        setUpload(s.id, result, persist)
        HeartRateRepository.log.add("strava", "session ${s.id}: ${result.status}${result.activityId?.let { " activity $it" } ?: ""}${result.error?.let { " — $it" } ?: ""}")
        result
    }

    /**
     * Uploads a session again, e.g. after its distance was corrected (Strava's API can't change distance, so the
     * old activity has to be deleted on Strava first; if it's still there Strava reports a duplicate and it's kept).
     */
    suspend fun uploadAgain(s: SessionSummary): StravaUpload {
        _state.update { it.copy(uploads = it.uploads - s.id) }
        prefs.edit {
            val json = JSONObject(prefs.getString("uploads", "{}") ?: "{}")
            json.remove(s.id.toString())
            putString("uploads", json.toString())
        }
        return upload(s)
    }

    /** Every finished real session not on Strava yet, oldest first. */
    fun notUploaded(sessions: List<SessionSummary>): List<SessionSummary> =
        sessions.filter { uploadable(it) && _state.value.uploads[it.id]?.status != UploadStatus.DONE }.sortedBy { it.startedAt }

    suspend fun uploadHistory(sessions: List<SessionSummary>) {
        val todo = notUploaded(sessions)
        var failed = 0
        todo.forEachIndexed { i, s ->
            _state.update { it.copy(progress = "Uploading ${i + 1} of ${todo.size}…") }
            if (upload(s).status == UploadStatus.FAILED) failed++
            if (!_state.value.connected) return@forEachIndexed
        }
        _state.update {
            it.copy(progress = null, message = if (failed == 0) "Uploaded ${todo.size} ${if (todo.size == 1) "activity" else "activities"}" else "$failed of ${todo.size} failed (see the activities)")
        }
    }

    /**
     * With auto-upload on, uploads each session of at least [AUTO_SYNC_MIN_MS] that finishes after connecting. Sessions that failed because the
     * phone was offline are tried again the next time the app starts.
     */
    fun watch(scope: CoroutineScope, history: SessionHistory) {
        scope.launch {
            history.sessions.collect { list ->
                val st = _state.value
                if (!st.connected || !st.autoUpload) return@collect
                val since = prefs.getLong("connectedAt", Long.MAX_VALUE)
                list.filter { it.status == SessionStatus.COMPLETED && it.reviewed && SYNC_STRAVA !in it.skipSync && uploadable(it) && it.activeMs >= AUTO_SYNC_MIN_MS && (it.endedAt ?: 0) >= since && it.id !in st.uploads }
                    .sortedBy { it.startedAt }
                    .forEach { s ->
                        // Marked before launching so the next emission doesn't start it twice.
                        setUpload(s.id, StravaUpload(UploadStatus.UPLOADING), persist = false)
                        launch { upload(s) }
                    }
            }
        }
    }

    /**
     * Distance at each fix (GPS gaps bridged by steps), scaled so the last one is the activity's saved distance:
     * Strava then shows the same distance as the app instead of re-measuring the GPS track.
     */
    private fun pointDistances(s: SessionSummary, points: List<GeoPoint>): Map<Long, Double> {
        if (points.size < 2 || s.distanceM <= 0) return emptyMap()
        val track = replayTrack(s, points, steps = history.stepTimeline(s.id))
        if (track.distanceM <= 0) return emptyMap()
        val scale = s.distanceM / track.distanceM
        return track.fixDistances.associate { (t, d) -> t to d * scale }
    }

    private suspend fun send(s: SessionSummary): Long {
        val tcx = withContext(SessionHistory.dispatcher) {
            val points = history.points(s.id)
            val samples = history.samples(s.id)
            TcxWriter.write(
                ExportSession(
                    startedAtMs = s.startedAt, type = s.type, activeMs = s.activeMs, distanceM = s.distanceM,
                    avgBpm = s.avgBpm, maxBpm = s.maxBpm, samples = samples, points = points,
                    cadence = history.cadence(s.id), segmentBreaksMs = s.segmentBreaksMs, laps = history.laps(s.id),
                    pointDistancesM = pointDistances(s, points),
                ),
            )
        }
        val token = accessToken()
        val fields = buildMap {
            put("data_type", "tcx")
            put("name", s.title)
            put("description", s.notes(devices.device))
            put("external_id", "fit3zone-${s.id}-${s.startedAt / 1000}")
            if (s.type.stravaTrainer) put("trainer", "1")
        }
        var upload = multipart("https://www.strava.com/api/v3/uploads", token, fields, "fit3zone-${s.id}.tcx", tcx.toByteArray())
        val uploadId = upload.getLong("id")
        // Processing usually takes a few seconds.
        repeat(45) {
            upload.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { err ->
                // Already there (re-upload, or Samsung Health sent it first): link to the existing activity.
                DUPLICATE.find(err)?.let { return it.groupValues[1].toLong() }
                error("Strava: ${err.replace(Regex("<[^>]+>"), "")}")
            }
            if (!upload.isNull("activity_id")) {
                val id = upload.getLong("activity_id")
                fixSportType(id, s.type, token)
                return id
            }
            delay(2_000)
            upload = get("https://www.strava.com/api/v3/uploads/$uploadId", token)
        }
        error("Strava is still processing the upload; check strava.com in a minute")
    }

    /**
     * Puts the session's current name and description (note + summary) on its Strava activity. Nothing to do
     * when it isn't on Strava (a later upload uses them). Strava answers 404 unless the login granted read
     * access (activity:read_all).
     */
    suspend fun syncDetails(s: SessionSummary): Result<Boolean> = withContext(Dispatchers.IO) {
        val activityId = _state.value.uploads[s.id]?.takeIf { it.status == UploadStatus.DONE }?.activityId
            ?: return@withContext Result.success(false)
        if (!_state.value.canEdit) {
            return@withContext Result.failure(IOException("Strava needs one more permission: Device → Strava → Allow renaming"))
        }
        runCatching {
            try {
                request("PUT", "https://www.strava.com/api/v3/activities/$activityId", form(mapOf("name" to s.title, "description" to s.notes(devices.device))), "Bearer ${accessToken()}", FORM)
            } catch (e: HttpError) {
                if (e.code == 404) throw IOException("Strava can't find this activity (deleted there?)")
                throw e
            }
            true
        }.also { r ->
            HeartRateRepository.log.add("strava", "update $activityId \"${s.title}\": ${r.exceptionOrNull()?.let { "failed — ${it.message}" } ?: "ok"}")
        }
    }

    /** TCX only says run / bike / other; walks and gym sessions get their real type afterwards. */
    private fun fixSportType(activityId: Long, type: ActivityType, token: String) {
        val inferred = when (TcxWriter.sport(type)) { "Running" -> "Run"; "Biking" -> "Ride"; else -> "Workout" }
        val wanted = type.stravaSportType()
        if (wanted == inferred) return
        // Needs read access (logins before activity:read_all was asked for get 404): then the type stays as uploaded.
        runCatching { request("PUT", "https://www.strava.com/api/v3/activities/$activityId", form(mapOf("sport_type" to wanted)), "Bearer $token", FORM) }
            .onFailure { HeartRateRepository.log.add("strava", "couldn't set sport type $wanted on $activityId: ${it.message}") }
    }

    private fun setUpload(id: Long, upload: StravaUpload, persist: Boolean) {
        _state.update { it.copy(uploads = it.uploads + (id to upload)) }
        if (persist) {
            val json = JSONObject(prefs.getString("uploads", "{}") ?: "{}")
            json.put(id.toString(), JSONObject().apply { upload.activityId?.let { put("a", it) }; upload.error?.let { put("e", it) } })
            prefs.edit { putString("uploads", json.toString()) }
        }
    }

    private fun readUploads(): Map<Long, StravaUpload> = runCatching {
        val json = JSONObject(prefs.getString("uploads", "{}") ?: "{}")
        json.keys().asSequence().associate { k ->
            val o = json.getJSONObject(k)
            k.toLong() to if (o.has("a")) StravaUpload(UploadStatus.DONE, o.getLong("a")) else StravaUpload(UploadStatus.FAILED, error = o.optString("e"))
        }
    }.getOrDefault(emptyMap())

    // ---- HTTP ----

    private class HttpError(val code: Int, body: String) : IOException("HTTP $code ${body.take(200)}")
    private class NotConnected : Exception("Not connected to Strava")

    private fun post(url: String, params: Map<String, String>, auth: String? = null) = JSONObject(request("POST", url, form(params), auth, FORM))
    private fun get(url: String, token: String) = JSONObject(request("GET", url, null, "Bearer $token", null))

    private fun multipart(url: String, token: String, fields: Map<String, String>, fileName: String, file: ByteArray): JSONObject {
        val boundary = "fit3zone" + System.nanoTime()
        val body = java.io.ByteArrayOutputStream().apply {
            fun line(s: String) = write("$s\r\n".toByteArray())
            fields.forEach { (k, v) ->
                line("--$boundary"); line("Content-Disposition: form-data; name=\"$k\""); line(""); line(v)
            }
            line("--$boundary"); line("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"")
            line("Content-Type: application/xml"); line("")
            write(file); line("")
            line("--$boundary--")
        }.toByteArray()
        return JSONObject(request("POST", url, body, "Bearer $token", "multipart/form-data; boundary=$boundary"))
    }

    private fun form(params: Map<String, String>) =
        params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(method: String, url: String, body: ByteArray?, auth: String?, contentType: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            auth?.let { c.setRequestProperty("Authorization", it) }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 429) throw IOException("Strava rate limit reached, try again in 15 minutes")
            if (code !in 200..299) throw HttpError(code, text)
            return text.ifBlank { "{}" }
        } finally {
            c.disconnect()
        }
    }

    /** AES-GCM with a key that never leaves the Android Keystore; tokens are stored only encrypted. */
    private class TokenVault {
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
        }

        fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray())
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        fun decrypt(stored: String): String {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            }
            return String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }

        companion object { const val ALIAS = "strava_tokens" }
    }

    companion object {
        /** Strava accepts any redirect on the app's callback domain (localhost); Android routes this scheme to us. */
        const val REDIRECT_URI = BuildConfig.STRAVA_REDIRECT_URI
        private fun canEdit(scope: String?) = scope?.split(",")?.any { it == "activity:read_all" || it == "activity:read" } == true
        private const val SCOPE = "read,activity:read_all,activity:write"
        private const val FORM = "application/x-www-form-urlencoded"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private val DUPLICATE = Regex("""duplicate of .*?activities/(\d+)""", RegexOption.IGNORE_CASE)

        @Volatile private var instance: Strava? = null
        fun get(context: Context): Strava =
            instance ?: synchronized(this) { instance ?: Strava(context.applicationContext).also { instance = it } }
    }
}
