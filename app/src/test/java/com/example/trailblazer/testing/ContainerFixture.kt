package com.example.trailblazer.testing

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.places.PlaceSearchBackend
import com.example.trailblazer.sensors.FakeSensorSource
import com.example.trailblazer.sensors.Clock
import com.example.trailblazer.weather.HttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf

/**
 * An [AppContainer] over an in-memory database and fake seams, with a scope for collecting a ViewModel's flows, for
 * ViewModel tests that need the real repositories. DataStore outlives each test's Application, so settings start
 * from their defaults.
 */
class ContainerFixture(
    sensorTypes: Set<Int> = emptySet(),
    http: HttpTransport = HttpTransport { error("this test expects no network call") },
    placeBackend: PlaceSearchBackend = PlaceSearchBackend { _, _ -> emptyList() },
    placeSearchAvailable: () -> Boolean = { true },
    clock: Clock = Clock.System,
) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val sensors = FakeSensorSource(sensorTypes)
    val db: TrailDb = Room.inMemoryDatabaseBuilder(app, TrailDb::class.java).allowMainThreadQueries().build()
    val container: AppContainer
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        container = AppContainer(app, sensorSource = sensors, http = http, clock = clock, db = db, placeBackend = placeBackend, placeSearchAvailable = placeSearchAvailable)
        container.permissions.refresh()
        var reset = false
        container.scope.launch { container.prefs.update { Settings() }; reset = true }
        awaitMainLooper(10_000) { reset }
        check(reset) { "settings were not reset within 10 s" }
    }

    fun close() {
        scope.cancel()
        container.scope.cancel()
        db.close()
    }
}

/**
 * What the system keeps of a ViewModel's [SavedStateHandle] when the process dies: it is written into a Bundle and the
 * new process gets a handle built from that Bundle, not the old object.
 */
fun SavedStateHandle.afterProcessDeath(): SavedStateHandle =
    SavedStateHandle.createHandle(savedStateProvider().saveState(), null)
