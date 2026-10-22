package com.rameez.fit3zone.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rameez.fit3zone.R

/**
 * Design tokens, "Kesar Dusk" (chosen 2026-10-21): warm charcoal surfaces, one saffron accent, marigold highlights,
 * zone colours for meaning. Flat cards with hairlines, no glow.
 */
object Palette {
    val bg = Color(0xFF14110F)
    val surface = Color(0xFF1E1A17)
    val surfaceHigh = Color(0xFF28221E)
    val hairline = Color(0xFF2E2824)
    val text = Color(0xFFF5EEE6)
    val muted = Color(0xFFA89C90)
    val faint = Color(0xFF5A5048)
    val accent = Color(0xFFFF7A1A)
    val onAccent = Color(0xFF1A0C02)
    val accent2 = Color(0xFFFFC14D)
    val steps = Color(0xFFFF7A1A)
    val sleep = Color(0xFF9D7CFF)
    val heart = Color(0xFFFF4D6A)
    val oxygen = Color(0xFFFF7A90)
    val load = Color(0xFFFFB020)
}

private fun inter(weight: Int) = Font(
    R.font.inter_variable, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk_variable, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Body text. */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800), inter(900))

/** Big numbers: geometric, sporty, tabular-looking. */
val Numeric = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))

private val base = TextStyle(fontFamily = Inter, color = Palette.text)

private val AppTypography = Typography(
    displayLarge = base.copy(fontFamily = Numeric, fontWeight = FontWeight.Bold, fontSize = 96.sp, letterSpacing = (-3).sp),
    displayMedium = base.copy(fontFamily = Numeric, fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = (-1.5).sp),
    displaySmall = base.copy(fontFamily = Numeric, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-1).sp),
    headlineLarge = base.copy(fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-1).sp),
    headlineMedium = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.8).sp),
    // Screen titles ("My Day", "Training", …) use this.
    headlineSmall = base.copy(fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp),
    titleLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, color = Palette.muted),
    labelLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    labelMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppColorScheme = darkColorScheme(
    background = Palette.bg,
    surface = Palette.surface,
    surfaceVariant = Palette.surfaceHigh,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Palette.surfaceHigh,
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    primaryContainer = Color(0xFF3A1E0A),
    onPrimaryContainer = Palette.accent,
    secondary = Palette.accent2,
    secondaryContainer = Color(0xFF2E2824),
    onSecondaryContainer = Palette.text,
    onBackground = Palette.text,
    onSurface = Palette.text,
    onSurfaceVariant = Palette.muted,
    outline = Color(0xFF3A322C),
    outlineVariant = Palette.hairline,
    error = Color(0xFFFF4D5E),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, typography = AppTypography, shapes = AppShapes, content = content)
}
