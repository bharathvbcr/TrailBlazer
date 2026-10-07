package com.trailblazer.core.geo

import com.trailblazer.core.math.angleDiff
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
    /** Vincenty inverse on WGS-84, used only as an independent oracle. */
    private fun vincentyM(a: LatLon, b: LatLon): Double {
        val aa = 6378137.0
        val f = 1 / 298.257223563
        val bb = aa * (1 - f)
        fun toRad(deg: Double) = deg * (PI / 180.0)
        val l = toRad(b.lon - a.lon)
        val u1 = atan((1 - f) * tan(toRad(a.lat)))
        val u2 = atan((1 - f) * tan(toRad(b.lat)))
        var lambda = l
        var sinSigma: Double; var cosSigma: Double; var sigma: Double; var cos2Alpha: Double; var cos2SigmaM: Double
        var iter = 0
        do {
            val sinL = sin(lambda); val cosL = cos(lambda)
            sinSigma = sqrt((cos(u2) * sinL).let { it * it } + (cos(u1) * sin(u2) - sin(u1) * cos(u2) * cosL).let { it * it })
            if (sinSigma == 0.0) return 0.0
            cosSigma = sin(u1) * sin(u2) + cos(u1) * cos(u2) * cosL
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cos(u1) * cos(u2) * sinL / sinSigma
            cos2Alpha = 1 - sinAlpha * sinAlpha
            cos2SigmaM = if (cos2Alpha != 0.0) cosSigma - 2 * sin(u1) * sin(u2) / cos2Alpha else 0.0
            val c = f / 16 * cos2Alpha * (4 + f * (4 - 3 * cos2Alpha))
            val prev = lambda
            lambda = l + (1 - c) * f * sinAlpha * (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
        } while (abs(lambda - prev) > 1e-12 && ++iter < 200)
        val uSq = cos2Alpha * (aa * aa - bb * bb) / (bb * bb)
        val a2 = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val b2 = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val ds = b2 * sinSigma * (cos2SigmaM + b2 / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
            b2 / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
        return bb * a2 * (sigma - ds)
    }

    @Test
    fun haversineWithinPointSixPercentOfVincenty() {
        val rnd = Random(42)
        repeat(2000) {
            val a = LatLon(rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(-180.0, 180.0))
            val b = LatLon(rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(-180.0, 180.0))
            val v = vincentyM(a, b)
            if (v > 1000) assertTrue(abs(Geo.distanceM(a, b) - v) / v < 0.006, "$a→$b")
        }
    }

    @Test
    fun knownDistanceAndBearing() {
        // Denver → Boulder, roughly 38–39 km at ~NW.
        val denver = LatLon(39.7392, -104.9903)
        val boulder = LatLon(40.0150, -105.2705)
        assertEquals(38_700.0, Geo.distanceM(denver, boulder), 800.0)
        assertTrue(Geo.initialBearing(denver, boulder) in 300.0..330.0)
    }

    @Test
    fun bearingIsNeverNegativeAndCrossesTheDateLine() {
        val fiji = LatLon(-18.14, 178.44)
        val samoa = LatLon(-13.83, -171.77)
        val b = Geo.initialBearing(fiji, samoa)
        assertTrue(b in 0.0..360.0)
        assertTrue(b in 50.0..80.0, "short way east over the date line, got $b")
        assertTrue(Geo.distanceM(fiji, samoa) < 1_200_000)
        assertEquals(0.0, Geo.initialBearing(fiji, fiji), 0.0)
    }

    @Test
    fun destinationRoundTrip() {
        val start = LatLon(46.5, 7.9)
        val end = Geo.destination(start, 123.0, 25_000.0)
        assertEquals(25_000.0, Geo.distanceM(start, end), 1.0)
        assertEquals(0.0, angleDiff(123.0, Geo.initialBearing(start, end)), 0.01)
    }

    @Test
    fun crossTrackSign() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 1.0)
        assertTrue(Geo.crossTrackM(LatLon(-0.01, 0.5), a, b) > 0) // south of an eastbound path = right
        assertTrue(Geo.crossTrackM(LatLon(0.01, 0.5), a, b) < 0)
    }

    @Test
    fun latLonValidation() {
        assertNull(LatLon.of(Double.NaN, 0.0))
        assertNull(LatLon.of(91.0, 0.0))
        assertEquals(-170.0, LatLon.of(10.0, 190.0)!!.lon, 1e-9)
    }

    @Test
    fun dmsFormattingCarriesRounding() {
        assertEquals("40°26′46.0″ N", Dms.formatAxis(40.446111, true, CoordinateFormat.DegreesMinutesSeconds))
        assertEquals("80°00′00.0″ W", Dms.formatAxis(-79.99999999, false, CoordinateFormat.DegreesMinutesSeconds))
        assertEquals("12°30.000′ S", Dms.formatAxis(-12.5, true, CoordinateFormat.DegreesMinutes))
    }
}

