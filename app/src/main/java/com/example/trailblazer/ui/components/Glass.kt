package com.example.trailblazer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.trailblazer.ui.theme.LocalStatusColors
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The scrolling content that glass chrome blurs. Screens mark it with Modifier.hazeSource(LocalHaze.current). */
val LocalHaze = compositionLocalOf<HazeState?> { null }

private val isDarkSurface @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Frosted chrome for bars. Real backdrop blur on API 31+; below that Haze draws [fallback], a near-opaque
 * tint, so text stays readable. Night mode uses a flat surface: blur would bleed non-red colours.
 */
@Composable
fun GlassChrome(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(0.dp), content: @Composable BoxScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val state = LocalHaze.current
    val night = LocalStatusColors.current.isNight
    val base = modifier.clip(shape)
    val m = if (state == null || night) {
        base.background(surface.copy(alpha = 0.96f))
    } else {
        base.hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(24.dp)
                noiseFactor(0.08f)
                colorEffects(listOf(HazeColorEffect.tint(surface.copy(alpha = 0.70f))))
                fallbackColorEffect(HazeColorEffect.tint(surface.copy(alpha = 0.95f)))
            },
        )
    }
    Box(m.border(0.5.dp, edgeBrush(), shape)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
private fun edgeBrush(): Brush = if (isDarkSurface) {
    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))
} else {
    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

/**
 * Content card in the liquid-glass style: a translucent layered fill with a light top edge. No blur,
 * so long scrolling lists stay cheap to draw.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    padding: Dp = 16.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(corner)
    val fill = Brush.verticalGradient(
        if (isDarkSurface) listOf(cs.surfaceContainerHigh.copy(alpha = 0.82f), cs.surfaceContainer.copy(alpha = 0.66f))
        else listOf(cs.surfaceContainerLowest.copy(alpha = 0.86f), cs.surfaceContainerLow.copy(alpha = 0.72f)),
    )
    // Cards always span the column (a short card must not shrink to its text and sit at the start); callers that
    // want less pass a width limit or a Row weight, which fillMaxWidth then fills.
    var m = modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, edgeBrush(), shape)
    if (onClick != null) m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    Column(m.padding(padding), horizontalAlignment = horizontalAlignment) {
        CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
            content()
        }
    }
}

/**
 * The backdrop wash. [endY] is where the gradient ends in the drawing's own coordinates: the backdrop fills the
 * window, so passing the window height lets another layer (the header) repaint exactly the same colours in place.
 */
@Composable
fun backdropBrush(endY: Float = Float.POSITIVE_INFINITY): Brush {
    val cs = MaterialTheme.colorScheme
    return if (LocalStatusColors.current.isNight) SolidColor(Color.Black)
    else Brush.verticalGradient(
        0f to cs.secondaryContainer.copy(alpha = 0.55f),
        0.45f to cs.background,
        1f to cs.primaryContainer.copy(alpha = 0.35f),
        endY = endY,
    )
}

/** Full-screen backdrop: a soft sky-to-forest wash behind all content. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val brush = backdropBrush()
    Box(modifier.fillMaxSize().background(cs.background).background(brush)) {
        CompositionLocalProvider(LocalContentColor provides cs.onBackground) {
            content()
        }
    }
}
