package com.trailblazer.core

import com.trailblazer.core.alerts.SpeedAlert
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.intents.TravelMode
import com.trailblazer.core.math.CircularLowPass
import com.trailblazer.core.math.MedianFilter
import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.math.cardinal16
import com.trailblazer.core.math.crossesNorth
import com.trailblazer.core.math.mod360
import com.trailblazer.core.motion.GForce
import com.trailblazer.core.motion.GSeverity
import com.trailblazer.core.motion.Inclination
import com.trailblazer.core.motion.OrbitCamera
import com.trailblazer.core.motion.STANDARD_GRAVITY
import com.trailblazer.core.motion.Vec3
import com.trailblazer.core.sos.Morse
import com.trailblazer.core.sos.Pulse
import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.time.Iso8601
import com.trailblazer.core.trip.LegCalculator
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.TripRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AnglesTest {
    @Test
    fun wrapAround() {
        assertEquals(0.0, mod360(360.0), 0.0)
        assertEquals(350.0, mod360(-10.0), 1e-9)
        assertEquals(2.0, angleDiff(359.0, 1.0), 1e-9)
        assertEquals(-2.0, angleDiff(1.0, 359.0), 1e-9)
        assertEquals(180.0, angleDiff(0.0, 180.0), 1e-9)
        assertTrue(crossesNorth(359.0, 1.0))
        assertTrue(crossesNorth(1.0, 359.0))
        assertFalse(crossesNorth(10.0, 20.0))
        assertFalse(crossesNorth(179.0, 181.0))
        assertEquals("N", cardinal16(359.0))
        assertEquals("ENE", cardinal16(67.5))
        assertEquals("WNW", cardinal16(292.5))
    }

    @Test
    fun circularFilterAveragesAcrossNorth() {
        val f = CircularLowPass(0.5)
        f.update(359.0)
        val v = f.update(1.0)!!
        assertTrue("got $v", v > 359.5 || v < 0.5)
    }

    @Test
    fun medianRejectsSpike() {
        val m = MedianFilter(5)
        listOf(1000.0, 1000.1, 1000.0, 1500.0, 1000.2).forEach { m.update(it) }
        assertEquals(1000.1, m.update(Double.NaN)!!, 1e-9)
        assertNull(MedianFilter(3).update(Double.NaN))
    }
}

class TimeTest {
    @Test
    fun civilDateMatchesJavaTime() {
        for (d in listOf(-719162L, -1L, 0L, 11_016L, 20_512L, 47_482L)) {
            val (y, m, day) = CivilDate.civilFromDays(d)
            assertEquals(LocalDate.ofEpochDay(d), LocalDate.of(y, m, day))
            assertEquals(d, CivilDate.daysFromCivil(y, m, day))
        }
    }

    @Test
    fun iso8601() {
        assertEquals(Instant.parse("2026-06-21T12:00:00Z").toEpochMilli(), Iso8601.parse("2026-06-21T12:00:00Z"))
        assertEquals(Instant.parse("2026-06-21T10:00:00Z").toEpochMilli(), Iso8601.parse("2026-06-21T12:00:00+02:00"))
        assertEquals(Instant.parse("2028-02-29T00:00:00.123Z").toEpochMilli(), Iso8601.parse("2028-02-29T00:00:00.123456Z"))
        assertNull(Iso8601.parse("2026-02-29T00:00:00Z"))
        assertNull(Iso8601.parse("2026-13-01"))
        assertNull(Iso8601.parse("garbage"))
        assertEquals("2026-06-21T12:00:00Z", Iso8601.format(Instant.parse("2026-06-21T12:00:00Z").toEpochMilli()))
        assertEquals("1969-12-31T23:59:59.999Z", Iso8601.format(-1))
    }
}

class MotionTest {
    @Test
    fun gForceAndSeverity() {
        assertEquals(1.0, GForce.of(STANDARD_GRAVITY)!!, 1e-12)
        assertNull(GForce.of(Double.NaN))
        assertNull(GForce.of(-1.0))
        assertEquals(GSeverity.Normal, GForce.severity(1.2))
        assertEquals(GSeverity.Elevated, GForce.severity(1.5))
        assertEquals(GSeverity.High, GForce.severity(2.5))
        assertEquals(5.0, Vec3(0.0, 3.0, 4.0).magnitude, 0.0)
    }

    @Test
    fun inclination() {
        val flat = Inclination.fromGravity(Vec3(0.0, 0.0, STANDARD_GRAVITY), flat = true)!!
        assertEquals(0.0, flat.pitchDeg, 1e-9)
        assertEquals(0.0, flat.rollDeg, 1e-9)
        val g = STANDARD_GRAVITY
        val tilted = Inclination.fromGravity(Vec3(0.0, g * Math.sin(Math.toRadians(10.0)), g * Math.cos(Math.toRadians(10.0))), flat = true)!!
        assertEquals(10.0, tilted.pitchDeg, 1e-9)
        assertEquals(17.63, tilted.gradePercent, 0.01)
        val upright = Inclination.fromGravity(Vec3(0.0, g, 0.0), flat = false)!!
        assertEquals(0.0, upright.pitchDeg, 1e-9)
        assertEquals(0.0, upright.rollDeg, 1e-9)
        assertNull(Inclination.fromGravity(Vec3(0.0, 0.0, 0.1), flat = true))
        val zeroed = tilted - tilted
        assertEquals(0.0, zeroed.pitchDeg, 0.0)
    }

    @Test
    fun projection() {
        val cam = OrbitCamera(0.0, 0.0, 10.0)
        val o = cam.project(Vec3(0.0, 0.0, 0.0), 200.0, 150.0)
        assertEquals(200.0, o.x, 1e-9)
        assertEquals(150.0, o.y, 1e-9)
        assertTrue(cam.project(Vec3(0.0, 0.0, 10.0), 200.0, 150.0).y < 150.0)
        val wild = OrbitCamera(3600.0, 120.0, 10.0).project(Vec3(5.0, 5.0, 5.0), 100.0, 100.0)
        assertTrue(wild.x.isFinite() && wild.y.isFinite() && wild.depth.isFinite())
    }
}

class SosAndAlertTest {
    @Test
    fun sosTimeline() {
        assertEquals("... --- ...", Morse.code("sos"))
        val t = Morse.SOS
        val ons = t.filter { it.on }.map { if (it.durationMs == 200L) '.' else '-' }.joinToString("")
        assertEquals("...---...", ons)
        assertTrue(t.filter { !it.on }.dropLast(1).all { it.durationMs == 200L })
        assertEquals(Pulse(false, 1400), t.last())
        assertEquals(6, Morse.whistleTimeline().size)
    }

    @Test
    fun speedAlertRearms() {
        val a = SpeedAlert(limitMps = 25.0)
        assertFalse(a.update(24.0))
        assertTrue(a.update(26.0))
        assertFalse(a.update(27.0))
        assertFalse(a.update(24.0)) // inside hysteresis band
        assertFalse(a.update(23.0)) // re-armed
        assertTrue(a.update(25.5))
        assertFalse(a.update(null))
        assertFalse(a.update(Double.NaN))
    }
}

class TripAndLinksTest {
    private fun stop(i: Int, lat: Double, lon: Double, kind: StopKind = StopKind.Visit, day: Long? = null) =
        Stop("s$i", "S$i", kind, LatLon(lat, lon), day)

    @Test
    fun legsAndRules() {
        val stops = listOf(stop(0, 46.0, 7.0, StopKind.Start, 1), stop(1, 46.1, 7.0, day = 2), stop(2, 46.1, 7.0, day = 1))
        assertEquals(2, LegCalculator.legs(stops).size)
        assertEquals(11_119.0, LegCalculator.totalM(stops), 5.0)
        val problems = TripRules.check(stops)
        assertTrue(problems.contains(TripRules.Problem.DuplicateAdjacent(2)))
        assertTrue(problems.contains(TripRules.Problem.DatesOutOfOrder(2)))
        assertEquals(listOf(TripRules.Problem.TooFewStops), TripRules.check(stops.take(1)))
    }

    @Test
    fun googleLinksChunkAtNineWaypoints() {
        val pts = (0 until 25).map { LatLon(46.0 + it * 0.01, 7.0) }
        val links = MapLinks.googleDirections(pts, TravelMode.Driving)
        assertEquals(3, links.size)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=46.000000,7.000000&destination=46.100000,7.000000" +
                "&waypoints=46.010000,7.000000%7C46.020000,7.000000%7C46.030000,7.000000%7C46.040000,7.000000%7C46.050000,7.000000%7C46.060000,7.000000%7C46.070000,7.000000%7C46.080000,7.000000%7C46.090000,7.000000&travelmode=driving",
            links[0],
        )
        assertTrue(links[1].contains("origin=46.100000,7.000000"))
        assertTrue(links[2].contains("destination=46.240000,7.000000"))
        for (l in links) assertTrue(l.substringAfter("waypoints=", "").split("%7C").size <= 9)
    }

    @Test
    fun geoAndOsmLinks() {
        assertEquals("geo:1.500000,-2.250000?q=1.500000,-2.250000(Camp%20%26%20Lake)", MapLinks.geo(LatLon(1.5, -2.25), "Camp & (Lake)"))
        assertEquals("geo:1.500000,-2.250000?q=1.500000,-2.250000", MapLinks.geo(LatLon(1.5, -2.25), " "))
        assertEquals(
            listOf("https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=1.000000,2.000000%3B3.000000,4.000000"),
            MapLinks.osmDirections(listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0)), TravelMode.Walking),
        )
        assertTrue(MapLinks.shareText("Hut", LatLon(1.0, 2.0)).startsWith("Hut\n1.000000, 2.000000\nhttps://www.openstreetmap.org/"))
    }
}
