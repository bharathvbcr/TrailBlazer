package com.trailblazer.core.plot

import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.DEG
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** One finite sample, addressed by its index in the original series (gaps keep their slots). */
data class PlotSample(val index: Int, val value: Double)

/**
 * Vertical scale for a series. [count] is the original length, including gaps.
 * [lo] and [hi] are the drawn range and satisfy `hi > lo`, both finite.
 */
data class PlotFrame(
    val samples: List<PlotSample>,
    val count: Int,
    val lo: Double,
    val hi: Double,
)

/** Inclusive low/high for one slot of a range chart. [low] is never above [high]. */
data class RangeSample(val index: Int, val low: Double, val high: Double)

/** A body on the local sky, azimuth clockwise from north, elevation above the horizon. */
data class SkyBody(val azimuthDeg: Double, val elevationDeg: Double, val used: Boolean, val label: String)

/** Projected sky mark in a unit disk: +x east, +y north, index into the input list. */
data class PolarMark(val x: Double, val y: Double, val index: Int)

/** East/north metres on a local tangent plane. */
data class PlanePoint(val eastM: Double, val northM: Double)

/**
 * Geometry shared by the sparkline, range bars, day scrub and scale bar.
 * Non-finite inputs are gaps or a refusal, never a scale.
 */
object SeriesPlot {
    /**
     * Finite samples in original index order. Null, NaN and infinities are gaps.
     * Returns null when fewer than two finite samples remain, or when the span overflows.
     * [minSpan] widens a flat series; non-positive and non-finite spans are ignored.
     */
    fun frame(values: List<Double?>, minSpan: Double = 0.0): PlotFrame? {
        if (values.size < 2) return null
        val samples = ArrayList<PlotSample>(values.size)
        var lo0 = Double.POSITIVE_INFINITY
        var hi0 = Double.NEGATIVE_INFINITY
        for (i in values.indices) {
            val v = values[i] ?: continue
            if (!v.isFinite()) continue
            samples += PlotSample(i, v)
            if (v < lo0) lo0 = v
            if (v > hi0) hi0 = v
        }
        if (samples.size < 2) return null
        val span = hi0 - lo0
        if (!span.isFinite()) return null
        val requested = if (minSpan.isFinite() && minSpan > 0.0) minSpan else 0.0
        val pad = ((requested - span).coerceAtLeast(0.0)) / 2.0
        val lo = lo0 - pad
        val hi = hi0 + pad
        if (!lo.isFinite() || !hi.isFinite() || !(hi > lo)) return null
        return PlotFrame(samples, values.size, lo, hi)
    }

    /** Fraction of the way up the plot, in [0, 1]. Anything that is not a real position is the midline. */
    fun yFraction(value: Double, lo: Double, hi: Double): Double {
        if (!value.isFinite() || !lo.isFinite() || !hi.isFinite()) return 0.5
        val span = hi - lo
        if (!span.isFinite() || span <= 0.0) return 0.5
        val y = (value - lo) / span
        if (!y.isFinite()) return 0.5
        return y.coerceIn(0.0, 1.0)
    }

    /** Slot in a series of [count] for a horizontal fraction. Out of range and non-finite fractions clamp. */
    fun scrubIndex(xFraction: Double, count: Int): Int {
        if (count <= 1) return 0
        val f = if (xFraction.isFinite()) xFraction.coerceIn(0.0, 1.0) else 0.0
        return (f * (count - 1)).toInt().coerceIn(0, count - 1)
    }

    /** Epoch inside [startMs, endMs) for a horizontal fraction. A bad window returns [startMs]. */
    fun fractionToEpoch(fraction: Double, startMs: Long, endMs: Long): Long {
        if (endMs <= startMs) return startMs
        val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        val span = (endMs - startMs).toDouble()
        if (!span.isFinite() || span <= 0.0) return startMs
        val delta = f * span
        if (!delta.isFinite()) return startMs
        val ms = startMs + delta.toLong()
        val last = endMs - 1
        return when {
            ms < startMs -> startMs
            ms > last -> last
            else -> ms
        }
    }

    /**
     * A 1-2-5 length that is at most 1.5× a positive finite [target].
     * Zero, negative, non-finite and underflowing targets return 1 so a scale bar can still draw.
     */
    fun niceLength(target: Double): Double {
        if (!target.isFinite() || target <= 0.0) return 1.0
        val log = log10(target)
        if (!log.isFinite()) return 1.0
        val mag = 10.0.pow(floor(log))
        if (!mag.isFinite() || mag <= 0.0) return 1.0
        var pick = mag
        val limit = target * 1.5
        for (c in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) {
            val n = c * mag
            if (n.isFinite() && n > 0.0 && n <= limit) pick = n
        }
        return if (pick.isFinite() && pick > 0.0) pick else 1.0
    }

    /**
     * Per-bucket mean, one entry per bucket. Empty buckets and non-finite means stay null
     * so a later chart keeps the distance or time axis honest.
     */
    fun alignedMeans(sums: DoubleArray, counts: IntArray): List<Double?> {
        require(sums.size == counts.size) { "sums (${sums.size}) and counts (${counts.size}) differ" }
        return List(counts.size) { i ->
            val n = counts[i]
            if (n <= 0) null
            else {
                val v = sums[i] / n
                if (v.isFinite()) v else null
            }
        }
    }

    /** Paired lows and highs. A slot is skipped unless both ends are finite; a reversed pair is swapped. */
    fun ranges(lows: List<Double?>, highs: List<Double?>, minSpan: Double = 0.0): Pair<PlotFrame, List<RangeSample>>? {
        val n = max(lows.size, highs.size)
        if (n == 0) return null
        val samples = ArrayList<RangeSample>(n)
        val ends = ArrayList<Double?>(n * 2)
        for (i in 0 until n) {
            val lo = lows.getOrNull(i)
            val hi = highs.getOrNull(i)
            if (lo == null || hi == null || !lo.isFinite() || !hi.isFinite()) {
                ends += null
                ends += null
                continue
            }
            val a = min(lo, hi)
            val b = max(lo, hi)
            samples += RangeSample(i, a, b)
            ends += a
            ends += b
        }
        if (samples.isEmpty()) return null
        val frame = frame(ends, minSpan) ?: return null
        return frame to samples
    }
}

/** North-up local plane. Longitude is wrapped so a track across the antimeridian stays a short hop. */
object LocalPlane {
    fun project(points: List<LatLon>): List<PlanePoint> {
        if (points.isEmpty()) return emptyList()
        var latSum = 0.0
        for (p in points) latSum += p.lat
        val lat0 = latSum / points.size
        val lon0 = points.first().lon
        val kx = cos(lat0 * DEG) * Geo.EARTH_RADIUS_M * DEG
        val ky = Geo.EARTH_RADIUS_M * DEG
        return points.map { p ->
            PlanePoint(wrapLon(p.lon - lon0) * kx, (p.lat - lat0) * ky)
        }
    }

    private fun wrapLon(d: Double): Double {
        val w = ((d + 540.0) % 360.0) - 180.0
        return if (w.isFinite()) w else 0.0
    }
}

/** Unit-disk sky: zenith at the origin, horizon on the rim, north up. Below-horizon bodies are omitted. */
object SkyPolar {
    fun project(azimuthDeg: Double, elevationDeg: Double): Pair<Double, Double>? {
        if (!azimuthDeg.isFinite() || !elevationDeg.isFinite()) return null
        if (elevationDeg < 0.0 || elevationDeg > 90.0) return null
        val r = cos(elevationDeg * DEG)
        if (!r.isFinite()) return null
        val a = azimuthDeg * DEG
        val x = r * sin(a)
        val y = r * cos(a)
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    fun layout(bodies: List<SkyBody>): List<PolarMark> {
        val out = ArrayList<PolarMark>(bodies.size)
        for (i in bodies.indices) {
            val p = project(bodies[i].azimuthDeg, bodies[i].elevationDeg) ?: continue
            out += PolarMark(p.first, p.second, i)
        }
        return out
    }

    fun nearest(marks: List<PolarMark>, x: Double, y: Double, maxDist: Double): PolarMark? {
        if (marks.isEmpty() || !x.isFinite() || !y.isFinite() || !maxDist.isFinite() || maxDist < 0.0) return null
        var best: PolarMark? = null
        var bestD = maxDist * maxDist
        for (m in marks) {
            val dx = m.x - x
            val dy = m.y - y
            val d = dx * dx + dy * dy
            if (d <= bestD) {
                best = m
                bestD = d
            }
        }
        return best
    }
}
