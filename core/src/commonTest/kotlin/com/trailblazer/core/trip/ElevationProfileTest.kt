package com.trailblazer.core.trip

import com.trailblazer.core.geo.DemResolution
import com.trailblazer.core.geo.DemTileId
import com.trailblazer.core.geo.DemTileSet
import com.trailblazer.core.geo.ElevationSource
import com.trailblazer.core.geo.FakeHgt
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.summit
import com.trailblazer.core.track.ElevationClimb
import com.trailblazer.core.track.TrackPoint
import com.trailblazer.core.track.TrackStats
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationProfileTest {
    private fun at(lat: Double, lon: Double) = LatLon(lat, lon)

    /** A profile from bare heights, one every [stepM] metres, null for a gap. */
    private fun profileOf(vararg heights: Double?, stepM: Double = 30.0) = ElevationProfile(
        heights.mapIndexed { i, h -> ProfileSample(i * stepM, at(36.0, -118.0), h) },
        listOf(0.0, (heights.size - 1) * stepM),
        stepM,
        stepM,
    )

    /** Ground that rises 10,000 m per degree of latitude (about 9 %), whatever the longitude. */
    private val ramp = ElevationSource { p -> (p.lat - 36.0) * 10_000.0 }

    // ---- sampling a multi-stop route ------------------------------------------------------------------------

    @Test
    fun aMultiStopRouteIsOneContinuousProfileThatIncludesEveryStop() {
        val stops = listOf(at(36.0, -118.0), at(36.1, -118.0), at(36.05, -118.0), at(36.05, -117.95))
        val profile = assertNotNull(ElevationProfiler.sample(stops, ramp, 30.0))
        assertEquals(LegCalculator.totalM(stops.mapIndexed { i, p -> Stop("s$i", "s$i", StopKind.Visit, p) }), profile.totalDistanceM, 1e-6)
        assertEquals(4, profile.stopDistancesM.size)
        // Distance only ever grows, and no step is longer than the spacing.
        profile.samples.zipWithNext().forEach { (a, b) ->
            assertTrue(b.distanceM > a.distanceM)
            assertTrue(b.distanceM - a.distanceM <= 30.0 + 1e-6, "gap ${b.distanceM - a.distanceM}")
        }
        // Every stop is a sample at its own coordinates.
        stops.forEachIndexed { i, stop ->
            val s = profile.samples.single { abs(it.distanceM - profile.stopDistancesM[i]) < 1e-6 }
            assertEquals(stop, s.position)
        }
        assertEquals(0.0, profile.stopDistancesM.first())
        assertEquals(profile.totalDistanceM, profile.stopDistancesM.last(), 1e-6)
        assertFalse(profile.spacingWidened)
        // 0.1 deg up the ramp and 0.05 deg down it: about 1000 m of gain and 500 m of loss, within the 3 m band per turn.
        val stats = profile.stats()
        assertEquals(1000.0, stats.gainM, 8.0)
        assertEquals(500.0, stats.lossM, 8.0)
        assertEquals(0.0, stats.minM!!, 1e-6)
        assertEquals(1000.0, stats.maxM!!, 1e-6)
        assertFalse(stats.hasGaps)
    }

    @Test
    fun theSpacingIsHonouredPerLeg() {
        val stops = listOf(at(0.0, 0.0), at(0.0, 0.01))
        val profile = ElevationProfiler.sample(stops, ramp, 90.0)!!
        val leg = Geo.distanceM(stops[0], stops[1])
        assertEquals(kotlin.math.ceil(leg / 90.0).toInt() + 1, profile.samples.size)
    }

    @Test
    fun aRouteTooLongForTheCapIsSampledMoreCoarselyAndSaysSo() {
        // 50 stops 3 degrees of longitude apart along 40 N: roughly 8,000 km.
        val stops = (0 until 50).map { at(40.0, -170.0 + it * 3.0) }
        val profile = ElevationProfiler.sample(stops, ramp, 30.0)!!
        assertTrue(profile.samples.size <= ElevationProfiler.MAX_SAMPLES, "${profile.samples.size} samples")
        assertTrue(profile.spacingWidened)
        assertEquals(30.0, profile.requestedSpacingM)
        assertTrue(profile.spacingM > 30.0)
        assertEquals(profile.totalDistanceM, profile.samples.last().distanceM)
        assertEquals(stops.last(), profile.samples.last().position, "the whole route is covered, not cut at the cap")
        // A tiny cap is raised just enough to keep every stop.
        val tiny = ElevationProfiler.sample(stops, ramp, 30.0, maxSamples = 3)!!
        assertTrue(tiny.samples.size <= 2 * stops.size)
        assertEquals(stops.last(), tiny.samples.last().position)
    }

    @Test
    fun theSampleBoundIsTheCapOrTwoPerStopWhicheverIsLarger() {
        // A 50-stop trip (the most a trip holds) never passes the 2,000 cap.
        val fifty = (0 until 50).map { at(if (it % 2 == 0) 10.0 else 10.5, it * 0.7) }
        val a = ElevationProfiler.sample(fifty, ramp, 30.0)!!
        assertTrue(a.samples.size <= ElevationProfiler.MAX_SAMPLES, "${a.samples.size}")
        // 1,500 stops cannot fit in 2,000 samples with one more per leg, so the bound is two per stop: 3,000.
        val many = (0 until 1500).map { at(if (it % 2 == 0) 10.0 else 10.5, it * 0.1) }
        val b = ElevationProfiler.sample(many, ramp, 30.0)!!
        assertTrue(b.samples.size <= 3000, "${b.samples.size}")
        assertTrue(b.spacingWidened)
        assertEquals(many.last(), b.samples.last().position)
        assertEquals(1500, b.stopDistancesM.size)
    }

    @Test
    fun degenerateRoutesAreRefusedOrHandled() {
        assertNull(ElevationProfiler.sample(emptyList(), ramp, 30.0))
        assertNull(ElevationProfiler.sample(listOf(at(1.0, 1.0)), ramp, 30.0))
        assertNull(ElevationProfiler.sample(listOf(at(1.0, 1.0), at(1.1, 1.1)), ramp, 0.0))
        assertNull(ElevationProfiler.sample(listOf(at(1.0, 1.0), at(1.1, 1.1)), ramp, Double.NaN))
        // Two identical stops: one sample, no distance, no crash.
        val same = ElevationProfiler.sample(listOf(at(1.0, 1.0), at(1.0, 1.0)), ramp, 30.0)!!
        assertEquals(1, same.samples.size)
        assertEquals(0.0, same.totalDistanceM)
        // A zero-length middle leg does not duplicate a sample.
        val withRepeat = ElevationProfiler.sample(listOf(at(1.0, 1.0), at(1.0, 1.01), at(1.0, 1.01), at(1.0, 1.02)), ramp, 90.0)!!
        assertEquals(withRepeat.samples.size, withRepeat.samples.map { it.distanceM }.toSet().size)
        assertEquals(4, withRepeat.stopDistancesM.size)
    }

    @Test
    fun aRouteAcrossTheAntimeridianStaysShortAndContinuous() {
        val stops = listOf(at(10.0, 179.95), at(10.0, -179.95))
        val profile = ElevationProfiler.sample(stops, ramp, 90.0)!!
        assertEquals(Geo.distanceM(stops[0], stops[1]), profile.totalDistanceM, 1e-6)
        assertTrue(profile.totalDistanceM < 12_000.0)
        assertTrue(profile.samples.all { abs(it.position.lon) > 179.9 })
    }

    @Test
    fun cancellationStopsTheWork() {
        val stops = listOf(at(36.0, -118.0), at(37.0, -118.0))
        var calls = 0
        assertFailsWith<IllegalStateException> {
            ElevationProfiler.sample(stops, ramp, 30.0, checkCancelled = { if (++calls == 3) throw IllegalStateException("cancelled") })
        }
        assertEquals(3, calls)
    }

    @Test
    fun aNonFiniteElevationIsAGapNotANumber() {
        val bad = ElevationSource { p -> if (p.lat > 36.05) Double.NaN else 100.0 }
        val profile = ElevationProfiler.sample(listOf(at(36.0, -118.0), at(36.1, -118.0)), bad, 90.0)!!
        assertTrue(profile.samples.any { it.elevationM == null })
        assertTrue(profile.samples.none { it.elevationM?.isNaN() == true })
    }

    // ---- through real tiles ---------------------------------------------------------------------------------

    @Test
    fun aRouteOverMountWhitneyProfilesItsSummitAndMarksItAsThePeak() {
        val id = DemTileId(36, -119)
        val set = DemTileSet({ if (it == id) FakeHgt(DemResolution.ThreeArcSecond, summit(505, 849, 4421)).tile(it) else null })
        val stops = listOf(at(36.5785 - 0.03, -118.2923 - 0.03), at(36.5785, -118.2923), at(36.5785 + 0.03, -118.2923 + 0.03))
        val spacing = set.finestSpacingM(stops)
        assertEquals(90.0, spacing)
        val profile = ElevationProfiler.sample(stops, set, spacing)!!
        val stats = profile.stats()
        assertEquals(4421.0, stats.maxM!!, 1e-6)
        assertFalse(stats.hasGaps)
        assertTrue(stats.gainM > 200.0 && stats.lossM > 200.0)
        val peak = profile.extrema().single { it.kind == ExtremumKind.Peak }
        assertEquals(4421.0, peak.elevationM, 1e-6)
        assertEquals(profile.stopDistancesM[1], profile.samples[peak.index].distanceM, 1e-6)
        set.close()
    }

    @Test
    fun routeStretchesOffTheInstalledTilesBecomeGapsAndGainIgnoresThem() {
        val id = DemTileId(36, -119)
        val set = DemTileSet({ if (it == id) FakeHgt(DemResolution.ThreeArcSecond) { _, _ -> 2000 }.tile(it) else null })
        // Starts 0.1 deg inside the tile and runs 0.3 deg north of it, off the tile.
        val profile = ElevationProfiler.sample(listOf(at(36.9, -118.5), at(37.2, -118.5)), set, 90.0)!!
        val stats = profile.stats()
        assertTrue(stats.hasGaps)
        assertTrue(stats.hasData)
        assertEquals(2000.0, stats.minM)
        assertEquals(0.0, stats.gainM)
        val firstGap = profile.samples.indexOfFirst { it.elevationM == null }
        assertTrue(firstGap > 0)
        assertTrue(profile.samples.drop(firstGap).all { it.elevationM == null }, "past the tile edge nothing is invented")
        // No tiles at all: no data, and not a single 0 m.
        val none = ElevationProfiler.sample(listOf(at(36.9, -118.5), at(37.2, -118.5)), DemTileSet({ null }), 90.0)!!
        assertFalse(none.stats().hasData)
        assertTrue(none.samples.all { it.elevationM == null })
        assertNull(none.stats().minM)
        assertTrue(none.extrema().isEmpty())
    }

    // ---- gain and loss with noise rejection ------------------------------------------------------------------

    @Test
    fun noiseInsideTheBandIsNotClimbing() {
        val noisy = ElevationProfile(
            (0 until 400).map { ProfileSample(it * 30.0, at(36.0, -118.0), 1500.0 + if (it % 2 == 0) 1.4 else -1.4) },
            listOf(0.0, 399 * 30.0), 30.0, 30.0,
        )
        val stats = noisy.stats()
        assertEquals(0.0, stats.gainM)
        assertEquals(0.0, stats.lossM)
        // With the band switched off the same noise is 1,100 m of phantom climbing: the filter is what stops it.
        assertTrue(noisy.stats(hysteresisM = 0.0).gainM > 500.0)
    }

    @Test
    fun aRealClimbThroughNoiseIsCountedAndTheNoiseIsNot() {
        // 600 m up in 100 m steps of 1 m per sample, with +-1.2 m of noise on top, then 600 m down.
        val heights = (0 until 600).map { 1000.0 + it + if (it % 2 == 0) 1.2 else -1.2 } + (0 until 600).map { 1600.0 - it + if (it % 2 == 0) 1.2 else -1.2 }
        val stats = profileOf(*heights.toTypedArray()).stats()
        assertEquals(600.0, stats.gainM, 12.0)
        assertEquals(600.0, stats.lossM, 12.0)
    }

    @Test
    fun aGapStartsANewRunSoNothingIsCountedAcrossIt() {
        val gap = profileOf(1000.0, 1000.0, null, 1100.0, 1100.0).stats()
        assertEquals(0.0, gap.gainM, "100 m between the two ends of a gap is not climb we know about")
        assertEquals(4, gap.knownSamples)
        assertTrue(gap.hasGaps)
        assertEquals(100.0, profileOf(1000.0, 1100.0).stats().gainM)
        assertEquals(100.0, profileOf(1100.0, 1000.0).stats().lossM)
        assertEquals(1000.0, gap.minM)
        assertEquals(1100.0, gap.maxM)
    }

    @Test
    fun recordedTracksAndPlannedRoutesShareOneFilter() {
        val heights = listOf(100.0, 101.0, 104.5, 103.0, 99.0, 99.5, 120.0)
        val track = TrackStats()
        heights.forEachIndexed { i, h -> track.add(TrackPoint(i * 1000L, at(36.0, -118.0 + i * 0.001), elevationM = h)) }
        val planned = profileOf(*heights.toTypedArray()).stats()
        assertEquals(track.gainM, planned.gainM)
        assertEquals(track.lossM, planned.lossM)
        val climb = ElevationClimb()
        heights.forEach { climb.add(it) }
        assertEquals(track.gainM, climb.gainM)
        assertEquals(track.minElevationM, climb.minM)
        assertEquals(track.maxElevationM, climb.maxM)
        climb.add(null)
        climb.add(Double.NaN)
        assertEquals(track.gainM, climb.gainM)
    }

    // ---- peaks, valleys, steep sections ----------------------------------------------------------------------

    @Test
    fun peaksAndValleysThatStandOutAreMarkedAndNoiseIsNot() {
        val shape = listOf(1000.0, 1100.0, 1200.0, 1120.0, 1050.0, 1180.0, 1300.0)
        // Two metres of jitter on every post.
        val jittered = shape.flatMap { h -> listOf(h, h + 2.0, h - 2.0, h + 1.0) }
        val marks = profileOf(*jittered.toTypedArray()).extrema()
        assertEquals(listOf(ExtremumKind.Valley, ExtremumKind.Peak, ExtremumKind.Valley, ExtremumKind.Peak), marks.map { it.kind })
        assertTrue(marks.zipWithNext().all { (a, b) -> a.index < b.index })
        assertEquals(1000.0, marks[0].elevationM, 3.0)
        assertEquals(1200.0, marks[1].elevationM, 3.0)
        assertEquals(1050.0, marks[2].elevationM, 3.0)
        assertEquals(1300.0, marks[3].elevationM, 3.0)
    }

    @Test
    fun aFlatOrSlightlyUndulatingRouteHasNoMarkers() {
        assertTrue(profileOf(*Array(100) { 500.0 + if (it % 2 == 0) 2.0 else 0.0 }).extrema().isEmpty())
        assertTrue(profileOf(500.0).extrema().isEmpty())
        assertTrue(profileOf().extrema().isEmpty())
    }

    @Test
    fun theHighestAndLowestKnownPointsAreAlwaysMarkedAndTheCapKeepsTheBiggestSwings() {
        val sawtooth = (0 until 40).map { if (it % 2 == 0) 1000.0 else 1100.0 + it }
        val marks = profileOf(*sawtooth.toTypedArray()).extrema(maxMarkers = 5)
        assertEquals(5, marks.size)
        val all = profileOf(*sawtooth.toTypedArray())
        assertTrue(marks.any { it.elevationM == all.stats().maxM })
        assertTrue(marks.any { it.elevationM == all.stats().minM })
        assertTrue(marks.zipWithNext().all { (a, b) -> a.index < b.index })
    }

    @Test
    fun aGapDoesNotLetPeakDetectionBridgeIt() {
        val marks = profileOf(1000.0, 1100.0, 1000.0, null, 1500.0, 1600.0, 1500.0).extrema()
        assertTrue(marks.none { it.index == 3 })
        assertTrue(marks.any { it.kind == ExtremumKind.Peak && it.elevationM == 1100.0 })
        assertTrue(marks.any { it.kind == ExtremumKind.Peak && it.elevationM == 1600.0 })
    }

    @Test
    fun steepSectionsAreFoundOverAWindowNotFromSinglePostNoise() {
        // 30 m posts. Flat, then a 40 % climb for 300 m, then flat, with 3 m of jitter that on its own is 10 % per post.
        val flat1 = List(10) { 1000.0 + if (it % 2 == 0) 3.0 else 0.0 }
        val climb = List(10) { 1000.0 + (it + 1) * 12.0 }
        val flat2 = List(10) { 1120.0 + if (it % 2 == 0) 3.0 else 0.0 }
        val profile = profileOf(*(flat1 + climb + flat2).toTypedArray())
        val sections = profile.steepSections()
        assertEquals(1, sections.size, "$sections")
        assertTrue(sections[0].gradePercent in 30.0..45.0, "${sections[0]}")
        assertTrue(sections[0].startM >= 8 * 30.0 && sections[0].endM <= 22 * 30.0 + 150.0)
        // The jitter alone never trips the warning.
        assertTrue(profileOf(*Array(60) { 1000.0 + if (it % 2 == 0) 3.0 else 0.0 }).steepSections().isEmpty())
    }

    @Test
    fun descentsAreWarnedWithANegativeGradeAndGapsAreNeverMeasuredAcross() {
        val down = profileOf(*Array(12) { 2000.0 - it * 15.0 })
        val s = down.steepSections()
        assertEquals(1, s.size)
        assertTrue(s[0].gradePercent < -25.0)
        val gapped = profileOf(2000.0, 1985.0, 1970.0, null, 1900.0, 1885.0, 1870.0, 1855.0, 1840.0, 1825.0)
        assertTrue(gapped.steepSections().all { sec -> gapped.samples.filter { it.distanceM in sec.startM..sec.endM }.all { it.elevationM != null } })
        assertTrue(profileOf(2000.0, 1000.0).steepSections().isEmpty(), "a route shorter than the window has no window")
        assertContentEquals(emptyList(), profileOf().steepSections())
    }
}
