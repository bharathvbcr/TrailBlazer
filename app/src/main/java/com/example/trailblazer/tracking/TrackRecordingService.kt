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
import com.example.trailblazer.location.Fix
import com.example.trailblazer.permissions.AppPermission
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.units.Length
import com.trailblazer.core.units.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Foreground service (type location) that records a track while the screen is off. It holds no state
 * that is not also in Room: if the process dies, the open track is resumed from the database the next
 * time the app is in the foreground. The service is not sticky on purpose: Android will not let a restarted
 * service enter the foreground with the location type while the app is in the background, so a system restart
 * could only crash (see [onStartCommand]).
 *
 * The recording lifecycle itself lives in [RecordingSession]; this class owns the foreground notification and
 * what happens to the service when the session stops.
 */
class TrackRecordingService : LifecycleService(), RecordingSession.Listener {
    private lateinit var recorder: TrackRecorder
    private lateinit var session: RecordingSession
    private lateinit var dutyCycle: GnssDutyCycle
    private var units = UnitSystem.Metric
    private var foreground = false

    /** What the notification currently shows, so an unchanged one is not posted again. */
    private var lastRendered: Rendered? = null

    override fun onCreate() {
        super.onCreate()
        recorder = TrackRecorder(container.db.tracks(), container.clock)
        dutyCycle = GnssDutyCycle(container.sensorSource)
        session = RecordingSession(
            scope = lifecycleScope,
            recorder = recorder,
            readings = ::readings,
            noteMovement = dutyCycle::noteMovement,
            defaultName = ::defaultName,
            permissionLoss = {
                if (container.permissions.isGranted(AppPermission.Location)) InterruptReason.PreciseLocationRequired
                else InterruptReason.LocationPermissionRevoked
            },
            listener = this,
        )
        ensureChannel(this)
        lifecycleScope.launch { container.prefs.settings.collect { units = it.units } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                val blocked = promote()
                lifecycleScope.launch {
                    if (blocked == null) start() else stopBecause(blocked)
                }
            }
            ACTION_PAUSE -> lifecycleScope.launch { pause() }
            // Stopping needs nothing from the foreground, so a revoked permission cannot keep the track open.
            ACTION_STOP -> lifecycleScope.launch { finish() }
            // Every sender sets an action and the service is not sticky, so the system never delivers a null intent.
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Enters the foreground immediately, as Android requires. Returns why it could not, or null once it has.
     * Recording needs precise location, which is checked here as well as in the controller because the
     * notification's Resume button starts the service directly.
     */
    private fun promote(): InterruptReason? {
        val permissions = container.permissions
        if (!permissions.isGranted(AppPermission.Location)) return InterruptReason.LocationPermissionRevoked
        if (!permissions.isGranted(AppPermission.PreciseLocation)) return InterruptReason.PreciseLocationRequired
        return try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            val rendered = render(TrackState.Recording)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(rendered), type)
            lastRendered = rendered
            foreground = true
            null
        } catch (_: SecurityException) {
            InterruptReason.LocationPermissionRevoked
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) when started from the background.
            InterruptReason.ForegroundStartBlocked
        }
    }

    private suspend fun start() {
        if (!session.start()) return stopBecause(InterruptReason.StorageFailed)
        if (container.interrupts.current() != null) container.interrupts.set(null)
        getSystemService(NotificationManager::class.java)?.cancel(STOPPED_NOTIFICATION_ID)
        _running.value = true
        updateNotification(TrackState.Recording)
    }

    private suspend fun pause() {
        val reason = session.pause(null)
        when {
            reason != null -> stopBecause(reason)
            foreground -> updateNotification(TrackState.Paused)
            // Nothing is running to show a notification for (the process was restarted): there is nothing left to keep alive.
            else -> stopSelf()
        }
    }

    private suspend fun finish() {
        val failure = session.finish()
        if (failure != null) return stopBecause(failure)
        if (container.interrupts.current() != null) container.interrupts.set(null)
        leave()
    }

    /** Duty-cycled fixes. Recording needs precise fixes, so losing precision ends them like losing the permission does. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun readings(): Flow<Reading<Fix>?> {
        val mode = container.prefs.settings.map { it.trackingMode }
        return dutyCycle.readings(mode) { interval ->
            container.permissions.flowOf(AppPermission.PreciseLocation).flatMapLatest { precise ->
                if (precise) container.location.live(interval) else flowOf(Reading.Unavailable(UnavailableReason.PermissionDenied))
            }
        }
    }

    override fun onRestingChanged() = updateNotification(TrackState.Recording)

    override fun onTick() = updateNotification(TrackState.Recording)

    override suspend fun onInterrupted(reason: InterruptReason) = stopBecause(reason)

    /** Records why recording ended, tells the user, and takes the service down. */
    private suspend fun stopBecause(reason: InterruptReason) {
        container.interrupts.set(reason)
        // Leaving the foreground removes the recording notification, so say what happened where the user will see it.
        getSystemService(NotificationManager::class.java)?.notify(STOPPED_NOTIFICATION_ID, stoppedNotification(reason))
        leave()
    }

    /** Leaves the foreground (no location-type service should run without location) and stops. */
    private fun leave() {
        _running.value = false
        foreground = false
        lastRendered = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        // Persist anything still buffered even though the service scope is being cancelled.
        container.scope.launch {
            withContext(NonCancellable) {
                session.flush()?.let { container.interrupts.set(it) }
            }
        }
        super.onDestroy()
    }

    private fun defaultName(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(Locale.getDefault(), "Track %1\$tF %1\$tR", c)
    }

    /** Posts the notification only when what it shows has changed; the tick would otherwise repost it every five seconds. */
    private fun updateNotification(state: TrackState) {
        if (!foreground) return
        val rendered = render(state)
        if (rendered == lastRendered) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        lastRendered = rendered
        nm.notify(NOTIFICATION_ID, build(rendered))
    }

    private fun action(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, TrackRecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Everything the recording notification shows that can change; equal values render the same notification. */
    private data class Rendered(val paused: Boolean, val resting: Boolean, val text: String)

    private fun render(state: TrackState): Rendered {
        val (d, unit) = Length.distance(if (::recorder.isInitialized) recorder.distanceM else 0.0, units)
        val minutes = (if (::recorder.isInitialized) recorder.elapsedMs else 0L) / 60_000
        val text = String.format(Locale.getDefault(), "%.1f %s · %d:%02d h", d, unit, minutes / 60, minutes % 60)
        val paused = state == TrackState.Paused
        return Rendered(paused, resting = !paused && ::session.isInitialized && session.resting, text)
    }

    private fun build(r: Rendered): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(getString(if (r.paused) R.string.recording_paused else if (r.resting) R.string.recording_gps_resting else R.string.recording_track))
            .setContentText(r.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (r.paused) b.addAction(0, getString(R.string.resume), action(ACTION_RESUME, 2))
        else b.addAction(0, getString(R.string.pause), action(ACTION_PAUSE, 1))
        b.addAction(0, getString(R.string.stop), action(ACTION_STOP, 3))
        return b.build()
    }

    private fun stoppedNotification(reason: InterruptReason): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(getString(R.string.recording_stopped))
            .setContentText(getString(reason.messageRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(reason.messageRes)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openApp())
            .build()

    companion object {
        const val ACTION_START = "com.example.trailblazer.track.START"
        const val ACTION_PAUSE = "com.example.trailblazer.track.PAUSE"
        const val ACTION_RESUME = "com.example.trailblazer.track.RESUME"
        const val ACTION_STOP = "com.example.trailblazer.track.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 42
        const val STOPPED_NOTIFICATION_ID = 43

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> get() = _running

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW),
            )
        }

        /**
         * Sends [action] to the service. Starting it in the foreground throws [IllegalStateException]
         * (ForegroundServiceStartNotAllowedException on API 31+) when the app is in the background.
         */
        fun send(context: Context, action: String) {
            val i = Intent(context, TrackRecordingService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_RESUME) ContextCompat.startForegroundService(context, i)
            else context.startService(i)
        }
    }
}
