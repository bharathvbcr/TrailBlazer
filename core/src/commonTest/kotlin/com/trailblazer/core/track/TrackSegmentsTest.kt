package com.trailblazer.core.track

import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Segment breaks (a pause, a `trkseg` boundary) and elevation datum switches in [TrackStats]. */
class TrackSegmentsTest {
    private val start = LatLon(46.0, 7.0)

    /** [n] fixes 15 m apart north of [from], 10 s apart at walking speed, starting at [t0]. */
    private fun walk(s: TrackStats, from: LatLon, t0: Long, n: Int, segment: Int, ele: Double? = null, datum: ElevationDatum? = null) {
        for (i in 0 until n) {
            s.add(TrackPoint(t0 + i * 10_000L, Geo.destination(from, 0.0, i * 15.0), ele, null, 1.5, segment, datum))
        }
    }

    @Test
    fun aPauseGapIsNotDistanceOrMovingTime() {
        val s = TrackStats()
        walk(s, start, 0, 5, segment = 0)
        val before = s.distanceM
        assertEquals(60.0, before, 0.5)
        assertEquals(40_000L, s.movingMs)
        // Paused for 20 minutes and resumed 5 km away (a lift in a car): the straight line across is not walked.
        walk(s, Geo.destination(start, 90.0, 5_000.0), 1_200_000, 5, segment = 1)
        assertEquals(120.0, s.distanceM, 1.0, "distance must be the two walks only, was ${s.distanceM}")
        assertEquals(80_000L, s.movingMs, "the 20-minute gap must not be moving time")
        assertEquals(10, s.points)
    }

    @Test
    fun theSameFixesInOneSegmentStillBridgeTheGap() {
        // The Battery Saver / process-death case: no pause, so the gap is stitched as before (distance counts, moving time
        // is judged by the average speed across it).
        val s = TrackStats()
        walk(s, start, 0, 5, segment = 0)
        walk(s, Geo.destination(start, 90.0, 5_000.0), 1_200_000, 5, segment = 0)
        assertTrue(s.distanceM > 5_000.0)
    }

    @Test
    fun climbAcrossASegmentBreakIsNotCounted() {
        val s = TrackStats()
        walk(s, start, 0, 3, segment = 0, ele = 1000.0)
        // Resumed 200 m higher up after a bus ride: the first fix of the new segment is a fresh reference.
        walk(s, start, 600_000, 3, segment = 1, ele = 1200.0)
        assertEquals(0.0, s.gainM, 0.0)
        assertEquals(0.0, s.lossM, 0.0)
        // Real climbing inside the new segment still counts, from its own reference.
        s.add(TrackPoint(700_000, start, 1212.0, segment = 1))
        assertEquals(12.0, s.gainM, 1e-9)
    }

    @Test
    fun aDatumSwitchMidSegmentIsNotClimb() {
        val s = TrackStats()
        // 120 s of sea-level fixes, then the geoid lookup fails and the receiver's ellipsoid height (47 m higher here)
        // is recorded in the same segment, then sea level again.
        walk(s, start, 0, 3, segment = 0, ele = 1000.0, datum = ElevationDatum.Msl)
        walk(s, start, 30_000, 3, segment = 0, ele = 1047.0, datum = ElevationDatum.Ellipsoid)
        walk(s, start, 60_000, 3, segment = 0, ele = 1000.0, datum = ElevationDatum.Msl)
        assertEquals(0.0, s.gainM, 0.0, "gain was ${s.gainM}")
        assertEquals(0.0, s.lossM, 0.0, "loss was ${s.lossM}")
    }

    @Test
    fun climbingAfterADatumSwitchCountsFromTheNewReference() {
        val s = TrackStats()
        s.add(TrackPoint(0, start, 1000.0, datum = ElevationDatum.Msl))
        s.add(TrackPoint(1_000, start, 1047.0, datum = ElevationDatum.Ellipsoid))
        s.add(TrackPoint(2_000, start, 1057.0, datum = ElevationDatum.Ellipsoid))
        assertEquals(10.0, s.gainM, 1e-9)
    }

    @Test
    fun aFixWithoutElevationDoesNotResetTheReference() {
        val s = TrackStats()
        s.add(TrackPoint(0, start, 1000.0, datum = ElevationDatum.Msl))
        // No height reported, whatever its datum: it neither counts nor resets the climb reference.
        s.add(TrackPoint(1_000, start, null, datum = ElevationDatum.Ellipsoid))
        s.add(TrackPoint(2_000, start, 1010.0, datum = ElevationDatum.Msl))
        assertEquals(10.0, s.gainM, 1e-9)
    }

    @Test
    fun unknownDatumToKnownDatumIsASwitchToo() {
        // A legacy track (datum null) continued by a recorder that stores the datum: the two are not comparable.
        val s = TrackStats()
        s.add(TrackPoint(0, start, 1000.0))
        s.add(TrackPoint(1_000, start, 1050.0, datum = ElevationDatum.Msl))
        assertEquals(0.0, s.gainM, 0.0)
    }

    @Test
    fun aSingleFixSegmentContributesNothingButSeparatesItsNeighbours() {
        val s = TrackStats()
        walk(s, start, 0, 3, segment = 0)
        s.add(TrackPoint(100_000, Geo.destination(start, 90.0, 3_000.0), segment = 1, speedMps = 9.0))
        walk(s, start, 200_000, 3, segment = 2)
        assertEquals(60.0, s.distanceM, 0.5)
        assertEquals(40_000L, s.movingMs)
        assertEquals(7, s.points)
        assertEquals(9.0, s.maxSpeedMps!!, 0.0)
    }

    @Test
    fun theFirstFixOfALaterSegmentNeverLowersTheMaxSpeed() {
        val s = TrackStats()
        s.add(TrackPoint(0, start, speedMps = 8.0))
        s.add(TrackPoint(60_000, start, speedMps = 1.0, segment = 1))
        assertEquals(8.0, s.maxSpeedMps!!, 0.0)
    }

    @Test
    fun outOfOrderTimestampsInsideASegmentAddNoMovingTimeAndNoNegativeTime() {
        val s = TrackStats()
        val a = start
        val b = Geo.destination(start, 0.0, 100.0)
        s.add(TrackPoint(20_000, a, speedMps = 5.0))
        s.add(TrackPoint(10_000, b, speedMps = 5.0))
        s.add(TrackPoint(30_000, a, speedMps = 5.0))
        assertTrue(s.movingMs >= 0)
        assertTrue(s.distanceM >= 0.0)
    }

    @Test
    fun segmentsThatArriveInDescendingOrderAreStillBreaks() {
        val s = TrackStats()
        walk(s, start, 0, 3, segment = 1)
        walk(s, Geo.destination(start, 90.0, 2_000.0), 100_000, 3, segment = 0)
        assertEquals(60.0, s.distanceM, 0.5)
    }
}
