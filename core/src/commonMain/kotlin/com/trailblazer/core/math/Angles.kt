package com.trailblazer.core.math

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

const val DEG = PI / 180.0
const val RAD = 180.0 / PI

/** Normalizes any finite angle into [0, 360). */
fun mod360(deg: Double): Double {
    val r = deg - 360.0 * floor(deg / 360.0)
    return if (r >= 360.0) 0.0 else r
}

/**
 * Signed shortest rotation from [from] to [to], in (-180, 180].
 * Positive means turn clockwise (right).
 */
fun angleDiff(from: Double, to: Double): Double {
    val d = mod360(to - from)
    return if (d > 180.0) d - 360.0 else d
}

/**
 * True when the short-way rotation from [previous] to [current] passes over north:
 * clockwise arriving at or past 360°, or anticlockwise leaving 0° downwards.
 * Callers debounce repeated crossings (e.g. jitter around 0°).
 */
fun crossesNorth(previous: Double, current: Double): Boolean {
    val p = mod360(previous)
    val step = angleDiff(p, current)
    val end = p + step
    return (step > 0.0 && end >= 360.0) || (step < 0.0 && end < 0.0)
}

/** 16-point compass label for a bearing. */
fun cardinal16(deg: Double): String {
    val names = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    return names[((mod360(deg) / 22.5) + 0.5).toInt() % 16]
}

/**
 * Exponential low-pass filter for angles that works on the unit circle, so
 * averaging 359° and 1° yields 0°, not 180°.
 */
class CircularLowPass(private val alpha: Double) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
    }

    private var x = 0.0
    private var y = 0.0
    private var primed = false

    fun reset() {
        primed = false
    }

    /**
     * Adds a sample and returns the smoothed angle. A non-finite sample (a glitching sensor) is skipped without
     * touching the state and yields null, so one bad value can never poison every later reading.
     */
    fun update(deg: Double): Double? {
        if (!deg.isFinite()) return null
        val c = cos(deg * DEG)
        val s = sin(deg * DEG)
        if (!primed) {
            x = c; y = s; primed = true
        } else {
            x += alpha * (c - x)
            y += alpha * (s - y)
        }
        if (hypot(x, y) < 1e-9) {
            x = c; y = s
        }
        return mod360(atan2(y, x) * RAD)
    }
}
