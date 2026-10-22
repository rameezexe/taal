package com.rameez.fit3zone.ui

import com.rameez.fit3zone.APP_NAME
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import com.rameez.fit3zone.data.BandData
import com.rameez.fit3zone.data.SapCapture
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rameez.fit3zone.Fit3ZoneApp
import com.rameez.fit3zone.core.SimulatedSequence
import com.rameez.fit3zone.data.HeartRateRepository

@Composable
fun DiagnosticsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val repo = HeartRateRepository
    val simulator = remember { (context.applicationContext as Fit3ZoneApp).simulator }
    val connection by repo.connection.collectAsStateWithLifecycle()
    val stats by repo.stats.collectAsStateWithLifecycle()
    val last by repo.lastPacket.collectAsStateWithLifecycle()
    val info by repo.deviceInfo.collectAsStateWithLifecycle()
    val log by repo.log.entries.collectAsStateWithLifecycle()
    val simRunning by simulator.running.collectAsStateWithLifecycle()
    var sequence by remember { mutableStateOf(SimulatedSequence.DEFAULT) }
    var simError by remember { mutableStateOf<String?>(null) }
    val sap by SapCapture.state.collectAsStateWithLifecycle()
    val band by BandData.state.collectAsStateWithLifecycle()
    var importResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var sapFiles by remember { mutableStateOf(SapCapture.files(context)) }
    val now = rememberElapsedNow()

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Section("Connection") }
        item { Mono("state: $connection") }
        item { Mono("device: ${info.name} ${info.address}") }
        item { Mono("firmware rev: ${info.firmwareRevision ?: "-"}   software rev: ${info.softwareRevision ?: "-"}") }
        item { Mono("body sensor location: ${info.bodySensorLocation ?: "-"}") }
        item { Mono("freshness timeout: ${repo.freshness.timeoutMs} ms   fresh now: ${repo.isFresh(now)}") }

        item { Section("Packets") }
        item {
            Mono(
                "total ${stats.total} · valid ${stats.valid} · rejected ${stats.rejected} · " +
                    "malformed ${stats.malformed} · duplicate ${stats.duplicates}",
            )
        }
        item { Mono("last reject: ${stats.lastRejectReason ?: "-"}") }
        item {
            Mono(last?.let { "last: ${it.hex}  ${it.decoded}  (${(now - it.elapsedMs) / 1000}s ago)" } ?: "last: -")
        }

        item { Section("Band data (decoded from SAP, read-only)") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = band.enabled, onCheckedChange = { BandData.setEnabled(context, it) })
                Text(
                    if (band.enabled) "Decoding heart rate and steps the band sends when Galaxy Wearable syncs."
                    else "Off. My Day won't show band data.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            val st = band.stats
            Mono(
                "notifications ${st.notifications} · frames ${st.frames} · crc errors ${st.crcErrors} · health msgs ${st.healthMessages}\n" +
                    "hr records ${st.hrRecords} · step records ${st.stepRecords} · other ${st.otherRecords} · encrypted ${st.encryptedFrames} · malformed ${st.malformed}\n" +
                    "unmapped types ${band.unknownTypes.sorted().joinToString { "%02x".format(it) }.ifEmpty { "-" }} · " +
                    "last records ${band.lastRecordsAt?.let { formatClock(it) } ?: "-"}",
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    importResult = "decoding…"
                    scope.launch {
                        importResult = withContext(Dispatchers.IO) {
                            runCatching { "stored ${BandData.importCaptures(context, SapCapture.files(context))} records" }
                                .getOrElse { "failed: ${it.message}" }
                        }
                    }
                }, enabled = sapFiles.isNotEmpty()) { Text("Decode saved captures") }
                importResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item { Section("Passive SAP listener (research, read-only)") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = sap.enabled, onCheckedChange = {
                    SapCapture.setEnabled(context, it)
                    sapFiles = SapCapture.files(context)
                })
                Text(
                    if (sap.enabled) "Recording what the band sends. Now open Galaxy Wearable and sync."
                    else "Off. Records band → phone frames only; never writes to the band.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (sap.enabled) {
            item { Mono("frames ${sap.frames} · ${sap.bytes} bytes · file ${sap.file?.name ?: "-"}") }
            item { Mono(if (sap.listening.isEmpty()) "not registered yet (connect the band)" else "listening:") }
            items(sap.listening) { Mono("  $it") }
            items(sap.recent.asReversed().take(12)) { f -> Mono("${f.source} ${f.bytes}B ${f.hex.take(96)}") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sapFiles.firstOrNull()?.let { shareCapture(context, it) } }, enabled = sapFiles.isNotEmpty()) {
                    Text("Share latest")
                }
                OutlinedButton(onClick = { SapCapture.deleteOld(context); sapFiles = SapCapture.files(context) }, enabled = sapFiles.size > (if (sap.enabled) 1 else 0)) {
                    Text("Delete old")
                }
            }
        }
        items(sapFiles.take(5)) { Mono("${it.name}  ${it.length() / 1024} KB") }

        item { Section("Simulator (data is tagged SIMULATED)") }
        item {
            OutlinedTextField(
                value = sequence, onValueChange = { sequence = it; simError = null },
                label = { Text("BPM sequence, 5 s per value") }, modifier = Modifier.fillMaxWidth(),
                isError = simError != null, supportingText = simError?.let { { Text(it) } },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching { SimulatedSequence.parse(sequence) }
                        .onSuccess { simulator.start(it) }
                        .onFailure { simError = it.message }
                }, enabled = !simRunning) { Text("Run") }
                OutlinedButton(onClick = { simulator.stop() }, enabled = simRunning) { Text("Stop") }
            }
        }

        item { Section("Services (${info.services.size})") }
        items(info.services) { Mono(it) }

        item { Section("Log (${log.size})") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, repo.log.dump()) }) { Text("Copy log") }
                OutlinedButton(onClick = { repo.log.clear() }) { Text("Clear") }
            }
        }
        items(log.asReversed()) { Mono(it.toString()) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp))
}

@Composable
private fun Mono(text: String) {
    Column { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD0D4D8)) }
}

/** Capture files contain raw health data; they only leave the phone via the share sheet. */
private fun shareCapture(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "$APP_NAME SAP capture ${file.name}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export SAP capture"))
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("$APP_NAME log", text))
}
