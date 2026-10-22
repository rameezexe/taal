package com.rameez.fit3zone.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.APP_NAME
import com.rameez.fit3zone.data.Backup
import com.rameez.fit3zone.data.BackupInfo
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SessionHistory
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** Device → Your data: export everything to one file, or replace this phone's data with a backup. */
@Composable
fun DataScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var staged by remember { mutableStateOf<Pair<BackupInfo, File>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val locale = currentLocale()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = withContext(SessionHistory.dispatcher) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { Backup.export(context, it) }
                }.fold(
                    { "Saved ${it.activities} ${if (it.activities == 1) "activity" else "activities"} and your settings." },
                    { HeartRateRepository.log.add("backup", "export failed: $it"); "Couldn't save the backup: ${it.message}" },
                )
            }
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(SessionHistory.dispatcher) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.read(context, it) } }
            }.fold({ staged = it; confirm = false; message = null }, { staged = null; message = it.message ?: "Couldn't read that file." })
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Everything stays on this phone, and uninstalling $APP_NAME deletes it. Save a backup before you reinstall " +
                "or change phones.",
            color = ZoneColors.muted, fontSize = 14.sp,
        )
        GlowCard(accent = Palette.accent) {
            CardLabel("Export", Palette.accent)
            Text("Activities (routes, heart rate, laps, notes), band data, your profile, zones, alerts, workouts and plans, and the chosen sensor — in one .zip file.", fontSize = 14.sp)
            GradientButton(if (busy) "Saving…" else "Save backup file", {
                if (!busy) exporter.launch("taal-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip")
            })
        }
        GlowCard(accent = Palette.accent2) {
            CardLabel("Import", Palette.accent2)
            Text(
                "Replaces all activities and settings on this phone with the backup. Connect Strava and Samsung Health " +
                    "again afterwards (logins aren't stored in backups).",
                fontSize = 14.sp,
            )
            val s = staged
            if (s == null) {
                GhostButton("Choose backup file", { importer.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), height = 56.dp)
            } else {
                val (info, dir) = s
                Text(
                    "Backup from ${SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(info.exportedAt))} " +
                        "(${info.versionName}): ${info.activities} ${if (info.activities == 1) "activity" else "activities"}.",
                    fontWeight = FontWeight.SemiBold,
                )
                GradientButton(if (confirm) "Tap again to replace" else "Replace this phone's data", {
                    if (!confirm) confirm = true
                    else scope.launch {
                        val error = withContext(SessionHistory.dispatcher) { runCatching { Backup.import(context, dir) }.exceptionOrNull() }
                        if (error == null) {
                            HeartRateRepository.log.add("backup", "imported ${info.activities} activities from ${info.versionName}; restarting")
                            restartApp(context)
                        } else {
                            message = error.message ?: "Import failed."
                            confirm = false
                        }
                    }
                })
                TextAction("Cancel", { staged = null; confirm = false; dir.deleteRecursively() })
            }
        }
        message?.let { Text(it, color = if (it.startsWith("Saved")) ZoneColors.inZone else ZoneColors.pending) }
    }
}

/** Starts the app fresh: the imported databases and settings are read from scratch. */
private fun restartApp(context: Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        context.startActivity(Intent.makeRestartActivityTask(it.component))
    }
    exitProcess(0)
}
