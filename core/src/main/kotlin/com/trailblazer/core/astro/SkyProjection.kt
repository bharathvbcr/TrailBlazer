package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import kotlin.math.cos
import kotlin.math.sin

/**
 * Azimuthal-equidistant sky chart, drawn the way a planisphere is used: held overhead, the direction you face at the
 * bottom edge. The zenith is the centre and the horizon the rim. Because you look up at it, the chart is the mirror
 * of a map: with north at the top, east is on the left.
 *
 * Coordinates are unit-disc screen coordinates: x to the right, y down, the rim at radius 1.
 */
object SkyProjection {
    data class Point(val x: Double, val y: Double)

    /** Projects an object at [azimuthDeg]/[altitudeDeg], or null when it is below the horizon. */
    fun project(azimuthDeg: Double, altitudeDeg: Double, facingDeg: Double): Point? {
        if (!azimuthDeg.isFinite() || !altitudeDeg.isFinite() || !facingDeg.isFinite()) return null
        if (altitudeDeg < 0.0) return null
        val rho = (90.0 - altitudeDeg.coerceAtMost(90.0)) / 90.0
        val rel = (azimuthDeg - facingDeg) * DEG
        return Point(sin(rel) * rho, cos(rel) * rho)
    }

    /** The default facing: towards the equator, where the planets and the Moon travel. */
    fun defaultFacing(latDeg: Double): Double = if (latDeg >= 0) 180.0 else 0.0
}
