package com.trailblazer.core.trip

import com.trailblazer.core.astro.SolarEvents
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.intents.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial cases for the trip planner: dates that go backwards across an undated stop,
 * positions that are the same place without being the same bits, and map links that must
 * not throw or drop points.
 */
class TripStressTest {
    private fun stop(
        i: Int,
        lat: Double,
        lon: Double,
        id: String = "s$i",
        kind: StopKind = StopKind.Visit,
        day: Long? = null,
    ) = Stop(id, "S$i", kind, LatLon(lat, lon), day)

    @Test
    fun datesGoingBackwardsAcrossAnUndatedStopAreRejected() {
        val stops = listOf(
            stop(0, 46.0, 7.0, day = 10),
            stop(1, 46.2, 7.0, day = null),
            stop(2, 46.4, 7.0, day = 3),
        )
        assertTrue(TripRules.check(stops).contains(TripRules.Problem.DatesOutOfOrder(2)))
    }

    @Test
    fun theSameDayAfterAGapIsFine() {
        val stops = listOf(
            stop(0, 46.0, 7.0, day = 5),
            stop(1, 46.2, 7.0, day = null),
            stop(2, 46.4, 7.0, day = 5),
        )
        assertFalse(TripRules.check(stops).any { it is TripRules.Problem.DatesOutOfOrder })
    }

    @Test
    fun antimeridianAndPolesAreTheSamePlace() {
        val dateline = listOf(stop(0, 0.0, 180.0), stop(1, 0.0, -180.0))
        val pole = listOf(stop(0, 90.0, 0.0), stop(1, 90.0, 45.0))
        assertTrue(Geo.distanceM(dateline[0].position, dateline[1].position) < 1.0)
        assertTrue(Geo.distanceM(pole[0].position, pole[1].position) < 1.0)
        assertTrue(TripRules.check(dateline).contains(TripRules.Problem.DuplicateAdjacent(1)))
        assertTrue(TripRules.check(pole).contains(TripRules.Problem.DuplicateAdjacent(1)))
    }

    @Test
    fun subMetreTwinsAreTheSamePlaceAndTwentyMetresAreNot() {
        val twins = listOf(stop(0, 46.0, 7.0), stop(1, 46.0 + 1e-6, 7.0))
        val apart = listOf(stop(0, 46.0, 7.0), stop(1, 46.0 + 20.0 / 111_195.0, 7.0))
        assertTrue(Geo.distanceM(twins[0].position, twins[1].position) < 1.0)
        assertTrue(Geo.distanceM(apart[0].position, apart[1].position) > 15.0)
        assertTrue(TripRules.check(twins).contains(TripRules.Problem.DuplicateAdjacent(1)))
        assertFalse(TripRules.check(apart).any { it is TripRules.Problem.DuplicateAdjacent })
    }

    @Test
    fun repeatedIdsAreRejected() {
        val dup = listOf(stop(0, 46.0, 7.0, id = "same"), stop(1, 47.0, 8.0, id = "same"))
        assertTrue(TripRules.check(dup).contains(TripRules.Problem.DuplicateId(1)))
        val many = (0 until 51).map { stop(it, 10.0 + it * 0.05, 20.0) }
        assertTrue(TripRules.check(many).contains(TripRules.Problem.TooManyStops))
        assertFalse(TripRules.check(many.take(TripRules.MAX_STOPS)).contains(TripRules.Problem.TooManyStops))
    }

    @Test
    fun legsStayFiniteAtThePolesAndAcrossTheDateline() {
        val stops = listOf(
            stop(0, 90.0, 0.0),
            stop(1, 90.0, 90.0),
            stop(2, 0.0, 180.0),
            stop(3, 0.0, -180.0),
            stop(4, -90.0, 10.0),
        )
        val legs = LegCalculator.legs(stops)
        assertEquals(4, legs.size)
        assertTrue(legs.all { it.distanceM.isFinite() && it.distanceM >= 0.0 })
        assertTrue(legs.all { it.initialBearingDeg.isFinite() && it.initialBearingDeg in 0.0..<360.0 })
        assertTrue(LegCalculator.totalM(stops).isFinite())
        assertEquals(0.0, LegCalculator.totalM(emptyList()), 0.0)
        assertEquals(0.0, LegCalculator.legs(stops.take(1)).size.toDouble(), 0.0)
    }

    @Test
    fun aLongChainOfLegsDoesNotOverflow() {
        val stops = (0 until 500).map { stop(it, (it % 170) - 80.0, ((it * 3) % 360) - 180.0) }
        val total = LegCalculator.totalM(stops)
        assertTrue(total.isFinite() && total > 0.0)
        assertEquals(499, LegCalculator.legs(stops).size)
    }

    @Test
    fun daylightSkipsAnInvertedWindowInsteadOfCrashing() {
        val stops = listOf(stop(0, 46.0, 7.0, day = 10), stop(1, 47.0, 8.0, day = 11))
        val rows = DaylightPlanner.plan(stops) { day ->
            if (day == 10L) 5_000L to 1_000L else 0L to 86_400_000L
        }
        assertEquals(listOf("s1"), rows.map { it.stop.id })
        assertTrue(rows.single().day.daylightMs >= 0L)
    }

    @Test
    fun mapLinksDoNotThrowForShortRoutesAndDoNotDropPoints() {
        val one = listOf(LatLon(46.0, 7.0))
        assertEquals(emptyList<String>(), MapLinks.googleDirections(emptyList(), TravelMode.Driving))
        assertEquals(emptyList<String>(), MapLinks.googleDirections(one, TravelMode.Walking))
        assertEquals(emptyList<String>(), MapLinks.osmDirections(emptyList(), TravelMode.Bicycling))
        assertEquals(emptyList<String>(), MapLinks.osmDirections(one, TravelMode.Driving))

        val points = (0 until 100).map { LatLon(10.0 + it * 0.01, 20.0) }
        for (mode in TravelMode.entries) {
            val links = MapLinks.googleDirections(points, mode)
            assertTrue(links.isNotEmpty())
            assertTrue(links.all { it.contains("travelmode=${mode.google}") })
            links.forEach { link ->
                val via = link.substringAfter("waypoints=", "").substringBefore("&")
                val count = if (via.isEmpty()) 0 else via.split("%7C").size
                assertTrue(count <= MapLinks.GOOGLE_MAX_WAYPOINTS, "waypoint count $count")
            }
            for (i in 0 until links.size - 1) {
                val dest = links[i].substringAfter("destination=").substringBefore("&")
                val origin = links[i + 1].substringAfter("origin=").substringBefore("&")
                assertEquals(dest, origin)
            }
            assertTrue(links.first().contains("origin=10.000000,20.000000"))
            assertTrue(links.last().contains("destination=10.990000,20.000000"))
            assertEquals(points.size - 1, MapLinks.osmDirections(points, mode).size)
        }
    }

    @Test
    fun elevenPointsAreOneGoogleLinkAndTwelveAreTwo() {
        val eleven = (0 until 11).map { LatLon(1.0 + it * 0.01, 2.0) }
        val twelve = eleven + LatLon(1.11, 2.0)
        assertEquals(1, MapLinks.googleDirections(eleven, TravelMode.Driving).size)
        val split = MapLinks.googleDirections(twelve, TravelMode.Walking)
        assertEquals(2, split.size)
        assertEquals(
            split[0].substringAfter("destination=").substringBefore("&"),
            split[1].substringAfter("origin=").substringBefore("&"),
        )
    }

    @Test
    fun shareAndGeoLinksStayWellFormed() {
        assertFalse(MapLinks.shareText("  ", LatLon(1.0, 2.0)).startsWith("\n"))
        assertFalse(MapLinks.shareText(null, LatLon(1.0, 2.0)).startsWith("\n"))
        val geo = MapLinks.geo(LatLon(1.0, 2.0), "A/B (North)")
        assertFalse(geo.contains("(") && geo.indexOf('(') != geo.lastIndexOf('('))
        assertTrue(geo.startsWith("geo:1.000000,2.000000?q="))
        assertEquals("trip", TripFiles.exportBase("   "))
        assertEquals("trip", TripFiles.exportBase("///"))
        assertEquals("Camp Lake", TripFiles.exportBase("Camp/Lake\n"))
        assertTrue(TripFiles.exportBase("Z".repeat(80)).length <= 60)
        assertEquals("Zürich", TripFiles.exportBase("Zürich"))
    }

    @Test
    fun stopLabelsCoverTheAlphabetBoundaries() {
        assertEquals("A", StopLabel.of(0))
        assertEquals("Z", StopLabel.of(25))
        assertEquals("AA", StopLabel.of(26))
        assertEquals("AZ", StopLabel.of(51))
        assertEquals("BA", StopLabel.of(52))
        assertEquals("ZZ", StopLabel.of(26 * 26 + 25))
        assertEquals("?", StopLabel.of(-1))
        assertTrue(StopLabel.of(10_000).all { it in 'A'..'Z' })
    }

    @Test
    fun solarDayStillRejectsAnEmptyWindowWhenCalledDirectly() {
        assertFailsWith<IllegalArgumentException> {
            SolarEvents.day(46.0, 7.0, 100L, 100L)
        }
    }
}

