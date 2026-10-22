package com.rameez.fit3zone.ui

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.core.GeoPoint
import com.rameez.fit3zone.core.drawablePieces
import com.rameez.fit3zone.core.HrSample
import com.rameez.fit3zone.core.HrZones
import com.rameez.fit3zone.core.ZoneTarget

val CardColor = Palette.surface

/** Gives cards on one screen consecutive entrance slots, so they cascade in; resets per screen. */
class AppearOrder {
    private val created = System.currentTimeMillis()
    private var n = 0
    /** Slot for a newly composed item: sequential during the screen's first second, then immediate. */
    fun next(): Int = if (System.currentTimeMillis() - created < 900) n++ else 0
}

val LocalAppearOrder = staticCompositionLocalOf { AppearOrder() }

/** [appear] using the screen's cascade order. */
fun Modifier.appearInOrder(): Modifier = composed {
    val order = LocalAppearOrder.current
    val slot = remember { order.next() }
    appear(slot)
}

/**
 * The app's surface: rounded graphite panel with a hairline border that catches [accent] light in the top-left
 * corner and a faint accent glow behind it. Clickable panels squish when pressed.
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    accent: Color = Palette.accent,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .appearInOrder()
            .let { if (onClick != null) it.pressScale(source) else it }
            .clip(shape)
            .background(Palette.surface)
            .border(1.dp, Palette.hairline, shape)
            .let { if (onClick != null) it.clickable(interactionSource = source, indication = ripple(color = accent), onClick = onClick) else it }
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Small uppercase label with an accent dot. */
@Composable
fun CardLabel(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(accent, CircleShape))
        Text(text.uppercase(), color = accent, style = MaterialTheme.typography.labelMedium)
    }
}

/** Garmin-style tile: small label, big value (rolls when it changes), optional detail line and content. */
@Composable
fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = ZoneColors.muted,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    GlowCard(modifier, accent, onClick) {
        CardLabel(title, accent)
        Spacer(Modifier.height(2.dp))
        val style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp, lineHeight = 38.sp)
        // Numbers roll; words (names, statuses) wrap normally since rolling text lays out per character.
        if (value.length <= 14 && value.any { it.isDigit() }) RollingText(value, style = style)
        else Text(value, style = style.copy(fontSize = if (value.length > 16) 26.sp else 30.sp, lineHeight = 34.sp))
        detail?.let { Text(it, color = ZoneColors.muted, style = MaterialTheme.typography.bodyMedium) }
        content()
    }
}

/** Vertical gradient bars that grow in one after another; [highlightLast] lights up the current period. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    highlightLast: Boolean = true,
    target: Float? = null,
) {
    val max = maxOf(values.maxOrNull() ?: 0f, target ?: 0f, 1f)
    val grow = rememberGrow(values, durationMs = 1100)
    Column(modifier.padding(top = 6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(barW / 2.4f)
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - barW) / 2
                // Ghost track so empty periods still read as a scale.
                drawRoundRect(Color.White.copy(alpha = 0.035f), Offset(x, 0f), Size(barW, size.height), r)
                val local = ((grow - i.toFloat() / n * 0.4f) / 0.6f).coerceIn(0f, 1f)
                val h = (v / max * size.height * Emphasized.transform(local)).coerceAtLeast(if (v > 0) barW * 0.5f * local else 0f)
                if (h <= 0f) return@forEachIndexed
                val lit = !highlightLast || i == values.lastIndex
                val top = if (lit) color else color.copy(alpha = 0.55f)
                drawRoundRect(
                    Brush.verticalGradient(listOf(top, top.copy(alpha = top.alpha * 0.35f)), size.height - h, size.height),
                    Offset(x, size.height - h), Size(barW, h), r,
                )
            }
            target?.let {
                val y = size.height - it / max * size.height
                drawLine(
                    Color.White.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width * grow, y), 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                )
            }
        }
        if (labels.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            // Labels may be wider than their bar slot (e.g. 28 daily bars): one line, allowed to overflow into empty neighbours.
            labels.forEach { Text(it, Modifier.weight(1f), color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible) }
        }
    }
}

/** Segmented bar (rounded, small gaps) that sweeps in left to right. */
@Composable
private fun SegmentBar(parts: List<Pair<Long, Color>>, height: Dp, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1).toFloat()
    val grow = rememberGrow(parts.map { it.first })
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRoundRect(Palette.hairline, cornerRadius = CornerRadius(size.height / 2))
        val gap = 3.dp.toPx()
        val visible = parts.filter { it.first > 0 }
        var x = 0f
        val limit = size.width * grow
        visible.forEachIndexed { i, (ms, c) ->
            val w = ms / total * size.width
            val drawW = (minOf(x + w, limit) - x - if (i < visible.lastIndex) gap else 0f)
            if (drawW > 0f) drawRoundRect(c, Offset(x, 0f), Size(drawW, size.height), CornerRadius(size.height / 2))
            x += w
        }
    }
}

/** Horizontal stacked bar of below / in / above target time. */
@Composable
fun ZoneTimeBar(belowMs: Long, inMs: Long, aboveMs: Long, modifier: Modifier = Modifier, inLabel: String = "In target") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentBar(listOf(belowMs to ZoneColors.below, inMs to ZoneColors.inZone, aboveMs to ZoneColors.above), 12.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Below ${formatDuration(belowMs)}", color = ZoneColors.below, style = MaterialTheme.typography.labelMedium)
            Text("$inLabel ${formatDuration(inMs)}", color = ZoneColors.inZone, style = MaterialTheme.typography.labelMedium)
            Text("Above ${formatDuration(aboveMs)}", color = ZoneColors.above, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Single stacked bar of time in zones 0..5, zone colours. */
@Composable
fun HrZoneStrip(zoneMs: List<Long>, modifier: Modifier = Modifier) {
    SegmentBar(zoneMs.mapIndexed { z, ms -> ms to ZoneColors.hrZone(z) }, 8.dp, modifier)
}

/**
 * Garmin Connect's "Time in zones": one row per zone, Z5 at the top, with BPM range, a bar
 * proportional to the share of zoned time (growing in, top to bottom), duration and percent.
 */
@Composable
fun HrZoneBars(zoneMs: List<Long>, zones: HrZones?, modifier: Modifier = Modifier) {
    val total = zoneMs.sum().coerceAtLeast(1)
    val grow = rememberGrow(zoneMs, durationMs = 1000)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rows = zoneMs.indices.reversed().filter { !(it == 0 && zoneMs[it] == 0L) }
        rows.forEachIndexed { row, z ->
            val ms = zoneMs[z]
            val local = Emphasized.transform(((grow - row * 0.08f) / 0.6f).coerceIn(0f, 1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(60.dp)) {
                    Text(zoneName(z), fontWeight = FontWeight.Bold, color = ZoneColors.hrZone(z), fontFamily = Numeric, fontSize = 16.sp)
                    val range = zones?.let { if (z == 0) "<${it.floors[0]}" else it.range(z).let { r -> if (z == HrZones.COUNT) "${r.first}+" else "${r.first}–${r.last}" } }
                    range?.let { Text(it, color = ZoneColors.muted, style = MaterialTheme.typography.labelSmall) }
                }
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Palette.hairline, cornerRadius = r)
                    val w = size.width * ms / total * local
                    if (w > 0f) drawRoundRect(
                        Brush.horizontalGradient(listOf(ZoneColors.hrZone(z).copy(alpha = 0.6f), ZoneColors.hrZone(z)), 0f, w),
                        Offset.Zero, Size(w.coerceAtLeast(size.height), size.height), r,
                    )
                }
                Text(formatDuration(ms), Modifier.width(56.dp), fontFamily = Numeric, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text("${(ms * 100 / total)}%", Modifier.width(38.dp), color = ZoneColors.muted, fontSize = 13.sp, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * Colour for a BPM value: by heart-rate zone when zones are known (Garmin style), else by
 * position against the target, else white.
 */
fun bpmColor(bpm: Int?, zones: HrZones?, target: ZoneTarget?): Color = when {
    bpm == null -> Color.White
    zones != null -> ZoneColors.hrZone(zones.zoneOf(bpm))
    target != null -> ZoneColors.of(target.classify(bpm))
    else -> Color.White
}

/** BPM of the HR sample nearest in time to [offsetMs] (samples sorted by offset), within 15 s. */
fun bpmAt(offsetMs: Long, samples: List<HrSample>): Int? {
    if (samples.isEmpty()) return null
    var lo = 0
    var hi = samples.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].offsetMs < offsetMs) lo = mid + 1 else hi = mid
    }
    val best = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1)).minBy { kotlin.math.abs(it.offsetMs - offsetMs) }
    return if (kotlin.math.abs(best.offsetMs - offsetMs) <= 15_000) best.bpm else null
}

/** Route shape without map tiles (used when no Maps API key is configured). */
@Composable
fun RouteSketch(
    points: List<GeoPoint>, samples: List<HrSample>, colorOf: (Int?) -> Color, modifier: Modifier = Modifier, animate: Boolean = true,
    breaksMs: List<Long> = emptyList(),
) {
    if (points.size < 2) return
    // Where a new drawable piece starts, the step from the previous point is a GPS gap (drawn faint and dashed).
    val gapStarts = remember(points, breaksMs) {
        points.drawablePieces(breaksMs).runningFold(0) { i, piece -> i + piece.size }.drop(1).dropLast(1).toSet()
    }
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val lonScale = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
    // A live route grows by a point a second: draw it as is instead of replaying the draw-in each time.
    val grow = if (animate) rememberGrow(points.size, durationMs = 1600) else 1f
    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-9)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
        val scale = minOf(size.width / spanX, size.height / spanY) * 0.9
        val ox = (size.width - spanX * scale) / 2
        val oy = (size.height - spanY * scale) / 2
        fun pos(p: GeoPoint) = Offset(
            (ox + (p.lon - minLon) * lonScale * scale).toFloat(),
            (size.height - oy - (p.lat - minLat) * scale).toFloat(),
        )
        val shown = 1 + ((points.size - 1) * grow).toInt()
        for (i in 1 until shown) {
            if (i in gapStarts) {
                drawLine(Palette.faint, pos(points[i - 1]), pos(points[i]), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                continue
            }
            val c = colorOf(bpmAt(points[i].offsetMs, samples))
            drawLine(c.copy(alpha = 0.25f), pos(points[i - 1]), pos(points[i]), 18f, StrokeCap.Round)
            drawLine(c, pos(points[i - 1]), pos(points[i]), 6f, StrokeCap.Round)
        }
        drawCircle(Color.White, 13f, pos(points.first()))
        drawCircle(ZoneColors.inZone, 9f, pos(points.first()))
        if (grow >= 1f) {
            drawCircle(Color.White, 13f, pos(points.last()))
            drawCircle(ZoneColors.above, 9f, pos(points.last()))
        }
    }
}

fun hasMapsKey(context: Context): Boolean = runCatching {
    context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData?.getString("com.google.android.geo.API_KEY")
}.getOrNull().isNullOrBlank().not()

fun formatKm(m: Double): String = "%.2f km".format(m / 1000)

/** Big pill button, solid accent by default (or a gradient of [colors]); [shimmer] sweeps a soft highlight across it now and then. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Palette.accent, Palette.accent),
    contentColor: Color = if (colors.first().luminance() > 0.3f) Palette.onAccent else Color.White,
    enabled: Boolean = true,
    shimmer: Boolean = false,
    height: Dp = 64.dp,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val sweep = if (shimmer) {
        val t = rememberInfiniteTransition(label = "shimmer")
        t.animateFloat(-0.4f, 1.6f, infiniteRepeatable(tween(2600, delayMillis = 900, easing = LinearEasing)), label = "sweep").value
    } else -2f
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pressScale(source, 0.96f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(Brush.horizontalGradient(colors))
            .drawWithContent {
                drawContent()
                if (sweep > -1f) {
                    val x = size.width * sweep
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                            Offset(x - size.height * 1.5f, 0f), Offset(x + size.height * 1.5f, size.height),
                        ),
                    )
                }
            }
            .clickable(interactionSource = source, indication = ripple(color = Color.White), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = contentColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary pill: graphite with a hairline border. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 64.dp) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(height).pressScale(source, 0.95f).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape).background(Palette.surfaceHigh).border(1.dp, Palette.hairline, shape)
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** Selectable pill: fills with [color] (springy) when selected. [compact] for dense rows (editor, day picker). */
@Composable
fun PillChip(label: String, selected: Boolean, color: Color, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) color else Palette.surfaceHigh, tween(260), label = "chipBg")
    val border by animateColorAsState(if (selected) color else Palette.hairline, tween(260), label = "chipBorder")
    val fg = when {
        !enabled -> Palette.faint
        selected -> if (color.luminance() > 0.5f) Color(0xFF0A0D10) else Color.White
        else -> Palette.text
    }
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.pressScale(source, 0.92f).clip(shape).background(bg).border(1.dp, border, shape)
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 10.dp),
    ) {
        Text(
            label, color = fg, fontWeight = FontWeight.Bold, maxLines = 1,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

/** Small text action inside a card ("Edit", "Delete", "‹ Back"): coloured label, pill-shaped press area. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.accent, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(source, 0.94f).clip(RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = ripple(color = color), enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(text, color = if (enabled) color else Palette.faint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Line chart over session time with a soft fill (pace, cadence, elevation). The y range spans the
 * 5th–95th percentile so GPS outliers don't flatten the line; [invert] puts low values on top
 * (pace: faster is higher). [avg] draws a dashed reference line. [format] labels the y range.
 */
@Composable
fun LineChart(
    points: List<Pair<Long, Float>>,
    color: Color,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    invert: Boolean = false,
    avg: Float? = null,
    minSpan: Float = 1f,
    robust: Boolean = true,
) {
    if (points.size < 2) return
    val sorted = remember(points) { points.map { it.second }.sorted() }
    val lo0 = if (robust) sorted[(sorted.size * 0.05).toInt()] else sorted.first()
    val hi0 = if (robust) sorted[((sorted.size - 1) * 0.95).toInt()] else sorted.last()
    val pad = maxOf((hi0 - lo0) * 0.12f, minSpan / 2)
    val lo = lo0 - pad
    val hi = hi0 + pad
    val maxX = points.last().first.coerceAtLeast(1).toFloat()
    val grow = rememberGrow(points.size, durationMs = 900)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            fun x(ms: Long) = ms / maxX * size.width
            fun y(v: Float): Float {
                val f = ((v.coerceIn(lo, hi) - lo) / (hi - lo))
                return if (invert) f * size.height else size.height - f * size.height
            }
            val step = (points.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
            val shown = points.filterIndexed { i, _ -> i % step == 0 }
            val line = Path()
            shown.forEachIndexed { i, (t, v) -> if (i == 0) line.moveTo(x(t), y(v)) else line.lineTo(x(t), y(v)) }
            val area = Path().apply {
                addPath(line)
                lineTo(x(shown.last().first), size.height)
                lineTo(x(shown.first().first), size.height)
                close()
            }
            clipRect(right = size.width * grow) {
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(line, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            avg?.let {
                drawLine(
                    Color.White.copy(alpha = 0.5f), Offset(0f, y(it)), Offset(size.width, y(it)), 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", color = ZoneColors.muted, fontSize = 12.sp)
            Text(
                "${format(lo)} – ${format(hi)}",
                color = ZoneColors.muted, fontSize = 12.sp,
            )
            Text(formatDuration(points.last().first), color = ZoneColors.muted, fontSize = 12.sp)
        }
    }
}
