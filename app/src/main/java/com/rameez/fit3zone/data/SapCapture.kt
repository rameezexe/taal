package com.rameez.fit3zone.data

import com.rameez.fit3zone.APP_NAME
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class SapFrame(val wallClockMs: Long, val source: String, val bytes: Int, val hex: String)

data class SapCaptureState(
    val enabled: Boolean = false,
    /** Characteristics we are locally registered on, as "service/char props". */
    val listening: List<String> = emptyList(),
    val frames: Int = 0,
    val bytes: Long = 0,
    val file: File? = null,
    val recent: List<SapFrame> = emptyList(),
)

/**
 * Passive recorder for notifications the band sends on its proprietary characteristics (SAP 0x1A1A
 * and the unknown vendor services). Read-only research aid: the BLE manager only *registers locally*
 * for these characteristics and never writes anything to the band (no CCCD write, no SAP write).
 *
 * One line per frame: `wallClockIso elapsedMs source len hex`. Files stay in app storage
 * (`files/sap/`) and only leave the phone through the user's share sheet.
 */
object SapCapture {
    private val _state = MutableStateFlow(SapCaptureState())
    val state: StateFlow<SapCaptureState> = _state.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sap-capture") }
    private var writer: BufferedWriter? = null // touched on [io] only
    private var prefsLoaded = false

    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US) }

    fun dir(context: Context) = File(context.filesDir, "sap")

    /** Restores the user's on/off choice; call before the BLE manager connects. */
    fun load(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        if (prefs(context).getBoolean(KEY_ENABLED, false)) start(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) start(context) else stop()
    }

    private fun start(context: Context) {
        if (_state.value.enabled) return
        val name = "sap-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        val file = File(dir(context), name)
        _state.update { it.copy(enabled = true, frames = 0, bytes = 0, file = file, recent = emptyList()) }
        io.execute {
            file.parentFile?.mkdirs()
            writer = file.bufferedWriter().also { it.write("# $APP_NAME passive SAP capture, started ${stamp.get()!!.format(Date())}\n") }
            flush()
        }
    }

    private fun stop() {
        _state.update { it.copy(enabled = false) }
        io.execute {
            writer?.runCatching { close() }
            writer = null
        }
    }

    val isEnabled: Boolean get() = _state.value.enabled

    fun setListening(list: List<String>) = _state.update { it.copy(listening = list) }

    /** Records a free-form marker line (connect, registration, user notes). */
    fun note(text: String) {
        if (!isEnabled) return
        val line = "# ${stamp.get()!!.format(Date())} $text\n"
        io.execute { writer?.write(line); flush() }
    }

    fun onFrame(service: UUID, characteristic: UUID, value: ByteArray) {
        if (!isEnabled) return
        val now = System.currentTimeMillis()
        val source = "${short(service)}/${short(characteristic)}"
        val hex = value.joinToString("") { "%02x".format(it) }
        val line = "${stamp.get()!!.format(Date(now))} ${SystemClock.elapsedRealtime()} $source ${value.size} $hex\n"
        _state.update {
            it.copy(
                frames = it.frames + 1, bytes = it.bytes + value.size,
                recent = (it.recent + SapFrame(now, source, value.size, hex)).takeLast(RECENT),
            )
        }
        io.execute { writer?.write(line); flush() }
    }

    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".log") }?.sortedByDescending { it.name } ?: emptyList()

    /** Deletes every capture except the one currently being written. */
    fun deleteOld(context: Context) {
        val current = _state.value.file.takeIf { isEnabled }
        files(context).filter { it != current }.forEach { it.delete() }
    }

    private fun flush() = runCatching { writer?.flush() }

    private fun short(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8) else s.substring(0, 8)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("sap_capture", Context.MODE_PRIVATE)

    private const val KEY_ENABLED = "enabled"
    private const val RECENT = 30
}
