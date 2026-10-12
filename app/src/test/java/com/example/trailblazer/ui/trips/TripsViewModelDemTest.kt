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
import com.trailblazer.core.geo.DemResolution
import com.trailblazer.core.geo.DemTileId
import kotlinx.coroutines.cancel
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

/** The Trips tab's list of elevation tiles follows the manager, and says honestly when a delete did not happen. */
@RunWith(AndroidJUnit4::class)
class TripsViewModelDemTest {
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
        container.demTiles.demDir.deleteRecursively()
        container.demTiles.demDir.mkdirs()
    }

    @After
    fun tearDown() {
        container.scope.cancel()
        container.demTiles.demDir.deleteRecursively()
        db.close()
    }

    private fun tile(name: String): File = File(app.cacheDir, name).also {
        RandomAccessFile(it, "rw").use { raf -> raf.setLength(DemResolution.ThreeArcSecond.fileBytes) }
    }

    @Test
    fun tilesImportedFromTheTripEditorAppearInTheTripsListWithoutARefresh() {
        val vm = TripsViewModel(container)
        awaitMainLooper(5_000) { false }
        assertTrue(vm.demTiles.value.isEmpty())
        // Imported through the manager, as the trip editor's empty state does, not through this view model.
        runBlocking { container.demTiles.importTile(Uri.fromFile(tile("N36W119.hgt"))).getOrThrow() }
        awaitMainLooper(10_000) { vm.demTiles.value.size == 1 }
        assertEquals(listOf(DemTileId(36, -119)), vm.demTiles.value.map { it.id })
        runBlocking { container.demTiles.deleteTile(DemTileId(36, -119)) }
        awaitMainLooper(10_000) { vm.demTiles.value.isEmpty() }
        assertTrue(vm.demTiles.value.isEmpty())
    }

    @Test
    fun deletingATileReportsSuccessOnlyWhenItWasDeleted() {
        val vm = TripsViewModel(container)
        runBlocking { container.demTiles.importTile(Uri.fromFile(tile("N40E010.hgt"))).getOrThrow() }
        vm.deleteDemTile(DemTileId(40, 10))
        awaitMainLooper(10_000) { vm.message.value != null }
        assertEquals("Deleted elevation tile N40E010.hgt", vm.message.value)

        vm.message.value = null
        vm.deleteDemTile(DemTileId(40, 10)) // already gone
        awaitMainLooper(10_000) { vm.message.value != null }
        val failure = vm.message.value!!
        assertTrue(failure, failure.startsWith("Could not delete elevation tile N40E010.hgt"))
        assertFalse(failure, failure.startsWith("Deleted"))
    }
}
