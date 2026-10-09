package com.trailblazer.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** GnssStatus values straight from the chipset: impossible ones must never reach the sky plot or the signal bars. */
class SatelliteBoundaryTest {
    @Test
    fun plausibleSatellitePassesThrough() {
        val s = Satellite.of(1, 12, 38.5f, 45f, 370f, true)!!
        assertEquals(38.5, s.cn0DbHz, 1e-9)
        assertEquals(45.0, s.elevationDeg, 1e-9)
        assertEquals(10.0, s.azimuthDeg, 1e-9) // wrapped into [0, 360)
    }

    @Test
    fun impossiblePositionsAreDroppedAndBadSignalIsClamped() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 91f, -91f)) assertNull(Satellite.of(1, 1, 30f, bad, 10f, false), "elevation $bad")
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY)) assertNull(Satellite.of(1, 1, 30f, 10f, bad, false), "azimuth $bad")
        assertEquals(0.0, Satellite.of(1, 1, Float.NaN, 10f, 10f, false)!!.cn0DbHz, 0.0)
        assertEquals(0.0, Satellite.of(1, 1, -5f, 10f, 10f, false)!!.cn0DbHz, 0.0)
        assertEquals(99.0, Satellite.of(1, 1, 1e9f, 10f, 10f, false)!!.cn0DbHz, 0.0)
    }

    @Test
    fun canonicalConstellationNames() {
        assertEquals("GPS", Satellite.constellationName(1))
        assertEquals("SBAS", Satellite.constellationName(2))
        assertEquals("GLONASS", Satellite.constellationName(3))
        assertEquals("QZSS", Satellite.constellationName(4))
        assertEquals("BeiDou", Satellite.constellationName(5))
        assertEquals("Galileo", Satellite.constellationName(6))
        assertEquals("NavIC", Satellite.constellationName(7))
        assertEquals("Other", Satellite.constellationName(0))
        assertEquals("Other", Satellite.constellationName(99))
    }
}
