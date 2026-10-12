package com.example.trailblazer.tracking

import android.database.sqlite.SQLiteException
import android.util.Log
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.location.Fix
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The recording lifecycle without Android service plumbing: what runs while a track is Recording, and how it stops.
 *
 * Every transition ([start], [pause], [finish], [flush]) and every write to the recorder happens under one lock, and
 * the collector and the tick job are created and cancelled only under it, so no collector can outlive a pause. Any
 * database error ends the recording with [InterruptReason.StorageFailed] instead of escaping into a coroutine that
 * would kill the process: it is handed to [Listener.onInterrupted] once the session has stopped collecting.
 */
class RecordingSession(
    private val scope: CoroutineScope,
    private val recorder: TrackRecorder,
    /** Duty-cycled fixes; null while the receiver sleeps because the phone lies still. */
    private val readings: () -> Flow<Reading<Fix>?>,
    private val noteMovement: () -> Unit,
    private val defaultName: () -> String,
    /** Why recording cannot go on when a reading says permission is gone. */
    private val permissionLoss: () -> InterruptReason,
    private val listener: Listener,
) {
    interface Listener {
        /** The receiver started or stopped resting. */
        fun onRestingChanged()

        /** Every [TICK_MS] while recording, after buffered points were written. */
        fun onTick()

        /** The session paused itself for [reason]; the owner should leave the foreground and stop. */
        suspend fun onInterrupted(reason: InterruptReason)
    }

    private val lock = Mutex()
    private var collectJob: Job? = null
    private var tickJob: Job? = null

    /** True while the GNSS receiver sleeps because the phone is lying still. */
    var resting = false
        private set

    val isCollecting: Boolean get() = collectJob?.isActive == true

    /** Opens or continues the track and starts collecting. False when the database refused, after which the session is stopped and idle. */
    suspend fun start(): Boolean = lock.withLock {
        try {
            val t = recorder.track ?: recorder.openOrStart(defaultName())
            if (t.state != TrackState.Recording) recorder.setState(TrackState.Recording)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Could not open the track", e)
            return@withLock false
        }
        if (collectJob?.isActive != true) collectJob = scope.launch { collect() }
        tickJob?.cancel()
        tickJob = scope.launch { tick() }
        true
    }

    /**
     * Stops collecting and marks the track Paused. Returns why recording is no longer going on: [reason], or
     * [InterruptReason.StorageFailed] when the state could not be written (the track stays Recording in the database,
     * with whatever the recorder still holds in memory), or null for a clean pause.
     */
    suspend fun pause(reason: InterruptReason?): InterruptReason? = lock.withLock {
        stopCollecting()
        storageFailure { if (attachToOpenTrack()) recorder.setState(TrackState.Paused) } ?: reason
    }

    /** Stops collecting and marks the track Finished. Returns [InterruptReason.StorageFailed] when that could not be written. */
    suspend fun finish(): InterruptReason? = lock.withLock {
        stopCollecting()
        storageFailure { if (attachToOpenTrack()) recorder.setState(TrackState.Finished) }
    }

    /** Writes whatever is buffered, for a service being destroyed. Returns [InterruptReason.StorageFailed] if that failed. */
    suspend fun flush(): InterruptReason? = lock.withLock { storageFailure { recorder.flush() } }

    /** Must be called with the lock held: a job started later by [start] would otherwise survive this. */
    private fun stopCollecting() {
        collectJob?.cancel()
        tickJob?.cancel()
        collectJob = null
        tickJob = null
        resting = false
    }

    private inline fun storageFailure(block: () -> Unit): InterruptReason? = try {
        block()
        null
    } catch (e: SQLiteException) {
        Log.e(TAG, "Database write failed while recording", e)
        InterruptReason.StorageFailed
    }

    /** Binds the recorder to the open track (after process death the service starts empty). Never creates one. */
    private suspend fun attachToOpenTrack(): Boolean {
        if (recorder.track != null) return true
        if (!recorder.hasOpenTrack()) return false
        recorder.openOrStart(defaultName())
        return true
    }

    private suspend fun collect() {
        readings().collect { r ->
            if ((r == null) != resting) {
                resting = r == null
                listener.onRestingChanged()
            }
            when (r) {
                // The receiver sleeps while the phone lies still; TrackStats judges the gap by its average speed.
                null -> Unit
                is Reading.Value -> record(r.value)
                is Reading.Unavailable -> {
                    val reason = if (r.reason == UnavailableReason.Disabled) InterruptReason.LocationDisabled else permissionLoss()
                    interrupt(reason)
                }
                Reading.Acquiring -> Unit
            }
        }
    }

    private suspend fun record(fix: Fix) {
        val failed = lock.withLock {
            // A pause that won the lock cancelled this collector; a fix it had already taken must not be recorded.
            currentCoroutineContext().ensureActive()
            storageFailure {
                if (recorder.offer(fix)) noteMovement()
                if (recorder.flushDue()) recorder.flush()
            }
        }
        if (failed != null) interrupt(failed)
    }

    private suspend fun tick() {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            val failed = lock.withLock {
                currentCoroutineContext().ensureActive()
                storageFailure { if (recorder.flushDue()) recorder.flush() }
            }
            if (failed != null) {
                interrupt(failed)
                return
            }
            listener.onTick()
        }
    }

    /** pause() cancels the collector and the tick that call this, so it runs in a coroutine of its own. */
    private fun interrupt(reason: InterruptReason) {
        scope.launch { listener.onInterrupted(pause(reason) ?: reason) }
    }

    companion object {
        const val TICK_MS = 5_000L
        private const val TAG = "TrackRecording"
    }
}
