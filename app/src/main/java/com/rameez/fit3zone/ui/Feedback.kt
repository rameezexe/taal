package com.rameez.fit3zone.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.BuildConfig
import com.rameez.fit3zone.data.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens the share sheet with a feedback template, phone/app details and a copy of the app log (connection,
 * session and sync events; no GPS positions, heart-rate history or tokens). Nothing is sent until the user
 * picks an app and sends it.
 */
suspend fun sendFeedback(context: Context) {
    val log = withContext(Dispatchers.IO) { logCopy(context) }
    val device = DevicePrefs(context).device
    val details = listOf(
        "$APP_NAME ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Sensor: ${device?.let { "${it.displayName ?: "unnamed"} (${it.kind.title})" } ?: "none"}",
        "Background: battery ${if (ignoresBatteryOptimizations(context)) "unrestricted" else "optimized"}, " +
            "power saving ${if (powerSaverLimitsGps(context)) "on (limits GPS)" else "off"}, phone family ${PhoneFamily.of().label}",
    ).joinToString("\n")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        BuildConfig.FEEDBACK_EMAIL.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        putExtra(Intent.EXTRA_SUBJECT, "$APP_NAME feedback")
        putExtra(Intent.EXTRA_TEXT, "What happened, or what would you like?\n\n\nWhat did you expect?\n\n\n---\n$details\n")
        log?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(send, "Send feedback"))
}

/** The last ~400 KB of the log (previous + current file) in cache/feedback, or null when there's no log yet. */
private fun logCopy(context: Context): File? {
    val dir = File(context.filesDir, "logs")
    val text = listOf(File(dir, "fit3zone.log.1"), File(dir, "fit3zone.log"))
        .filter { it.exists() }.joinToString("") { it.readText() }
        .takeLast(400_000)
    if (text.isBlank()) return null
    val out = File(context.cacheDir, "feedback").apply { mkdirs() }
    return File(out, "log.txt").apply { writeText(text) }
}
