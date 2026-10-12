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
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Editing a stored trip in the editor and saving it again must not change when the trip was created. */
@RunWith(AndroidJUnit4::class)
class TripEditCreatedMsTest {
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

    @Test
    fun savingAnEditedTripKeepsWhenItWasCreated() {
        val stops = listOf(
            Stop("a", "Start", StopKind.Start, LatLon(46.0, 7.0)),
            Stop("b", "End", StopKind.End, LatLon(46.1, 7.1)),
        )
        runBlocking { container.trips.save(Trip("t1", "Alps", stops)) }
        // Pretend the trip was made long ago, so "now" can never equal it by accident.
        db.openHelper.writableDatabase.execSQL("UPDATE trips SET createdMs = 1000 WHERE id = 't1'")

        val vm = TripEditViewModel(container, "t1")
        awaitMainLooper(5_000) { vm.loaded.value }
        vm.rename("Alps, edited")
        var done = false
        vm.save { done = true }
        awaitMainLooper(5_000) { done }

        assertEquals(1000L, runBlocking { db.trips().createdMs("t1") })
        val updated = db.openHelper.readableDatabase.query("SELECT name, updatedMs FROM trips WHERE id = 't1'").use { c ->
            c.moveToFirst()
            c.getString(0) to c.getLong(1)
        }
        assertEquals("Alps, edited", updated.first)
        assertTrue("updatedMs is the time of the edit", updated.second > 1000L)
    }
}
