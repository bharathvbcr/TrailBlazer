package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.time.JulianDay
import kotlin.math.sin

/**
 * One annual meteor shower from the IMO Working List of Visual Meteor Showers (Table 5 of the IMO 2026 Meteor
 * Shower Calendar, Rendtel, IMO INFO(3-25)). [peakSolarLongitude] is λ☉ for equinox J2000, which fixes the peak
 * independently of the calendar; the activity dates are those of the list and drift by at most a day between years.
 * [zhr] is null for showers the list marks as variable.
 */
data class MeteorShower(
    val code: String,
    val name: String,
    val start: MonthDay,
    val end: MonthDay,
    val peakSolarLongitude: Double,
    val radiantRaDeg: Double,
    val radiantDecDeg: Double,
    val speedKmS: Int,
    val zhr: Int?,
)

data class MonthDay(val month: Int, val day: Int) {
    init {
        require(month in 1..12 && day in 1..31) { "invalid month/day" }
    }
}

/** A shower's next peak and how good it will be to watch. */
data class ShowerOutlook(
    val shower: MeteorShower,
    val peakMs: Long,
    /** Moon illumination at the peak, 0–1; bright moonlight hides all but the brightest meteors. */
    val moonIllumination: Double,
    val activeNow: Boolean,
)

object MeteorShowers {
    /** Night-time showers from the IMO working list with a ZHR of at least 5 or notable variable outbursts. */
    val all: List<MeteorShower> = listOf(
        MeteorShower("QUA", "Quadrantids", MonthDay(12, 28), MonthDay(1, 12), 283.15, 230.0, 49.0, 41, 80),
        MeteorShower("ACE", "α-Centaurids", MonthDay(1, 31), MonthDay(2, 20), 319.4, 211.0, -58.0, 58, 6),
        MeteorShower("LYR", "April Lyrids", MonthDay(4, 14), MonthDay(4, 30), 32.32, 271.0, 34.0, 49, 18),
        MeteorShower("PPU", "π-Puppids", MonthDay(4, 15), MonthDay(4, 28), 33.5, 110.0, -45.0, 18, null),
        MeteorShower("ETA", "η-Aquariids", MonthDay(4, 19), MonthDay(5, 28), 45.5, 338.0, -1.0, 66, 50),
        MeteorShower("JBO", "June Bootids", MonthDay(6, 22), MonthDay(7, 2), 90.3, 221.0, 48.0, 18, null),
        MeteorShower("GDR", "July γ-Draconids", MonthDay(7, 25), MonthDay(7, 31), 125.13, 280.0, 51.0, 27, 5),
        MeteorShower("SDA", "Southern δ-Aquariids", MonthDay(7, 12), MonthDay(8, 23), 128.0, 340.0, -16.0, 41, 25),
        MeteorShower("CAP", "α-Capricornids", MonthDay(7, 3), MonthDay(8, 15), 128.0, 307.0, -10.0, 23, 5),
        MeteorShower("PER", "Perseids", MonthDay(7, 17), MonthDay(8, 24), 140.0, 48.0, 58.0, 59, 100),
        MeteorShower("AUR", "Aurigids", MonthDay(8, 28), MonthDay(9, 5), 158.6, 91.0, 39.0, 66, 6),
        MeteorShower("SPE", "September ε-Perseids", MonthDay(9, 5), MonthDay(9, 21), 166.7, 48.0, 40.0, 64, 8),
        MeteorShower("OCT", "October Camelopardalids", MonthDay(10, 5), MonthDay(10, 6), 192.58, 164.0, 79.0, 47, 5),
        MeteorShower("DRA", "Draconids", MonthDay(10, 6), MonthDay(10, 10), 195.4, 262.0, 54.0, 20, 5),
        MeteorShower("ORI", "Orionids", MonthDay(10, 2), MonthDay(11, 7), 208.0, 95.0, 16.0, 66, 20),
        MeteorShower("STA", "Southern Taurids", MonthDay(9, 20), MonthDay(11, 20), 223.0, 52.0, 15.0, 27, 7),
        MeteorShower("NTA", "Northern Taurids", MonthDay(10, 20), MonthDay(12, 10), 230.0, 58.0, 22.0, 29, 5),
        MeteorShower("LEO", "Leonids", MonthDay(11, 6), MonthDay(11, 30), 235.27, 152.0, 22.0, 71, 15),
        MeteorShower("AMO", "α-Monocerotids", MonthDay(11, 15), MonthDay(11, 25), 239.32, 117.0, 1.0, 65, null),
        MeteorShower("PHO", "Phoenicids", MonthDay(12, 1), MonthDay(12, 5), 249.5, 8.0, -27.0, 15, null),
        MeteorShower("PUP", "Puppid-Velids", MonthDay(12, 1), MonthDay(12, 15), 255.0, 123.0, -45.0, 44, 10),
        MeteorShower("HYD", "σ-Hydrids", MonthDay(12, 3), MonthDay(12, 20), 257.0, 125.0, 2.0, 58, 7),
        MeteorShower("GEM", "Geminids", MonthDay(12, 4), MonthDay(12, 20), 262.2, 112.0, 33.0, 35, 150),
        MeteorShower("URS", "Ursids", MonthDay(12, 17), MonthDay(12, 26), 270.7, 217.0, 76.0, 33, 10),
    )

    /**
     * The Sun's apparent longitude referred to the mean equinox of J2000, as the IMO tables give it: the apparent
     * longitude of date without its aberration (−20.5″) and nutation terms, minus general precession since J2000.
     */
    fun solarLongitudeJ2000(epochMs: Long): Double {
        val jde = JulianDay.ephemeris(epochMs)
        val t = JulianDay.centuries(jde)
        val omega = (125.04 - 1934.136 * t) * DEG
        val apparent = SolarPosition.ecliptic(jde).longitudeDeg
        return mod360(apparent + 0.00569 + 0.00478 * sin(omega) - Precession.longitudeDeg(jde))
    }

    /** The first instant at or after [fromMs] when the Sun reaches the shower's peak longitude. */
    fun nextPeak(shower: MeteorShower, fromMs: Long): Long? {
        val end = fromMs + 370L * 86_400_000L
        return RiseSetFinder.crossings(fromMs, end, stepMs = 86_400_000L, toleranceMs = 60_000L) {
            sin((solarLongitudeJ2000(it) - shower.peakSolarLongitude) * DEG)
        }.firstOrNull { it.rising }?.epochMs
    }

    /** True when [epochMs] (UTC date) falls inside the shower's activity period, including periods that span New Year. */
    fun isActive(shower: MeteorShower, epochMs: Long): Boolean {
        val (_, m, d) = CivilDate.civilFromDays(Math.floorDiv(epochMs, 86_400_000L))
        val today = m * 100 + d
        val s = shower.start.month * 100 + shower.start.day
        val e = shower.end.month * 100 + shower.end.day
        return if (s <= e) today in s..e else today >= s || today <= e
    }

    /** Every shower with its next peak, soonest first. */
    fun upcoming(fromMs: Long): List<ShowerOutlook> = all.mapNotNull { s ->
        // A shower that is active now and just peaked is still the one to watch, so search from 3 days back.
        val peak = nextPeak(s, fromMs - 3 * 86_400_000L) ?: return@mapNotNull null
        ShowerOutlook(s, peak, LunarPhase.at(peak).illumination, isActive(s, fromMs))
    }.sortedBy { it.peakMs }

    /**
     * Altitude of the radiant at [epochMs], degrees. The observed rate scales roughly with sin(altitude), so a
     * radiant below the horizon means few or no meteors from that shower.
     */
    fun radiantAltitude(shower: MeteorShower, epochMs: Long, latDeg: Double, lonDeg: Double): Double {
        val eq = ApparentPlace.fromJ2000(shower.radiantRaDeg, shower.radiantDecDeg, JulianDay.ephemeris(epochMs))
        return HorizontalTransform.toHorizontal(epochMs, latDeg, lonDeg, eq).altitudeDeg
    }

    /** Expected hourly rate for an observer: ZHR × sin(radiant altitude), under the reference dark sky (limiting mag 6.5). */
    fun expectedRate(shower: MeteorShower, radiantAltitudeDeg: Double): Double? {
        val z = shower.zhr ?: return null
        if (radiantAltitudeDeg <= 0) return 0.0
        return z * sin(radiantAltitudeDeg * DEG).coerceAtMost(1.0)
    }
}
