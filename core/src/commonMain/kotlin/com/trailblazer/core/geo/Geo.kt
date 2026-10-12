package com.trailblazer.core.geo

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.RAD
import com.trailblazer.core.math.mod360
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A validated WGS-84 position. Construct with [of] to get null instead of an exception for bad input. */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon.isFinite() && lon in -180.0..180.0) { "longitude out of range: $lon" }
    }

    companion object {
        /** Validates latitude and wraps longitude into [-180, 180]; null when not a real position. */
        fun of(lat: Double, lon: Double): LatLon? {
            if (!lat.isFinite() || !lon.isFinite() || lat < -90.0 || lat > 90.0) return null
            val wrapped = if (lon in -180.0..180.0) lon else mod360(lon + 180.0) - 180.0
            return LatLon(lat, wrapped)
        }
    }
}

object Geo {
    /** IUGG mean Earth radius. */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle (haversine) distance in metres; within ~0.6% of the WGS-84 ellipsoidal distance. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val p1 = a.lat * DEG
        val p2 = b.lat * DEG
        val dp = (b.lat - a.lat) * DEG
        val dl = (b.lon - a.lon) * DEG
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing from [a] to [b], degrees from true north in [0, 360). 0 for coincident points. */
    fun initialBearing(a: LatLon, b: LatLon): Double {
        val p1 = a.lat * DEG
        val p2 = b.lat * DEG
        val dl = (b.lon - a.lon) * DEG
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        if (y == 0.0 && x == 0.0) return 0.0
        return mod360(atan2(y, x) * RAD)
    }

    /** Bearing on arrival at [b] when travelling the great circle from [a]. */
    fun finalBearing(a: LatLon, b: LatLon): Double = mod360(initialBearing(b, a) + 180.0)

    /** Point reached from [start] after [distanceM] metres on initial bearing [bearingDeg]. */
    fun destination(start: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val th = bearingDeg * DEG
        val p1 = start.lat * DEG
        val l1 = start.lon * DEG
        val p2 = asin((sin(p1) * cos(d) + cos(p1) * sin(d) * cos(th)).coerceIn(-1.0, 1.0))
        val l2 = l1 + atan2(sin(th) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon.of(p2 * RAD, l2 * RAD)!!
    }

    /**
     * The point [fraction] (0 = [a], 1 = [b]) of the way along the great circle from [a] to [b] (spherical linear
     * interpolation), so a long leg stays on its great circle instead of drifting off a constant initial bearing.
     * Coincident and exactly antipodal endpoints, which have no unique great circle, return [a] for fractions below
     * one half and [b] otherwise. [fraction] is clamped to [0, 1].
     */
    fun intermediate(a: LatLon, b: LatLon, fraction: Double): LatLon {
        val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        if (f == 0.0) return a
        if (f == 1.0) return b
        val p1 = a.lat * DEG
        val l1 = a.lon * DEG
        val p2 = b.lat * DEG
        val l2 = b.lon * DEG
        val dp = p2 - p1
        val dl = l2 - l1
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val delta = 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        val s = sin(delta)
        // The haversine angle of an antipodal pair is only good to about 1e-8 rad, so "exactly antipodal" has to be a
        // tolerance; for coincident points (under a metre apart) either end is as good as an interpolation.
        if (s < 1e-7) return if (f < 0.5) a else b
        val wa = sin((1 - f) * delta) / s
        val wb = sin(f * delta) / s
        val x = wa * cos(p1) * cos(l1) + wb * cos(p2) * cos(l2)
        val y = wa * cos(p1) * sin(l1) + wb * cos(p2) * sin(l2)
        val z = wa * sin(p1) + wb * sin(p2)
        return LatLon.of(atan2(z, sqrt(x * x + y * y)) * RAD, atan2(y, x) * RAD) ?: a
    }

    /** Signed distance (m) of [p] from the great circle [a]→[b]; positive means right of the path. */
    fun crossTrackM(p: LatLon, a: LatLon, b: LatLon): Double {
        val d13 = distanceM(a, p) / EARTH_RADIUS_M
        val t13 = initialBearing(a, p) * DEG
        val t12 = initialBearing(a, b) * DEG
        return asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
    }
}
