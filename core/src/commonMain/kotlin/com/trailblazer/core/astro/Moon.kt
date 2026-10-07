package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.RAD
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Lunar position from Meeus ch. 47, using the largest periodic terms of tables 47.A/47.B
 * (truncation error well under 0.01° in longitude and latitude, a few km in distance).
 */
object LunarPosition {
    data class Ecliptic(val longitudeDeg: Double, val latitudeDeg: Double, val distanceKm: Double)

    // D, M, M', F, Σl (1e-6 deg), Σr (1e-3 km)
    private val lr = arrayOf(
        intArrayOf(0, 0, 1, 0, 6288774, -20905355),
        intArrayOf(2, 0, -1, 0, 1274027, -3699111),
        intArrayOf(2, 0, 0, 0, 658314, -2955968),
        intArrayOf(0, 0, 2, 0, 213618, -569925),
        intArrayOf(0, 1, 0, 0, -185116, 48888),
        intArrayOf(0, 0, 0, 2, -114332, -3149),
        intArrayOf(2, 0, -2, 0, 58793, 246158),
        intArrayOf(2, -1, -1, 0, 57066, -152138),
        intArrayOf(2, 0, 1, 0, 53322, -170733),
        intArrayOf(2, -1, 0, 0, 45758, -204586),
        intArrayOf(0, 1, -1, 0, -40923, -129620),
        intArrayOf(1, 0, 0, 0, -34720, 108743),
        intArrayOf(0, 1, 1, 0, -30383, 104755),
        intArrayOf(2, 0, 0, -2, 15327, 10321),
        intArrayOf(0, 0, 1, 2, -12528, 0),
        intArrayOf(0, 0, 1, -2, 10980, 79661),
        intArrayOf(4, 0, -1, 0, 10675, -34782),
        intArrayOf(0, 0, 3, 0, 10034, -23210),
        intArrayOf(4, 0, -2, 0, 8548, -21636),
        intArrayOf(2, 1, -1, 0, -7888, 24208),
        intArrayOf(2, 1, 0, 0, -6766, 30824),
        intArrayOf(1, 0, -1, 0, -5163, -8379),
        intArrayOf(1, 1, 0, 0, 4987, -16675),
        intArrayOf(2, -1, 1, 0, 4036, -12831),
        intArrayOf(2, 0, 2, 0, 3994, -10445),
        intArrayOf(4, 0, 0, 0, 3861, -11650),
        intArrayOf(2, 0, -3, 0, 3665, 14403),
        intArrayOf(0, 1, -2, 0, -2689, -7003),
        intArrayOf(2, 0, -1, 2, -2602, 0),
        intArrayOf(2, -1, -2, 0, 2390, 10056),
        intArrayOf(1, 0, 1, 0, -2348, 6322),
        intArrayOf(2, -2, 0, 0, 2236, -9884),
    )

    // D, M, M', F, Σb (1e-6 deg)
    private val b = arrayOf(
        intArrayOf(0, 0, 0, 1, 5128122),
        intArrayOf(0, 0, 1, 1, 280602),
        intArrayOf(0, 0, 1, -1, 277693),
        intArrayOf(2, 0, 0, -1, 173237),
        intArrayOf(2, 0, -1, 1, 55413),
        intArrayOf(2, 0, -1, -1, 46271),
        intArrayOf(2, 0, 0, 1, 32573),
        intArrayOf(0, 0, 2, 1, 17198),
        intArrayOf(2, 0, 1, -1, 9266),
        intArrayOf(0, 0, 2, -1, 8822),
        intArrayOf(2, -1, 0, -1, 8216),
        intArrayOf(2, 0, -2, -1, 4324),
        intArrayOf(2, 0, 1, 1, 4200),
        intArrayOf(2, 1, 0, -1, -3359),
        intArrayOf(2, -1, -1, 1, 2463),
        intArrayOf(2, -1, 0, 1, 2211),
        intArrayOf(2, -1, -1, -1, 2065),
        intArrayOf(0, 1, -1, -1, -1870),
        intArrayOf(4, 0, -1, -1, 1828),
        intArrayOf(0, 1, 0, 1, -1794),
    )

    /** Geometric geocentric ecliptic coordinates (mean equinox of date) for an ephemeris Julian Day. */
    fun ecliptic(jde: Double): Ecliptic {
        val t = JulianDay.centuries(jde)
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        val lp = mod360(218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841 - t4 / 65194000)
        val d = mod360(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868 - t4 / 113065000)
        val m = mod360(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000)
        val mp = mod360(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699 - t4 / 14712000)
        val f = mod360(93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000 + t4 / 863310000)
        val a1 = mod360(119.75 + 131.849 * t)
        val a2 = mod360(53.09 + 479264.290 * t)
        val a3 = mod360(313.45 + 481266.484 * t)
        val e = 1 - 0.002516 * t - 0.0000074 * t2

        var sl = 0.0
        var sr = 0.0
        for (row in lr) {
            val arg = (row[0] * d + row[1] * m + row[2] * mp + row[3] * f) * DEG
            val ef = eFactor(row[1], e)
            sl += row[4] * ef * sin(arg)
            sr += row[5] * ef * cos(arg)
        }
        var sb = 0.0
        for (row in b) {
            val arg = (row[0] * d + row[1] * m + row[2] * mp + row[3] * f) * DEG
            sb += row[4] * eFactor(row[1], e) * sin(arg)
        }
        sl += 3958 * sin(a1 * DEG) + 1962 * sin((lp - f) * DEG) + 318 * sin(a2 * DEG)
        sb += -2235 * sin(lp * DEG) + 382 * sin(a3 * DEG) + 175 * sin((a1 - f) * DEG) +
            175 * sin((a1 + f) * DEG) + 127 * sin((lp - mp) * DEG) - 115 * sin((lp + mp) * DEG)

        return Ecliptic(mod360(lp + sl / 1e6), sb / 1e6, 385000.56 + sr / 1000)
    }

    private fun eFactor(mCoeff: Int, e: Double): Double = when (abs(mCoeff)) {
        1 -> e
        2 -> e * e
        else -> 1.0
    }

    /** Apparent geocentric equatorial coordinates (nutation applied). */
    fun equatorial(epochMs: Long): Equatorial {
        val jde = JulianDay.ephemeris(epochMs)
        val ecl = ecliptic(jde)
        val nut = Nutation.at(jde)
        val lambda = (ecl.longitudeDeg + nut.deltaPsiDeg) * DEG
        val beta = ecl.latitudeDeg * DEG
        val eps = nut.trueObliquityDeg * DEG
        val ra = mod360(atan2(sin(lambda) * cos(eps) - tan(beta) * sin(eps), cos(lambda)) * RAD)
        val dec = asin((sin(beta) * cos(eps) + cos(beta) * sin(eps) * sin(lambda)).coerceIn(-1.0, 1.0)) * RAD
        return Equatorial(ra, dec, ecl.distanceKm)
    }

    /** Equatorial horizontal parallax in degrees. */
    fun parallaxDeg(distanceKm: Double): Double = asin(6378.14 / distanceKm) * RAD

    /** Geocentric horizontal position (used for rise/set with Meeus' standard altitude). */
    fun geocentricHorizontal(epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal =
        HorizontalTransform.toHorizontal(epochMs, latDeg, lonDeg, equatorial(epochMs))

    /** Topocentric horizontal position: parallax lowers the Moon by up to ~1° near the horizon. */
    fun horizontal(epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal {
        val eq = equatorial(epochMs)
        val geo = HorizontalTransform.toHorizontal(epochMs, latDeg, lonDeg, eq)
        val alt = geo.altitudeDeg - parallaxDeg(eq.distanceKm) * cos(geo.altitudeDeg * DEG)
        return Horizontal(geo.azimuthDeg, alt)
    }
}
