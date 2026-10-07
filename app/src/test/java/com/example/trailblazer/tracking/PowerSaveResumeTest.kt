package com.example.trailblazer.tracking

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.TrackingMode
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * Battery Saver can switch location off while the screen is off (LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF); the
 * platform then simply delivers no fixes until it ends. Recording must carry on in the same track afterwards, and the
 * silent stretch must not turn into moving time.
 */
@RunWith(AndroidJUnit4::class)
class PowerSaveResumeTest {
    private val app: TrailApp = ApplicationProvider.getApplicationContext()
    private val lm = app.getSystemService(LocationManager::class.java)
    private val pm = app.getSystemService(PowerManager::class.java)
    private val origin = LatLon(46.0, 7.0)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(lm).setLocationEnabled(true)
        app.container.permissions.refresh()
        // DataStore outlives each test's Application, so set the mode this test relies on.
        runBlocking { app.container.prefs.update { it.copy(trackingMode = TrackingMode.Balanced) } }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(20)
            idle()
        }
    }

    private fun provider(): String? = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { shadowOf(lm).getLocationRequests(it).isNotEmpty() }

    /** Time passes on the device clock as it would on a phone. */
    private fun elapse(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    private fun fix(provider: String, metersNorth: Double, timeMs: Long) {
        elapse(Duration.ofSeconds(15))
        shadowOf(lm).simulateLocation(Location(provider).apply {
            val p = Geo.destination(origin, 0.0, metersNorth)
            latitude = p.lat; longitude = p.lon; accuracy = 5f; speed = 1.4f; time = timeMs
            // A receiver stamps each fix; the shadow drops fixes closer together than the request's minimum interval.
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        idle()
    }

    private fun powerSave(on: Boolean) {
        shadowOf(pm).setIsPowerSaveMode(on)
        shadowOf(pm).setLocationPowerSaveMode(
            if (on) PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF else PowerManager.LOCATION_MODE_NO_CHANGE,
        )
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        idle()
    }

    private fun send(controller: ServiceController<TrackRecordingService>, action: String, startId: Int) {
        controller.withIntent(Intent(app, TrackRecordingService::class.java).setAction(action)).startCommand(0, startId)
        idle()
    }

    @Test
    fun recordingContinuesInTheSameTrackAcrossBatterySaverAndTheGapIsNotMovingTime() {
        val tracks = app.container.db.tracks()
        val controller = Robolectric.buildService(TrackRecordingService::class.java).create()
        send(controller, TrackRecordingService.ACTION_START, 1)
        awaitUntil("a location request") { provider() != null }
        val provider = provider()!!
        assertEquals("the Balanced setting reaches the receiver", 15_000L, shadowOf(lm).getLocationRequests(provider).single().intervalMillis)
        val id = runBlocking { tracks.open() }!!.id

        // Ten fixes walking north, 20 m every 15 s; the tenth fills a batch and is written.
        val t0 = System.currentTimeMillis()
        for (i in 0 until 10) fix(provider, i * 20.0, t0 + i * 15_000L)
        awaitUntil("the first batch") { runBlocking { tracks.maxSeq(id) } == 9 }

        // No fixes for 40 minutes, as Battery Saver withholds them with the screen off. The shadow does not withhold
        // them itself; the test simply delivers none.
        powerSave(true)
        elapse(Duration.ofMinutes(40))
        powerSave(false)
        val open = runBlocking { tracks.open() }!!
        assertEquals("still the same track", id, open.id)
        assertEquals(TrackState.Recording, open.state)
        assertTrue("location is still requested", shadowOf(lm).getLocationRequests(provider).isNotEmpty())

        // Fixes return 40 minutes later, 20 m further on, and walking continues.
        val t1 = t0 + 9 * 15_000L + 40 * 60_000L
        for (i in 0 until 10) fix(provider, (10 + i) * 20.0, t1 + i * 15_000L)
        awaitUntil("the second batch") { runBlocking { tracks.maxSeq(id) } == 19 }

        send(controller, TrackRecordingService.ACTION_STOP, 2)
        awaitUntil("the track to finish") { runBlocking { tracks.get(id) }?.state == TrackState.Finished }
        val all = runBlocking { tracks.observeAll().first() }
        assertEquals("one track, not one per side of the gap", 1, all.size)
        val t = all.single()
        assertEquals(20, t.pointCount)
        assertEquals(19 * 20.0, t.distanceM, 1.0)
        // 18 walking steps of 15 s; the 40-minute step covered 20 m, far below walking pace.
        assertEquals(18 * 15_000L, t.movingMs)
    }
}
