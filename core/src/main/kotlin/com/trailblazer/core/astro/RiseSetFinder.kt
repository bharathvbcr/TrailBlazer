package com.trailblazer.core.astro

import kotlin.math.abs
import kotlin.math.sqrt

/** A time at which a function crosses zero; [rising] is true for a negative-to-positive crossing. */
data class Crossing(val epochMs: Long, val rising: Boolean)

/**
 * Finds zero crossings of a smooth function of time using Montenbruck–Pfleger quadratic
 * interpolation over three samples (which also catches two crossings between samples, e.g. a
 * grazing Moon or a high-latitude sunrise and sunset close together), then refines each root by
 * bisection on the real function to [toleranceMs].
 */
object RiseSetFinder {
    private const val MAX_BISECTIONS = 60

    fun crossings(
        startMs: Long,
        endMs: Long,
        stepMs: Long = 3_600_000L,
        toleranceMs: Long = 1_000L,
        f: (Long) -> Double,
    ): List<Crossing> {
        require(endMs > startMs) { "empty window" }
        require(stepMs > 0) { "step must be positive" }
        val out = ArrayList<Crossing>(4)
        var center = startMs + stepMs
        var yMinus = f(startMs)
        while (center - stepMs < endMs) {
            val y0 = f(center)
            val yPlus = f(center + stepMs)
            val a = 0.5 * (yPlus + yMinus) - y0
            val b = 0.5 * (yPlus - yMinus)
            val c = y0
            val roots = ArrayList<Double>(2)
            if (abs(a) < 1e-12) {
                if (abs(b) > 1e-12) {
                    val z = -c / b
                    if (z >= -1.0 && z < 1.0) roots += z
                }
            } else {
                val xe = -b / (2 * a)
                val dis = b * b - 4 * a * c
                if (dis >= 0) {
                    val dx = 0.5 * sqrt(dis) / abs(a)
                    val z1 = xe - dx
                    val z2 = xe + dx
                    if (z1 >= -1.0 && z1 < 1.0) roots += z1
                    if (z2 >= -1.0 && z2 < 1.0 && dx > 0) roots += z2
                }
            }
            for ((i, z) in roots.withIndex()) {
                val slope = 2 * a * z + b
                if (slope == 0.0) continue
                val rising = slope > 0
                // Bracket each root: a single root spans the whole window; two roots split at the extremum.
                val lo: Long
                val hi: Long
                if (roots.size == 1) {
                    lo = center - stepMs; hi = center + stepMs
                } else {
                    val mid = center + ((-b / (2 * a)) * stepMs).toLong()
                    if (i == 0) { lo = center - stepMs; hi = mid } else { lo = mid; hi = center + stepMs }
                }
                val approx = center + (z * stepMs).toLong()
                val t = refine(lo, hi, rising, toleranceMs, f) ?: approx
                if (t in startMs until endMs && out.none { abs(it.epochMs - t) < toleranceMs * 2 && it.rising == rising }) {
                    out += Crossing(t, rising)
                }
            }
            yMinus = yPlus
            center += 2 * stepMs
        }
        out.sortBy { it.epochMs }
        return out
    }

    private fun refine(lo0: Long, hi0: Long, rising: Boolean, tol: Long, f: (Long) -> Double): Long? {
        var lo = lo0
        var hi = hi0
        var fLo = f(lo)
        val fHi = f(hi)
        val ok = if (rising) fLo <= 0 && fHi >= 0 else fLo >= 0 && fHi <= 0
        if (!ok) return null
        var n = 0
        while (hi - lo > tol && n++ < MAX_BISECTIONS) {
            val mid = lo + (hi - lo) / 2
            val fm = f(mid)
            val sameSideAsLo = if (fLo <= 0) fm <= 0 else fm >= 0
            if (sameSideAsLo) { lo = mid; fLo = fm } else hi = mid
        }
        return lo + (hi - lo) / 2
    }

    /** Total time within [startMs, endMs) for which f > 0, given the crossings found in that window. */
    fun timeAbove(startMs: Long, endMs: Long, crossings: List<Crossing>, initiallyAbove: Boolean): Long {
        var above = initiallyAbove
        var last = startMs
        var total = 0L
        for (c in crossings) {
            if (above) total += c.epochMs - last
            above = c.rising
            last = c.epochMs
        }
        if (above) total += endMs - last
        return total
    }
}
