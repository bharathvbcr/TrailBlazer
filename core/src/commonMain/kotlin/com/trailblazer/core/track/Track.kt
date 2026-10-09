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
    private class SegmentHeap {
        private var items = arrayOfNulls<Seg>(64)
        var size = 0
            private set

        fun add(seg: Seg) {
            if (size == items.size) {
                items = items.copyOf(items.size * 2)
            }
            var i = size++
            items[i] = seg
            while (i > 0) {
                val parent = (i - 1) shr 1
                if (items[i]!!.maxD <= items[parent]!!.maxD) break
                val tmp = items[i]; items[i] = items[parent]; items[parent] = tmp
                i = parent
            }
        }

        fun poll(): Seg? {
            if (size == 0) return null
            val root = items[0]
            val last = items[--size]
            items[size] = null
            if (size > 0) {
                items[0] = last
                var i = 0
                while (true) {
                    val left = (i shl 1) + 1
                    if (left >= size) break
                    val right = left + 1
                    val largest = if (right < size && items[right]!!.maxD > items[left]!!.maxD) right else left
                    if (items[i]!!.maxD >= items[largest]!!.maxD) break
                    val tmp = items[i]; items[i] = items[largest]; items[largest] = tmp
                    i = largest
                }
            }
            return root
        }
    }

    private data class Seg(val s: Int, val e: Int, val idx: Int, val maxD: Double)

    fun <T> simplify(points: List<T>, toleranceM: Double, position: (T) -> LatLon): List<T> {
        if (points.size < 3 || toleranceM <= 0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            val res = findMax(points, s, e, position) ?: continue
            val (idx, maxD) = res
            if (maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Simplifies until at most [maxPoints] remain, in a single ranked pass. */
    fun <T> simplifyToMax(points: List<T>, maxPoints: Int, position: (T) -> LatLon): List<T> {
        require(maxPoints >= 2)
        if (points.size <= maxPoints) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        var keptCount = 2

        val heap = SegmentHeap()
        findMax(points, 0, points.size - 1, position)?.let { (idx, maxD) ->
            if (maxD > 0.0) heap.add(Seg(0, points.size - 1, idx, maxD))
        }

        while (keptCount < maxPoints && heap.size > 0) {
            val seg = heap.poll() ?: break
            if (seg.maxD <= 0.0) break
            keep[seg.idx] = true
            keptCount++

            if (seg.idx > seg.s + 1) {
                findMax(points, seg.s, seg.idx, position)?.let { (idx, maxD) ->
                    if (maxD > 0.0) heap.add(Seg(seg.s, seg.idx, idx, maxD))
                }
            }
            if (seg.e > seg.idx + 1) {
                findMax(points, seg.idx, seg.e, position)?.let { (idx, maxD) ->
                    if (maxD > 0.0) heap.add(Seg(seg.idx, seg.e, idx, maxD))
                }
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun <T> findMax(points: List<T>, s: Int, e: Int, position: (T) -> LatLon): Pair<Int, Double>? {
        if (e <= s + 1) return null
        val a = position(points[s])
        val b = position(points[e])
        val k = cos(a.lat * DEG) * Geo.EARTH_RADIUS_M * DEG
        val kLat = Geo.EARTH_RADIUS_M * DEG
        val bx = wrapLon(b.lon - a.lon) * k
        val by = (b.lat - a.lat) * kLat
        val len2 = bx * bx + by * by
        var maxD = -1.0
        var idx = -1
        for (i in s + 1 until e) {
            val p = position(points[i])
            val px = wrapLon(p.lon - a.lon) * k
            val py = (p.lat - a.lat) * kLat
            val d = if (len2 == 0.0) {
                kotlin.math.hypot(px, py)
            } else {
                val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
                kotlin.math.hypot(px - t * bx, py - t * by)
            }
            if (d > maxD) {
                maxD = d
                idx = i
            }
        }
        return if (idx >= 0) idx to maxD else null
    }

    private fun wrapLon(d: Double): Double {
        val w = ((d + 540.0) % 360.0) - 180.0
        return if (w.isFinite()) w else 0.0
    }

    private fun perpendicularM(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(a.lat * DEG) * Geo.EARTH_RADIUS_M * DEG
        val kLat = Geo.EARTH_RADIUS_M * DEG
        val bx = wrapLon(b.lon - a.lon) * k
        val by = (b.lat - a.lat) * kLat
        val px = wrapLon(p.lon - a.lon) * k
        val py = (p.lat - a.lat) * kLat
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return kotlin.math.hypot(px, py)
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - t * bx, py - t * by)
    }
}
