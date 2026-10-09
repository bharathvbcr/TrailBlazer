package com.example.trailblazer.sensors

import android.hardware.GeomagneticField
import com.example.trailblazer.location.Fix
import com.trailblazer.core.geo.WorldMagneticModel
import com.trailblazer.core.math.mod360

/**
 * Magnetic declination calculation with bundled World Magnetic Model (WMM) tables.
 *
 * Uses bundled WMM2025/2030 coefficients from :core to guarantee sub-0.1° declination
 * accuracy anywhere on Earth, eliminating drift on older Android OS builds whose firmware
 * WMM tables have expired. Falls back gracefully to system [GeomagneticField] when needed
 * (e.g., date outside WMM2025 epoch or calculation failure).
 */
object Declination {
    /** Whether to force fallback to Android system GeomagneticField (for testing or diagnostics). */
    var forceSystemFallback: Boolean = false

    /**
     * Returns the magnetic declination in degrees for [fix].
     * Positive is east of true north, negative is west.
     */
    fun degrees(fix: Fix): Double {
        val lat = fix.position.lat
        val lon = fix.position.lon
        val alt = fix.altitudeM ?: 0.0
        val timeMs = fix.timeMs

        if (forceSystemFallback) {
            return systemGeomagneticField(lat, lon, alt, timeMs)
        }

        return try {
            if (WorldMagneticModel.isDateValid(timeMs)) {
                WorldMagneticModel.declination(lat, lon, alt, timeMs)
            } else {
                systemGeomagneticField(lat, lon, alt, timeMs)
            }
        } catch (_: Throwable) {
            systemGeomagneticField(lat, lon, alt, timeMs)
        }
    }

    /**
     * Fallback calculation using Android's platform [GeomagneticField].
     */
    internal fun systemGeomagneticField(lat: Double, lon: Double, alt: Double, timeMs: Long): Double =
        GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), timeMs).declination.toDouble()
}

/** A heading ready for display: magnetic always, true only when a position gives us the declination. */
data class Heading(
    val magneticDeg: Double,
    val declinationDeg: Double?,
    val source: HeadingSource,
    val accuracyDeg: Double?,
    val upright: Boolean,
    val pitchDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val pitchOffsetDeg: Double = 0.0,
    val rollOffsetDeg: Double = 0.0,
) {
    val trueDeg: Double? get() = declinationDeg?.let { mod360(magneticDeg + it) }

    /** Pitch calibrated for camera bump (e.g. Pixel 10 Pro XL visor) or case offset. */
    val calibratedPitchDeg: Double get() = pitchDeg - pitchOffsetDeg

    /** Roll calibrated for camera bump or case offset. */
    val calibratedRollDeg: Double get() = rollDeg - rollOffsetDeg

    /** Whether zero level calibration is currently active. */
    val isCalibrated: Boolean get() = pitchOffsetDeg != 0.0 || rollOffsetDeg != 0.0

    /** Whether the phone is held flat and level enough for maximum compass accuracy (<= 2° tilt after calibration). */
    val isLevel: Boolean
        get() = !upright && calibratedPitchDeg.isFinite() && calibratedRollDeg.isFinite() &&
            kotlin.math.abs(calibratedPitchDeg) <= LEVEL_THRESHOLD_DEG && kotlin.math.abs(calibratedRollDeg) <= LEVEL_THRESHOLD_DEG

    /** Calibrated tilt angle away from flat in degrees. */
    val tiltDeg: Double get() = kotlin.math.hypot(calibratedPitchDeg, calibratedRollDeg)

    /** Raw uncalibrated tilt angle away from flat in degrees. */
    val rawTiltDeg: Double get() = kotlin.math.hypot(pitchDeg, rollDeg)

    companion object {
        const val LEVEL_THRESHOLD_DEG = 2.0
    }
}
