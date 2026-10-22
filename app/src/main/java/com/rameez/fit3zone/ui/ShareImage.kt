package com.rameez.fit3zone.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.rameez.fit3zone.R
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.drawablePieces
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SessionSummary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Story fits Instagram / WhatsApp status; Post fits an Instagram feed post (4:5) and WhatsApp chats. */
enum class ShareFormat(val label: String, val width: Int, val height: Int) {
    STORY("Story", 1080, 1920),
    POST("Post", 1080, 1350),
}

/** Preview of the share image with a format choice; Share hands the PNG to the system share sheet. */
@Composable
fun ShareImageDialog(s: SessionSummary, points: List<GeoPoint>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ShareFormat.STORY) }
    val image by produceState<Bitmap?>(null, format, s) {
        value = null
        value = withContext(Dispatchers.Default) { renderShareImage(context, s, points, format) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Share activity", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                TextAction("Close", onDismiss, color = Palette.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareFormat.entries.forEach { f -> PillChip(f.label, format == f, Palette.accent, compact = true) { format = f } }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bmp = image
                if (bmp == null) Text("Drawing…", color = Palette.muted)
                else Image(
                    bmp.asImageBitmap(), "Share image preview",
                    Modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit,
                )
            }
            GradientButton(
                "Share",
                { image?.let { bmp -> scope.launch { shareImage(context, bmp, s) } } },
                Modifier.fillMaxWidth(), enabled = image != null, height = 56.dp,
            )
        }
    }
}

/** Writes the PNG to cache/exports and opens the share sheet; it only leaves the phone if the user picks an app. */
private suspend fun shareImage(context: Context, bmp: Bitmap, s: SessionSummary) {
    val file = runCatching {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, "taal-activity-${s.id}.png").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }.getOrElse {
        HeartRateRepository.log.add("export", "share image failed: $it")
        android.widget.Toast.makeText(context, "Couldn't make the image: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("activity", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share activity"))
}

/**
 * Strava-style card: title and date, the route shape (no map tiles, so no street names or home location),
 * three headline numbers (the same ones as the detail screen), the zone strip and the ताल wordmark.
 */
fun renderShareImage(context: Context, s: SessionSummary, points: List<GeoPoint>, format: ShareFormat): Bitmap {
    val w = format.width
    val h = format.height
    val story = format == ShareFormat.STORY
    val bmp = createBitmap(w, h)
    val c = Canvas(bmp)
    val margin = 90f
    val inter = ResourcesCompat.getFont(context, R.font.inter_variable) ?: Typeface.DEFAULT
    val grotesk = ResourcesCompat.getFont(context, R.font.space_grotesk_variable) ?: Typeface.DEFAULT
    fun paint(face: Typeface, weight: Int, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        fontVariationSettings = "'wght' $weight"
        textSize = size
        this.color = color
    }

    // Layout: header, route box, zone strip, stats, wordmark (top to bottom).
    val headerTop = if (story) 170f else 120f
    val wordmarkH = if (story) 96f else 76f
    val wordmarkTop = h - (if (story) 150f else 110f) - wordmarkH
    val numbersBase = wordmarkTop - (if (story) 110f else 80f)
    val labelsBase = numbersBase - (if (story) 128f else 116f)
    val stripTop = labelsBase - (if (story) 110f else 90f)
    val routeBox = RectF(margin, headerTop + (if (story) 230f else 190f), w - margin, stripTop - (if (story) 80f else 50f))

    c.drawColor(Palette.bg.toArgb())
    // Soft saffron glow behind the route.
    Paint().apply {
        shader = RadialGradient(
            routeBox.centerX(), routeBox.centerY(), routeBox.width() * 0.75f,
            Palette.accent.copy(alpha = 0.16f).toArgb(), Palette.bg.toArgb(), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), this)
    }

    // Header.
    val date = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.getDefault()))
    c.drawText("$date · ${s.type.label}".uppercase(), margin, headerTop, paint(inter, 600, 32f, Palette.muted.toArgb()).apply { letterSpacing = 0.08f })
    val titlePaint = paint(inter, 700, if (story) 76f else 68f, Palette.text.toArgb())
    val title = TextUtils.ellipsize(s.title, android.text.TextPaint(titlePaint), w - 2 * margin, TextUtils.TruncateAt.END).toString()
    c.drawText(title, margin, headerTop + (if (story) 110f else 96f), titlePaint)

    // Middle: the route, or the time in zones for activities without GPS.
    if (points.size >= 2) drawRoute(c, points, s.segmentBreaksMs, routeBox)
    else if (s.hasZoneTimes) drawZoneBars(c, s.zoneMs, routeBox, inter, grotesk)

    // Zone strip.
    if (s.hasZoneTimes && points.size >= 2) {
        val total = s.zoneMs.drop(1).sum().coerceAtLeast(1).toFloat()
        var x = margin
        val stripW = w - 2 * margin
        c.withClip(android.graphics.Path().apply { addRoundRect(RectF(margin, stripTop, w - margin, stripTop + 14f), 7f, 7f, android.graphics.Path.Direction.CW) }) {
            for (z in 1..HrZones.COUNT) {
                val segW = stripW * s.zoneMs[z] / total
                drawRect(x, stripTop, x + segW, stripTop + 14f, Paint().apply { color = ZoneColors.hrZone(z).toArgb() })
                x += segW
            }
        }
        zoneLine(s)?.let { c.drawText(it, margin, stripTop + 62f, paint(inter, 500, 30f, Palette.muted.toArgb())) }
    }

    // Three numbers, one size for all so they line up.
    val stats = headlineStats(s)
    val colW = (w - 2 * margin) / stats.size
    val unitPaint = paint(inter, 600, 34f, Palette.muted.toArgb())
    var numSize = if (story) 112f else 100f
    stats.forEach { (value, _, unit) ->
        val p = paint(grotesk, 600, numSize, 0)
        val width = p.measureText(value) + (unit?.let { 10f + unitPaint.measureText(it) } ?: 0f)
        if (width > colW - 24f) numSize *= (colW - 24f) / width
    }
    val numPaint = paint(grotesk, 600, numSize, Palette.text.toArgb())
    val labelPaint = paint(inter, 600, 28f, Palette.muted.toArgb()).apply { letterSpacing = 0.1f }
    stats.forEachIndexed { i, (value, label, unit) ->
        val x = margin + i * colW
        c.drawText(label.uppercase(), x, labelsBase, labelPaint)
        c.drawText(value, x, numbersBase, numPaint)
        unit?.let { c.drawText(it, x + numPaint.measureText(value) + 10f, numbersBase, unitPaint) }
    }

    // Wordmark (viewport 91.8 × 64), bottom left.
    ContextCompat.getDrawable(context, R.drawable.taal_wordmark)?.let { d ->
        val ww = wordmarkH * 91.8f / 64f
        d.setBounds(margin.toInt() - 6, wordmarkTop.toInt(), (margin - 6 + ww).toInt(), (wordmarkTop + wordmarkH).toInt())
        d.draw(c)
    }
    return bmp
}

/** (value, label, unit): the same choice of numbers as [HeadlineStats]. */
private fun headlineStats(s: SessionSummary): List<Triple<String, String, String?>> =
    if (s.hasGps && s.distanceM > 0) listOf(
        Triple("%.2f".format(s.distanceM / 1000), "Distance", "km"),
        Triple(s.avgPaceSecPerKm?.let { s.type.formatPaceOrSpeed(it) } ?: "--", s.type.paceLabel, s.type.paceUnit),
        Triple(formatDuration(s.activeMs), "Time", null),
    ) else listOf(
        Triple(formatDuration(s.activeMs), "Time", null),
        Triple(s.avgBpm?.toString() ?: "--", "Avg HR", "bpm"),
        if (s.avgCadenceSpm != null) Triple(s.avgCadenceSpm.toString(), "Cadence", "spm")
        else Triple(s.maxBpm?.toString() ?: "--", "Max HR", "bpm"),
    )

private fun drawRoute(c: Canvas, points: List<GeoPoint>, breaksMs: List<Long>, box: RectF) {
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
    val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-9)
    val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
    val pad = 30f
    val scale = minOf((box.width() - 2 * pad) / spanX, (box.height() - 2 * pad) / spanY)
    val ox = box.left + (box.width() - spanX * scale) / 2
    val oy = box.top + (box.height() - spanY * scale) / 2
    fun x(p: GeoPoint) = (ox + (p.lon - minLon) * lonScale * scale).toFloat()
    fun y(p: GeoPoint) = (box.bottom - (oy - box.top) - (p.lat - minLat) * scale).toFloat()

    val pieces = points.drawablePieces(breaksMs)
    val route = android.graphics.Path()
    pieces.forEach { piece ->
        piece.forEachIndexed { i, p -> if (i == 0) route.moveTo(x(p), y(p)) else route.lineTo(x(p), y(p)) }
    }
    val accent = Palette.accent.toArgb()
    fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    // GPS gaps: a faint dashed line, so the shape stays honest.
    val gaps = stroke(5f, Palette.faint.toArgb()).apply { pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f) }
    pieces.zipWithNext { a, b -> if (a.isNotEmpty() && b.isNotEmpty()) c.drawLine(x(a.last()), y(a.last()), x(b.first()), y(b.first()), gaps) }
    c.drawPath(route, stroke(34f, Palette.accent.copy(alpha = 0.35f).toArgb()).apply { maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) })
    c.drawPath(route, stroke(12f, accent))

    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    fun marker(p: GeoPoint, fill: Int) {
        dot.color = Palette.bg.toArgb(); c.drawCircle(x(p), y(p), 20f, dot)
        dot.color = fill; c.drawCircle(x(p), y(p), 13f, dot)
    }
    marker(points.last(), Palette.text.toArgb())
    marker(points.first(), Palette.accent2.toArgb())
}

/** Indoor activities: Z1–Z5 bars with minutes, like the detail screen's zone card. */
private fun drawZoneBars(c: Canvas, zoneMs: List<Long>, box: RectF, inter: Typeface, grotesk: Typeface) {
    val total = zoneMs.drop(1).sum().coerceAtLeast(1).toFloat()
    val rowH = minOf(110f, box.height() / HrZones.COUNT)
    val top = box.centerY() - rowH * HrZones.COUNT / 2
    val labelW = 110f
    val valueW = 170f
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = inter; fontVariationSettings = "'wght' 600"; textSize = 34f; color = Palette.muted.toArgb() }
    val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = grotesk; fontVariationSettings = "'wght' 600"; textSize = 38f; color = Palette.text.toArgb(); textAlign = Paint.Align.RIGHT }
    val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    for (z in HrZones.COUNT downTo 1) {
        val cy = top + (HrZones.COUNT - z) * rowH + rowH / 2
        c.drawText("Z$z", box.left, cy + 12f, label)
        val maxW = box.width() - labelW - valueW
        bar.color = Palette.surfaceHigh.toArgb()
        c.drawRoundRect(box.left + labelW, cy - 18f, box.left + labelW + maxW, cy + 18f, 18f, 18f, bar)
        val fill = maxW * zoneMs[z] / total
        if (fill > 0) {
            bar.color = ZoneColors.hrZone(z).toArgb()
            c.drawRoundRect(box.left + labelW, cy - 18f, box.left + labelW + fill.coerceAtLeast(36f), cy + 18f, 18f, 18f, bar)
        }
        c.drawText("${(zoneMs[z] / 60_000)} min", box.right, cy + 13f, value)
    }
}
