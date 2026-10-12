package com.example.trailblazer.tracking

import android.util.Log
import androidx.annotation.StringRes
import com.example.trailblazer.R
import com.example.trailblazer.data.PrefsRepository
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
