package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class MoonPhaseName {
    NewMoon, WaxingCrescent, FirstQuarter, WaxingGibbous, FullMoon, WaningGibbous, LastQuarter, WaningCrescent
}

/**
 * Lunar phase at an instant. Name, age and illumination all derive from the same corrected
 * Sun–Moon geometry, so they can never disagree with each other.
 */
data class LunarPhaseInfo(
    /** Moon minus Sun apparent ecliptic longitude, [0, 360): 0 new, 90 first quarter, 180 full, 270 last quarter. */
    val elongationDeg: Double,
    /** Illuminated fraction of the disc, [0, 1] (Meeus ch. 48). */
    val illumination: Double,
    val name: MoonPhaseName,
    val waxing: Boolean,
    /** Mean-synodic age estimate in days since new moon. */
    val ageDays: Double,
)

data class LunarDay(
    val moonriseMs: Long?,
    val moonsetMs: Long?,
    val transitMs: Long?,
    val alwaysUp: Boolean,
    val alwaysDown: Boolean,
)

object LunarPhase {
    const val SYNODIC_MONTH_DAYS = 29.530588853
    /** Principal phases are named within ±12° of elongation (about ±1 day). */
    private const val PRINCIPAL_WINDOW_DEG = 12.0

    fun elongation(epochMs: Long): Double {
        val jde = JulianDay.ephemeris(epochMs)
        return mod360(LunarPosition.ecliptic(jde).longitudeDeg - SolarPosition.ecliptic(jde).longitudeDeg)
    }

    fun at(epochMs: Long): LunarPhaseInfo {
        val jde = JulianDay.ephemeris(epochMs)
        val moon = LunarPosition.ecliptic(jde)
        val sun = SolarPosition.ecliptic(jde)
        val elong = mod360(moon.longitudeDeg - sun.longitudeDeg)
        // Meeus 48.2/48.3: geocentric elongation ψ, then phase angle i.
        val beta = moon.latitudeDeg * DEG
        val psi = acos((cos(beta) * cos((moon.longitudeDeg - sun.longitudeDeg) * DEG)).coerceIn(-1.0, 1.0))
        val r = sun.distanceAu * SolarPosition.AU_KM
        val i = atan2(r * sin(psi), moon.distanceKm - r * cos(psi))
        val k = ((1 + cos(i)) / 2).coerceIn(0.0, 1.0)
        return LunarPhaseInfo(
            elongationDeg = elong,
            illumination = k,
            name = nameFor(elong),
            waxing = elong < 180.0,
            ageDays = elong / 360.0 * SYNODIC_MONTH_DAYS,
        )
    }

    fun nameFor(elongationDeg: Double): MoonPhaseName {
        val e = mod360(elongationDeg)
        fun near(target: Double) = abs(angleDiff(target, e)) <= PRINCIPAL_WINDOW_DEG
        return when {
            near(0.0) -> MoonPhaseName.NewMoon
            near(90.0) -> MoonPhaseName.FirstQuarter
            near(180.0) -> MoonPhaseName.FullMoon
            near(270.0) -> MoonPhaseName.LastQuarter
            e < 90.0 -> MoonPhaseName.WaxingCrescent
            e < 180.0 -> MoonPhaseName.WaxingGibbous
            e < 270.0 -> MoonPhaseName.WaningGibbous
            else -> MoonPhaseName.WaningCrescent
        }
    }

    /**
     * Next instant at or after [fromMs] when the elongation reaches [targetDeg]
     * (0 new, 90 first quarter, 180 full, 270 last quarter). Searches up to 35 days.
     */
    fun next(fromMs: Long, targetDeg: Double): Long? {
        val end = fromMs + 35L * 86_400_000L
        return RiseSetFinder.crossings(fromMs, end, stepMs = 12 * 3_600_000L, toleranceMs = 5_000L) {
            sin((elongation(it) - targetDeg) * DEG)
        }.firstOrNull { it.rising }?.epochMs
    }
}

object LunarEvents {
    fun day(latDeg: Double, lonDeg: Double, windowStartMs: Long, windowEndMs: Long): LunarDay {
        require(windowEndMs > windowStartMs) { "empty day window" }
        require(latDeg in -90.0..90.0 && lonDeg in -180.0..180.0) { "invalid coordinates" }
        // Meeus ch. 15: geocentric altitude against h0 = 0.7275·π − 0°34′.
        val f = { t: Long ->
            val eq = LunarPosition.equatorial(t)
            val h0 = 0.7275 * LunarPosition.parallaxDeg(eq.distanceKm) - 34.0 / 60.0
            HorizontalTransform.toHorizontal(t, latDeg, lonDeg, eq).altitudeDeg - h0
        }
        val c = RiseSetFinder.crossings(windowStartMs, windowEndMs, f = f)
        val transit = RiseSetFinder.crossings(windowStartMs, windowEndMs) {
            sin(HorizontalTransform.hourAngle(it, lonDeg, LunarPosition.equatorial(it)) * DEG)
        }.firstOrNull { it.rising }?.epochMs
        val startsUp = f(windowStartMs) > 0
        return LunarDay(
            moonriseMs = c.firstOrNull { it.rising }?.epochMs,
            moonsetMs = c.firstOrNull { !it.rising }?.epochMs,
            transitMs = transit,
            alwaysUp = c.isEmpty() && startsUp,
            alwaysDown = c.isEmpty() && !startsUp,
        )
    }
}
