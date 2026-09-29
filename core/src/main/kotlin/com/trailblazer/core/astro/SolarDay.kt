package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import kotlin.math.sin

enum class DayType { Normal, PolarDay, PolarNight }

/** Sun altitudes (degrees, true altitude of the centre) that define the day's events. */
object SunAltitude {
    /** Upper limb on the horizon with standard refraction (34′) and semidiameter (16′). */
    const val HORIZON = -0.833
    const val CIVIL = -6.0
    const val NAUTICAL = -12.0
    const val ASTRONOMICAL = -18.0
    /** Golden hour spans −4°…+6°; blue hour spans −6°…−4°. */
    const val GOLDEN_UPPER = 6.0
    const val BLUE_GOLDEN_BOUNDARY = -4.0
}

/** An interval of the day; either end is null when the Sun never reaches that altitude inside the day window. */
data class Band(val startMs: Long?, val endMs: Long?)

/**
 * Everything the Sky screen needs about the Sun for one local day, defined by the caller as
 * [windowStartMs, windowEndMs) — normally local midnight to the next local midnight, so DST days
 * (23 h / 25 h) are handled by the caller's time zone rules, not guessed here.
 */
data class SolarDay(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val sunriseMs: Long?,
    val sunsetMs: Long?,
    val solarNoonMs: Long?,
    val noonAltitudeDeg: Double?,
    val sunriseAzimuthDeg: Double?,
    val sunsetAzimuthDeg: Double?,
    val civil: Band,
    val nautical: Band,
    val astronomical: Band,
    val goldenMorning: Band?,
    val goldenEvening: Band?,
    val blueMorning: Band?,
    val blueEvening: Band?,
    val daylightMs: Long,
    val dayType: DayType,
)

/** Where the day stands at a given instant, for the "daylight left" readout. */
sealed interface DaylightStatus {
    data class UntilSunrise(val ms: Long) : DaylightStatus
    data class DaylightLeft(val ms: Long) : DaylightStatus
    data object AfterSunset : DaylightStatus
    data object PolarDay : DaylightStatus
    data object PolarNight : DaylightStatus
}

object SolarEvents {
    fun day(latDeg: Double, lonDeg: Double, windowStartMs: Long, windowEndMs: Long): SolarDay {
        require(windowEndMs > windowStartMs) { "empty day window" }
        require(latDeg in -90.0..90.0 && lonDeg in -180.0..180.0) { "invalid coordinates" }
        val alt = { t: Long -> SolarPosition.horizontal(t, latDeg, lonDeg).altitudeDeg }
        val cache = HashMap<Long, Double>()
        val cachedAlt = { t: Long -> cache.getOrPut(t) { alt(t) } }

        fun crossingsAt(threshold: Double) =
            RiseSetFinder.crossings(windowStartMs, windowEndMs) { cachedAlt(it) - threshold }

        fun band(threshold: Double): Band {
            val c = crossingsAt(threshold)
            return Band(c.firstOrNull { it.rising }?.epochMs, c.lastOrNull { !it.rising }?.epochMs)
        }

        val horizon = crossingsAt(SunAltitude.HORIZON)
        val sunrise = horizon.firstOrNull { it.rising }?.epochMs
        val sunset = horizon.lastOrNull { !it.rising }?.epochMs
        val startsAbove = cachedAlt(windowStartMs) > SunAltitude.HORIZON
        val daylight = RiseSetFinder.timeAbove(windowStartMs, windowEndMs, horizon, startsAbove)
        val window = windowEndMs - windowStartMs
        val dayType = when {
            horizon.isEmpty() && startsAbove -> DayType.PolarDay
            horizon.isEmpty() -> DayType.PolarNight
            else -> DayType.Normal
        }

        val noon = RiseSetFinder.crossings(windowStartMs, windowEndMs) {
            sin(HorizontalTransform.hourAngle(it, lonDeg, SolarPosition.equatorial(it)) * DEG)
        }.firstOrNull { it.rising }?.epochMs

        val golden6 = band(SunAltitude.GOLDEN_UPPER)
        val minus4 = band(SunAltitude.BLUE_GOLDEN_BOUNDARY)
        val civil = band(SunAltitude.CIVIL)

        return SolarDay(
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            sunriseMs = sunrise,
            sunsetMs = sunset,
            solarNoonMs = noon,
            noonAltitudeDeg = noon?.let { alt(it) },
            sunriseAzimuthDeg = sunrise?.let { SolarPosition.horizontal(it, latDeg, lonDeg).azimuthDeg },
            sunsetAzimuthDeg = sunset?.let { SolarPosition.horizontal(it, latDeg, lonDeg).azimuthDeg },
            civil = civil,
            nautical = band(SunAltitude.NAUTICAL),
            astronomical = band(SunAltitude.ASTRONOMICAL),
            goldenMorning = bandBetween(minus4.startMs, golden6.startMs),
            goldenEvening = bandBetween(golden6.endMs, minus4.endMs),
            blueMorning = bandBetween(civil.startMs, minus4.startMs),
            blueEvening = bandBetween(minus4.endMs, civil.endMs),
            daylightMs = daylight.coerceIn(0L, window),
            dayType = dayType,
        )
    }

    /** A band exists only if at least one of its edges happens inside the day. */
    private fun bandBetween(start: Long?, end: Long?): Band? =
        if (start == null && end == null) null else Band(start, end)

    fun status(day: SolarDay, nowMs: Long): DaylightStatus = when (day.dayType) {
        DayType.PolarDay -> DaylightStatus.PolarDay
        DayType.PolarNight -> DaylightStatus.PolarNight
        DayType.Normal -> {
            val rise = day.sunriseMs
            val set = day.sunsetMs
            when {
                rise != null && nowMs < rise -> DaylightStatus.UntilSunrise(rise - nowMs)
                set != null && nowMs < set -> DaylightStatus.DaylightLeft(set - nowMs)
                else -> DaylightStatus.AfterSunset
            }
        }
    }
}
