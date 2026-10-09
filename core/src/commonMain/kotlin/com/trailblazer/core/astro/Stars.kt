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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.withSign

/**
 * A catalogue star. Position is J2000.0; [pmRaCosDec] and [pmDec] are proper motion in arcsec per year
 * (right ascension already multiplied by cos δ, as the Bright Star Catalogue lists it).
 */
data class Star(
    val hr: Int,
    val designation: String,
    val name: String?,
    val raJ2000Deg: Double,
    val decJ2000Deg: Double,
    val vmag: Double,
    val pmRaCosDec: Double,
    val pmDec: Double,
) {
    /** Proper name, else the Bayer designation with a Greek letter ("γ Per"), else the HR number. */
    val label: String get() = name ?: Bayer.greek(designation) ?: "HR $hr"

    /** Mean J2000 place moved by proper motion to [jde], then precessed and corrected to the apparent place of date. */
    fun apparent(jde: Double): Equatorial = apparent(ApparentContext(jde))

    fun apparent(ctx: ApparentContext): Equatorial {
        val years = (ctx.jde - JulianDay.J2000) / 365.25
        val cosDec = cos(decJ2000Deg * DEG)
        // Near the celestial poles cos δ → 0; the catalogue's μα·cos δ stays finite, so guard only true zero.
        val dRa = if (abs(cosDec) < 1e-9) 0.0 else pmRaCosDec * years / 3600.0 / cosDec
        val dDec = pmDec * years / 3600.0
        return ctx.apply(raJ2000Deg + dRa, (decJ2000Deg + dDec).coerceIn(-90.0, 90.0))
    }
}

/** Bayer abbreviations as the Bright Star Catalogue writes them ("Alp", "Gam1") → Greek letters. */
object Bayer {
    private val letters = mapOf(
        "Alp" to "α", "Bet" to "β", "Gam" to "γ", "Del" to "δ", "Eps" to "ε", "Zet" to "ζ", "Eta" to "η", "The" to "θ",
        "Iot" to "ι", "Kap" to "κ", "Lam" to "λ", "Mu" to "μ", "Nu" to "ν", "Xi" to "ξ", "Omi" to "ο", "Pi" to "π",
        "Rho" to "ρ", "Sig" to "σ", "Tau" to "τ", "Ups" to "υ", "Phi" to "φ", "Chi" to "χ", "Psi" to "ψ", "Ome" to "ω",
    )
    private val pattern = Regex("""^([A-Z][a-z]{1,2})\s*(\d?)\s*([A-Z][A-Za-z]{2})$""")

    fun greek(designation: String): String? {
        val m = pattern.matchEntire(designation.trim()) ?: return null
        val (abbr, index, constellation) = m.destructured
        val letter = letters[abbr] ?: return null
        val superscript = index.map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")
        return "$letter$superscript $constellation"
    }
}

/** Precession constants for a single Julian epoch to avoid recomputing polynomial terms per star. */
class PrecessionContext(val jde: Double) {
    val t = JulianDay.centuries(jde)
    val t2 = t * t
    val t3 = t2 * t
    val zeta = (2306.2181 * t + 0.30188 * t2 + 0.017998 * t3) / 3600.0
    val z = (2306.2181 * t + 1.09468 * t2 + 0.018203 * t3) / 3600.0
    val theta = (2004.3109 * t - 0.42665 * t2 - 0.041833 * t3) / 3600.0
    val cosTh = cos(theta * DEG)
    val sinTh = sin(theta * DEG)

    fun apply(raDeg: Double, decDeg: Double): Pair<Double, Double> {
        val a0 = (raDeg + zeta) * DEG
        val d0 = decDeg * DEG
        val a = cos(d0) * sin(a0)
        val b = cosTh * cos(d0) * cos(a0) - sinTh * sin(d0)
        val c = sinTh * cos(d0) * cos(a0) + cosTh * sin(d0)
        val ra = mod360(atan2(a, b) * RAD + z)
        // asin loses precision near the poles (Polaris, σ Oct); use the cos form there.
        val dec = if (abs(c) > 0.9) acos(sqrt(a * a + b * b).coerceAtMost(1.0)).withSign(c) * RAD else asin(c) * RAD
        return ra to dec
    }
}

/** Precession of mean places from J2000.0 to a date (Meeus ch. 21, rigorous method, IAU 1976 constants). */
object Precession {
    fun fromJ2000(raDeg: Double, decDeg: Double, jde: Double): Pair<Double, Double> =
        PrecessionContext(jde).apply(raDeg, decDeg)

    /**
     * General precession in ecliptic longitude since J2000, in degrees (Lieske et al. 1977). Subtracting it from a
     * longitude of date refers that longitude to the J2000 equinox, as the meteor-shower tables use.
     */
    fun longitudeDeg(jde: Double): Double {
        val t = JulianDay.centuries(jde)
        return (5029.0966 * t + 1.11113 * t * t - 0.000006 * t * t * t) / 3600.0
    }
}

/** Context caching nutation, obliquity and solar coordinates once per epoch across multiple stars. */
class ApparentContext(val jde: Double) {
    val prec = PrecessionContext(jde)
    val nut = Nutation.at(jde)
    val eps = nut.trueObliquityDeg * DEG
    val cosEps = cos(eps)
    val sinEps = sin(eps)
    val tanEps = tan(eps)
    val sun = SolarPosition.ecliptic(jde).longitudeDeg * DEG
    val cosSun = cos(sun)
    val sinSun = sin(sun)

    fun apply(raDeg: Double, decDeg: Double, distanceKm: Double = Double.POSITIVE_INFINITY): Equatorial {
        val (ra, dec) = prec.apply(raDeg, decDeg)
        val a = ra * DEG
        val d = dec * DEG
        // First-order corrections; at |δ| > 89.5° tan δ blows up, so clamp it (the resulting error is under 1″ on the sky).
        val tanD = tan(d.coerceIn(-89.5 * DEG, 89.5 * DEG))
        val cosD = cos(d).let { if (abs(it) < 1e-6) 1e-6 else it }
        val dRaNut = (cosEps + sinEps * sin(a) * tanD) * nut.deltaPsiDeg - cos(a) * tanD * nut.deltaEpsDeg
        val dDecNut = sinEps * cos(a) * nut.deltaPsiDeg + sin(a) * nut.deltaEpsDeg
        val dRaAb = -ApparentPlace.KAPPA_DEG * (cos(a) * cosSun * cosEps + sin(a) * sinSun) / cosD
        val dDecAb = -ApparentPlace.KAPPA_DEG * (cosSun * cosEps * (tanEps * cos(d) - sin(a) * sin(d)) + cos(a) * sin(d) * sinSun)
        return Equatorial(mod360(ra + dRaNut + dRaAb), (dec + dDecNut + dDecAb).coerceIn(-90.0, 90.0), distanceKm)
    }
}

/** Mean J2000 place → apparent place of date: precession, then nutation (Meeus 23.1) and annual aberration (23.3). */
object ApparentPlace {
    const val KAPPA_DEG = 20.49552 / 3600.0

    fun fromJ2000(raDeg: Double, decDeg: Double, jde: Double, distanceKm: Double = Double.POSITIVE_INFINITY): Equatorial =
        ApparentContext(jde).apply(raDeg, decDeg, distanceKm)
}
