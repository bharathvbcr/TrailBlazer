package com.trailblazer.core.track

import com.trailblazer.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hysteresis boundary: a change of exactly the band counts, a hair under it does not. */
class ElevationClimbTest {
    private fun climb(vararg heights: Double, band: Double = 3.0) = ElevationClimb(band).also { c -> heights.forEach { c.add(it) } }

    private fun stats(vararg heights: Double) = TrackStats().also { s ->
        heights.forEachIndexed { i, h -> s.add(TrackPoint(i * 1000L, LatLon(46.0, 7.0), elevationM = h)) }
    }

    @Test
    fun aClimbOfExactlyTheBandCounts() {
        assertEquals(3.0, climb(100.0, 103.0).gainM)
        assertEquals(3.0, climb(103.0, 100.0).lossM)
    }

    @Test
    fun aClimbJustUnderTheBandDoesNot() {
        assertEquals(0.0, climb(100.0, 102.9).gainM)
        assertEquals(0.0, climb(102.9, 100.0).lossM)
    }

    @Test
    fun theSameBoundaryHoldsThroughTrackStats() {
        assertEquals(3.0, stats(100.0, 103.0).gainM)
        assertEquals(0.0, stats(100.0, 102.9).gainM)
        assertEquals(3.0, stats(103.0, 100.0).lossM)
        assertEquals(0.0, stats(102.9, 100.0).lossM)
    }

    @Test
    fun aNarrowerBandCountsSmallerChanges() {
        assertEquals(1.0, climb(100.0, 101.0, band = 1.0).gainM)
        assertEquals(0.0, climb(100.0, 100.9, band = 1.0).gainM)
    }
}
