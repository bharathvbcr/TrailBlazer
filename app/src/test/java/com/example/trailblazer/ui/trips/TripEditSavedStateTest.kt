package com.example.trailblazer.ui.trips

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.afterProcessDeath
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.ParsedPlace
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import com.trailblazer.core.trip.TripRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trip editor's unsaved work must come back when the process is killed. Each case builds the second editor from
 * what the system would keep: the first one's SavedStateHandle written to a Bundle and read back, not the same object.
 */
@RunWith(AndroidJUnit4::class)
class TripEditSavedStateTest {
    private val fx = ContainerFixture()

    @After
    fun tearDown() = fx.close()

    private fun places(n: Int) = (0 until n).map { ParsedPlace(LatLon(10.0 + it * 0.05, 20.0), "P$it") }

    @Test
    fun aNewTripsEditsComeBackWithTheSameIdNameStopsAndDirtyFlag() {
        val handle = SavedStateHandle()
        val first = TripEditViewModel(fx.container, null, saved = handle)
        first.addPlaces(places(4))
        first.rename("Alps")
        first.setKind(1, StopKind.Night)
        first.setDay(2, 20_000)
        first.renameStop(3, "Hut")

        val second = TripEditViewModel(fx.container, null, saved = handle.afterProcessDeath())

        assertEquals("a second save must overwrite this trip, not add another", first.id, second.id)
        assertEquals("Alps", second.name.value)
        assertEquals(first.stops.value, second.stops.value)
        assertEquals(StopKind.Night, second.stops.value[1].kind)
        assertEquals(20_000L, second.stops.value[2].plannedDay)
        assertTrue(second.dirty.value)
    }

    @Test
    fun aSharedPlaceIsNotAddedAgainWhenTheEditorIsRestored() {
        val handle = SavedStateHandle()
        val seed = listOf(ParsedPlace(LatLon(46.0207, 7.7491), "Zermatt"))
        val first = TripEditViewModel(fx.container, null, seed, handle)
        assertEquals(1, first.stops.value.size)

        val second = TripEditViewModel(fx.container, null, seed, handle.afterProcessDeath())

        assertEquals("the restored editor already holds the shared place", listOf("Zermatt"), second.stops.value.map { it.name })
    }

    @Test
    fun editsToASavedTripAreNotReplacedByTheStoredCopyWhenRestored() {
        runBlocking {
            fx.container.trips.save(Trip("t1", "Original", listOf(Stop("a", "A", StopKind.Start, LatLon(46.0, 7.0)), Stop("b", "B", StopKind.End, LatLon(47.0, 8.0)))))
        }
        val handle = SavedStateHandle()
        val first = TripEditViewModel(fx.container, "t1", saved = handle)
        awaitMainLooper(5_000) { first.loaded.value }
        first.rename("Edited")
        first.addPlaces(places(1))

        val second = TripEditViewModel(fx.container, "t1", saved = handle.afterProcessDeath())
        awaitMainLooper(5_000) { second.loaded.value }

        assertEquals("Edited", second.name.value)
        assertEquals(3, second.stops.value.size)
        assertTrue(second.dirty.value)
    }

    @Test
    fun aSavedEditorIsNotDirtyAfterwards() {
        val handle = SavedStateHandle()
        val first = TripEditViewModel(fx.container, null, saved = handle)
        first.addPlaces(places(2))
        var done = false
        first.save { done = true }
        awaitMainLooper(5_000) { done }

        val second = TripEditViewModel(fx.container, null, saved = handle.afterProcessDeath())

        assertFalse("nothing is waiting to be saved", second.dirty.value)
        assertEquals(2, second.stops.value.size)
    }

    @Test
    fun malformedSavedStopsAreDroppedAndTheListIsCapped() {
        fun bundle(id: String?, lat: Double, lon: Double, kind: String = "Visit") = Bundle().apply {
            id?.let { putString("id", it) }
            putString("name", "n$id")
            putString("kind", kind)
            putDouble("lat", lat)
            putDouble("lon", lon)
        }
        val raw = ArrayList<Bundle>().apply {
            add(bundle("ok", 10.0, 20.0))
            add(bundle("nan", Double.NaN, 20.0))
            add(bundle("far", 91.0, 20.0))
            add(bundle(null, 10.0, 20.0))
            add(bundle("odd", 11.0, 21.0, kind = "Teleport"))
            repeat(80) { add(bundle("x$it", 12.0, 22.0)) }
        }
        val handle = SavedStateHandle().apply { set("stops", raw) }

        val vm = TripEditViewModel(fx.container, null, saved = handle.afterProcessDeath())

        assertEquals(TripRules.MAX_STOPS, vm.stops.value.size)
        assertEquals(listOf("ok", "odd"), vm.stops.value.take(2).map { it.id })
        assertEquals("an unknown kind falls back to a visit", StopKind.Visit, vm.stops.value[1].kind)
        assertTrue(vm.stops.value.none { it.id == "nan" || it.id == "far" })
    }

    @Test
    fun theSavedFormStaysSmallForAFullTrip() {
        val handle = SavedStateHandle()
        val vm = TripEditViewModel(fx.container, null, saved = handle)
        vm.addPlaces((0 until 60).map { ParsedPlace(LatLon(10.0 + it * 0.01, 20.0), "A place with quite a long name ".repeat(4)) })
        vm.rename("N".repeat(500))

        val bytes = android.os.Parcel.obtain().let { p ->
            p.writeBundle(handle.savedStateProvider().saveState())
            p.dataSize().also { p.recycle() }
        }

        assertEquals(TripRules.MAX_STOPS, vm.stops.value.size)
        assertEquals(SavedStops.NAME_MAX, vm.name.value.length)
        assertTrue("a full trip is $bytes bytes in the Bundle; the whole app shares about 1 MB", bytes < 64 * 1024)
    }
}
