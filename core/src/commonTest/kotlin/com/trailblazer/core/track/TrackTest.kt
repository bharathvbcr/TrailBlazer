package com.trailblazer.core.track

import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random

class TrackTest {
    @Test
    fun statsDistanceMovingAndHysteresis() {
        val s = TrackStats()
        val start = LatLon(46.0, 7.0)
        var t = 0L
        // 10 points 100 m apart north, 20 s apart (5 m/s), elevation noisy ±1 m around a 50 m climb.
        val noise = listOf(0.0, 1.0, -1.0, 0.5, -0.5, 1.0, 0.0, -1.0, 1.0, 0.0)
        for (i in 0 until 10) {
            val p = Geo.destination(start, 0.0, i * 100.0)
            s.add(TrackPoint(t, p, 1000.0 + i * 50.0 / 9 + noise[i], speedMps = 5.0))
            t += 20_000
        }
        assertEquals(900.0, s.distanceM, 1.0)
        assertEquals(180_000L, s.movingMs)
        assertEquals(5.0, s.maxSpeedMps!!, 0.0)
        assertTrue(s.gainM in 45.0..55.0, "gain ${s.gainM}")
        assertEquals(0.0, s.lossM, 0.0)
    }

    @Test
    fun jitterWhileStationaryAddsNoClimbOrMovingTime() {
        val s = TrackStats()
        val rnd = Random(7)
        val c = LatLon(46.0, 7.0)
        for (i in 0 until 600) {
            s.add(TrackPoint(i * 1000L, c, 1000.0 + rnd.nextDouble(-1.4, 1.4), speedMps = 0.1))
        }
        assertEquals(0.0, s.gainM, 0.0)
        assertEquals(0L, s.movingMs)
        assertNull(s.averageMovingSpeedMps)
    }

    @Test
    fun douglasPeuckerOnHundredThousandPointsStaysBounded() {
        val rnd = Random(3)
        var p = LatLon(46.0, 7.0)
        var bearing = 0.0
        val pts = ArrayList<LatLon>(100_000)
        repeat(100_000) {
            bearing += rnd.nextDouble(-15.0, 15.0)
            p = Geo.destination(p, bearing, 5.0)
            pts += p
        }
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val simplified = DouglasPeucker.simplifyToMax(pts, 2000) { it }
        val ms = mark.elapsedNow().inWholeMilliseconds
        assertTrue(simplified.size in 2..2000)
        assertEquals(pts.first(), simplified.first())
        assertEquals(pts.last(), simplified.last())
        assertTrue(ms < 20_000, "took $ms ms")
    }

    @Test
    fun douglasPeuckerKeepsCorners() {
        val a = LatLon(0.0, 0.0)
        val corner = LatLon(0.0, 0.01)
        val b = LatLon(0.01, 0.01)
        val line = listOf(a, LatLon(0.0, 0.005), corner, LatLon(0.005, 0.01), b)
        assertEquals(listOf(a, corner, b), DouglasPeucker.simplify(line, 5.0) { it })
    }
}
