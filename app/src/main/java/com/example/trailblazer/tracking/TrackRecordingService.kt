package com.example.trailblazer.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.trailblazer.MainActivity
import com.example.trailblazer.R
import com.example.trailblazer.container
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sensors.UnavailableReason
import com.trailblazer.core.units.Length
import com.trailblazer.core.units.UnitSystem
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Why recording stopped on its own, shown on the Tracks screen. */
enum class InterruptReason { LocationPermissionRevoked, LocationDisabled }

/**
 * Foreground service (type location) that records a track while the screen is off. It holds no state
 * that is not also in Room: if the process dies, the open track is resumed from the database the next
 * time the app is in the foreground.
 */
class TrackRecordingService : LifecycleService() {
    private lateinit var recorder: TrackRecorder
    private var collectJob: Job? = null
    private var tickJob: Job? = null
    private val lock = Mutex()
    private var units = UnitSystem.Metric
    private lateinit var dutyCycle: GnssDutyCycle

    /** True while the GNSS receiver sleeps because the phone is lying still. */
    private var resting = false

    override fun onCreate() {
        super.onCreate()
        recorder = TrackRecorder(container.db.tracks(), container.clock)
        dutyCycle = GnssDutyCycle(container.sensorSource)
        ensureChannel(this)
        lifecycleScope.launch { container.prefs.settings.collect { units = it.units } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!promote()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START, ACTION_RESUME, null -> lifecycleScope.launch { start() }
            ACTION_PAUSE -> lifecycleScope.launch { pause(null) }
            ACTION_STOP -> lifecycleScope.launch { finish() }
        }
        return START_NOT_STICKY
    }

    /** Enters the foreground immediately, as Android requires. Fails (and we stop) without location permission. */
    private fun promote(): Boolean {
        if (!container.permissions.isGranted(AppPermission.Location)) return false
        return try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(TrackState.Recording), type)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) when started from the background.
            false
        }
    }

    private suspend fun start() = lock.withLock {
        val t = recorder.track ?: recorder.openOrStart(defaultName())
        if (t.state != TrackState.Recording) recorder.setState(TrackState.Recording)
        _running.value = true
        _interrupted.value = null
        if (collectJob?.isActive == true) return@withLock
        collectJob = lifecycleScope.launch {
            val mode = container.prefs.settings.map { it.trackingMode }
            dutyCycle.readings(mode) { interval -> container.location.live(interval) }.collect { r ->
                if ((r == null) != resting) {
                    resting = r == null
                    updateNotification(TrackState.Recording)
                }
                when (r) {
                    // The receiver sleeps while the phone lies still; TrackStats judges the gap by its average speed.
                    null -> Unit
                    is Reading.Value -> lock.withLock {
                        if (recorder.offer(r.value)) dutyCycle.noteMovement()
                        if (recorder.flushDue()) recorder.flush()
                    }
                    is Reading.Unavailable -> {
                        // pause() cancels this collector, so it must run in its own coroutine.
                        val reason = if (r.reason == UnavailableReason.Disabled) InterruptReason.LocationDisabled else InterruptReason.LocationPermissionRevoked
                        lifecycleScope.launch { pause(reason) }
                    }
                    Reading.Acquiring -> Unit
                }
            }
        }
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                lock.withLock { if (recorder.flushDue()) recorder.flush() }
                updateNotification(TrackState.Recording)
            }
        }
    }

    private suspend fun pause(reason: InterruptReason?) {
        collectJob?.cancel()
        tickJob?.cancel()
        resting = false
        lock.withLock {
            if (attachToOpenTrack()) recorder.setState(TrackState.Paused)
        }
        _interrupted.value = reason
        if (reason != null) {
            // Location is gone: leave the foreground so no location-type service runs without location.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            _running.value = false
            stopSelf()
        } else {
            updateNotification(TrackState.Paused)
        }
    }

    private suspend fun finish() {
        collectJob?.cancel()
        tickJob?.cancel()
        lock.withLock {
            if (attachToOpenTrack()) recorder.setState(TrackState.Finished)
        }
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Binds the recorder to the open track (after process death the service starts empty). Never creates one. */
    private suspend fun attachToOpenTrack(): Boolean {
        if (recorder.track != null) return true
        if (container.db.tracks().open() == null) return false
        recorder.openOrStart(defaultName())
        return true
    }

    override fun onDestroy() {
        _running.value = false
        // Persist anything still buffered even though the service scope is being cancelled.
        container.scope.launch { withContext(NonCancellable) { lock.withLock { recorder.flush() } } }
        super.onDestroy()
    }

    private fun defaultName(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(Locale.getDefault(), "Track %1\$tF %1\$tR", c)
    }

    private fun updateNotification(state: TrackState) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, notification(state))
    }

    private fun action(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, TrackRecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notification(state: TrackState): Notification {
        val (d, unit) = Length.distance(if (::recorder.isInitialized) recorder.distanceM else 0.0, units)
        val minutes = (if (::recorder.isInitialized) recorder.elapsedMs else 0L) / 60_000
        val text = String.format(Locale.getDefault(), "%.1f %s · %d:%02d h", d, unit, minutes / 60, minutes % 60)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(
                getString(
                    when {
                        state == TrackState.Paused -> R.string.recording_paused
                        resting -> R.string.recording_gps_resting
                        else -> R.string.recording_track
                    },
                ),
            )
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state == TrackState.Paused) b.addAction(0, getString(R.string.resume), action(ACTION_RESUME, 2))
        else b.addAction(0, getString(R.string.pause), action(ACTION_PAUSE, 1))
        b.addAction(0, getString(R.string.stop), action(ACTION_STOP, 3))
        return b.build()
    }

    companion object {
        const val ACTION_START = "com.example.trailblazer.track.START"
        const val ACTION_PAUSE = "com.example.trailblazer.track.PAUSE"
        const val ACTION_RESUME = "com.example.trailblazer.track.RESUME"
        const val ACTION_STOP = "com.example.trailblazer.track.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> get() = _running
        private val _interrupted = MutableStateFlow<InterruptReason?>(null)
        val interrupted: StateFlow<InterruptReason?> get() = _interrupted

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW),
            )
        }

        fun send(context: Context, action: String) {
            val i = Intent(context, TrackRecordingService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_RESUME) ContextCompat.startForegroundService(context, i)
            else context.startService(i)
        }
    }
}
