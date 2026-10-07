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

/** UI-facing control of the recording service. */
class TrackingController(
    private val context: Context,
    private val tracks: TrackRepository,
    private val permissions: Permissions,
    sensors: SensorSource,
) {
    /** Whether a motion-gated [com.example.trailblazer.data.TrackingMode] can let GPS sleep on this phone. */
    val canRestGps: Boolean = GnssDutyCycle(sensors).canSleep
    val running: StateFlow<Boolean> = TrackRecordingService.running
    val interrupted: StateFlow<InterruptReason?> = TrackRecordingService.interrupted
    val open: Flow<com.example.trailblazer.data.TrackSummary?> = tracks.open

    /** False when location permission is missing; the caller asks for it in context. */
    fun start(): Boolean {
        if (!permissions.isGranted(AppPermission.Location)) return false
        TrackRecordingService.send(context, TrackRecordingService.ACTION_START)
        return true
    }

    fun pause() = TrackRecordingService.send(context, TrackRecordingService.ACTION_PAUSE)

    fun resume(): Boolean = start()

    fun stop() = TrackRecordingService.send(context, TrackRecordingService.ACTION_STOP)

    /**
     * Called when the app comes to the foreground: a track still marked Recording with no running service
     * means the process was killed mid-recording, so recording continues where it left off.
     */
    suspend fun resumeIfInterrupted() {
        if (running.value) return
        val t = open.first() ?: return
        if (t.state == TrackState.Recording) start()
    }
}
