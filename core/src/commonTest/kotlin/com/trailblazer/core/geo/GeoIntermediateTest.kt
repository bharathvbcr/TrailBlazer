package com.trailblazer.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoIntermediateTest {
    private fun at(lat: Double, lon: Double) = LatLon(lat, lon)

    @Test
    fun endpointsAreExactAndTheMidpointOfAnEquatorLegIsHalfway() {
        val a = at(0.0, 0.0)
        val b = at(0.0, 90.0)
        assertEquals(a, Geo.intermediate(a, b, 0.0))
        assertEquals(b, Geo.intermediate(a, b, 1.0))
        val mid = Geo.intermediate(a, b, 0.5)
        assertEquals(0.0, mid.lat, 1e-9)
        assertEquals(45.0, mid.lon, 1e-9)
    }

    @Test
    fun everyPointIsTheRightDistanceAlongTheSameGreatCircle() {
        val a = at(60.0, -20.0)
        val b = at(-35.0, 110.0)
        val total = Geo.distanceM(a, b)
        for (f in listOf(0.1, 0.25, 0.5, 0.9)) {
            val p = Geo.intermediate(a, b, f)
            assertEquals(f * total, Geo.distanceM(a, p), total * 1e-9, "fraction $f")
            assertEquals(0.0, Geo.crossTrackM(p, a, b), 1.0, "off the great circle at $f")
        }
    }

    @Test
    fun aConstantBearingWalkDriftsOffTheGreatCircleAndThisDoesNot() {
        // 3,000 km along 60 N: the great circle bows far to the north of the straight east-west line.
        val a = at(60.0, 0.0)
        val b = at(60.0, 50.0)
        val mid = Geo.intermediate(a, b, 0.5)
        assertTrue(mid.lat > 62.0, "great circle rises towards the pole, got ${mid.lat}")
    }

    @Test
    fun legsAcrossTheAntimeridianTakeTheShortWay() {
        val mid = Geo.intermediate(at(10.0, 179.0), at(10.0, -179.0), 0.5)
        assertTrue(abs(mid.lon) > 179.9, "got ${mid.lon}")
        assertEquals(10.0, mid.lat, 0.01)
    }

    @Test
    fun coincidentAntipodalAndBadFractionsDoNotThrow() {
        val a = at(10.0, 20.0)
        assertEquals(a, Geo.intermediate(a, a, 0.5))
        val anti = at(-10.0, -160.0)
        assertEquals(a, Geo.intermediate(a, anti, 0.4))
        assertEquals(anti, Geo.intermediate(a, anti, 0.6))
        assertEquals(a, Geo.intermediate(a, at(11.0, 21.0), Double.NaN))
        assertEquals(at(11.0, 21.0), Geo.intermediate(a, at(11.0, 21.0), 7.0))
        // Over the pole.
        val over = Geo.intermediate(at(89.9, 0.0), at(89.9, 180.0), 0.5)
        assertEquals(90.0, over.lat, 0.11)
    }
}
