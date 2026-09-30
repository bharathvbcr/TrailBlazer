package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A half-open time interval [startMs, endMs). */
data class Interval(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "interval ends before it starts" }
    }

    val durationMs: Long get() = endMs - startMs
}

object Intervals {
    /** The parts of [startMs, endMs) where f > 0, from the crossings of f. */
    fun above(startMs: Long, endMs: Long, stepMs: Long = 3_600_000L, f: (Long) -> Double): List<Interval> {
        val crossings = RiseSetFinder.crossings(startMs, endMs, stepMs = stepMs, toleranceMs = 30_000L, f = f)
        val out = ArrayList<Interval>()
        var open: Long? = if (f(startMs) > 0) startMs else null
        for (c in crossings) {
            if (c.rising && open == null) open = c.epochMs
            if (!c.rising && open != null) {
                if (c.epochMs > open) out += Interval(open, c.epochMs)
                open = null
            }
        }
        if (open != null && endMs > open) out += Interval(open, endMs)
        return out
    }

    /** Pairwise intersection of two sorted, non-overlapping interval lists. */
    fun intersect(a: List<Interval>, b: List<Interval>): List<Interval> {
        val out = ArrayList<Interval>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val s = max(a[i].startMs, b[j].startMs)
            val e = min(a[i].endMs, b[j].endMs)
            if (e > s) out += Interval(s, e)
            if (a[i].endMs < b[j].endMs) i++ else j++
        }
        return out
    }
}

/** How dark one night gets, over a caller-chosen window (normally local noon to the next local noon). */
data class NightPlan(
    val windowStartMs: Long,
    val windowEndMs: Long,
    /** Sun below −18°: no twilight left in the sky. Empty at high latitudes around midsummer. */
    val astronomicalDark: List<Interval>,
    /** Sun below −12°: dark enough for bright stars and planets, and for most landscape astrophotography. */
    val nauticalDark: List<Interval>,
    /** Astronomical darkness with the Moon below the horizon: the best time for faint objects and the Milky Way. */
    val moonlessDark: List<Interval>,
    /** Moon illumination at the middle of the night, 0–1. */
    val moonIllumination: Double,
) {
    val moonlessDarkMs: Long get() = moonlessDark.sumOf { it.durationMs }
    val astronomicalDarkMs: Long get() = astronomicalDark.sumOf { it.durationMs }
}

/** When a body is up during one night. */
data class BodyNight(
    val riseMs: Long?,
    val setMs: Long?,
    val transitMs: Long?,
    val upAllNight: Boolean,
    val downAllNight: Boolean,
    /** Highest altitude reached while the Sun is below −12°, and when; null if it never clears the horizon then. */
    val bestAltitudeDeg: Double?,
    val bestTimeMs: Long?,
)

/** Where the pole star sits around the celestial pole, for aligning an equatorial mount. */
data class PolarAlignment(
    val starLabel: String,
    /** Local hour angle of the pole star, hours in [0, 24). 0 means it is directly above the pole. */
    val hourAngleHours: Double,
    /** Angular distance of the pole star from the true pole, degrees. */
    val poleDistanceDeg: Double,
    /**
     * Where to look for the star relative to the pole, with the naked eye or a finder that does not invert, facing
     * the pole with the zenith up: clockwise angle from straight up, degrees, and the matching clock-face hour.
     */
    val angleFromUpDeg: Double,
    val clockHour: Double,
    /** Altitude of the celestial pole above the horizon (equal to |latitude|). */
    val poleAltitudeDeg: Double,
)

object NightSky {
    /** Standard altitude for the rise and set of stars and planets: refraction only (Meeus ch. 15). */
    const val STAR_H0 = -0.5667

    /** Sgr A*, the centre of the Milky Way (J2000). */
    const val GALACTIC_CENTRE_RA = 266.41683
    const val GALACTIC_CENTRE_DEC = -29.00781

    private const val TEN_MINUTES = 600_000L

    fun plan(latDeg: Double, lonDeg: Double, windowStartMs: Long, windowEndMs: Long): NightPlan {
        require(windowEndMs > windowStartMs) { "empty night window" }
        require(latDeg in -90.0..90.0 && lonDeg in -180.0..180.0) { "invalid coordinates" }
        val sunAlt = { t: Long -> SolarPosition.horizontal(t, latDeg, lonDeg).altitudeDeg }
        val astro = Intervals.above(windowStartMs, windowEndMs) { SunAltitude.ASTRONOMICAL - sunAlt(it) }
        val nautical = Intervals.above(windowStartMs, windowEndMs) { SunAltitude.NAUTICAL - sunAlt(it) }
        // Moon "down" uses the same criterion as moonrise and moonset (Meeus ch. 15), so the two never disagree.
        val moonDown = Intervals.above(windowStartMs, windowEndMs) { t ->
            val eq = LunarPosition.equatorial(t)
            val h0 = 0.7275 * LunarPosition.parallaxDeg(eq.distanceKm) - 34.0 / 60.0
            h0 - HorizontalTransform.toHorizontal(t, latDeg, lonDeg, eq).altitudeDeg
        }
        val mid = windowStartMs + (windowEndMs - windowStartMs) / 2
        return NightPlan(
            windowStartMs, windowEndMs,
            astronomicalDark = astro,
            nauticalDark = nautical,
            moonlessDark = Intervals.intersect(astro, moonDown),
            moonIllumination = LunarPhase.at(mid).illumination,
        )
    }

    /** Rise, set, transit and best viewing time of a body over the night, given its apparent place at any instant. */
    fun body(latDeg: Double, lonDeg: Double, windowStartMs: Long, windowEndMs: Long, place: (Long) -> Equatorial): BodyNight {
        require(windowEndMs > windowStartMs) { "empty night window" }
        val alt = { t: Long -> HorizontalTransform.toHorizontal(t, latDeg, lonDeg, place(t)).altitudeDeg }
        val c = RiseSetFinder.crossings(windowStartMs, windowEndMs) { alt(it) - STAR_H0 }
        val transit = RiseSetFinder.crossings(windowStartMs, windowEndMs) {
            sin(HorizontalTransform.hourAngle(it, lonDeg, place(it)) * DEG)
        }.firstOrNull { it.rising }?.epochMs
        val startsUp = alt(windowStartMs) > STAR_H0
        var bestAlt: Double? = null
        var bestT: Long? = null
        var t = windowStartMs
        while (t < windowEndMs) {
            if (SolarPosition.horizontal(t, latDeg, lonDeg).altitudeDeg < SunAltitude.NAUTICAL) {
                val a = alt(t)
                if (a > 0 && (bestAlt == null || a > bestAlt)) {
                    bestAlt = a
                    bestT = t
                }
            }
            t += TEN_MINUTES
        }
        return BodyNight(
            riseMs = c.firstOrNull { it.rising }?.epochMs,
            setMs = c.firstOrNull { !it.rising }?.epochMs,
            transitMs = transit,
            upAllNight = c.isEmpty() && startsUp,
            downAllNight = c.isEmpty() && !startsUp,
            bestAltitudeDeg = bestAlt,
            bestTimeMs = bestT,
        )
    }

    fun planetNight(planet: Planet, latDeg: Double, lonDeg: Double, windowStartMs: Long, windowEndMs: Long): BodyNight =
        body(latDeg, lonDeg, windowStartMs, windowEndMs) { Planets.position(planet, it).apparent }

    fun galacticCentreNight(latDeg: Double, lonDeg: Double, windowStartMs: Long, windowEndMs: Long): BodyNight =
        body(latDeg, lonDeg, windowStartMs, windowEndMs) {
            ApparentPlace.fromJ2000(GALACTIC_CENTRE_RA, GALACTIC_CENTRE_DEC, JulianDay.ephemeris(it))
        }

    /**
     * Polaris (north of the equator) or σ Octantis (south of it). Returns null on the equator itself, where the pole
     * lies on the horizon and a polar alignment view means nothing.
     */
    fun polarAlignment(latDeg: Double, lonDeg: Double, epochMs: Long): PolarAlignment? {
        require(latDeg in -90.0..90.0 && lonDeg in -180.0..180.0) { "invalid coordinates" }
        if (latDeg == 0.0) return null
        val north = latDeg > 0
        val star = BrightStars.all.first { it.hr == if (north) POLARIS_HR else SIGMA_OCTANTIS_HR }
        val eq = star.apparent(JulianDay.ephemeris(epochMs))
        val ha = mod360(HorizontalTransform.hourAngle(epochMs, lonDeg, eq))
        // Facing north, stars circle the pole anticlockwise; facing south, clockwise. At HA 0 the star is above the pole.
        val angle = if (north) mod360(-ha) else ha
        return PolarAlignment(
            starLabel = star.label,
            hourAngleHours = ha / 15.0,
            poleDistanceDeg = 90.0 - abs(eq.decDeg),
            angleFromUpDeg = angle,
            clockHour = (angle / 30.0).let { if (it <= 0.0) 12.0 else it },
            poleAltitudeDeg = abs(latDeg),
        )
    }

    const val POLARIS_HR = 424
    const val SIGMA_OCTANTIS_HR = 7228
}
