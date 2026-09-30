package com.example.trailblazer.ui.trips

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.sensors.FakeSensorSource
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.ParsedPlace
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import com.trailblazer.core.trip.TripRules
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import com.example.trailblazer.testing.awaitMainLooper

/**
 * The editor is the gate in front of the database. These cases are the ones the screen can
 * reach: saving too early, dating a later stop before an earlier one, pasting a long route,
 * and typing before the saved trip has loaded.
 */
@RunWith(AndroidJUnit4::class)
class TripEditViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrailDb
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        db = Room.inMemoryDatabaseBuilder(app, TrailDb::class.java).allowMainThreadQueries().build()
        container = AppContainer(app, sensorSource = FakeSensorSource(emptySet()), db = db)
        container.permissions.refresh()
    }

    @After
    fun tearDown() {
        container.scope.cancel()
        db.close()
    }

    private fun settle() {
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun places(n: Int) = (0 until n).map { ParsedPlace(LatLon(10.0 + it * 0.05, 20.0), "P$it") }

    @Test
    fun aSharedPlaceGoesBeforeTheDestinationOfAnExistingTripAndKeepsItsStops() {
        val saved = Trip("t1", "Alps", places(3).mapIndexed { i, p -> Stop("s$i", p.label!!, StopKind.Visit, p.position) })
        runBlocking { container.trips.save(saved) }
        val shared = ParsedPlace(LatLon(46.0207, 7.7491), "Zermatt")
        val vm = TripEditViewModel(container, "t1", listOf(shared))
        awaitMainLooper(5_000) { vm.loaded.value && vm.stops.value.size == 4 }
        // Seeding before the load finished used to leave only the shared stop, which Save then wrote over the trip.
        assertEquals(listOf("P0", "P1", "Zermatt", "P2"), vm.stops.value.map { it.name })
        assertEquals(StopKind.End, vm.stops.value.last().kind)
        assertTrue(vm.dirty.value)
        var done = false
        vm.save { done = true }
        awaitMainLooper(5_000) { done }
        assertEquals(4, runBlocking { container.trips.observe("t1").first() }!!.stops.size)
    }

    @Test
    fun aSharedPlaceStartsANewTrip() {
        val vm = TripEditViewModel(container, null, listOf(ParsedPlace(LatLon(46.0207, 7.7491), "Zermatt")))
        assertEquals(listOf("Zermatt"), vm.stops.value.map { it.name })
        assertTrue(vm.dirty.value)
    }

    @Test
    fun aSharedPlaceDoesNotOverfillATrip() {
        val saved = Trip("full", "Long", places(TripRules.MAX_STOPS).mapIndexed { i, p -> Stop("f$i", p.label!!, StopKind.Visit, p.position) })
        runBlocking { container.trips.save(saved) }
        val vm = TripEditViewModel(container, "full", listOf(ParsedPlace(LatLon(46.0, 7.0), "Extra")))
        awaitMainLooper(5_000) { vm.loaded.value && vm.message.value != null }
        assertEquals(TripRules.MAX_STOPS, vm.stops.value.size)
        assertTrue(vm.message.value!!.contains("were not added"))
    }

    @Test
    fun saveRefusesASingleStop() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(1))
        var done = false
        vm.save { done = true }
        settle()
        assertFalse(done)
        assertTrue(runBlocking { container.trips.all.first() }.isEmpty())
    }

    @Test
    fun saveRefusesADateThatGoesBackwards() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(2))
        vm.setDay(0, 10)
        vm.setDay(1, 3)
        var done = false
        vm.save { done = true }
        settle()
        assertFalse(done)
        assertEquals("Fix the problems on this trip before saving.", vm.message.value)
        assertTrue(runBlocking { container.trips.all.first() }.isEmpty())
    }

    @Test
    fun savePersistsTwoDistinctStops() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(2))
        var done = false
        vm.save { done = true }
        awaitMainLooper(5_000) { done }
        assertTrue(done)
        val stored = runBlocking { container.trips.all.first() }
        assertEquals(1, stored.size)
        assertEquals(2, stored.single().stops.size)
        assertEquals(StopKind.Start, stored.single().stops.first().kind)
        assertEquals(StopKind.End, stored.single().stops.last().kind)
        assertTrue(TripRules.check(stored.single().stops).isEmpty())
    }

    @Test
    fun undoingARemovalRestoresTheTripExactly() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(4))
        vm.setKind(1, StopKind.Night)
        val before = vm.stops.value
        vm.remove(0) // removing the start re-labels its neighbour as the new start
        assertEquals(StopKind.Start, vm.stops.value.first().kind)
        assertEquals("P0", vm.lastRemoved.value?.stop?.name)
        vm.undoRemove()
        assertEquals("the night stop must get its kind back, not become a visit", before, vm.stops.value)
        assertNull(vm.lastRemoved.value)
    }

    @Test
    fun undoIsOfferedOnlyUntilTheNextEdit() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(3))
        vm.remove(1)
        vm.renameStop(0, "Home")
        assertNull("another edit drops the undo", vm.lastRemoved.value)
        val after = vm.stops.value
        vm.undoRemove()
        assertEquals("a stale undo changes nothing", after, vm.stops.value)
        vm.remove(99) // out of range: nothing removed, nothing to undo
        assertNull(vm.lastRemoved.value)
        assertEquals(after, vm.stops.value)
    }

    @Test
    fun pastingMoreThanFiftyStopsKeepsTheFirstFifty() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(60))
        assertEquals(TripRules.MAX_STOPS, vm.stops.value.size)
        assertTrue(vm.message.value!!.contains("50"))
        assertTrue(vm.dirty.value)
    }

    @Test
    fun aSecondPasteDoesNotPushPastTheCap() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(50))
        vm.addPlaces(places(5))
        assertEquals(TripRules.MAX_STOPS, vm.stops.value.size)
    }

    @Test
    fun editsMadeBeforeTheSavedTripLoadsAreKept() {
        runBlocking {
            container.trips.save(
                Trip(
                    "t1",
                    "Original",
                    listOf(
                        Stop("a", "A", StopKind.Start, LatLon(46.0, 7.0)),
                        Stop("b", "B", StopKind.End, LatLon(47.0, 8.0)),
                    ),
                ),
            )
        }
        val vm = TripEditViewModel(container, "t1")
        assertEquals("the load must still be in flight", "New trip", vm.name.value)
        vm.rename("Edited")
        vm.addPlaces(listOf(ParsedPlace(LatLon(48.0, 9.0), "C")))
        awaitMainLooper(5_000) { vm.loaded.value }
        assertEquals("Edited", vm.name.value)
        assertEquals(1, vm.stops.value.size)
        assertEquals("C", vm.stops.value.single().name)
    }

    @Test
    fun aMissingTripIsReportedAndNotInvented() {
        val vm = TripEditViewModel(container, "gone")
        awaitMainLooper(5_000) { vm.loaded.value }
        assertTrue(vm.loaded.value)
        assertEquals("This trip is no longer on this phone.", vm.message.value)
        assertTrue(vm.stops.value.isEmpty())
    }

    @Test
    fun movingAStopKeepsStartFirstAndEndLast() {
        val vm = TripEditViewModel(container, null)
        vm.addPlaces(places(3))
        vm.setKind(1, StopKind.Night)
        assertEquals(StopKind.Night, vm.stops.value[1].kind)
        vm.move(2, -1)
        assertEquals(StopKind.Start, vm.stops.value.first().kind)
        assertEquals(StopKind.End, vm.stops.value.last().kind)
        assertEquals(StopKind.Visit, vm.stops.value[1].kind)
    }

    @Test
    fun locationAlreadyGrantedDoesNotWaitOnNotifications() {
        val ask = permissionsToRequest(
            locationGranted = false,
            notificationsGranted = false,
            location = arrayOf("loc"),
            notifications = arrayOf("note"),
        )
        assertEquals(listOf("loc", "note"), ask!!.toList())
        assertNull(permissionsToRequest(true, false, arrayOf("loc"), arrayOf("note")))
        assertEquals(listOf("loc"), permissionsToRequest(false, true, arrayOf("loc"), arrayOf("note"))!!.toList())
    }

    @Test
    fun tripCardsSayWhereTheyGoAndHowLongTheyAre() {
        assertEquals(null, tripRouteLine(emptyList()))
        assertEquals("Hut", tripRouteLine(listOf("Hut")))
        assertEquals("Trailhead → Hut", tripRouteLine(listOf("Trailhead", "Camp", "Hut")))
        assertEquals("1 stop · 4 km straight-line", tripMeta(1, 0, "4 km"))
        assertEquals("3 stops · 12 km straight-line · 1 night", tripMeta(3, 1, "12 km"))
        assertEquals("4 stops · 20 km straight-line · 2 nights", tripMeta(4, 2, "20 km"))
        assertEquals("1 trip · 0 waypoints · 2 tracks", librarySummary(1, 0, 2))
        assertEquals("0 trips · 1 waypoint · 0 tracks", librarySummary(0, 1, 0))
    }
}
