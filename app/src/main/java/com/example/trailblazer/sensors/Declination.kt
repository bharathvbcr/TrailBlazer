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
) {
    val trueDeg: Double? get() = declinationDeg?.let { mod360(magneticDeg + it) }
}
