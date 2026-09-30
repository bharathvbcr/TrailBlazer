package com.trailblazer.core.plot

import com.trailblazer.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Adversarial cases for chart geometry. These encode the contract the screens need:
 * non-finite samples are gaps, scales stay finite, hit-testing stays inside the series,
 * and a track that crosses the antimeridian stays a short hop.
 */
class SeriesPlotTest {
    @Test
    fun nanAndInfinityAreGapsAndDoNotPoisonTheScale() {
        val f = SeriesPlot.frame(listOf(10.0, Double.NaN, Double.POSITIVE_INFINITY, 12.0, null))
        assertNotNull(f)
        f!!
        assertTrue("lo ${f.lo}", f.lo.isFinite())
        assertTrue("hi ${f.hi}", f.hi.isFinite())
        assertTrue(f.hi > f.lo)
        assertEquals(5, f.count)
        assertEquals(listOf(0, 3), f.samples.map { it.index })
        assertEquals(listOf(10.0, 12.0), f.samples.map { it.value })
    }

    @Test
    fun seriesWithNoTwoFiniteSamplesIsNotAChart() {
        assertNull(SeriesPlot.frame(emptyList()))
        assertNull(SeriesPlot.frame(listOf(1.0)))
        assertNull(SeriesPlot.frame(listOf(null, null)))
        assertNull(SeriesPlot.frame(listOf(Double.NaN, Double.NaN, Double.NEGATIVE_INFINITY)))
        assertNull(SeriesPlot.frame(listOf(1.0, Double.NaN)))
    }

    @Test
    fun flatSeriesGetsTheMinimumSpan() {
        val f = SeriesPlot.frame(listOf(5.0, 5.0, 5.0), minSpan = 10.0)!!
        assertEquals(0.0, f.lo, 1e-9)
        assertEquals(10.0, f.hi, 1e-9)
    }

    @Test
    fun negativeOrNonFiniteMinSpanDoesNotInvertTheRange() {
        val f = SeriesPlot.frame(listOf(0.0, 4.0), minSpan = Double.NaN)!!
        assertEquals(0.0, f.lo, 0.0)
        assertEquals(4.0, f.hi, 0.0)
        val g = SeriesPlot.frame(listOf(0.0, 4.0), minSpan = -50.0)!!
        assertTrue(g.hi > g.lo)
        assertTrue(g.lo <= 0.0 && g.hi >= 4.0)
    }

    @Test
    fun infiniteSpanIsRefused() {
        assertNull(SeriesPlot.frame(listOf(-1e308, 1e308)))
    }

    @Test
    fun yFractionStaysInsideThePlot() {
        assertEquals(0.0, SeriesPlot.yFraction(0.0, 0.0, 10.0), 0.0)
        assertEquals(1.0, SeriesPlot.yFraction(10.0, 0.0, 10.0), 0.0)
        assertEquals(0.5, SeriesPlot.yFraction(5.0, 0.0, 10.0), 1e-12)
        assertEquals(0.0, SeriesPlot.yFraction(-5.0, 0.0, 10.0), 0.0)
        assertEquals(1.0, SeriesPlot.yFraction(50.0, 0.0, 10.0), 0.0)
        assertEquals(0.5, SeriesPlot.yFraction(Double.NaN, 0.0, 10.0), 0.0)
        assertEquals(0.5, SeriesPlot.yFraction(1.0, 0.0, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.5, SeriesPlot.yFraction(1.0, 5.0, 5.0), 0.0)
    }

    @Test
    fun scrubIndexIsClampedToTheSeries() {
        assertEquals(0, SeriesPlot.scrubIndex(-1.0, 10))
        assertEquals(9, SeriesPlot.scrubIndex(2.0, 10))
        assertEquals(0, SeriesPlot.scrubIndex(Double.NaN, 10))
        assertEquals(0, SeriesPlot.scrubIndex(0.0, 10))
        assertEquals(9, SeriesPlot.scrubIndex(1.0, 10))
        assertEquals(0, SeriesPlot.scrubIndex(0.5, 0))
        assertEquals(0, SeriesPlot.scrubIndex(0.5, 1))
        assertEquals(4, SeriesPlot.scrubIndex(0.5, 10))
    }

    @Test
    fun niceLengthNeverThrowsAndStaysPositive() {
        val targets = doubleArrayOf(
            0.0, -1.0, -1e9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1e-320, 1e-12, 0.7, 1.0, 3.0, 1234.5, 1e6, 1e308,
        )
        for (t in targets) {
            val n = SeriesPlot.niceLength(t)
            assertTrue("$t -> $n", n.isFinite() && n > 0.0)
            if (t.isFinite() && t >= 1e-300 && t < 1e300) assertTrue("$t -> $n", n <= t * 1.5 + 1e-6)
        }
    }

    @Test
    fun alignedMeansKeepEmptyBucketsAsGaps() {
        val sums = doubleArrayOf(10.0, 0.0, 30.0, Double.NaN)
        val counts = intArrayOf(2, 0, 3, 1)
        assertEquals(listOf(5.0, null, 10.0, null), SeriesPlot.alignedMeans(sums, counts))
    }

    @Test
    fun everySampleOfANoisySeriesLandsInsideTheFrame() {
        val rnd = Random(11)
        val values = List(100_000) {
            when (rnd.nextInt(20)) {
                0 -> null
                1 -> Double.NaN
                2 -> Double.POSITIVE_INFINITY
                3 -> Double.NEGATIVE_INFINITY
                else -> rnd.nextDouble(-500.0, 3500.0)
            }
        }
        val start = System.nanoTime()
        val f = SeriesPlot.frame(values, minSpan = 20.0)
        val ms = (System.nanoTime() - start) / 1e6
        assertTrue("took ${ms}ms", ms < 2_000)
        assertNotNull(f)
        f!!
        assertEquals(values.size, f.count)
        assertTrue(f.samples.size >= 2)
        assertTrue(f.lo.isFinite() && f.hi.isFinite() && f.hi > f.lo)
        var prev = -1
        for (s in f.samples) {
            assertTrue(s.index > prev)
            prev = s.index
            assertTrue(values[s.index] == s.value)
            val y = SeriesPlot.yFraction(s.value, f.lo, f.hi)
            assertTrue("y=$y v=${s.value}", y.isFinite() && y in 0.0..1.0)
        }
    }

    @Test
    fun antimeridianHopStaysShort() {
        val pts = LocalPlane.project(listOf(LatLon(0.0, 179.0), LatLon(0.0, -179.0)))
        val dx = abs(pts[1].eastM - pts[0].eastM)
        assertTrue("east separation $dx m", dx in 200_000.0..250_000.0)
        assertEquals(0.0, pts[1].northM, 1.0)
    }

    @Test
    fun localPlaneOfOnePointIsTheOrigin() {
        val pts = LocalPlane.project(listOf(LatLon(46.5, 7.9)))
        assertEquals(1, pts.size)
        assertEquals(0.0, pts[0].eastM, 1e-6)
        assertEquals(0.0, pts[0].northM, 1e-6)
        assertTrue(LocalPlane.project(emptyList()).isEmpty())
    }

    @Test
    fun skyPolarDropsBelowHorizonAndNonFinite() {
        val zenith = SkyPolar.project(12.0, 90.0)!!
        assertEquals(0.0, zenith.first, 1e-9)
        assertEquals(0.0, zenith.second, 1e-9)
        val north = SkyPolar.project(0.0, 0.0)!!
        assertEquals(0.0, north.first, 1e-9)
        assertEquals(1.0, north.second, 1e-9)
        val east = SkyPolar.project(90.0, 0.0)!!
        assertEquals(1.0, east.first, 1e-9)
        assertEquals(0.0, east.second, 1e-9)
        assertNull(SkyPolar.project(10.0, -0.1))
        assertNull(SkyPolar.project(10.0, 90.1))
        assertNull(SkyPolar.project(Double.NaN, 20.0))
        assertTrue(SkyPolar.layout(listOf(SkyBody(0.0, -5.0, true, "down"))).isEmpty())
    }

    @Test
    fun nearestSatelliteIgnoresBadQueriesAndTies() {
        val marks = SkyPolar.layout(
            listOf(
                SkyBody(0.0, 45.0, true, "a"),
                SkyBody(90.0, 45.0, false, "b"),
            ),
        )
        assertEquals(0, SkyPolar.nearest(marks, marks[0].x, marks[0].y, 0.2)!!.index)
        assertNull(SkyPolar.nearest(marks, 0.0, 0.0, 0.01))
        assertNull(SkyPolar.nearest(marks, Double.NaN, 0.0, 1.0))
        assertNull(SkyPolar.nearest(emptyList(), 0.0, 0.0, 1.0))
        assertNull(SkyPolar.nearest(marks, 0.0, 0.0, -1.0))
    }

    @Test
    fun nearestOnAPackedSkyStaysTheTouchedBody() {
        val bodies = ArrayList<SkyBody>(180)
        for (az in 0 until 360 step 4) {
            bodies += SkyBody(az.toDouble(), 30.0, az % 8 == 0, "$az")
        }
        val marks = SkyPolar.layout(bodies)
        assertEquals(bodies.size, marks.size)
        for (m in marks) {
            val hit = SkyPolar.nearest(marks, m.x, m.y, 0.05)
            assertEquals(m.index, hit?.index)
        }
    }

    @Test
    fun fractionToEpochStaysInsideTheWindow() {
        val start = 1_000L
        val end = 1_000L + 86_400_000L
        assertEquals(start, SeriesPlot.fractionToEpoch(0.0, start, end))
        assertEquals(end - 1, SeriesPlot.fractionToEpoch(1.0, start, end))
        assertEquals(start + 43_200_000L, SeriesPlot.fractionToEpoch(0.5, start, end))
        assertEquals(start, SeriesPlot.fractionToEpoch(Double.NaN, start, end))
        assertEquals(start, SeriesPlot.fractionToEpoch(-2.0, start, end))
        assertEquals(start, SeriesPlot.fractionToEpoch(0.5, start, start))
    }

    @Test
    fun rangesSkipBrokenSlotsAndSwapReversedEnds() {
        val parsed = SeriesPlot.ranges(listOf(1.0, null, 8.0), listOf(3.0, 9.0, 2.0), minSpan = 0.0)
        assertNotNull(parsed)
        val (frame, bars) = parsed!!
        assertEquals(listOf(0, 2), bars.map { it.index })
        assertEquals(1.0, bars[0].low, 0.0)
        assertEquals(3.0, bars[0].high, 0.0)
        assertEquals(2.0, bars[1].low, 0.0)
        assertEquals(8.0, bars[1].high, 0.0)
        assertTrue(frame.lo <= 1.0 && frame.hi >= 8.0)
        assertNull(SeriesPlot.ranges(listOf(null, Double.NaN), listOf(1.0, 2.0)))
        assertNull(SeriesPlot.ranges(emptyList(), emptyList()))
    }

    @Test
    fun alignedMeansRejectsMismatchedArrays() {
        assertThrows(IllegalArgumentException::class.java) {
            SeriesPlot.alignedMeans(doubleArrayOf(1.0), intArrayOf(1, 1))
        }
    }
}
