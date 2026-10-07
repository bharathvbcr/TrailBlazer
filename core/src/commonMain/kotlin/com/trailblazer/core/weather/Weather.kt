package com.trailblazer.core.weather

import com.trailblazer.core.atmo.Isa
import kotlin.math.abs
import kotlin.math.roundToLong

/** A stored barometer reading. [elevationM] is the GPS elevation at the time, when a fix was available. */
data class PressureSample(val epochMs: Long, val stationHpa: Double, val elevationM: Double?)

/** Which pressure series the trend was computed from. */
enum class TrendBasis {
    /** Elevation stayed within [PressureTrend.STATIONARY_SPAN_M]; raw station pressure is the most precise signal. */
    Station,
    /** Elevation changed, so each sample was reduced to sea level with its own elevation. GPS height error lowers confidence. */
    SeaLevel,
    /** No elevation data: correct only if you stayed at the same elevation. */
    StationElevationUnknown,
}

/** Pressure tendency on one scale, in hPa per 3 h (thresholds after the UK Met Office wording). */
enum class Tendency { FallingVeryRapidly, FallingQuickly, Falling, FallingSlowly, Steady, RisingSlowly, Rising, RisingQuickly, RisingVeryRapidly }

sealed interface TrendResult {
    data class Insufficient(val spanMinutes: Long, val samples: Int) : TrendResult
    data class Trend(
        val hpaPer3h: Double,
        val tendency: Tendency,
        val basis: TrendBasis,
        val spanMinutes: Long,
        val samples: Int,
        /** Latest value of the series the trend used (station or sea level hPa). */
        val latestHpa: Double,
    ) : TrendResult
}

object PressureTrend {
    const val MIN_SPAN_MINUTES = 60L
    const val MIN_SAMPLES = 6
    const val WINDOW_MS = 3 * 3_600_000L
    const val STATIONARY_SPAN_M = 20.0

    /**
     * Least-squares slope over the samples inside the last 3 h (ending at [nowMs]), scaled to hPa/3 h
     * by the actual time base, so a 2 h window is not divided by 3.
     */
    fun compute(samples: List<PressureSample>, nowMs: Long): TrendResult {
        val window = samples
            .filter { it.epochMs in (nowMs - WINDOW_MS)..nowMs && it.stationHpa.isFinite() && it.stationHpa in 300.0..1100.0 }
            .sortedBy { it.epochMs }
        val span = if (window.size >= 2) (window.last().epochMs - window.first().epochMs) / 60_000 else 0
        if (window.size < MIN_SAMPLES || span < MIN_SPAN_MINUTES) return TrendResult.Insufficient(span, window.size)

        val elevations = window.mapNotNull { it.elevationM?.takeIf { e -> e.isFinite() } }
        val allHaveElevation = elevations.size == window.size
        val elevSpan = if (elevations.isEmpty()) 0.0 else elevations.max() - elevations.min()

        val (basis, series) = when {
            allHaveElevation && elevSpan > STATIONARY_SPAN_M -> {
                val reduced = window.map { s -> Isa.qnhHpa(s.stationHpa, s.elevationM!!) }
                if (reduced.any { it == null }) return TrendResult.Insufficient(span, window.size)
                TrendBasis.SeaLevel to reduced.map { it!! }
            }
            elevations.isEmpty() -> TrendBasis.StationElevationUnknown to window.map { it.stationHpa }
            elevSpan > STATIONARY_SPAN_M -> {
                // Mixed: only the samples with elevation can be compared fairly.
                val withElev = window.filter { it.elevationM != null }
                val withSpan = (withElev.last().epochMs - withElev.first().epochMs) / 60_000
                if (withElev.size < MIN_SAMPLES || withSpan < MIN_SPAN_MINUTES) return TrendResult.Insufficient(withSpan, withElev.size)
                return compute(withElev, nowMs)
            }
            else -> TrendBasis.Station to window.map { it.stationHpa }
        }
        val t0 = window.first().epochMs
        val xs = window.map { (it.epochMs - t0) / 3_600_000.0 }
        val slopePerHour = slope(xs, series)
        val per3h = slopePerHour * 3.0
        return TrendResult.Trend(per3h, tendency(per3h), basis, span, window.size, series.last())
    }

    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            num += (x[i] - mx) * (y[i] - my)
            den += (x[i] - mx) * (x[i] - mx)
        }
        return if (den == 0.0) 0.0 else num / den
    }

    /**
     * One scale for every label in the app. The Met Office "steady" band is ±0.1 hPa/3 h; phone
     * barometers drift more than that, so this app treats |Δ| < 0.5 as steady.
     */
    fun tendency(hpaPer3h: Double): Tendency {
        val a = abs(hpaPer3h)
        val rising = hpaPer3h > 0
        return when {
            a < 0.5 -> Tendency.Steady
            a < 1.6 -> if (rising) Tendency.RisingSlowly else Tendency.FallingSlowly
            a < 3.6 -> if (rising) Tendency.Rising else Tendency.Falling
            a <= 6.0 -> if (rising) Tendency.RisingQuickly else Tendency.FallingQuickly
            else -> if (rising) Tendency.RisingVeryRapidly else Tendency.FallingVeryRapidly
        }
    }
}

/**
 * Storm warning with hysteresis: turns on at a fall of 4 hPa/3 h or more and only turns off once the
 * fall eases below 2 hPa/3 h, so it does not flicker around a single threshold.
 */
object StormAlert {
    const val ON_HPA_PER_3H = -4.0
    const val OFF_HPA_PER_3H = -2.0

    fun next(active: Boolean, trend: TrendResult): Boolean {
        if (trend !is TrendResult.Trend) return false
        return if (active) trend.hpaPer3h < OFF_HPA_PER_3H else trend.hpaPer3h <= ON_HPA_PER_3H
    }
}

enum class ZambrettiForecast(val letter: Char) {
    SettledFine('A'), FineWeather('B'), BecomingFine('C'), FineBecomingLessSettled('D'), FinePossibleShowers('E'),
    FairlyFineImproving('F'), FairlyFinePossibleShowersEarly('G'), FairlyFineShoweryLater('H'), ShoweryEarlyImproving('I'),
    ChangeableMending('J'), FairlyFineShowersLikely('K'), RatherUnsettledClearingLater('L'), UnsettledProbablyImproving('M'),
    ShoweryBrightIntervals('N'), ShoweryBecomingLessSettled('O'), ChangeableSomeRain('P'), UnsettledShortFineIntervals('Q'),
    UnsettledRainLater('R'), UnsettledSomeRain('S'), MostlyVeryUnsettled('T'), OccasionalRainWorsening('U'),
    RainAtTimesVeryUnsettled('V'), RainAtFrequentIntervals('W'), RainVeryUnsettled('X'), StormyMayImprove('Y'), StormyMuchRain('Z'),
}

/**
 * Negretti & Zambra "Zambretti" forecaster from sea-level pressure and its 3-hour trend. Needs real
 * sea-level pressure (station pressure plus a known elevation); returns null outside 950–1050 hPa where
 * the method is undefined.
 */
object Zambretti {
    private val falling = "ABDHORUXZ"
    private val steady = "ABEKNPSWXZ"
    private val rising = "ABCFGIJLMMQTY"

    fun forecast(seaLevelHpa: Double, hpaPer3h: Double): ZambrettiForecast? {
        if (!seaLevelHpa.isFinite() || seaLevelHpa !in 950.0..1050.0 || !hpaPer3h.isFinite()) return null
        val t = PressureTrend.tendency(hpaPer3h)
        val (z, table, base) = when {
            t == Tendency.Steady -> Triple(144 - 0.13 * seaLevelHpa, steady, 10)
            hpaPer3h < 0 -> Triple(127 - 0.12 * seaLevelHpa, falling, 1)
            else -> Triple(185 - 0.16 * seaLevelHpa, rising, 20)
        }
        val idx = (z.roundToLong().toInt() - base).coerceIn(0, table.length - 1)
        val letter = table[idx]
        return ZambrettiForecast.entries.first { it.letter == letter }
    }
}
