package com.trailblazer.core.trip

import com.trailblazer.core.geo.ElevationSource
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.track.ElevationClimb
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** One point along a planned route: how far from the start, where, and how high (null = no elevation data there). */
data class ProfileSample(val distanceM: Double, val position: LatLon, val elevationM: Double?)

enum class ExtremumKind { Peak, Valley }

/** A peak or valley of the profile: an index into [ElevationProfile.samples]. */
data class Extremum(val index: Int, val kind: ExtremumKind, val elevationM: Double)

/** A stretch of the route steeper than the warning grade. [gradePercent] is signed: positive climbs, negative descends. */
data class SteepSection(val startM: Double, val endM: Double, val gradePercent: Double)

/** Climb, descent and range of a profile. Gain and loss count only the stretches that have data. */
data class ElevationStats(
    val gainM: Double,
    val lossM: Double,
    val minM: Double?,
    val maxM: Double?,
    val knownSamples: Int,
    val totalSamples: Int,
) {
    val hasData: Boolean get() = knownSamples > 0

    /** True when some of the route has no elevation data: gain and loss are then a lower bound for the whole route. */
    val hasGaps: Boolean get() = knownSamples < totalSamples
}

/**
 * Elevation along a planned route, sampled at [spacingM] (or wider, see [spacingWidened]). Legs are joined, so
 * `distanceM` runs continuously from the first stop; [stopDistancesM] is where each stop falls on that axis.
 */
class ElevationProfile(
    val samples: List<ProfileSample>,
    val stopDistancesM: List<Double>,
    val requestedSpacingM: Double,
    val spacingM: Double,
) {
    val totalDistanceM: Double get() = samples.lastOrNull()?.distanceM ?: 0.0

    /** True when the route was too long for the sample cap at the requested spacing, so it is sampled more coarsely. */
    val spacingWidened: Boolean get() = spacingM > requestedSpacingM

    /**
     * Gain and loss through the shared [ElevationClimb] hysteresis filter (default 3 m, as for recorded tracks). A
     * stretch without data starts a new run, so no climb is invented across it.
     */
    fun stats(hysteresisM: Double = ElevationClimb.DEFAULT_HYSTERESIS_M): ElevationStats {
        val climb = ElevationClimb(hysteresisM)
        var known = 0
        for (s in samples) {
            val e = s.elevationM
            if (e == null) {
                climb.breakRun()
            } else {
                climb.add(e)
                known++
            }
        }
        return ElevationStats(climb.gainM, climb.lossM, climb.minM, climb.maxM, known, samples.size)
    }

    /**
     * Peaks and valleys that stand out: a turning point counts once the profile has moved [minProminenceM] (or a tenth
     * of the whole range, if that is more) away from it, so DEM noise on a slope makes no markers. The highest and lowest
     * known points are always marked. At most [maxMarkers] come back, the biggest swings first, in route order.
     */
    fun extrema(minProminenceM: Double = 10.0, maxMarkers: Int = 12): List<Extremum> {
        val known = samples.mapNotNull { it.elevationM }
        if (known.size < 2 || maxMarkers < 1) return emptyList()
        val range = known.max() - known.min()
        if (range < minProminenceM) return emptyList()
        val threshold = max(minProminenceM, range / 10.0)

        val found = HashMap<Int, Extremum>()
        var runStart = 0
        while (runStart < samples.size) {
            if (samples[runStart].elevationM == null) {
                runStart++
                continue
            }
            var runEnd = runStart
            while (runEnd + 1 < samples.size && samples[runEnd + 1].elevationM != null) runEnd++
            zigzag(runStart, runEnd, threshold).forEach { found[it.index] = it }
            runStart = runEnd + 1
        }
        var hi = -1
        var lo = -1
        for (i in samples.indices) {
            val e = samples[i].elevationM ?: continue
            if (hi < 0 || e > samples[hi].elevationM!!) hi = i
            if (lo < 0 || e < samples[lo].elevationM!!) lo = i
        }
        found[hi] = Extremum(hi, ExtremumKind.Peak, samples[hi].elevationM!!)
        found[lo] = Extremum(lo, ExtremumKind.Valley, samples[lo].elevationM!!)

        val ordered = found.values.sortedBy { it.index }
        if (ordered.size <= maxMarkers) return ordered
        // Keep the global high and low, then the markers with the biggest swing to a neighbouring marker.
        val swing = ordered.mapIndexed { i, e ->
            val before = ordered.getOrNull(i - 1)?.let { abs(e.elevationM - it.elevationM) } ?: 0.0
            val after = ordered.getOrNull(i + 1)?.let { abs(e.elevationM - it.elevationM) } ?: 0.0
            max(before, after)
        }
        val keep = HashSet<Int>()
        keep += hi
        keep += lo
        ordered.indices.sortedByDescending { swing[it] }.forEach { if (keep.size < maxMarkers) keep += ordered[it].index }
        return ordered.filter { it.index in keep }
    }

    /** Turning points of one run of known samples, with the classic zig-zag rule at [threshold] metres. */
    private fun zigzag(start: Int, end: Int, threshold: Double): List<Extremum> {
        fun v(i: Int) = samples[i].elevationM!!
        val out = ArrayList<Extremum>()
        var state = 0
        var maxI = start
        var minI = start
        for (i in start + 1..end) {
            val x = v(i)
            when (state) {
                0 -> {
                    if (x > v(maxI)) maxI = i
                    if (x < v(minI)) minI = i
                    if (x - v(minI) >= threshold) {
                        out += Extremum(minI, ExtremumKind.Valley, v(minI))
                        state = 1
                        maxI = i
                    } else if (v(maxI) - x >= threshold) {
                        out += Extremum(maxI, ExtremumKind.Peak, v(maxI))
                        state = -1
                        minI = i
                    }
                }
                1 -> if (x > v(maxI)) maxI = i else if (v(maxI) - x >= threshold) {
                    out += Extremum(maxI, ExtremumKind.Peak, v(maxI))
                    state = -1
                    minI = i
                }
                else -> if (x < v(minI)) minI = i else if (x - v(minI) >= threshold) {
                    out += Extremum(minI, ExtremumKind.Valley, v(minI))
                    state = 1
                    maxI = i
                }
            }
        }
        if (state == 1) out += Extremum(maxI, ExtremumKind.Peak, v(maxI))
        if (state == -1) out += Extremum(minI, ExtremumKind.Valley, v(minI))
        return out
    }

    /**
     * Stretches steeper than [thresholdPercent], measured over at least [windowM] of route so that post-to-post DEM
     * noise (a few metres over 30–90 m) is not mistaken for a cliff. Overlapping steep windows in the same direction are
     * merged; a window is never measured across a stretch without data. DEM posts smooth steep ground, so these are
     * lower bounds on real gradients.
     */
    fun steepSections(thresholdPercent: Double = 25.0, windowM: Double = 150.0): List<SteepSection> {
        val out = ArrayList<SteepSection>()
        var j = 0
        for (i in samples.indices) {
            val ei = samples[i].elevationM ?: continue
            if (j <= i) j = i + 1
            while (j < samples.size && samples[j].distanceM - samples[i].distanceM < windowM) j++
            if (j >= samples.size) break
            if (!allKnown(i, j)) continue
            val run = samples[j].distanceM - samples[i].distanceM
            val grade = 100.0 * (samples[j].elevationM!! - ei) / run
            if (abs(grade) < thresholdPercent) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.endM >= samples[i].distanceM && (prev.gradePercent > 0) == (grade > 0)) {
                out[out.lastIndex] = SteepSection(prev.startM, samples[j].distanceM, if (abs(grade) > abs(prev.gradePercent)) grade else prev.gradePercent)
            } else {
                out += SteepSection(samples[i].distanceM, samples[j].distanceM, grade)
            }
        }
        return out
    }

    private fun allKnown(from: Int, to: Int): Boolean {
        for (k in from..to) if (samples[k].elevationM == null) return false
        return true
    }
}

object ElevationProfiler {
    /**
     * Default bound on samples per route, whatever its length: a 50-stop trip across a continent must stay cheap to
     * plot. The bound is `max(maxSamples, 2 x stops)`, because every stop is always a sample and each leg needs at
     * least one more. A trip (at most 50 stops, see `TripRules`) therefore never exceeds this; a caller passing more
     * stops than half the bound gets two samples per stop at most.
     */
    const val MAX_SAMPLES = 2_000

    /**
     * Samples [source] along the great-circle legs between [stops], every [spacingM] metres (30 m for 1 arc-second data,
     * 90 m for 3), with each stop included exactly. If that would take more than `max(maxSamples, 2 x stops)` samples
     * the spacing is widened until it fits, and the result says so ([ElevationProfile.spacingWidened]); the bound never
     * silently cuts the route.
     * Returns null for fewer than two stops or a non-positive spacing. [checkCancelled] is called about every hundred
     * samples and may throw to stop the work.
     */
    fun sample(
        stops: List<LatLon>,
        source: ElevationSource,
        spacingM: Double,
        maxSamples: Int = MAX_SAMPLES,
        checkCancelled: () -> Unit = {},
    ): ElevationProfile? {
        if (stops.size < 2 || !spacingM.isFinite() || spacingM <= 0.0) return null
        val legCount = stops.size - 1
        val lengths = DoubleArray(legCount) { Geo.distanceM(stops[it], stops[it + 1]) }
        val total = lengths.sum()
        val cap = maxSamples.coerceAtLeast(2 * stops.size)
        // ceil() adds at most one interval per leg, so spacing >= total / (cap - 1 - legs) keeps the count under the cap.
        val widest = if (total > 0.0) total / (cap - 1 - legCount) else spacingM
        val spacing = max(spacingM, widest)

        val samples = ArrayList<ProfileSample>()
        val stopDistances = ArrayList<Double>(stops.size)
        var sinceCheck = 0
        fun add(distance: Double, p: LatLon) {
            if (++sinceCheck >= CHECK_EVERY) {
                sinceCheck = 0
                checkCancelled()
            }
            samples += ProfileSample(distance, p, source.elevationM(p)?.takeIf { it.isFinite() })
        }
        var cumulative = 0.0
        stopDistances += 0.0
        add(0.0, stops[0])
        for (leg in 0 until legCount) {
            val length = lengths[leg]
            if (length <= 0.0) {
                stopDistances += cumulative
                continue
            }
            val intervals = ceil(length / spacing).toInt().coerceAtLeast(1)
            for (k in 1..intervals) {
                val f = k.toDouble() / intervals
                add(cumulative + f * length, Geo.intermediate(stops[leg], stops[leg + 1], f))
            }
            cumulative += length
            stopDistances += cumulative
        }
        return ElevationProfile(samples, stopDistances, spacingM, spacing)
    }

    private const val CHECK_EVERY = 100
}
