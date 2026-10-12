package com.example.trailblazer.tracking

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.data.TrackEntity
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.TrackingMode
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/** The recording service end to end under Robolectric: permissions, actions, interruptions and what survives them. */
@RunWith(AndroidJUnit4::class)
class TrackRecordingServiceTest {
    private val app: TrailApp = ApplicationProvider.getApplicationContext()
    private val lm = app.getSystemService(LocationManager::class.java)
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val tracks get() = app.container.db.tracks()
    private val origin = LatLon(46.0, 7.0)
    private var startId = 0

    /** Fix times must increase or the recorder drops a fix as out of order; the wall clock may not have moved between two. */
    private var fixTimeMs = System.currentTimeMillis()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(lm).setLocationEnabled(true)
        app.container.permissions.refresh()
        // DataStore outlives each test's Application, so set what these tests rely on.
        runBlocking {
            app.container.prefs.update { it.copy(trackingMode = TrackingMode.Balanced) }
            app.container.prefs.setTrackingInterrupt(null)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun elapse(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        awaitMainLooper(60_000, condition)
        check(condition()) { "timed out waiting for $what" }
    }

    private fun provider(): String? = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { shadowOf(lm).getLocationRequests(it).isNotEmpty() }

    private fun send(controller: ServiceController<TrackRecordingService>, action: String) {
        controller.withIntent(Intent(app, TrackRecordingService::class.java).setAction(action)).startCommand(0, ++startId)
        idle()
    }

    private fun fix(provider: String, metersNorth: Double) {
        elapse(Duration.ofSeconds(15))
        shadowOf(lm).simulateLocation(Location(provider).apply {
            val p = Geo.destination(origin, 0.0, metersNorth)
            latitude = p.lat; longitude = p.lon; accuracy = 5f; speed = 1.4f; fixTimeMs += 15_000L; time = fixTimeMs
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        idle()
    }

    private fun openTrack(state: TrackState = TrackState.Recording): TrackEntity {
        val t = TrackEntity("t1", "Seeded", System.currentTimeMillis(), null, state, 0.0, 0.0, 0.0, 0L, null, 0)
        runBlocking { tracks.insert(t) }
        return t
    }

    private fun revokeLocation(keepCoarse: Boolean) {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!keepCoarse) shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        app.container.permissions.refresh()
    }

    private val services = mutableListOf<ServiceController<TrackRecordingService>>()

    private fun newService() = Robolectric.buildService(TrackRecordingService::class.java).create().also { services += it }

    /** The service's running flag is static and outlives a test's Application. */
    @After
    fun tearDown() {
        services.forEach { runCatching { it.destroy() } }
        idle()
    }

    /**
     * Runs [block] on the app's main-thread scope and pumps the looper until it is done. The controller shares that
     * scope with InterruptState's initial read, so blocking the main thread in runBlocking would deadlock the two.
     */
    private fun <T> onMain(block: suspend () -> T): T {
        var result: Result<T>? = null
        app.container.scope.launch { result = runCatching { block() } }
        awaitMainLooper(60_000) { result != null }
        return checkNotNull(result) { "timed out on the main scope" }.getOrThrow()
    }

    private fun storedInterrupt(): String? = runBlocking { app.container.prefs.trackingInterrupt.first() }

    private fun startRecording(): Pair<ServiceController<TrackRecordingService>, String> {
        val controller = newService()
        send(controller, TrackRecordingService.ACTION_START)
        awaitUntil("a location request") { provider() != null }
        return controller to provider()!!
    }

    // --- coarse-only location ---

    @Test
    fun approximateOnlyLocationIsRefusedWithAReasonAndNothingStarts() {
        revokeLocation(keepCoarse = true)

        val r = app.container.tracking.start()

        assertEquals(StartResult.Refused(StartRefusal.PreciseLocationRequired), r)
        assertNull("no service was asked to start", shadowOf(app).nextStartedService)
        assertNull("and no track was opened", runBlocking { tracks.open() })
    }

    @Test
    fun theNotificationsResumeButtonCannotStartAnApproximateOnlyRecording() {
        revokeLocation(keepCoarse = true)
        val controller = newService()

        send(controller, TrackRecordingService.ACTION_RESUME)

        awaitUntil("the reason to be stored") { storedInterrupt() != null }
        assertEquals(InterruptReason.PreciseLocationRequired.name, storedInterrupt())
        assertEquals(InterruptReason.PreciseLocationRequired, app.container.interrupts.reason.value)
        assertNull("no track from fixes that would all be dropped", runBlocking { tracks.open() })
        assertFalse(TrackRecordingService.running.value)
        awaitUntil("the notification") { shadowOf(nm).getNotification(TrackRecordingService.STOPPED_NOTIFICATION_ID) != null }
        assertNotNull("the user is told where they will see it", shadowOf(nm).getNotification(TrackRecordingService.STOPPED_NOTIFICATION_ID))
    }

    @Test
    fun upgradingFromApproximateToPreciseIsSeenWithoutARestart() {
        revokeLocation(keepCoarse = true)
        val seen = ArrayList<Reading<*>>()
        app.container.scope.launch { app.container.gnss.status.collect { seen += it } }
        idle()
        assertEquals(Reading.Unavailable(UnavailableReason.PermissionDenied), seen.last())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.container.permissions.refresh()
        idle()

        assertTrue("satellite status left PermissionDenied: ${seen.last()}", seen.last() != Reading.Unavailable(UnavailableReason.PermissionDenied))
    }

    // --- stopping ---

    @Test
    fun stopFinishesTheTrackEvenWhenLocationPermissionIsGone() {
        val t = openTrack()
        revokeLocation(keepCoarse = false)
        val controller = newService()

        send(controller, TrackRecordingService.ACTION_STOP)

        awaitUntil("the track to finish") { runBlocking { tracks.get(t.id) }?.state == TrackState.Finished }
        assertNull("nothing left open to be resumed by openOrStart", runBlocking { tracks.open() })
        assertNotNull(runBlocking { tracks.get(t.id) }!!.endedMs)
    }

    @Test
    fun anIntentWithoutAKnownActionStopsTheServiceInsteadOfLeavingItIdle() {
        val controller = newService()
        controller.withIntent(Intent(app, TrackRecordingService::class.java)).startCommand(0, 1)
        idle()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(runBlocking { tracks.open() })
    }

    // --- pause, interrupt, destroy ---

    @Test
    fun pausePausesTheTrackAndReleasesLocation() {
        val (controller, provider) = startRecording()
        val id = runBlocking { tracks.open() }!!.id

        send(controller, TrackRecordingService.ACTION_PAUSE)

        awaitUntil("the track to pause") { runBlocking { tracks.get(id) }?.state == TrackState.Paused }
        assertTrue("no location request outlives a pause", shadowOf(lm).getLocationRequests(provider).isEmpty())
        assertNull(app.container.interrupts.reason.value)
    }

    @Test
    fun switchingLocationOffPausesTheTrackAndTheReasonSurvivesTheProcess() {
        val (controller, _) = startRecording()
        val id = runBlocking { tracks.open() }!!.id

        shadowOf(lm).setLocationEnabled(false)
        idle()

        awaitUntil("the track to pause") { runBlocking { tracks.get(id) }?.state == TrackState.Paused }
        awaitUntil("the reason to be stored") { storedInterrupt() != null }
        assertEquals(InterruptReason.LocationDisabled, app.container.interrupts.reason.value)
        awaitUntil("the service to stop itself") { shadowOf(controller.get()).isStoppedBySelf }

        // A new process starts with an empty InterruptState and reads the reason back from DataStore.
        val afterRestart = InterruptState(app.container.prefs, app.container.scope)
        assertEquals(InterruptReason.LocationDisabled, onMain { afterRestart.current() })
    }

    @Test
    fun destroyingTheServiceWritesPointsThatAreStillBuffered() {
        val (controller, provider) = startRecording()
        val id = runBlocking { tracks.open() }!!.id
        for (i in 0 until 3) fix(provider, i * 20.0)
        // Fixes reach the recorder asynchronously; the notification shows the distance of what it has kept (two 20 m steps).
        awaitUntil("three fixes to be recorded") {
            elapse(Duration.ofSeconds(5))
            shadowOf(nm).getNotification(42)?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.startsWith("40") == true
        }
        assertEquals("three points are below the batch size, so not written yet", -1, runBlocking { tracks.maxSeq(id) })

        services.remove(controller)
        controller.destroy()

        awaitMainLooper(60_000) { runBlocking { tracks.maxSeq(id) } >= 0 }
        assertEquals("the buffered points were written on destroy", 2, runBlocking { tracks.maxSeq(id) })
    }

    // --- notification ---

    @Test
    fun theNotificationIsOnlyRepostedWhenWhatItShowsChanges() {
        val (controller, provider) = startRecording()
        val first = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(first)

        // Several 5 s ticks with nothing new to show.
        elapse(Duration.ofSeconds(20))
        assertSame("the tick must not repost an identical notification", first, shadowOf(nm).getNotification(42) ?: first)

        // Walking 180 m changes the distance it shows (0.0 m before), and the next tick posts it.
        for (i in 0 until 10) fix(provider, i * 20.0)
        // Fixes reach the recorder asynchronously, so tick until the notification shows them.
        awaitUntil("the new distance to be posted") {
            elapse(Duration.ofSeconds(5))
            shadowOf(nm).getNotification(42) !== first
        }
        val now = shadowOf(nm).getNotification(42)
        assertNotSame(first, now)
        val text = now.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("the repost shows the new distance: $text", text.startsWith("1") && text.contains(" m "))
    }

    // --- controller ---

    @Test
    fun resumeIfInterruptedNeitherRestartsNorStaysSilentWhenItCannot() {
        val controller = app.container.tracking
        assertEquals("no open track", ResumeResult.NothingToResume, onMain { controller.resumeIfInterrupted() })

        openTrack(TrackState.Paused)
        assertEquals("a paused track is the user's to resume", ResumeResult.NothingToResume, onMain { controller.resumeIfInterrupted() })
    }

    @Test
    fun resumeIfInterruptedRestartsARecordingTheProcessLost() {
        openTrack()

        assertEquals(ResumeResult.Resumed, onMain { app.container.tracking.resumeIfInterrupted() })
        assertEquals(TrackRecordingService.ACTION_START, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun resumeIfInterruptedDoesNotRestartARecordingThatStoppedForAStoredReason() {
        openTrack()
        runBlocking { app.container.interrupts.set(InterruptReason.StorageFailed) }

        val r = onMain { app.container.tracking.resumeIfInterrupted() }

        assertEquals(ResumeResult.Interrupted(InterruptReason.StorageFailed), r)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun resumeIfInterruptedSaysWhyWhenPermissionIsMissing() {
        openTrack()
        revokeLocation(keepCoarse = true)

        val r = onMain { app.container.tracking.resumeIfInterrupted() }

        assertEquals(ResumeResult.Blocked(StartRefusal.PreciseLocationRequired), r)
        assertEquals("shown on the Tracks screen too", InterruptReason.PreciseLocationRequired, app.container.interrupts.reason.value)
        assertNull(shadowOf(app).nextStartedService)

        // Once the user has seen the reason it is stored, so the next foreground does not decide again behind their back.
        assertEquals(ResumeResult.Interrupted(InterruptReason.PreciseLocationRequired), onMain { app.container.tracking.resumeIfInterrupted() })

        runBlocking { app.container.interrupts.set(null) }
        revokeLocation(keepCoarse = false)
        assertEquals(ResumeResult.Blocked(StartRefusal.LocationPermissionMissing), onMain { app.container.tracking.resumeIfInterrupted() })
        assertEquals(InterruptReason.LocationPermissionRevoked, app.container.interrupts.reason.value)
    }

    @Test
    fun resumeIfInterruptedDoesNothingWhileTheServiceIsRunning() {
        openTrack()
        startRecording()
        awaitUntil("running") { TrackRecordingService.running.value }

        assertEquals(ResumeResult.NothingToResume, onMain { app.container.tracking.resumeIfInterrupted() })
    }

    @Test
    fun deletingAllDataFinishesTheActiveRecordingFirst() {
        val (controller, provider) = startRecording()
        val id = runBlocking { tracks.open() }!!.id
        for (i in 0 until 3) fix(provider, i * 20.0)

        val deletion = app.container.scope.launch { app.container.deleteAllData() }
        idle()

        // The service is asked to stop before anything is deleted, and the track is still there while it does.
        val stop = shadowOf(app).nextStartedService
        assertNotNull("deleteAllData must stop the recording first", stop)
        assertEquals(TrackRecordingService.ACTION_STOP, stop.action)
        assertNotNull(runBlocking { tracks.get(id) })
        assertFalse(deletion.isCompleted)

        send(controller, TrackRecordingService.ACTION_STOP)
        awaitUntil("the deletion") { deletion.isCompleted }

        assertNull(runBlocking { tracks.get(id) })
        assertFalse(TrackRecordingService.running.value)
        assertTrue(runBlocking { tracks.open() } == null)
    }
}
