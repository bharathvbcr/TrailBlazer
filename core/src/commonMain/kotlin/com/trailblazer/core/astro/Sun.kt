package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.RAD
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Solar position (Meeus ch. 25 low-precision series, as used by the NOAA calculator; ~0.01°). */
object SolarPosition {
    const val AU_KM = 149_597_870.7

    /** Apparent geocentric ecliptic longitude (degrees) and distance (AU) for an ephemeris Julian Day. */
    data class Ecliptic(val longitudeDeg: Double, val distanceAu: Double)

    fun ecliptic(jde: Double): Ecliptic {
        val t = JulianDay.centuries(jde)
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * DEG
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val trueLong = l0 + c
        val v = m + c * DEG
        val r = 1.000001018 * (1 - e * e) / (1 + e * cos(v))
        val omega = (125.04 - 1934.136 * t) * DEG
        val lambda = trueLong - 0.00569 - 0.00478 * sin(omega)
        return Ecliptic(mod360(lambda), r)
    }

    fun equatorial(epochMs: Long): Equatorial {
        val jde = JulianDay.ephemeris(epochMs)
        val ecl = ecliptic(jde)
        val t = JulianDay.centuries(jde)
        val omega = (125.04 - 1934.136 * t) * DEG
        val eps0 = 23.0 + 26.0 / 60 + (21.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0
        val eps = (eps0 + 0.00256 * cos(omega)) * DEG
        val lambda = ecl.longitudeDeg * DEG
        val ra = mod360(atan2(cos(eps) * sin(lambda), cos(lambda)) * RAD)
        val dec = asin(sin(eps) * sin(lambda)) * RAD
        return Equatorial(ra, dec, ecl.distanceAu * AU_KM)
    }

    fun horizontal(epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal =
        HorizontalTransform.toHorizontal(epochMs, latDeg, lonDeg, equatorial(epochMs))
}
