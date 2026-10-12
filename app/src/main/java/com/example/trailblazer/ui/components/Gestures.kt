package com.example.trailblazer.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt

/*
 * Every plot sits inside a scrolling page, so a plot may only claim the gestures the page does not need.
 * `detectDragGestures` claims a drag in any direction once it passes touch slop, which swallowed vertical swipes that
 * started on a plot; these two modifiers are the only way plots here take touch input.
 */

/**
 * How a screen reader adjusts a scrubbable plot. A plot with only pointer input is a dead end for TalkBack, so
 * [scrubX] turns this into an adjustable range (the same semantics as a slider): [fraction] is where the readout is
 * now, in [0, 1]; [steps] is the number of positions strictly between the ends (0 means continuous); [stateText] is
 * what the screen reader says for the current position.
 */
class ScrubAdjust(
    val fraction: Float,
    val stateText: String? = null,
    val steps: Int = 0,
    /** Where an adjustment goes when it should not take the pointer path (a plot with gaps steps over its samples). */
    val onAdjust: ((Double) -> Unit)? = null,
)

/**
 * Tap, or drag sideways, to scrub along a plot's width. [onFraction] gets the horizontal position as a fraction of
 * the width, clamped to [0, 1]. Vertical drags are left to the page, so it scrolls from anywhere on the plot.
 * With [adjust] set, a screen reader can move the same readout through [onFraction] by adjusting the plot.
 */
fun Modifier.scrubX(vararg keys: Any?, adjust: ScrubAdjust? = null, onFraction: (Double) -> Unit): Modifier = this
    .adjustable(adjust, onFraction)
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
 * Makes a plot adjustable for screen readers (volume keys or swipe up and down in TalkBack) without giving it any
 * pointer input: for a plot that shows a position set elsewhere. [scrubX] applies this itself.
 */
fun Modifier.adjustable(adjust: ScrubAdjust?, onFraction: (Double) -> Unit): Modifier =
    if (adjust == null) this else semantics {
        adjust.stateText?.let { stateDescription = it }
        progressBarRangeInfo = ProgressBarRangeInfo(adjust.fraction.coerceIn(0f, 1f), 0f..1f, adjust.steps.coerceAtLeast(0))
        val apply = adjust.onAdjust ?: onFraction
        setProgress { target -> snapToStep(target, adjust.steps)?.let { apply(it); true } ?: false }
    }

/**
 * [value] clamped to [0, 1] and, when [steps] > 0, snapped to the nearest of the evenly spaced positions; null when
 * it is not a number.
 * A position is returned a hair past its exact fraction: plots turn a fraction into a slot by truncating
 * (`SeriesPlot.scrubIndex`), and 1/3 x 3 can come out as 0.9999999, which would land one slot short.
 */
internal fun snapToStep(value: Float, steps: Int): Double? {
    if (!value.isFinite()) return null
    if (steps <= 0) return value.toDouble().coerceIn(0.0, 1.0)
    val positions = steps + 1
    val slot =(value.toDouble().coerceIn(0.0, 1.0) * positions).roundToInt()
    return ((slot + SNAP_NUDGE) / positions).coerceAtMost(1.0)
}

private const val SNAP_NUDGE = 1e-6

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
