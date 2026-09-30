package com.example.trailblazer.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/*
 * Every plot sits inside a scrolling page, so a plot may only claim the gestures the page does not need.
 * `detectDragGestures` claims a drag in any direction once it passes touch slop, which swallowed vertical swipes that
 * started on a plot; these two modifiers are the only way plots here take touch input.
 */

/**
 * Tap, or drag sideways, to scrub along a plot's width. [onFraction] gets the horizontal position as a fraction of
 * the width, clamped to [0, 1]. Vertical drags are left to the page, so it scrolls from anywhere on the plot.
 */
fun Modifier.scrubX(vararg keys: Any?, onFraction: (Double) -> Unit): Modifier = this
    .pointerInput(*keys) {
        detectTapGestures { p -> fraction(p.x, size.width)?.let(onFraction) }
    }
    .pointerInput(*keys) {
        detectHorizontalDragGestures { change, _ ->
            change.consume()
            fraction(change.position.x, size.width)?.let(onFraction)
        }
    }

/**
 * Tap to pick a point; press and hold, then drag, to move the pick around in two dimensions. A plain drag in any
 * direction stays with the page. [onPoint] gets the position in the plot's own pixels.
 */
fun Modifier.pickPoint(vararg keys: Any?, onPoint: (Offset) -> Unit): Modifier = this
    .pointerInput(*keys) {
        detectTapGestures { onPoint(it) }
    }
    .pointerInput(*keys) {
        detectDragGesturesAfterLongPress(onDragStart = onPoint) { change, _ ->
            change.consume()
            onPoint(change.position)
        }
    }

/**
 * Orbit a 3D view: a sideways drag turns it at once ([onDrag] gets dy = 0); press and hold, then drag, to tilt it too.
 * A plain vertical drag stays with the page.
 */
fun Modifier.orbit(vararg keys: Any?, onDrag: (dx: Float, dy: Float) -> Unit): Modifier = this
    .pointerInput(*keys) {
        detectHorizontalDragGestures { change, dx ->
            change.consume()
            onDrag(dx, 0f)
        }
    }
    .pointerInput(*keys) {
        detectDragGesturesAfterLongPress { change, d ->
            change.consume()
            onDrag(d.x, d.y)
        }
    }

/** A horizontal position as a fraction of [width] in [0, 1], or null while the plot has no width yet. */
internal fun fraction(x: Float, width: Int): Double? =
    if (width <= 0 || !x.isFinite()) null else (x / width.toDouble()).coerceIn(0.0, 1.0)
