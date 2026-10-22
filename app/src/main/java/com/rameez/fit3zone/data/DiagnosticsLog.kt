package com.rameez.fit3zone.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val wallClockMs: Long, val tag: String, val message: String) {
    override fun toString(): String = "${TIME.get()!!.format(Date(wallClockMs))} [$tag] $message"

    private companion object {
        val TIME: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    }
}

/**
 * Bounded in-memory log shown on the Diagnostics screen, mirrored to logcat (tag Fit3Zone) and, once
 * [attachFile] is called, appended to files/logs/fit3zone.log. Logcat on the phone rotates within minutes,
 * so the file is what survives a run (pull with `adb exec-out run-as … cat files/logs/fit3zone.log`).
 */
class DiagnosticsLog(private val capacity: Int = 500) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile private var file: File? = null
    @Volatile private var maxBytes = 2_000_000L
    /** File writes happen off the caller's thread (BLE / session threads must not wait on disk). */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "log-file").apply { isDaemon = true } }

    /** Keeps roughly the last 2 × [maxBytes] of log on disk: fit3zone.log plus the previous fit3zone.log.1. */
    fun attachFile(dir: File, maxBytes: Long = 2_000_000) {
        dir.mkdirs()
        this.maxBytes = maxBytes
        file = File(dir, "fit3zone.log")
    }

    fun add(tag: String, message: String) {
        Log.i("Fit3Zone", "[$tag] $message")
        val entry = LogEntry(System.currentTimeMillis(), tag, message)
        _entries.update { (it + entry).takeLast(capacity) }
        val f = file ?: return
        val line = "${DAY.get()!!.format(Date(entry.wallClockMs))} $entry\n"
        // After a crash the writer is shut down; late lines from other threads are dropped.
        runCatching {
            writer.execute {
                runCatching {
                    if (f.length() > maxBytes) f.renameTo(File(f.parentFile, "fit3zone.log.1"))
                    f.appendText(line)
                }
            }
        }
    }

    /**
     * For a crash: written to the file on the calling thread before returning, because the process dies right after
     * (queued [add] lines could be lost). Feedback attaches the file, so testers' crashes reach us.
     */
    fun crash(thread: String, error: Throwable) {
        val trace = error.stackTraceToString().lines().take(60).joinToString("\n")
        Log.e("Fit3Zone", "CRASH on $thread", error)
        val f = file ?: return
        val now = System.currentTimeMillis()
        val line = "${DAY.get()!!.format(Date(now))} ${LogEntry(now, "crash", "on thread $thread: $trace")}\n"
        writer.shutdown()
        runCatching { writer.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        runCatching { f.appendText(line) }
    }

    private companion object {
        val DAY: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("MM-dd", Locale.US) }
    }

    fun clear() { _entries.value = emptyList() }

    fun dump(): String = _entries.value.joinToString("\n")
}
