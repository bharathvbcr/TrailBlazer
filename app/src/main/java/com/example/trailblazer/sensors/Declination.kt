package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.sensors.shareReading

import android.hardware.GeomagneticField
import com.example.trailblazer.location.Fix
import com.trailblazer.core.geo.WorldMagneticModel
import com.trailblazer.core.math.mod360

import kotlin.math.roundToInt

/**
 * Magnetic declination calculation with bundled World Magnetic Model (WMM) tables and
 * cached Android platform [GeomagneticField] fallback.
 *
 * Uses bundled WMM2025/2030 coefficients from :core to guarantee sub-0.1° declination
 * accuracy anywhere on Earth, eliminating drift on older Android OS builds whose firmware
 * WMM tables have expired. Falls back gracefully to system [GeomagneticField] when needed
 * (e.g., date outside WMM2025 epoch or calculation failure).
 *
 * GeomagneticField computation is CPU-heavy (spherical harmonics and Legendre polynomials).
 * Instances are cached by a spatial-temporal grid cell (0.02° lat/lon ~2km, 100m altitude, 1 day)
 * to avoid rebuilding on every sensor sample.
 */
object Declination {
    /** Whether to force fallback to Android system GeomagneticField (for testing or diagnostics). */
    var forceSystemFallback: Boolean = false

    private data class GridKey(
        val latCell: Int,
        val lonCell: Int,
        val altCell: Int,
        val dayEpoch: Long,
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<GridKey, GeomagneticField>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GridKey, GeomagneticField>?): Boolean {
            return size > 32
        }
    }

    fun field(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): GeomagneticField {
        val key = GridKey(
            latCell = (lat * 50.0).roundToInt(),
            lonCell = (lon * 50.0).roundToInt(),
            altCell = (altitudeM / 100.0).roundToInt(),
            dayEpoch = if (timeMs > 0) timeMs / 86_400_000L else 0L,
        )
        synchronized(lock) {
            cache[key]?.let { return it }
            val field = GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                altitudeM.toFloat(),
                timeMs,
            )
            cache[key] = field
            return field
        }
    }

    fun field(fix: Fix): GeomagneticField = field(
        lat = fix.position.lat,
        lon = fix.position.lon,
        altitudeM = fix.altitudeM ?: 0.0,
        timeMs = fix.timeMs,
    )

    /**
     * Fallback calculation using Android's platform [GeomagneticField].
     */
    internal fun systemGeomagneticField(lat: Double, lon: Double, alt: Double, timeMs: Long): Double =
        field(lat, lon, alt, timeMs).declination.toDouble()

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

    internal fun cacheSize(): Int = synchronized(lock) { cache.size }
    internal fun clearCache() = synchronized(lock) { cache.clear() }
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
