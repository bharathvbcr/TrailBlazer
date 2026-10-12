package com.example.trailblazer.ui.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.TrackEntity
import com.example.trailblazer.data.TrackPointEntity
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The recorded-track screen's ViewModel: it summarises, charts, renames and deletes a stored track. */
@RunWith(AndroidJUnit4::class)
class TrackDetailViewModelTest {
    private val fx = ContainerFixture()
    private val start = 1_790_000_000_000L
    private val origin = LatLon(46.0, 7.0)

    @After
    fun tearDown() = fx.close()

    private fun store(id: String, points: Int) = runBlocking {
        val dao = fx.db.tracks()
        dao.insert(TrackEntity(id, "Morning walk", start, start + points * 1_000L, TrackState.Finished, points * 5.0, 0.0, 0.0, 0, null, points))
        var p = origin
        dao.insertPoints((0 until points).map { i ->
            p = Geo.destination(p, 90.0, 5.0)
            TrackPointEntity(id, i, start + i * 1_000L, p.lat, p.lon, 1_000.0 + i, 5.0, 5.0)
        })
    }

    private fun viewModel(id: String): TrackDetailViewModel = TrackDetailViewModel(fx.container, id).also { vm ->
        fx.scope.launch { vm.track.collect {} }
    }

    @Test
    fun theSummaryAndChartsAreBuiltFromTheStoredPoints() {
        store("walk", 200)
        val vm = viewModel("walk")

        awaitMainLooper(10_000) { vm.charts.value != null && vm.track.value != null }

        assertEquals("Morning walk", vm.track.value!!.name)
        val charts = vm.charts.value!!
        assertEquals(TrackDetailViewModel.BUCKETS, charts.elevationByDistance.size)
        assertEquals(TrackDetailViewModel.BUCKETS, charts.speedByTime.size)
        assertTrue("the sketch is the track, simplified", charts.path.size in 2..500)
        assertEquals(origin.lat, charts.path.first().lat, 0.001)
    }

    @Test
    fun aTrackThatIsNoLongerStoredGivesEmptyChartsAndNoSummary() {
        val vm = viewModel("gone")

        awaitMainLooper(10_000) { vm.charts.value != null }

        val charts = vm.charts.value!!
        assertTrue(charts.elevationByDistance.isEmpty() && charts.speedByTime.isEmpty() && charts.path.isEmpty())
        assertNull(vm.track.value)
    }

    @Test
    fun renamingChangesTheStoredName() {
        store("walk", 10)
        val vm = viewModel("walk")
        awaitMainLooper(10_000) { vm.track.value != null }

        vm.rename("Summit day")
        awaitMainLooper(5_000) { vm.track.value?.name == "Summit day" }

        assertEquals("Summit day", vm.track.value!!.name)
    }

    @Test
    fun aBlankNameKeepsTheOldOne() {
        store("walk", 10)
        val vm = viewModel("walk")
        awaitMainLooper(10_000) { vm.track.value != null }

        vm.rename("   ")
        awaitMainLooper(500) { false }

        assertEquals("Morning walk", vm.track.value!!.name)
    }

    @Test
    fun deletingRemovesTheTrackAndReportsDone() {
        store("walk", 10)
        val vm = viewModel("walk")
        awaitMainLooper(10_000) { vm.track.value != null }
        var done = false

        vm.delete { done = true }
        awaitMainLooper(5_000) { done }

        assertTrue(done)
        awaitMainLooper(5_000) { vm.track.value == null }
        assertNull(runBlocking { fx.db.tracks().get("walk") })
        assertNotNull(vm)
    }
}
