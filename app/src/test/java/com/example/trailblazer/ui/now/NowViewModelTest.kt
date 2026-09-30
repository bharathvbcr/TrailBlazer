package com.example.trailblazer.ui.now

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.sensors.FakeSensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The location listener is expensive (GPS, battery). It must run only while a screen is collecting it, never merely
 * because a ViewModel exists — a ViewModel outlives its screen when another screen is pushed on top or the app goes
 * to the background.
 */
@RunWith(AndroidJUnit4::class)
class NowViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)
    private lateinit var db: TrailDb
    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        db = Room.inMemoryDatabaseBuilder(app, TrailDb::class.java).allowMainThreadQueries().build()
        container = AppContainer(app, sensorSource = FakeSensorSource(emptySet()), db = db)
        container.permissions.refresh()
    }

    @After
    fun tearDown() {
        scope.cancel()
        container.scope.cancel()
        db.close()
    }

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun requests(): Int = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .sumOf { shadowOf(manager).getLocationRequests(it).size }

    @Test
    fun aViewModelWithNoScreenCollectingDoesNotKeepLocationOn() {
        NowViewModel(container)
        settle()
        assertEquals("location listeners registered with nobody watching", 0, requests())
    }

    @Test
    fun locationRunsWhileTheScreenCollectsTheFix() {
        val vm = NowViewModel(container)
        val job = scope.launch { vm.fix.collect {} }
        settle()
        assertTrue("the screen's own collection should register a listener", requests() > 0)
        job.cancel()
    }

    @Test
    fun speedAlertsListenWhileOnAndStopWhenTheCollectorLeaves() {
        kotlinx.coroutines.runBlocking { container.prefs.update { it.copy(speedAlertEnabled = true) } }
        val vm = NowViewModel(container)
        val job = scope.launch { vm.speedAlerts.collect {} }
        settle()
        assertTrue("an enabled alert must watch the speed", requests() > 0)
        job.cancel()
        // The shared fix keeps its listener for a 5 s grace period after the last collector, then releases it.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        settle()
        assertEquals(0, requests())
    }

    @Test
    fun speedAlertsDoNotNeedLocationWhileTheAlertIsOff() {
        val vm = NowViewModel(container)
        val job = scope.launch { vm.speedAlerts.collect {} }
        settle()
        assertEquals(0, requests())
        job.cancel()
    }
}
