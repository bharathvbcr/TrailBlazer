package com.example.trailblazer.tracking

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The recording lifecycle against a database the test controls: a gate parks a caller inside the lock, and a failing
 * write stands in for a full disk. Nothing here depends on real threads or wall-clock time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RecordingSessionTest {
    private val origin = LatLon(46.0, 7.0)

    private class Harness(test: TestScope, val dao: FakeTrackDao = FakeTrackDao()) {
        /** A child of the test's background scope, so a failed assertion cannot leave a tick loop running for runTest to drain forever. */
        val job = Job(test.backgroundScope.coroutineContext[Job])
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + job + CoroutineExceptionHandler { _, e -> uncaught += e })
        val clock = Clock { 1_700_000_000_000L + test.currentTime }
        val recorder = TrackRecorder(dao, clock)
        val feed = MutableSharedFlow<Reading<Fix>?>(extraBufferCapacity = 64)

        /** Collectors of [feed] alive right now: zero means nothing can record or request location. */
        var collectors = 0
        val interrupts = mutableListOf<InterruptReason>()
        var ticks = 0
        var restingChanges = 0

        val session = RecordingSession(
            scope = scope,
            recorder = recorder,
            readings = { flow { collectors++; try { feed.collect { emit(it) } } finally { collectors-- } } },
            noteMovement = {},
            defaultName = { "T" },
            permissionLoss = { InterruptReason.LocationPermissionRevoked },
            listener = object : RecordingSession.Listener {
                override fun onRestingChanged() { restingChanges++ }
                override fun onTick() { ticks++ }
                override suspend fun onInterrupted(reason: InterruptReason) { interrupts += reason }
            },
        )

        fun fix(i: Int) = Reading.Value(
            Fix(Geo.destination(LatLon(46.0, 7.0), 0.0, i * 20.0), 5.0, 1000.0, AltitudeDatum.SeaLevel, null, 2.0, null, 1_700_000_000_000L + i * 1000L, "gps"),
            Accuracy.High,
            0L,
        )

        fun close() = job.cancel()
    }

    @Test
    fun aPauseThatArrivesWhileStartIsInsideTheLockLeavesNoCollectorOrTickBehind() = runTest {
        // 200 runs of the interleaving, each with a different spacing of the two calls around the parked start.
        repeat(200) { n ->
            val h = Harness(this)
            val gate = CompletableDeferred<Unit>()
            h.dao.openGate = gate
            val rnd = Random(n)

            val start = launch { h.session.start() }
            runCurrent() // start is now parked inside the lock, waiting for the database
            val pause = launch { h.session.pause(null) }
            if (rnd.nextBoolean()) runCurrent() // pause is waiting for the lock, as it would have cancelled nothing yet
            if (rnd.nextBoolean()) h.feed.tryEmit(h.fix(0))
            gate.complete(Unit)
            start.join()
            pause.join()
            runCurrent()

            assertEquals("run $n: no collector may survive a pause", 0, h.collectors)
            assertFalse("run $n", h.session.isCollecting)
            val id = h.dao.allRows().single().id
            assertEquals("run $n", TrackState.Paused, h.dao.row(id)!!.state)

            // Nothing ticks, records or requests location after the pause.
            val ticksBefore = h.ticks
            h.feed.tryEmit(h.fix(1))
            advanceTimeBy(30_000)
            assertEquals("run $n: no tick after the pause", ticksBefore, h.ticks)
            assertEquals("run $n: no collector after time passed", 0, h.collectors)
            assertEquals("run $n", TrackState.Paused, h.dao.row(id)!!.state)
            assertTrue("run $n", h.uncaught.isEmpty())
            h.close()
        }
    }

    @Test
    fun aStartAfterAPauseRecordsAgain() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        h.session.pause(null)
        runCurrent()
        assertEquals(0, h.collectors)
        assertTrue(h.session.start())
        runCurrent()
        assertEquals(1, h.collectors)
        h.close()
    }

    @Test
    fun aFullDiskWhileRecordingEndsItWithAStorageReasonInsteadOfAnUncaughtException() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        val id = h.dao.allRows().single().id
        h.dao.failWrites = SQLiteFullException("database or disk is full")

        // The tenth kept fix fills a batch, which is written and fails.
        for (i in 0 until 10) h.feed.tryEmit(h.fix(i))
        runCurrent()

        assertEquals(listOf(InterruptReason.StorageFailed), h.interrupts)
        assertTrue("an exception escaped: ${h.uncaught}", h.uncaught.isEmpty())
        assertEquals(0, h.collectors)
        assertFalse(h.session.isCollecting)
        assertEquals("the pause could not be written either, so the database still says Recording", TrackState.Recording, h.dao.row(id)!!.state)
        assertEquals("the points are kept in memory for a retry", 10, h.recorder.pointCount)
        h.close()
    }

    @Test
    fun aFailureWhileTheTickFlushesEndsTheRecordingToo() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        h.feed.tryEmit(h.fix(0))
        runCurrent()
        h.dao.failWrites = SQLiteFullException()

        advanceTimeBy(RecordingSession.TICK_MS + 1)
        runCurrent()

        assertEquals(listOf(InterruptReason.StorageFailed), h.interrupts)
        assertTrue(h.uncaught.isEmpty())
        assertEquals(0, h.collectors)
        h.close()
    }

    @Test
    fun aTransientFailureStillPausesTheTrackAndReportsWhy() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        val id = h.dao.allRows().single().id
        h.dao.failWrites = SQLiteFullException()
        for (i in 0 until 10) h.feed.tryEmit(h.fix(i))
        runCurrent()
        // Space is freed before the pause is retried by the user.
        h.dao.failWrites = null
        assertNull("the pause itself went through", h.session.pause(null))
        assertEquals(TrackState.Paused, h.dao.row(id)!!.state)
        assertEquals("the buffered points reached the database with the pause", 10, h.dao.allPoints.size)
        h.close()
    }

    @Test
    fun startReportsFalseWhenTheDatabaseRefusesToOpenTheTrack() = runTest {
        val h = Harness(this)
        h.dao.failWrites = SQLiteFullException()
        assertFalse(h.session.start())
        runCurrent()
        assertEquals(0, h.collectors)
        assertFalse(h.session.isCollecting)
        assertTrue(h.uncaught.isEmpty())
        h.close()
    }

    @Test
    fun finishingWritesThePointsAndTheStateInOneTransaction() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        val id = h.dao.allRows().single().id
        for (i in 0 until 3) h.feed.tryEmit(h.fix(i))
        runCurrent()
        val appendsBefore = h.dao.appendCalls

        assertNull(h.session.finish())

        assertEquals("flush and state change are a single write", appendsBefore + 1, h.dao.appendCalls)
        assertEquals("no separate update that could land without the points", 0, h.dao.updateCalls)
        assertEquals(TrackState.Finished, h.dao.row(id)!!.state)
        assertEquals(3, h.dao.allPoints.size)
        assertEquals(3, h.dao.row(id)!!.pointCount)
        h.close()
    }

    @Test
    fun aFailedFinishLeavesTheTrackOpenWithItsPointsStillBuffered() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        val id = h.dao.allRows().single().id
        for (i in 0 until 3) h.feed.tryEmit(h.fix(i))
        runCurrent()
        h.dao.failWrites = SQLiteFullException()

        assertEquals(InterruptReason.StorageFailed, h.session.finish())

        assertEquals("not ended: neither the state nor a part of the points was written", TrackState.Recording, h.dao.row(id)!!.state)
        assertEquals(0, h.dao.allPoints.size)
        h.dao.failWrites = null
        assertNull(h.session.finish())
        assertEquals(TrackState.Finished, h.dao.row(id)!!.state)
        assertEquals("the retry wrote everything", 3, h.dao.allPoints.size)
        h.close()
    }

    @Test
    fun locationGoingAwayPausesTheTrackWithTheMatchingReason() = runTest {
        val disabled = Harness(this)
        assertTrue(disabled.session.start())
        runCurrent()
        disabled.feed.tryEmit(Reading.Unavailable(UnavailableReason.Disabled))
        runCurrent()
        assertEquals(listOf(InterruptReason.LocationDisabled), disabled.interrupts)
        assertEquals(TrackState.Paused, disabled.dao.row(disabled.dao.allRows().single().id)!!.state)
        assertEquals(0, disabled.collectors)
        disabled.close()

        val revoked = Harness(this)
        assertTrue(revoked.session.start())
        runCurrent()
        revoked.feed.tryEmit(Reading.Unavailable(UnavailableReason.PermissionDenied))
        runCurrent()
        assertEquals(listOf(InterruptReason.LocationPermissionRevoked), revoked.interrupts)
        revoked.close()
    }

    @Test
    fun flushingOnDestroyPersistsBufferedPointsAndReportsAFailure() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        for (i in 0 until 3) h.feed.tryEmit(h.fix(i))
        runCurrent()
        assertEquals("below the batch size, so still buffered", 0, h.dao.allPoints.size)

        assertNull(h.session.flush())
        assertEquals(3, h.dao.allPoints.size)

        h.feed.tryEmit(h.fix(3))
        runCurrent()
        h.dao.failWrites = SQLiteFullException()
        assertEquals(InterruptReason.StorageFailed, h.session.flush())
        h.close()
    }

    @Test
    fun restingIsReportedWhenTheReceiverSleepsAndClearedByAPause() = runTest {
        val h = Harness(this)
        assertTrue(h.session.start())
        runCurrent()
        h.feed.tryEmit(null)
        runCurrent()
        assertTrue(h.session.resting)
        assertEquals(1, h.restingChanges)
        h.session.pause(null)
        assertFalse(h.session.resting)
        h.close()
    }
}
