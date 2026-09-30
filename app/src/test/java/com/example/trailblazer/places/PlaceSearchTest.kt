package com.example.trailblazer.places

import android.location.Address
import android.location.Geocoder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Waypoint
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowGeocoder
import java.io.IOException
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlaceSearchTest {
    private class Counting(val answer: suspend (String) -> List<FoundPlace>) : PlaceSearchBackend {
        val queries = mutableListOf<String>()
        override suspend fun lookup(query: String, max: Int): List<FoundPlace> {
            queries += query
            return answer(query)
        }
    }

    private val zermatt = FoundPlace(LatLon(46.0207, 7.7491), "Zermatt", "Valais, Switzerland")

    @After
    fun resetGeocoder() = ShadowGeocoder.reset()

    @Test
    fun nothingIsSentBeforeConsentOrForABadQuery() = runTest {
        val b = Counting { listOf(zermatt) }
        val s = PlaceSearch(b) { true }
        assertEquals(SearchResult.NotConsented, s.search("Zermatt", consented = false))
        assertTrue(s.search("Z", consented = true) is SearchResult.Refused)
        assertTrue(s.search("x".repeat(201), consented = true) is SearchResult.Refused)
        assertTrue(s.search("   ", consented = true) is SearchResult.Refused)
        assertEquals(0, b.queries.size)
    }

    @Test
    fun noGeocoderIsSaidOutLoudNotAnEmptyList() = runTest {
        val b = Counting { listOf(zermatt) }
        assertEquals(SearchResult.NotAvailable, PlaceSearch(b) { false }.search("Zermatt", consented = true))
        assertEquals(0, b.queries.size)
    }

    @Test
    fun onlyTheTidiedQueryIsSentAndResultsAreDedupedAndCapped() = runTest {
        val many = List(9) { i -> FoundPlace(LatLon(46.0 + i, 7.0), "Place $i") } + zermatt + zermatt + FoundPlace(LatLon(1.0, 1.0), " ")
        val b = Counting { many }
        val r = PlaceSearch(b) { true }.search("  Zermatt \n  Valais ", consented = true) as SearchResult.Ok
        assertEquals(listOf("Zermatt Valais"), b.queries)
        assertEquals(PlaceSearch.MAX_RESULTS, r.places.size)
        assertTrue(r.places.none { it.name.isBlank() })
    }

    @Test
    fun offlineAndHangingServicesBecomeMessages() = runTest {
        val offline = PlaceSearch(Counting { throw IOException("grpc failed") }) { true }.search("Zermatt", true)
        assertTrue(offline is SearchResult.Failed && offline.message.contains("offline"))
        val hang = PlaceSearch(Counting { delay(60_000); emptyList() }) { true }.search("Zermatt", true)
        assertTrue(hang is SearchResult.Failed && hang.message.contains("too long"))
    }

    @Test
    fun theRealGeocoderPathTurnsAddressesIntoPlaces() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        fun address(lat: Double?, lon: Double?, feature: String?, line: String?, locality: String?) = Address(Locale.US).apply {
            lat?.let { latitude = it }
            lon?.let { longitude = it }
            featureName = feature
            line?.let { setAddressLine(0, it) }
            this.locality = locality
            adminArea = "Colorado"
            countryName = "United States"
        }
        val geocoder = Geocoder(app)
        shadowOf(geocoder).setFromLocation(
            listOf(
                address(40.3428, -105.6836, "Rocky Mountain National Park", null, "Estes Park"),
                address(39.7392, -104.9903, "1600", "1600 Broadway, Denver, CO", "Denver"),
                address(null, null, "Nowhere", null, null),
            ),
        )
        val found = runBlocking { GeocoderBackend(app) { geocoder }.lookup("rocky", 5) }
        assertEquals(2, found.size)
        assertEquals("Rocky Mountain National Park", found[0].name)
        assertEquals("Estes Park, Colorado, United States", found[0].detail)
        assertEquals("a bare house number is not a name", "1600 Broadway, Denver, CO", found[1].name)
        assertEquals("parts already in the name are not repeated", "Colorado, United States", found[1].detail)
    }

    @Test
    fun savedPlacesAreNearestFirstFilteredAndDeduped() {
        val home = LatLon(46.62, 8.03) // Grindelwald
        val w = listOf(
            Waypoint("w1", "Zermatt camp", LatLon(46.0207, 7.7491), null, 0, null),
            Waypoint("w2", "Hut", LatLon(46.6, 8.0), null, 0, null),
        )
        val trips = listOf(
            Trip("t1", "Alps", listOf(Stop("s1", "Hut", StopKind.Start, LatLon(46.6, 8.0)), Stop("s2", "Geneva", StopKind.End, LatLon(46.2, 6.14)))),
            Trip("t2", "This one", listOf(Stop("s3", "Bern", StopKind.Start, LatLon(46.95, 7.44)))),
        )
        val all = SavedPlaces.list(w, trips, home, excludeTripId = "t2")
        assertEquals(listOf("Hut", "Zermatt camp", "Geneva"), all.map { it.place.name })
        assertTrue(all.zipWithNext().all { (a, b) -> a.distanceM!! <= b.distanceM!! })
        assertEquals(listOf("Geneva"), SavedPlaces.list(w, trips, home, filter = "gen").map { it.place.name })
        assertEquals("filter also matches the trip it came from", 2, SavedPlaces.list(w, trips, home, filter = "alps").size)
        assertNull("no position, no distances", SavedPlaces.list(w, trips, null).first().distanceM)
    }
}
