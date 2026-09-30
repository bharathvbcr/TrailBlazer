package com.example.trailblazer.sensors

import android.hardware.GeomagneticField
import com.example.trailblazer.location.Fix
import com.trailblazer.core.math.mod360

/**
 * Magnetic declination from Android's GeomagneticField, which embeds the World Magnetic Model shipped
 * with the OS image — so its accuracy depends on how current the device firmware is.
 */
object Declination {
    fun degrees(fix: Fix): Double =
        GeomagneticField(fix.position.lat.toFloat(), fix.position.lon.toFloat(), (fix.altitudeM ?: 0.0).toFloat(), fix.timeMs)
            .declination.toDouble()
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
