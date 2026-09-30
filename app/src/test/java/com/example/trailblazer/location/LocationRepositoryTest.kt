package com.example.trailblazer.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.permissions.Permissions
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sensors.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class LocationRepositoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Fixes pass through the API 34+ sea-level conversion on Dispatchers.IO, so results land asynchronously. */
    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            idle()
        }
    }

    private fun collect(repo: LocationRepository): MutableList<Reading<Fix>> {
        val seen = ArrayList<Reading<Fix>>()
        scope.launch { repo.live(1_000L).collect { seen += it } }
        idle()
        return seen
    }

    @Test
    fun withoutPermissionItSaysSoAndNeverRegisters() {
        val repo = LocationRepository(app, manager, Permissions(app), scope)
        val seen = collect(repo)
        assertEquals(Reading.Unavailable(UnavailableReason.PermissionDenied), seen.last())
    }

    @Test
    fun deliversFixesThenReportsWhenLocationIsSwitchedOff() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        val perms = Permissions(app).also { it.refresh() }
        val repo = LocationRepository(app, manager, perms, scope)
        val seen = collect(repo)
        assertEquals(Reading.Acquiring, seen.last())

        for (provider in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)) {
            shadowOf(manager).simulateLocation(Location(provider).apply {
                latitude = 46.5582; longitude = 7.8352; accuracy = 4f; altitude = 2100.0; time = System.currentTimeMillis()
            })
        }
        awaitUntil { seen.any { it is Reading.Value } }
        val v = seen.filterIsInstance<Reading.Value<Fix>>().lastOrNull()
        assertTrue("expected a fix, got $seen", v != null)
        assertEquals(46.5582, v!!.value.position.lat, 1e-9)
        assertEquals(4.0, v.value.accuracyM!!, 1e-9)

        shadowOf(manager).setLocationEnabled(false)
        awaitUntil { seen.last() is Reading.Unavailable }
        assertEquals(Reading.Unavailable(UnavailableReason.Disabled), seen.last())
    }

    @Test
    fun invalidCoordinatesAreDropped() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        val repo = LocationRepository(app, manager, Permissions(app).also { it.refresh() }, scope)
        val seen = collect(repo)
        for (provider in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)) {
            shadowOf(manager).simulateLocation(Location(provider).apply { latitude = Double.NaN; longitude = 7.0; accuracy = 4f; time = 1 })
        }
        awaitUntil(1_000) { false }
        assertTrue(seen.none { it is Reading.Value })
    }

    /**
     * Mock providers and buggy chipsets report NaN accuracy, -1 speed, NaN altitude or bearings past 360. The position
     * is still good, so the fix is kept, but every impossible optional field becomes "not reported" (null).
     */
    @Test
    fun impossibleOptionalFieldsBecomeNotReported() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        val repo = LocationRepository(app, manager, Permissions(app).also { it.refresh() }, scope)
        val seen = collect(repo)
        for (provider in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)) {
            shadowOf(manager).simulateLocation(Location(provider).apply {
                latitude = 46.5; longitude = 7.8; accuracy = Float.NaN; altitude = Double.NaN; speed = -1f; bearing = 720.5f
                verticalAccuracyMeters = Float.POSITIVE_INFINITY; time = System.currentTimeMillis()
            })
        }
        awaitUntil { seen.any { it is Reading.Value } }
        val f = seen.filterIsInstance<Reading.Value<Fix>>().last().value
        assertEquals(46.5, f.position.lat, 0.0)
        assertEquals(null, f.accuracyM)
        assertEquals(null, f.altitudeM)
        assertEquals(null, f.speedMps)
        assertEquals(null, f.verticalAccuracyM)
        assertEquals("bearing is normalised", 0.5, f.bearingDeg!!, 1e-3)
    }
}
