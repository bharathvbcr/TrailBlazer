package com.trailblazer.core.motion

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.RAD
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val STANDARD_GRAVITY = 9.80665

data class Vec3(val x: Double, val y: Double, val z: Double) {
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
}

/**
 * Canonical gravity low-pass filter (alpha = 0.1).
 * Isolates gravity by smoothing 3D accelerometer readings.
 */
class GravityLowPass(val alpha: Double = 0.1) {
    var gravity: Vec3? = null
        private set

    fun update(x: Double, y: Double, z: Double): Vec3 {
        val curr = Vec3(x, y, z)
        val prev = gravity ?: return curr.also { gravity = it }
        val next = Vec3(
            prev.x + alpha * (x - prev.x),
            prev.y + alpha * (y - prev.y),
            prev.z + alpha * (z - prev.z),
        )
        gravity = next
        return next
    }

    fun update(v: Vec3): Vec3 = update(v.x, v.y, v.z)

    fun reset() {
        gravity = null
    }
}

enum class GSeverity { Normal, Elevated, High }

object GForce {
    fun of(magnitudeMs2: Double): Double? = magnitudeMs2.takeIf { it.isFinite() && it >= 0 }?.div(STANDARD_GRAVITY)

    fun severity(g: Double): GSeverity = when {
        g > 2.0 -> GSeverity.High
        g > 1.25 -> GSeverity.Elevated
        else -> GSeverity.Normal
    }
}

/** Tilt from the gravity vector in Android device coordinates (x right, y up the screen, z out of the screen). */
data class Inclination(val pitchDeg: Double, val rollDeg: Double) {
    /** Road-style gradient of the pitch axis, in percent (rise over run). */
    val gradePercent: Double get() = tan(pitchDeg * DEG) * 100

    operator fun minus(zero: Inclination) = Inclination(pitchDeg - zero.pitchDeg, rollDeg - zero.rollDeg)

    companion object {
        /**
         * [flat]: phone lying on its back (bubble level). Pitch is rotation about x (nose up positive),
         * roll about y (right side down positive).
         * Otherwise the phone stands upright in portrait (dashboard mount): pitch is the forward/back tilt.
         * Returns null when the vector is too short to be gravity (free fall or no data).
         */
        fun fromGravity(g: Vec3, flat: Boolean): Inclination? {
            val m = g.magnitude
            if (!m.isFinite() || m < 0.3 * STANDARD_GRAVITY) return null
            return if (flat) {
                Inclination(
                    pitchDeg = atan2(g.y, sqrt(g.x * g.x + g.z * g.z)) * RAD,
                    rollDeg = atan2(-g.x, sqrt(g.y * g.y + g.z * g.z)) * RAD,
                )
            } else {
                Inclination(
                    pitchDeg = atan2(-g.z, sqrt(g.x * g.x + g.y * g.y)) * RAD,
                    rollDeg = atan2(-g.x, g.y) * RAD,
                )
            }
        }
    }
}

/** Orbit camera for the 3D accelerometer plot: yaw about the vertical axis, then pitch. */
data class OrbitCamera(val yawDeg: Double, val pitchDeg: Double, val pixelsPerUnit: Double, val perspective: Double = 600.0) {
    data class Projected(val x: Double, val y: Double, val depth: Double)

    /** Projects [p] relative to a screen centre (cx, cy). Pitch is clamped to avoid the gimbal singularity. */
    fun project(p: Vec3, cx: Double, cy: Double): Projected {
        val yaw = yawDeg * DEG
        val pitch = pitchDeg.coerceIn(-89.9, 89.9) * DEG
        val x1 = p.x * cos(yaw) - p.y * sin(yaw)
        val y1 = p.x * sin(yaw) + p.y * cos(yaw)
        val y2 = y1 * cos(pitch) - p.z * sin(pitch)
        val z2 = y1 * sin(pitch) + p.z * cos(pitch)
        val s = pixelsPerUnit
        val persp = perspective / maxOf(30.0, perspective + y2 * s * 0.25)
        return Projected(cx + x1 * s * persp, cy - z2 * s * persp, y2)
    }

    companion object {
        val ISO = OrbitCamera(-40.0, 26.0, 1.0)
        val TOP = OrbitCamera(0.0, 89.0, 1.0)
        val FRONT = OrbitCamera(0.0, 0.0, 1.0)
        val SIDE = OrbitCamera(90.0, 0.0, 1.0)
    }
}
