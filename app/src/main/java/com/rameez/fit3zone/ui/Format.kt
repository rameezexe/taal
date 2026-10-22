package com.rameez.fit3zone.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.rameez.fit3zone.core.Zone
import kotlinx.coroutines.delay

/** Ticks every 500 ms so freshness/"x s ago" labels update without a new sample arriving. */
@Composable
fun rememberElapsedNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return now
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Local "HH:mm" for an epoch-ms timestamp. */
fun formatClock(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(epochMs))

object ZoneColors {
    val above = Color(0xFFFF4D5E)
    val inZone = Color(0xFF2ED47A)
    val below = Color(0xFF3B9EFF)
    val pending = Color(0xFFFFB020)
    val noData = Color(0xFF3A414B)
    val muted = Palette.muted

    fun of(zone: Zone?): Color = when (zone) {
        Zone.ABOVE_ZONE -> above
        Zone.IN_ZONE -> inZone
        Zone.BELOW_ZONE -> below
        null -> noData
    }

    /** Garmin's heart-rate zone colours: Z1 grey, Z2 blue, Z3 green, Z4 orange, Z5 red; zone 0 dark grey. */
    private val hr = listOf(
        Color(0xFF3A414B), Color(0xFF9AA6B8), Color(0xFF3B9EFF), Color(0xFF2ED47A), Color(0xFFFF9F1C), Color(0xFFFF4D5E),
    )

    fun hrZone(z: Int?): Color = z?.let { hr.getOrNull(it) } ?: noData
}

/** "Z3", or "Z0" shown as "Rest" since it is below Z1. */
fun zoneName(z: Int): String = if (z == 0) "Rest" else "Z$z"

/** The UI locale, read so composables recompose if it changes. */
@Composable
fun currentLocale(): java.util.Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
