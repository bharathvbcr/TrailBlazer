package com.trailblazer.core.track

import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.DEG
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** One recorded fix. Optional fields are null when the device did not report them. */
data class TrackPoint(
    val epochMs: Long,
    val position: LatLon,
    val elevationM: Double? = null,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
)

/**
 * Streaming track statistics: O(1) memory per update, so a 100 000-point track costs the same to
 * summarize as a 10-point one. Elevation gain and loss use a 3 m hysteresis band so GPS/baro noise
 * does not accumulate as fake climbing.
 *
 * A step is moving time when its speed reaches [movingThresholdMps]. The device-reported speed is an instant, so it
 * only speaks for a short step; a step longer than [instantSpeedMaxGapMs] (sparse expedition fixes, or a gap while
 * the receiver slept, Battery Saver withheld fixes, recording was paused or the process died) is judged by its
 * average speed, distance over time. Distance is the same either way: the straight line between the two fixes.
 */
class TrackStats(
    private val hysteresisM: Double = 3.0,
    private val movingThresholdMps: Double = 0.5,
    private val instantSpeedMaxGapMs: Long = 30_000,
) {
    var points = 0; private set
    var distanceM = 0.0; private set
    var movingMs = 0L; private set
    var gainM = 0.0; private set
    var lossM = 0.0; private set
    var maxSpeedMps: Double? = null; private set
    var minElevationM: Double? = null; private set
    var maxElevationM: Double? = null; private set
    var startMs: Long? = null; private set
    var endMs: Long? = null; private set

    private var last: TrackPoint? = null
    private var elevRef: Double? = null

    val elapsedMs: Long get() = if (startMs != null && endMs != null) endMs!! - startMs!! else 0L

    fun add(p: TrackPoint) {
        points++
        if (startMs == null) startMs = p.epochMs
        endMs = p.epochMs
        val prev = last
        if (prev != null && p.epochMs > prev.epochMs) {
            val d = Geo.distanceM(prev.position, p.position)
            val dt = p.epochMs - prev.epochMs
            distanceM += d
            val derived = d / (dt / 1000.0)
            val speed = if (dt > instantSpeedMaxGapMs) derived else p.speedMps?.takeIf { it.isFinite() && it >= 0 } ?: derived
            if (speed >= movingThresholdMps) movingMs += dt
            // Only trust device-reported speed for the maximum; derived speed spikes on position jumps.
            p.speedMps?.takeIf { it.isFinite() && it >= 0 }?.let { maxSpeedMps = max(maxSpeedMps ?: 0.0, it) }
        } else if (prev == null) {
            p.speedMps?.takeIf { it.isFinite() && it >= 0 }?.let { maxSpeedMps = it }
        }
        p.elevationM?.takeIf { it.isFinite() }?.let { e ->
            minElevationM = minOf(minElevationM ?: e, e)
            maxElevationM = maxOf(maxElevationM ?: e, e)
            val ref = elevRef
            if (ref == null) {
                elevRef = e
            } else {
                val delta = e - ref
                if (abs(delta) >= hysteresisM) {
                    if (delta > 0) gainM += delta else lossM -= delta
                    elevRef = e
                }
            }
        }
        last = p
    }

    val averageMovingSpeedMps: Double? get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else null
}

/**
 * Iterative Douglas–Peucker simplification (no recursion, so very long tracks cannot overflow the stack).
 * Distances use a local equirectangular projection, accurate for the short segments of a track.
 */
object DouglasPeucker {
    fun <T> simplify(points: List<T>, toleranceM: Double, position: (T) -> LatLon): List<T> {
        if (points.size < 3 || toleranceM <= 0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            if (e <= s + 1) continue
            val a = position(points[s])
            val b = position(points[e])
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = perpendicularM(position(points[i]), a, b)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Simplifies until at most [maxPoints] remain, by searching for the smallest sufficient tolerance. */
    fun <T> simplifyToMax(points: List<T>, maxPoints: Int, position: (T) -> LatLon): List<T> {
        require(maxPoints >= 2)
        if (points.size <= maxPoints) return points
        var lo = 0.0
        var hi = 1.0
        var best = simplify(points, hi, position)
        while (best.size > maxPoints && hi < 1e7) {
            lo = hi; hi *= 4; best = simplify(points, hi, position)
        }
        repeat(20) {
            val mid = (lo + hi) / 2
            val r = simplify(points, mid, position)
            if (r.size <= maxPoints) { hi = mid; best = r } else lo = mid
        }
        return best
    }

    private fun perpendicularM(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(a.lat * DEG) * Geo.EARTH_RADIUS_M * DEG
        val kLat = Geo.EARTH_RADIUS_M * DEG
        val ax = 0.0
        val ay = 0.0
        val bx = (b.lon - a.lon) * k
        val by = (b.lat - a.lat) * kLat
        val px = (p.lon - a.lon) * k
        val py = (p.lat - a.lat) * kLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return kotlin.math.hypot(px, py)
        val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - t * dx, py - t * dy)
    }
}
