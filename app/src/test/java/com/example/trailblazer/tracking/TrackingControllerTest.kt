package com.example.trailblazer.tracking

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.data.PrefsRepository
import com.example.trailblazer.data.TrackEntity
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.createSettingsStore
import com.example.trailblazer.sensors.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.UUID

/** A settings store whose writes can be made to fail, as they do on a full disk. Reads still work. */
private class FlakyStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
    @Volatile var failWrites = false
    override val data = real.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failWrites) throw IOException("No space left on device")
        return real.updateData(transform)
    }
}

/**
 * The controller's decisions about restarting a recording: when it must not (a stored reason, repeated failures, a
 * disk too full to even note the attempt) and what it says when the system will not let it.
 */
@RunWith(AndroidJUnit4::class)
class TrackingControllerTest {
    private val app: TrailApp = ApplicationProvider.getApplicationContext()
    private val io = Job()
    private val scope = CoroutineScope(Dispatchers.IO + io)
    private var nowMs = 1_700_000_000_000L
    private val clock = Clock { nowMs }
    private lateinit var store: FlakyStore
    private lateinit var prefs: PrefsRepository

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        app.container.permissions.refresh()
        assertFalse("an earlier test left the recording service flagged as running", TrackRecordingService.running.value)
        store = FlakyStore(createSettingsStore(app, scope, "controller-test-${UUID.randomUUID()}"))
        prefs = PrefsRepository(app, store)
    }

    @After
    fun tearDown() = io.cancel()

    private fun openTrack() = runBlocking {
        app.container.db.tracks().insert(TrackEntity("t1", "Seeded", nowMs, null, TrackState.Recording, 0.0, 0.0, 0.0, 0L, null, 0))
        assertEquals("the seeded track is the open one", "t1", app.container.db.tracks().open()?.id)
    }

    private fun controller(context: Context = app, backoff: ResumeBackoff = ResumeBackoff(prefs, clock)) = TrackingController(
        context, app.container.tracks, app.container.permissions, app.container.sensorSource, InterruptState(prefs, scope), backoff,
    )

    // --- backoff ---

    @Test
    fun automaticRestartsAreLimitedWithinTheWindowAndAllowedAgainAfterIt() = runBlocking {
        val b = ResumeBackoff(prefs, clock)
        repeat(ResumeBackoff.MAX_ATTEMPTS) { assertTrue("attempt ${it + 1}", b.beginAutoResume()) }
        assertFalse("one too many within the window", b.beginAutoResume())

        nowMs += ResumeBackoff.WINDOW_MS + 1
        assertTrue("a new window", b.beginAutoResume())
    }

    @Test
    fun aRestartThatWorkedClearsTheCount() = runBlocking {
        val b = ResumeBackoff(prefs, clock)
        repeat(ResumeBackoff.MAX_ATTEMPTS) { b.beginAutoResume() }
        b.reset()
        assertTrue(b.beginAutoResume())
    }

    @Test
    fun aRestartThatCannotBeRecordedIsRefusedBecauseTheDiskIsFull() = runBlocking {
        store.failWrites = true
        assertFalse(ResumeBackoff(prefs, clock).beginAutoResume())
    }

    @Test
    fun aFullDiskDoesNotLoopAutomaticRestartsAfterTheProcessDies() {
        openTrack()
        // The pause and the reason could not be written, so after the restart the track still says Recording and no
        // reason is stored. Nothing durable can be written either, so the restart is refused rather than retried.
        store.failWrites = true

        val c = controller()
        val r = runBlocking { c.resumeIfInterrupted() }

        assertEquals(ResumeResult.Interrupted(InterruptReason.StorageFailed), r)
        assertEquals("the reason is at least in memory for the Tracks screen", InterruptReason.StorageFailed, c.interrupted.value)
        assertNull("no service was started to fail again", shadowOf(app).nextStartedService)
        // And a second foreground in the same process stays quiet.
        assertEquals(ResumeResult.Interrupted(InterruptReason.StorageFailed), runBlocking { c.resumeIfInterrupted() })
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun repeatedRestartsThatKeepFailingStopAfterTheLimit() {
        openTrack()
        val c = controller()
        repeat(ResumeBackoff.MAX_ATTEMPTS) {
            runBlocking { c.clearInterrupt() }
            assertEquals(ResumeResult.Resumed, runBlocking { c.resumeIfInterrupted() })
        }
        runBlocking { c.clearInterrupt() }
        assertEquals(ResumeResult.Interrupted(InterruptReason.StorageFailed), runBlocking { c.resumeIfInterrupted() })
    }

    // --- surfacing what the system refused ---

    @Test
    fun aBackgroundStartTheSystemRefusesIsShownAsAReasonNotDropped() {
        openTrack()
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("not allowed from the background")
        }
        val c = controller(refusing)

        val r = runBlocking { c.resumeIfInterrupted() }

        assertEquals(ResumeResult.Blocked(StartRefusal.BackgroundStartBlocked), r)
        assertEquals(InterruptReason.ForegroundStartBlocked, c.interrupted.value)
    }
}
