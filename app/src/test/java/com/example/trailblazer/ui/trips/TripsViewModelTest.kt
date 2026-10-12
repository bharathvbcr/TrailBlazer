package com.example.trailblazer.ui.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the Trips screen's ViewModel does with the library: listing, deleting, renaming and choosing a target. */
@RunWith(AndroidJUnit4::class)
class TripsViewModelTest {
    private val fx = ContainerFixture()
    private val repo get() = fx.container

    @After
    fun tearDown() = fx.close()

    private fun trip(id: String, name: String) =
        Trip(id, name, listOf(Stop("$id-a", "A", StopKind.Start, LatLon(46.0, 7.0)), Stop("$id-b", "B", StopKind.End, LatLon(47.0, 8.0))))

    private fun viewModel(): TripsViewModel = TripsViewModel(fx.container).also { vm ->
        fx.scope.launch { vm.trips.collect {} }
        fx.scope.launch { vm.waypoints.collect {} }
        fx.scope.launch { vm.tracks.collect {} }
        fx.scope.launch { vm.settings.collect {} }
    }

    @Test
    fun theLibraryListsWhatIsStored() {
        runBlocking {
            repo.trips.save(trip("t1", "Alps"))
            repo.waypoints.add("Hut", LatLon(46.5, 7.5), 2_100.0)
        }
        val vm = viewModel()
        awaitMainLooper(5_000) { vm.trips.value.size == 1 && vm.waypoints.value.size == 1 }

        assertEquals(listOf("Alps"), vm.trips.value.map { it.name })
        assertEquals(listOf("Hut"), vm.waypoints.value.map { it.name })
        assertTrue(vm.tracks.value.isEmpty())
    }

    @Test
    fun deletingATripRemovesOnlyThatTrip() {
        runBlocking { repo.trips.save(trip("t1", "Alps")); repo.trips.save(trip("t2", "Jura")) }
        val vm = viewModel()
        awaitMainLooper(5_000) { vm.trips.value.size == 2 }

        vm.deleteTrip("t1")
        awaitMainLooper(5_000) { vm.trips.value.size == 1 }

        assertEquals(listOf("Jura"), vm.trips.value.map { it.name })
    }

    @Test
    fun deletingTheWaypointBeingNavigatedToClearsTheTarget() {
        val w = runBlocking { repo.waypoints.add("Hut", LatLon(46.5, 7.5), null) }
        val vm = viewModel()
        vm.navigateTo(w.id)
        awaitMainLooper(5_000) { vm.settings.value?.targetWaypointId == w.id }

        vm.deleteWaypoint(w.id)
        awaitMainLooper(5_000) { vm.waypoints.value.isEmpty() && vm.settings.value?.targetWaypointId == null }

        assertNull(runBlocking { repo.prefs.settings.first().targetWaypointId })
    }

    @Test
    fun deletingAnotherWaypointKeepsTheTarget() {
        val keep = runBlocking { repo.waypoints.add("Hut", LatLon(46.5, 7.5), null) }
        val drop = runBlocking { repo.waypoints.add("Pass", LatLon(46.6, 7.6), null) }
        val vm = viewModel()
        vm.navigateTo(keep.id)
        awaitMainLooper(5_000) { vm.settings.value?.targetWaypointId == keep.id }

        vm.deleteWaypoint(drop.id)
        awaitMainLooper(5_000) { vm.waypoints.value.size == 1 }

        assertEquals(keep.id, runBlocking { repo.prefs.settings.first().targetWaypointId })
    }

    @Test
    fun renamingAWaypointKeepsItsPosition() {
        val w = runBlocking { repo.waypoints.add("Hut", LatLon(46.5, 7.5), 2_100.0) }
        val vm = viewModel()
        awaitMainLooper(5_000) { vm.waypoints.value.size == 1 }

        vm.renameWaypoint(w.id, "  Cabane  ")
        awaitMainLooper(5_000) { vm.waypoints.value.firstOrNull()?.name == "Cabane" }

        assertEquals(LatLon(46.5, 7.5), vm.waypoints.value.single().position)
    }
}
