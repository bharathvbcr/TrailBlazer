package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import kotlin.math.sin

enum class DayType { Normal, PolarDay, PolarNight }

/** What the day plot is showing at one instant, innermost band first. */
enum class DaySlice { PolarDay, PolarNight, Day, Golden, Blue, Civil, Nautical, Astronomical, Night }

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

/**
 * An interval of the day. An end is null when the interval runs to that edge of the day window (the Sun is
 * already inside it when the window opens, or still inside when it closes). An end earlier than the start means
 * the interval wraps through the window edge. With both ends null, [allDay] tells the two cases apart: true
 * when the Sun is inside the band for the whole window, false when it never gets there.
 */
data class Band(val startMs: Long?, val endMs: Long?, val allDay: Boolean = false)

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

        fun band(threshold: Double, c: List<Crossing> = crossingsAt(threshold)): Band =
            Band(
                c.firstOrNull { it.rising }?.epochMs,
                c.lastOrNull { !it.rising }?.epochMs,
                allDay = c.isEmpty() && cachedAlt(windowStartMs) > threshold,
            )

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

        // sin(hour angle) crosses zero rising at the Sun's upper transit and falling at the lower one.
        val transits = RiseSetFinder.crossings(windowStartMs, windowEndMs) {
            sin(HorizontalTransform.hourAngle(it, lonDeg, SolarPosition.equatorial(it)) * DEG)
        }
        val noon = transits.firstOrNull { it.rising }?.epochMs
        val nadir = transits.firstOrNull { !it.rising }?.epochMs

        /**
         * Where the Sun is between [low] and [high] (exclusive of [low], inclusive of [high]), found from its
         * altitude between every crossing and transit rather than from the crossings alone, so a threshold the
         * Sun never reaches — or never leaves — is decided by where it really is. A stretch is "morning" while
         * the Sun climbs and "evening" while it sinks; a stretch that holds a transit is split there.
         */
        fun zone(low: Double, high: Double, lowCrossings: List<Crossing>, highCrossings: List<Crossing>): Pair<Band?, Band?> {
            val cuts = (lowCrossings + highCrossings).map { it.epochMs } + listOfNotNull(noon, nadir)
            val bounds = (listOf(windowStartMs, windowEndMs) + cuts.filter { it in (windowStartMs + 1) until windowEndMs }).distinct().sorted()
            class Stretch(val from: Long, var to: Long, val rising: Boolean)
            val stretches = ArrayList<Stretch>()
            for (i in 0 until bounds.size - 1) {
                val from = bounds[i]
                val to = bounds[i + 1]
                val mid = from + (to - from) / 2
                val a = cachedAlt(mid)
                if (!(a > low && a <= high)) continue
                val rising = alt(mid + 60_000L) > alt(mid - 60_000L)
                val last = stretches.lastOrNull()
                if (last != null && last.to == from && last.rising == rising) last.to = to else stretches += Stretch(from, to, rising)
            }

            fun bandOf(list: List<Stretch>): Band? {
                if (list.isEmpty()) return null
                val first = list.first()
                val last = list.last()
                val opensAtStart = first.from == windowStartMs
                val closesAtEnd = last.to == windowEndMs
                if (list.size > 1 && opensAtStart && closesAtEnd) return Band(last.from, first.to)
                val one = list.maxBy { it.to - it.from }
                val startOpen = one.from == windowStartMs
                val endOpen = one.to == windowEndMs
                return Band(
                    if (startOpen) null else one.from,
                    if (endOpen) null else one.to,
                    allDay = startOpen && endOpen,
                )
            }
            return bandOf(stretches.filter { it.rising }) to bandOf(stretches.filter { !it.rising })
        }

        val golden6Crossings = crossingsAt(SunAltitude.GOLDEN_UPPER)
        val minus4Crossings = crossingsAt(SunAltitude.BLUE_GOLDEN_BOUNDARY)
        val civilCrossings = crossingsAt(SunAltitude.CIVIL)
        val (goldenMorning, goldenEvening) = zone(SunAltitude.BLUE_GOLDEN_BOUNDARY, SunAltitude.GOLDEN_UPPER, minus4Crossings, golden6Crossings)
        val (blueMorning, blueEvening) = zone(SunAltitude.CIVIL, SunAltitude.BLUE_GOLDEN_BOUNDARY, civilCrossings, minus4Crossings)

        return SolarDay(
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            sunriseMs = sunrise,
            sunsetMs = sunset,
            solarNoonMs = noon,
            noonAltitudeDeg = noon?.let { alt(it) },
            sunriseAzimuthDeg = sunrise?.let { SolarPosition.horizontal(it, latDeg, lonDeg).azimuthDeg },
            sunsetAzimuthDeg = sunset?.let { SolarPosition.horizontal(it, latDeg, lonDeg).azimuthDeg },
            civil = band(SunAltitude.CIVIL, civilCrossings),
            nautical = band(SunAltitude.NAUTICAL),
            astronomical = band(SunAltitude.ASTRONOMICAL),
            goldenMorning = goldenMorning,
            goldenEvening = goldenEvening,
            blueMorning = blueMorning,
            blueEvening = blueEvening,
            daylightMs = daylight.coerceIn(0L, window),
            dayType = dayType,
        )
    }

    /** Innermost daylight band containing [ms], matching the nested bands drawn on the day plot. */
    fun slice(day: SolarDay, ms: Long): DaySlice = when (day.dayType) {
        DayType.PolarDay -> DaySlice.PolarDay
        DayType.PolarNight -> DaySlice.PolarNight
        DayType.Normal -> when {
            contains(day.goldenMorning, ms, day) || contains(day.goldenEvening, ms, day) -> DaySlice.Golden
            contains(day.blueMorning, ms, day) || contains(day.blueEvening, ms, day) -> DaySlice.Blue
            inDaylight(day, ms) -> DaySlice.Day
            contains(day.civil, ms, day) -> DaySlice.Civil
            contains(day.nautical, ms, day) -> DaySlice.Nautical
            contains(day.astronomical, ms, day) -> DaySlice.Astronomical
            else -> DaySlice.Night
        }
    }

    private fun contains(band: Band?, ms: Long, day: SolarDay): Boolean {
        if (band == null) return false
        val start = band.startMs
        val end = band.endMs
        if (start == null && end == null) return band.allDay
        val a = start ?: day.windowStartMs
        val b = end ?: day.windowEndMs
        return if (b >= a) ms in a..b else ms >= a || ms <= b
    }

    private fun inDaylight(day: SolarDay, ms: Long): Boolean {
        val rise = day.sunriseMs
        val set = day.sunsetMs
        return when {
            rise != null && set != null -> if (set >= rise) ms in rise..set else ms >= rise || ms <= set
            rise != null -> ms >= rise
            set != null -> ms <= set
            else -> false
        }
    }

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
