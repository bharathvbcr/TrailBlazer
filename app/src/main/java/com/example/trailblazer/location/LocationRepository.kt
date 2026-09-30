package com.example.trailblazer.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.permissions.Permissions
import com.example.trailblazer.sensors.Accuracy
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sensors.UnavailableReason
import com.example.trailblazer.sensors.shareReading
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.mod360
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/** Which vertical datum [Fix.altitudeM] is in. GPS reports height above the WGS-84 ellipsoid, which can differ from sea level by ±100 m. */
enum class AltitudeDatum { SeaLevel, Ellipsoid }

/** A position fix. Optional fields are null when the provider did not report them. */
data class Fix(
    val position: LatLon,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val altitudeDatum: AltitudeDatum,
    val verticalAccuracyM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val timeMs: Long,
    val provider: String?,
)

/**
 * Location without Google Play services: the platform fused provider on API 31+, otherwise GPS and
 * network providers together (the more accurate of recent fixes wins). On API 34+ ellipsoid heights are
 * converted to sea level with the platform's AltitudeConverter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepository(
    private val context: Context,
    private val manager: LocationManager,
    private val permissions: Permissions,
    scope: CoroutineScope,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /** Emits whenever location services are switched on or off in system settings. */
    private val enabled: Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                trySend(LocationManagerCompat.isLocationEnabled(manager))
            }
        }
        // The system location switch sends MODE_CHANGED; individual providers send PROVIDERS_CHANGED.
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }.onStart { emit(LocationManagerCompat.isLocationEnabled(manager)) }.distinctUntilChanged()

    /**
     * Location readings that also report why they stop: permission removed or location switched off in
     * system settings. The UI shares one instance ([fix]); the recording service collects its own.
     */
    fun live(intervalMs: Long): Flow<Reading<Fix>> = combine(permissions.flowOf(AppPermission.Location), enabled) { p, e -> p to e }
        .distinctUntilChanged()
        .flatMapLatest { (permitted, on) ->
            when {
                !permitted -> flowOf(Reading.Unavailable(UnavailableReason.PermissionDenied))
                !on -> flowOf(Reading.Unavailable(UnavailableReason.Disabled))
                else -> updates(intervalMs)
            }
        }

    val fix: StateFlow<Reading<Fix>> = live(1_000L).shareReading(scope)

    /**
     * The newest position the system already has, if it is at most [maxAgeMs] old, through the same validation as a
     * live fix. Never used for tracking, alerts or waypoints: only for screens where a place a few km off is fine
     * while the first live fix arrives. Null without permission, with location off, or when nothing is recent.
     */
    @SuppressLint("MissingPermission") // Checked here; SecurityException is still handled.
    fun lastKnown(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Fix? {
        if (!permissions.isGranted(AppPermission.Location) || !LocationManagerCompat.isLocationEnabled(manager)) return null
        val candidates = (providers() + LocationManager.PASSIVE_PROVIDER).distinct().mapNotNull { p ->
            try {
                manager.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null // provider missing on this device
            }
        }
        return candidates
            .filter { nowMs - it.time in 0..maxAgeMs }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    private fun providers(): List<String> {
        if (Build.VERSION.SDK_INT >= 31 && LocationManagerCompat.hasProvider(manager, LocationManager.FUSED_PROVIDER)) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { LocationManagerCompat.hasProvider(manager, it) }
    }

    /** Raw location updates at [intervalMs]; only reached through [live], which gates on permission and settings. */
    @SuppressLint("MissingPermission") // Gated by live(); SecurityException is still handled below.
    private fun updates(intervalMs: Long): Flow<Reading<Fix>> = callbackFlow {
        trySend(Reading.Acquiring)
        val list = providers()
        if (list.isEmpty()) {
            trySend(Reading.Unavailable(UnavailableReason.NoHardware))
            close()
            return@callbackFlow
        }
        var best: Location? = null
        val listener = LocationListenerCompat { loc ->
            val prev = best
            // With two providers, ignore a less accurate fix that is not newer by at least 10 s.
            if (prev != null && prev.provider != loc.provider && loc.hasAccuracy() && prev.hasAccuracy() &&
                loc.accuracy > prev.accuracy && loc.time - prev.time < 10_000
            ) return@LocationListenerCompat
            val fix = loc.toFix() ?: return@LocationListenerCompat
            best = loc
            trySend(Reading.Value(fix, accuracyFor(loc), System.currentTimeMillis()))
        }
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        try {
            for (p in list) LocationManagerCompat.requestLocationUpdates(manager, p, request, mainExecutor, listener)
        } catch (_: SecurityException) {
            trySend(Reading.Unavailable(UnavailableReason.PermissionDenied))
            close()
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }.map { r -> if (r is Reading.Value) r.copy(value = toSeaLevel(r.value)) else r }

    private fun accuracyFor(l: Location): Accuracy = when {
        !l.hasAccuracy() -> Accuracy.Unknown
        l.accuracy <= 10f -> Accuracy.High
        l.accuracy <= 30f -> Accuracy.Medium
        l.accuracy <= 100f -> Accuracy.Low
        else -> Accuracy.Unreliable
    }

    private var converter: Any? = null

    /** On API 34+, adds mean-sea-level altitude using the platform geoid model (runs off the main thread). */
    private suspend fun toSeaLevel(f: Fix): Fix {
        if (Build.VERSION.SDK_INT < 34 || f.altitudeDatum == AltitudeDatum.SeaLevel || f.altitudeM == null) return f
        return withContext(Dispatchers.IO) {
            try {
                val conv = (converter as? AltitudeConverter) ?: AltitudeConverter().also { converter = it }
                val loc = Location("tb").apply {
                    latitude = f.position.lat
                    longitude = f.position.lon
                    altitude = f.altitudeM
                    f.verticalAccuracyM?.let { verticalAccuracyMeters = it.toFloat() }
                }
                conv.addMslAltitudeToLocation(context, loc)
                if (loc.hasMslAltitude()) {
                    f.copy(altitudeM = loc.mslAltitudeMeters, altitudeDatum = AltitudeDatum.SeaLevel)
                } else f
            } catch (_: Exception) {
                // IOException when the geoid data cannot be loaded; keep the honest ellipsoid label.
                f
            }
        }
    }

    /** Null for a fix whose coordinates are not a real position; such fixes are dropped, never shown. */
    /**
     * Converts a platform fix. The position must be valid or the fix is dropped; every optional field must be finite and
     * physically possible or it becomes null ("not reported"). Mock providers and buggy chipsets do send NaN accuracy,
     * a speed of -1 and bearings past 360°, and a NaN accuracy would slip through every "accuracy ≤ x" filter.
     */
    private fun Location.toFix(): Fix? {
        val pos = LatLon.of(latitude, longitude) ?: return null
        val msl = Build.VERSION.SDK_INT >= 34 && hasMslAltitude()
        fun Double.within(r: ClosedFloatingPointRange<Double>) = takeIf { it.isFinite() && it in r }
        return Fix(
            position = pos,
            accuracyM = if (hasAccuracy()) accuracy.toDouble().within(0.0..MAX_ACCURACY_M) else null,
            altitudeM = when {
                msl -> mslAltitudeMeters
                hasAltitude() -> altitude
                else -> null
            }?.within(MIN_ALTITUDE_M..MAX_ALTITUDE_M),
            altitudeDatum = if (msl) AltitudeDatum.SeaLevel else AltitudeDatum.Ellipsoid,
            verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy()) verticalAccuracyMeters.toDouble().within(0.0..MAX_ACCURACY_M) else null,
            speedMps = if (hasSpeed()) speed.toDouble().within(0.0..MAX_SPEED_MPS) else null,
            bearingDeg = if (hasBearing()) bearing.toDouble().takeIf { it.isFinite() }?.let { mod360(it) } else null,
            timeMs = time,
            provider = provider,
        )
    }
}

/** One satellite as seen by the GNSS receiver. */
data class Satellite(
    val constellation: Int,
    val svid: Int,
    val cn0DbHz: Double,
    val elevationDeg: Double,
    val azimuthDeg: Double,
    val usedInFix: Boolean,
) {
    companion object {
        /**
         * Validates a chipset report: a satellite with no real position in the sky is dropped, azimuth is wrapped into
         * [0, 360), and signal strength (C/N0) is clamped to 0–99 dB-Hz, with an unreadable one shown as no signal.
         */
        fun of(constellation: Int, svid: Int, cn0: Float, elevation: Float, azimuth: Float, used: Boolean): Satellite? {
            val el = elevation.toDouble().takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
            val az = azimuth.toDouble().takeIf { it.isFinite() }?.let { mod360(it) } ?: return null
            val signal = cn0.toDouble().takeIf { it.isFinite() }?.coerceIn(0.0, 99.0) ?: 0.0
            return Satellite(constellation, svid, signal, el, az, used)
        }
    }
}

data class GnssSnapshot(val satellites: List<Satellite>) {
    val inView: Int get() = satellites.size
    val used: Int get() = satellites.count { it.usedInFix }
}

/** Live satellite status via GnssStatus (API 24+). */
@OptIn(ExperimentalCoroutinesApi::class)
class GnssRepository(
    context: Context,
    private val manager: LocationManager,
    permissions: Permissions,
    scope: CoroutineScope,
) {
    private val executor: Executor = ContextCompat.getMainExecutor(context)

    val status: StateFlow<Reading<GnssSnapshot>> = permissions.flowOf(AppPermission.Location).flatMapLatest { ok ->
        if (!ok || !permissions.hasFineLocation()) flowOf(Reading.Unavailable(UnavailableReason.PermissionDenied))
        else if (!LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER)) flowOf(Reading.Unavailable(UnavailableReason.NoHardware))
        else statusUpdates()
    }.shareReading(scope)

    @SuppressLint("MissingPermission") // Gated on fine location above.
    private fun statusUpdates(): Flow<Reading<GnssSnapshot>> = callbackFlow {
        trySend(Reading.Acquiring)
        val cb = object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                val sats = (0 until status.satelliteCount).mapNotNull { i ->
                    Satellite.of(
                        status.getConstellationType(i), status.getSvid(i), status.getCn0DbHz(i),
                        status.getElevationDegrees(i), status.getAzimuthDegrees(i), status.usedInFix(i),
                    )
                }
                trySend(Reading.Value(GnssSnapshot(sats), Accuracy.Unknown, System.currentTimeMillis()))
            }

            override fun onStopped() {
                trySend(Reading.Unavailable(UnavailableReason.Disabled))
            }
        }
        val ok = try {
            LocationManagerCompat.registerGnssStatusCallback(manager, executor, cb)
        } catch (_: SecurityException) {
            false
        }
        if (!ok) {
            trySend(Reading.Unavailable(UnavailableReason.PermissionDenied))
            close()
        }
        awaitClose { LocationManagerCompat.unregisterGnssStatusCallback(manager, cb) }
    }
}

/** Plausibility limits for optional fix fields: beyond them the chipset is reporting garbage, not the world. */
private const val MAX_ACCURACY_M = 100_000.0
private const val MIN_ALTITUDE_M = -1_000.0
private const val MAX_ALTITUDE_M = 20_000.0
private const val MAX_SPEED_MPS = 400.0
