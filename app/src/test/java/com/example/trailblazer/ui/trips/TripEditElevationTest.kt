package com.example.trailblazer.ui.trips

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.sensors.FakeSensorSource
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.ParsedPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile

/** The trip editor's elevation state: computed off the main thread, recomputed on a new stop or a new tile. */
@RunWith(AndroidJUnit4::class)
class TripEditElevationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrailDb
    private lateinit var container: AppContainer
    private val collector = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        db = Room.inMemoryDatabaseBuilder(app, TrailDb::class.java).allowMainThreadQueries().build()
        container = AppContainer(app, sensorSource = FakeSensorSource(emptySet()), db = db)
        container.permissions.refresh()
        container.demTiles.demDir.deleteRecursively()
        container.demTiles.demDir.mkdirs()
    }

    @After
    fun tearDown() {
        collector.cancel()
        container.scope.cancel()
        container.demTiles.demDir.deleteRecursively()
        db.close()
    }

    private fun whitneyTile(): File {
        val f = File(app.cacheDir, "N36W119.hgt")
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(1201L * 1201 * 2)
            val row = ByteArray(1201 * 2) { i -> if (i % 2 == 0) (1500 shr 8).toByte() else 1500.toByte() }
            for (r in 0 until 1201) {
                raf.seek(r.toLong() * row.size)
                raf.write(row)
            }
            for (r in 505..506) for (c in 849..850) {
                raf.seek((r.toLong() * 1201 + c) * 2)
                raf.write(byteArrayOf((4421 shr 8).toByte(), 4421.toByte()))
            }
        }
        return f
    }

    @Test
    fun theProfileFollowsTheStopsAndNewTilesAndNeverBlocksTheMainThread() {
        val vm = TripEditViewModel(container, null)
        collector.launch { vm.elevation.collect { } }
        assertTrue(vm.elevation.value is TripElevation.NotEnoughStops)

        vm.addPlaces(listOf(ParsedPlace(LatLon(36.5785, -118.2923), "Whitney")))
        awaitMainLooper(2_000) { false }
        assertTrue("one stop is not a route", vm.elevation.value is TripElevation.NotEnoughStops)

        vm.addPlaces(listOf(ParsedPlace(LatLon(36.5785, -118.2723), "East")))
        awaitMainLooper(10_000) { vm.elevation.value is TripElevation.Ready }
        val noTiles = vm.elevation.value as TripElevation.Ready
        assertFalse("no tile installed: no data, no invented 0 m", noTiles.stats.hasData)

        runBlocking { container.demTiles.importTile(Uri.fromFile(whitneyTile())).getOrThrow() }
        awaitMainLooper(10_000) { (vm.elevation.value as? TripElevation.Ready)?.stats?.hasData == true }
        val withTile = vm.elevation.value as TripElevation.Ready
        assertEquals(4421.0, withTile.stats.maxM!!, 1e-6)
        assertFalse(withTile.stats.hasGaps)

        // A third stop changes the route: the profile is rebuilt for it.
        val before = withTile.profile.totalDistanceM
        vm.addPlaces(listOf(ParsedPlace(LatLon(36.6285, -118.2723), "North")))
        awaitMainLooper(10_000) { (vm.elevation.value as? TripElevation.Ready)?.profile?.totalDistanceM?.let { it > before + 1000.0 } == true }
        assertTrue((vm.elevation.value as TripElevation.Ready).profile.totalDistanceM > before + 1000.0)
        assertEquals(3, (vm.elevation.value as TripElevation.Ready).profile.stopDistancesM.size)
    }

    @Test
    fun renamingAStopDoesNotRebuildTheProfile() {
        val vm = TripEditViewModel(container, null)
        collector.launch { vm.elevation.collect { } }
        vm.addPlaces(listOf(ParsedPlace(LatLon(36.5, -118.5), "A"), ParsedPlace(LatLon(36.6, -118.5), "B")))
        awaitMainLooper(10_000) { vm.elevation.value is TripElevation.Ready }
        val first = vm.elevation.value
        vm.renameStop(0, "Trailhead")
        awaitMainLooper(1_000) { false }
        assertTrue("the same profile object: nothing was recomputed", first === vm.elevation.value)
    }
}
