package com.rameez.fit3zone.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Motion language: springs for anything touched, short eased tweens for data, staggered entrances
 * for content. Durations stay under ~600 ms so the app never feels slow.
 */

val Emphasized = FastOutSlowInEasing

/** Squishes slightly while pressed and springs back. Pass the same source to the clickable. */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.965f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Fades and rises into place once, [index] × 55 ms after first composition (capped so long lists stay snappy). */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 55L)
        p.animateTo(1f, tween(520, easing = Emphasized))
    }
    graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 24.dp.toPx()
        val sc = 0.97f + 0.03f * p.value
        scaleX = sc; scaleY = sc
    }
}

/** 0 → 1 once when first shown, and again whenever [key] changes: for bars and lines that grow in. */
@Composable
fun rememberGrow(key: Any? = Unit, durationMs: Int = 900, delayMs: Long = 120): Float {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs)
        p.animateTo(1f, tween(durationMs, easing = Emphasized))
    }
    return p.value
}

/**
 * Text whose characters roll vertically when they change (like a split-flap counter): digits going up
 * roll up, going down roll down. Works for any string ("6:55", "3.64 km", "149").
 */
@Composable
fun RollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        text.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val up = targetState.isDigit() && initialState.isDigit() && targetState > initialState ||
                        !initialState.isDigit()
                    val dir = if (up) 1 else -1
                    (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it * dir } + fadeIn(tween(180)))
                        .togetherWith(slideOutVertically(tween(220)) { -it * dir } + fadeOut(tween(160)))
                },
                label = "roll$i",
            ) { c -> Text(c.toString(), style = style, color = color, softWrap = false, maxLines = 1) }
        }
    }
}
