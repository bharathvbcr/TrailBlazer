package com.example.trailblazer.tracking

import android.util.Log
import androidx.annotation.StringRes
import com.example.trailblazer.R
import com.example.trailblazer.data.PrefsRepository
import com.example.trailblazer.sensors.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Why recording stopped on its own, shown on the Tracks screen until the user resumes or finishes the track. */
enum class InterruptReason(@StringRes val messageRes: Int) {
    LocationPermissionRevoked(R.string.interrupt_permission_revoked),
    LocationDisabled(R.string.interrupt_location_disabled),

    /** Only approximate location is granted; its fixes are too coarse to record a track from. */
    PreciseLocationRequired(R.string.interrupt_precise_required),

    /** The database refused a write (a full disk, say). Points still buffered may be lost. */
    StorageFailed(R.string.interrupt_storage_failed),

    /** Android would not let the service enter the foreground, as when the app was in the background. */
    ForegroundStartBlocked(R.string.interrupt_foreground_blocked),
}

/** Why a request to start or resume recording was turned down before the service was asked. */
enum class StartRefusal(val message: String) {
    LocationPermissionMissing("Recording needs location permission."),
    PreciseLocationRequired("Recording needs precise location. Allow \"Precise\" for TrailBlazer in the app's permission settings, then start again."),
    BackgroundStartBlocked("Android would not start recording while the app is in the background. Open the app and try again."),
}

sealed interface StartResult {
    data object Started : StartResult
    data class Refused(val reason: StartRefusal) : StartResult
}

/** What [TrackingController.resumeIfInterrupted] did, and when it did not resume, why. */
sealed interface ResumeResult {
    /** No open track, one that is paused, or a recording already running. */
    data object NothingToResume : ResumeResult
    data object Resumed : ResumeResult
    data class Blocked(val refusal: StartRefusal) : ResumeResult

    /** The service stopped itself for [reason] and the user has to choose to continue; it is not restarted behind their back. */
    data class Interrupted(val reason: InterruptReason) : ResumeResult
}

/**
 * Bounds how often an interrupted recording is restarted automatically. Without it, a full disk loops: the pause
 * and the stored reason both fail to write, so after a process death the track still says Recording, the app
 * restarts it on every foreground, it fails again, and a notification is posted each time.
 *
 * An attempt is written down before it is made, and a restart that cannot be written down is refused: a disk too
 * full to record the attempt is a disk too full to record a track. At most [MAX_ATTEMPTS] attempts are allowed within
 * [WINDOW_MS] of the first; the service calls [reset] once a start has worked, so ordinary process deaths never
 * add up. A restart the user asks for is never gated.
 */
class ResumeBackoff(private val prefs: PrefsRepository, private val clock: Clock) {
    /** True if the automatic restart may go ahead (and has been recorded); false means leave the track for the user. */
    suspend fun beginAutoResume(): Boolean {
        val now = clock.nowMs()
        val stored = prefs.trackingResumeAttempts.first()?.split(':')
        val count = stored?.getOrNull(0)?.toIntOrNull()
        val first = stored?.getOrNull(1)?.toLongOrNull()
        val open = if (count != null && first != null && now - first in 0..WINDOW_MS) count to first else null
        val next = (open?.first ?: 0) + 1
        if (next > MAX_ATTEMPTS) return false
        return try {
            prefs.setTrackingResumeAttempts("$next:${open?.second ?: now}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Could not record a restart attempt, so not restarting", e)
            false
        }
    }

    suspend fun reset() {
        try {
            prefs.setTrackingResumeAttempts(null)
        } catch (e: IOException) {
            Log.e(TAG, "Could not clear the restart attempts", e)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val WINDOW_MS = 10 * 60_000L
        private const val TAG = "TrackRecording"
    }
}

/**
 * The reason recording stopped on its own, kept in memory for the UI and in DataStore so it survives the process:
 * the service that set it is usually gone by the time anyone looks. A failed write to DataStore is logged and the
 * in-memory value stays, so the reason is still shown for as long as the process lives.
 */
class InterruptState(private val prefs: PrefsRepository, scope: CoroutineScope) {
    private val _reason = MutableStateFlow<InterruptReason?>(null)
    val reason: StateFlow<InterruptReason?> = _reason.asStateFlow()

    private val loadLock = Mutex()
    private var loaded = false

    @Volatile
    private var touched = false

    init {
        scope.launch { ensureLoaded() }
    }

    /** The reason once the stored one has been read; use this where a decision depends on it. */
    suspend fun current(): InterruptReason? {
        ensureLoaded()
        return _reason.value
    }

    suspend fun set(reason: InterruptReason?) {
        touched = true
        _reason.value = reason
        try {
            prefs.setTrackingInterrupt(reason?.name)
        } catch (e: IOException) {
            Log.e(TAG, "Could not persist the interrupt reason $reason", e)
        }
    }

    private suspend fun ensureLoaded() = loadLock.withLock {
        if (loaded) return@withLock
        val stored = prefs.trackingInterrupt.first()
        // A reason set before the read finished is newer than the stored one.
        if (!touched) _reason.value = InterruptReason.entries.firstOrNull { it.name == stored }
        loaded = true
    }

    private companion object {
        const val TAG = "TrackRecording"
    }
}
