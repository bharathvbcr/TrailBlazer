package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.RAD
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Geocentric apparent equatorial coordinates of date. */
data class Equatorial(val raDeg: Double, val decDeg: Double, val distanceKm: Double)

/**
 * Local horizontal coordinates. Azimuth is measured from true north, clockwise, in [0, 360).
 * [altitudeDeg] is the true (airless) altitude; [apparentAltitudeDeg] adds atmospheric refraction.
 */
data class Horizontal(val azimuthDeg: Double, val altitudeDeg: Double) {
    val apparentAltitudeDeg: Double get() = altitudeDeg + Refraction.degrees(altitudeDeg)
}

/** Low-precision nutation and obliquity (Meeus ch. 22), accurate to ~0.5″. */
object Nutation {
    data class Terms(val deltaPsiDeg: Double, val deltaEpsDeg: Double, val trueObliquityDeg: Double)

    fun at(jde: Double): Terms {
        val t = JulianDay.centuries(jde)
        val omega = (125.04452 - 1934.136261 * t) * DEG
        val l = (280.4665 + 36000.7698 * t) * DEG
        val lp = (218.3165 + 481267.8813 * t) * DEG
        val dPsi = (-17.20 * sin(omega) - 1.32 * sin(2 * l) - 0.23 * sin(2 * lp) + 0.21 * sin(2 * omega)) / 3600.0
        val dEps = (9.20 * cos(omega) + 0.57 * cos(2 * l) + 0.10 * cos(2 * lp) - 0.09 * cos(2 * omega)) / 3600.0
        val eps0 = 23.0 + 26.0 / 60 + (21.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0
        return Terms(dPsi, dEps, eps0 + dEps)
    }
}

object Sidereal {
    /** Greenwich mean sidereal time in degrees for a UT Julian Day (Meeus 12.4). */
    fun greenwichMean(jdUt: Double): Double {
        val t = JulianDay.centuries(jdUt)
        return mod360(280.46061837 + 360.98564736629 * (jdUt - JulianDay.J2000) + 0.000387933 * t * t - t * t * t / 38_710_000.0)
    }

    /** Greenwich apparent sidereal time in degrees. */
    fun greenwichApparent(jdUt: Double, nutation: Nutation.Terms): Double =
        mod360(greenwichMean(jdUt) + nutation.deltaPsiDeg * cos(nutation.trueObliquityDeg * DEG))
}

object HorizontalTransform {
    /** Local hour angle in degrees, (-180, 180]. Longitude is east-positive. */
    fun hourAngle(epochMs: Long, lonDeg: Double, eq: Equatorial): Double {
        val jdUt = JulianDay.fromEpochMillis(epochMs)
        val nut = Nutation.at(JulianDay.ephemeris(epochMs))
        val h = mod360(Sidereal.greenwichApparent(jdUt, nut) + lonDeg - eq.raDeg)
        return if (h > 180.0) h - 360.0 else h
    }

    fun toHorizontal(epochMs: Long, latDeg: Double, lonDeg: Double, eq: Equatorial): Horizontal {
        val h = hourAngle(epochMs, lonDeg, eq) * DEG
        val phi = latDeg * DEG
        val dec = eq.decDeg * DEG
        val sinAlt = sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val north = sin(dec) * cos(phi) - cos(dec) * sin(phi) * cos(h)
        val east = -cos(dec) * sin(h)
        val az = mod360(atan2(east, north) * RAD)
        return Horizontal(az, alt * RAD)
    }
}

/** Atmospheric refraction for a true altitude (NOAA solar calculator piecewise fit), in degrees. */
object Refraction {
    fun degrees(trueAltitudeDeg: Double): Double {
        val e = trueAltitudeDeg
        if (e > 85.0) return 0.0
        val te = tan(e * DEG)
        val arcsec = when {
            e > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            e > -0.575 -> 1735.0 + e * (-518.2 + e * (103.4 + e * (-12.79 + e * 0.711)))
            else -> -20.772 / te
        }
        return arcsec / 3600.0
    }
}
