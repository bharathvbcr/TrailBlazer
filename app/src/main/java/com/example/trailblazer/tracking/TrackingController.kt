package com.example.trailblazer.tracking

import android.content.Context
import com.example.trailblazer.data.TrackRepository
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.permissions.Permissions
import com.example.trailblazer.sensors.SensorSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** UI-facing control of the recording service. */
class TrackingController(
    private val context: Context,
    private val tracks: TrackRepository,
    private val permissions: Permissions,
    sensors: SensorSource,
    private val interrupts: InterruptState,
) {
    /** Whether a motion-gated [com.example.trailblazer.data.TrackingMode] can let GPS sleep on this phone. */
    val canRestGps: Boolean = GnssDutyCycle(sensors).canSleep
    val running: StateFlow<Boolean> = TrackRecordingService.running
    val interrupted: StateFlow<InterruptReason?> = interrupts.reason
    val open: Flow<com.example.trailblazer.data.TrackSummary?> = tracks.open

    /** Refused, with the reason, when recording cannot start; the caller asks for the permission in context or tells the user. */
    fun start(): StartResult {
        if (!permissions.isGranted(AppPermission.Location)) return StartResult.Refused(StartRefusal.LocationPermissionMissing)
        // Approximate fixes are over 30 m wide and recording drops them, so a coarse-only recording would never keep a point.
        if (!permissions.isGranted(AppPermission.PreciseLocation)) return StartResult.Refused(StartRefusal.PreciseLocationRequired)
        return try {
            TrackRecordingService.send(context, TrackRecordingService.ACTION_START)
            StartResult.Started
        } catch (_: SecurityException) {
            StartResult.Refused(StartRefusal.LocationPermissionMissing)
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+): the app is in the background.
            StartResult.Refused(StartRefusal.BackgroundStartBlocked)
        }
    }

    fun pause() = TrackRecordingService.send(context, TrackRecordingService.ACTION_PAUSE)

    fun resume(): StartResult = start()

    fun stop() = TrackRecordingService.send(context, TrackRecordingService.ACTION_STOP)

    /**
     * Finishes the open track and waits (up to [timeoutMs]) for the service to be gone, for callers about to delete
     * tracks: a service still recording would otherwise write points for a track that no longer exists.
     * Returns false if the service was still running when the wait ended.
     */
    suspend fun stopAndAwait(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        if (!running.value) return true
        stop()
        return withTimeoutOrNull(timeoutMs) { running.first { !it } } != null
    }

    suspend fun clearInterrupt() = interrupts.set(null)

    /**
     * Called when the app comes to the foreground: a track still marked Recording with no running service
     * means the process was killed mid-recording, so recording continues where it left off. A service that stopped
     * itself (see [InterruptReason]) is not restarted behind the user's back, and a start that cannot happen says why.
     */
    suspend fun resumeIfInterrupted(): ResumeResult {
        if (running.value) return ResumeResult.NothingToResume
        val t = open.first() ?: return ResumeResult.NothingToResume
        if (t.state != TrackState.Recording) return ResumeResult.NothingToResume
        interrupts.current()?.let { return ResumeResult.Interrupted(it) }
        return when (val r = start()) {
            StartResult.Started -> ResumeResult.Resumed
            is StartResult.Refused -> {
                // Put the reason where the Tracks screen shows it, so the track does not just sit "interrupted" unexplained.
                when (r.reason) {
                    StartRefusal.LocationPermissionMissing -> interrupts.set(InterruptReason.LocationPermissionRevoked)
                    StartRefusal.PreciseLocationRequired -> interrupts.set(InterruptReason.PreciseLocationRequired)
                    StartRefusal.BackgroundStartBlocked -> Unit
                }
                ResumeResult.Blocked(r.reason)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
