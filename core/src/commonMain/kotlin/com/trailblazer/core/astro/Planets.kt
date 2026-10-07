package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.RAD
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

enum class Planet(val label: String) {
    Mercury("Mercury"), Venus("Venus"), Mars("Mars"), Jupiter("Jupiter"), Saturn("Saturn"), Uranus("Uranus"), Neptune("Neptune");

    /** Visible to the naked eye under a dark sky (Uranus is at the limit, Neptune never). */
    val nakedEye: Boolean get() = this != Uranus && this != Neptune
}

/** A planet seen from the geocentre at one instant. */
data class PlanetPosition(
    val planet: Planet,
    /** Astrometric place: J2000 frame, corrected for light time only. */
    val astrometric: Equatorial,
    /** Apparent place of date: precession, nutation and aberration applied. Use this for altitude and azimuth. */
    val apparent: Equatorial,
    val sunDistanceAu: Double,
    val earthDistanceAu: Double,
    /** Sun–planet–Earth angle, degrees. */
    val phaseAngleDeg: Double,
    /** Angular distance from the Sun as seen from Earth, degrees. */
    val elongationDeg: Double,
    val magnitude: Double,
)

/**
 * Planet positions from the JPL "Approximate Positions of the Planets" Keplerian elements (Standish & Williams 1992):
 * Table 1 for 1800–2050, Tables 2a/2b outside it (3000 BC – 3000 AD). The Earth is the Earth–Moon barycentre,
 * which moves the Earth by at most 4,700 km. Accuracy is arcminutes, which is well inside one pixel on a sky chart.
 * Magnitudes use the Astronomical Almanac formulas quoted by Meeus (ch. 41), with Saturn's rings (ch. 45).
 */
object Planets {
    private class Elements(
        val a: Double, val e: Double, val i: Double, val l: Double, val peri: Double, val node: Double,
        val aDot: Double, val eDot: Double, val iDot: Double, val lDot: Double, val periDot: Double, val nodeDot: Double,
        val b: Double = 0.0, val c: Double = 0.0, val s: Double = 0.0, val f: Double = 0.0,
    )

    private const val EARTH = -1

    private val table1: Map<Int, Elements> = mapOf(
        Planet.Mercury.ordinal to Elements(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593, 0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081),
        Planet.Venus.ordinal to Elements(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255, 0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418),
        EARTH to Elements(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0, 0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0),
        Planet.Mars.ordinal to Elements(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891, 0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343),
        Planet.Jupiter.ordinal to Elements(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909, -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106),
        Planet.Saturn.ordinal to Elements(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448, -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794),
        Planet.Uranus.ordinal to Elements(19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503, -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589),
        Planet.Neptune.ordinal to Elements(30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574, 0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664),
    )

    private val table2: Map<Int, Elements> = mapOf(
        Planet.Mercury.ordinal to Elements(0.38709843, 0.20563661, 7.00559432, 252.25166724, 77.45771895, 48.33961819, 0.00000000, 0.00002123, -0.00590158, 149472.67486623, 0.15940013, -0.12214182),
        Planet.Venus.ordinal to Elements(0.72332102, 0.00676399, 3.39777545, 181.97970850, 131.76755713, 76.67261496, -0.00000026, -0.00005107, 0.00043494, 58517.81560260, 0.05679648, -0.27274174),
        EARTH to Elements(1.00000018, 0.01673163, -0.00054346, 100.46691572, 102.93005885, -5.11260389, -0.00000003, -0.00003661, -0.01337178, 35999.37306329, 0.31795260, -0.24123856),
        Planet.Mars.ordinal to Elements(1.52371243, 0.09336511, 1.85181869, -4.56813164, -23.91744784, 49.71320984, 0.00000097, 0.00009149, -0.00724757, 19140.29934243, 0.45223625, -0.26852431),
        Planet.Jupiter.ordinal to Elements(5.20248019, 0.04853590, 1.29861416, 34.33479152, 14.27495244, 100.29282654, -0.00002864, 0.00018026, -0.00322699, 3034.90371757, 0.18199196, 0.13024619, -0.00012452, 0.06064060, -0.35635438, 38.35125000),
        Planet.Saturn.ordinal to Elements(9.54149883, 0.05550825, 2.49424102, 50.07571329, 92.86136063, 113.63998702, -0.00003065, -0.00032044, 0.00451969, 1222.11494724, 0.54179478, -0.25015002, 0.00025899, -0.13434469, 0.87320147, 38.35125000),
        Planet.Uranus.ordinal to Elements(19.18797948, 0.04685740, 0.77298127, 314.20276625, 172.43404441, 73.96250215, -0.00020455, -0.00001550, -0.00180155, 428.49512595, 0.09266985, 0.05739699, 0.00058331, -0.97731848, 0.17689245, 7.67025000),
        Planet.Neptune.ordinal to Elements(30.06952752, 0.00895439, 1.77005520, 304.22289287, 46.68158724, 131.78635853, 0.00006447, 0.00000818, 0.00022400, 218.46515314, 0.01009938, -0.00606302, -0.00041348, 0.68346318, -0.10162547, 7.67025000),
    )

    /** J2000 obliquity used by JPL to rotate the ecliptic frame to the equator. */
    private const val OBLIQUITY_J2000 = 23.43928

    /** Light travel time for one astronomical unit, in days. */
    private const val LIGHT_DAYS_PER_AU = 0.0057755183

    private const val KEPLER_MAX_ITERATIONS = 30

    /** Heliocentric position in the J2000 ecliptic frame, AU. */
    internal fun heliocentric(body: Int, jde: Double): DoubleArray {
        val t = JulianDay.centuries(jde)
        val el = (if (t in -2.0..0.5) table1 else table2).getValue(body)
        val a = el.a + el.aDot * t
        val e = el.e + el.eDot * t
        val i = el.i + el.iDot * t
        val l = el.l + el.lDot * t
        val peri = el.peri + el.periDot * t
        val node = el.node + el.nodeDot * t
        val w = peri - node
        var m = l - peri + el.b * t * t + el.c * cos(el.f * t * DEG) + el.s * sin(el.f * t * DEG)
        m = mod360(m + 180.0) - 180.0
        val eccAnomaly = solveKepler(m, e)
        val xp = a * (cos(eccAnomaly * DEG) - e)
        val yp = a * sqrt(1 - e * e) * sin(eccAnomaly * DEG)
        val cw = cos(w * DEG); val sw = sin(w * DEG)
        val cn = cos(node * DEG); val sn = sin(node * DEG)
        val ci = cos(i * DEG); val si = sin(i * DEG)
        return doubleArrayOf(
            (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp,
            (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp,
            (sw * si) * xp + (cw * si) * yp,
        )
    }

    /** Solves M = E − e·sin E (degrees) by Newton's method, as JPL describes, with a bounded number of steps. */
    internal fun solveKepler(meanAnomalyDeg: Double, e: Double): Double {
        val eStar = e * RAD
        var ecc = meanAnomalyDeg + eStar * sin(meanAnomalyDeg * DEG)
        repeat(KEPLER_MAX_ITERATIONS) {
            val dM = meanAnomalyDeg - (ecc - eStar * sin(ecc * DEG))
            val dE = dM / (1 - e * cos(ecc * DEG))
            ecc += dE
            if (abs(dE) <= 1e-6) return ecc
        }
        return ecc
    }

    fun position(planet: Planet, epochMs: Long): PlanetPosition {
        val jde = JulianDay.ephemeris(epochMs)
        val earth = heliocentric(EARTH, jde)
        // Light time: see the planet where it was when the light left it (two iterations converge to < 1 s).
        var tau = 0.0
        var p = heliocentric(planet.ordinal, jde)
        var g = DoubleArray(3)
        repeat(3) {
            p = heliocentric(planet.ordinal, jde - tau)
            g = doubleArrayOf(p[0] - earth[0], p[1] - earth[1], p[2] - earth[2])
            tau = norm(g) * LIGHT_DAYS_PER_AU
        }
        val delta = norm(g)
        val r = norm(p)
        val rEarth = norm(earth)
        val eps = OBLIQUITY_J2000 * DEG
        val xq = g[0]
        val yq = cos(eps) * g[1] - sin(eps) * g[2]
        val zq = sin(eps) * g[1] + cos(eps) * g[2]
        val ra = mod360(atan2(yq, xq) * RAD)
        val dec = asin((zq / delta).coerceIn(-1.0, 1.0)) * RAD
        val km = delta * SolarPosition.AU_KM
        val phase = acos(((r * r + delta * delta - rEarth * rEarth) / (2 * r * delta)).coerceIn(-1.0, 1.0)) * RAD
        // Sun as seen from Earth is −earth; elongation is the angle between that and the planet.
        val cosElong = -(g[0] * earth[0] + g[1] * earth[1] + g[2] * earth[2]) / (delta * rEarth)
        val elongation = acos(cosElong.coerceIn(-1.0, 1.0)) * RAD
        return PlanetPosition(
            planet = planet,
            astrometric = Equatorial(ra, dec, km),
            apparent = ApparentPlace.fromJ2000(ra, dec, jde, km),
            sunDistanceAu = r,
            earthDistanceAu = delta,
            phaseAngleDeg = phase,
            elongationDeg = elongation,
            magnitude = magnitude(planet, r, delta, phase, p, g, jde),
        )
    }

    fun horizontal(planet: Planet, epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal =
        HorizontalTransform.toHorizontal(epochMs, latDeg, lonDeg, position(planet, epochMs).apparent)

    private fun magnitude(planet: Planet, r: Double, delta: Double, i: Double, helio: DoubleArray, geo: DoubleArray, jde: Double): Double {
        val d = 5 * log10(r * delta)
        return when (planet) {
            Planet.Mercury -> -0.42 + d + 0.0380 * i - 0.000273 * i * i + 0.000002 * i * i * i
            Planet.Venus -> -4.40 + d + 0.0009 * i + 0.000239 * i * i - 0.00000065 * i * i * i
            Planet.Mars -> -1.52 + d + 0.016 * i
            Planet.Jupiter -> -9.40 + d + 0.005 * i
            Planet.Saturn -> saturnMagnitude(d, helio, geo, jde)
            Planet.Uranus -> -7.19 + d
            Planet.Neptune -> -6.87 + d
        }
    }

    /** Saturn with its rings: B is the ring tilt towards Earth, ΔU the Sun–Earth difference in ring-plane longitude (Meeus ch. 45). */
    private fun saturnMagnitude(d: Double, helio: DoubleArray, geo: DoubleArray, jde: Double): Double {
        val t = JulianDay.centuries(jde)
        val ringI = (28.075216 - 0.012998 * t + 0.000004 * t * t) * DEG
        val ringNode = (169.508470 + 1.394681 * t + 0.000412 * t * t) * DEG
        fun lonLat(v: DoubleArray): Pair<Double, Double> {
            val n = norm(v)
            return atan2(v[1], v[0]) to asin((v[2] / n).coerceIn(-1.0, 1.0))
        }
        val (lambda, beta) = lonLat(geo)
        val (l, b) = lonLat(helio)
        val sinB = sin(ringI) * cos(beta) * sin(lambda - ringNode) - cos(ringI) * sin(beta)
        val u1 = atan2(sin(ringI) * sin(b) + cos(ringI) * cos(b) * sin(l - ringNode), cos(b) * cos(l - ringNode))
        val u2 = atan2(sin(ringI) * sin(beta) + cos(ringI) * cos(beta) * sin(lambda - ringNode), cos(beta) * cos(lambda - ringNode))
        var du = abs(u1 - u2) * RAD
        if (du > 180) du = 360 - du
        val sinAbsB = abs(sinB)
        return -8.88 + d + 0.044 * du - 2.60 * sinAbsB + 1.25 * sinB * sinB
    }

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
