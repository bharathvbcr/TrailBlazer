package com.example.trailblazer.sensors

import android.annotation.SuppressLint
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import com.example.trailblazer.permissions.Permissions
import com.trailblazer.core.sensors.log.GnssMeasurementRow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Whether raw GNSS measurements are being delivered, and why not when they are not. */
sealed interface GnssRawRegistration {
    /** Closing the handle unregisters the callback and stops its thread. */
    class Active(val handle: AutoCloseable) : GnssRawRegistration

    class Unavailable(val reason: String) : GnssRawRegistration
}

/**
 * Seam for raw GNSS measurements (`GnssMeasurementsEvent`), the data behind pseudoranges. Separate from
 * [com.example.trailblazer.location.GnssRepository], which reports `GnssStatus` (what the satellite list shows): the
 * platform delivers the two through different callbacks. Tests substitute a fake.
 */
interface GnssMeasurementSource {
    /**
     * Starts delivering one batch of rows per measurement event to [onRows], on a thread owned by the source, never the
     * main thread. [onStatus] is told when the platform says raw measurements are not supported or not allowed.
     */
    fun register(onRows: (List<GnssMeasurementRow>) -> Unit, onStatus: (String) -> Unit): GnssRawRegistration
}

/**
 * Raw GNSS measurements through `LocationManagerCompat.registerGnssMeasurementsCallback` (an executor on API 30+, a
 * handler on older releases; the compat call picks). Needs precise location, the permission the satellite list already
 * uses; this adds none. Chipsets differ in which fields they fill, so every optional field is read only when its `has`
 * check is true and is otherwise left null.
 */
class AndroidGnssMeasurementSource(
    private val manager: LocationManager,
    private val permissions: Permissions,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : GnssMeasurementSource {
    @SuppressLint("MissingPermission") // Gated on fine location below; SecurityException is still handled.
    override fun register(onRows: (List<GnssMeasurementRow>) -> Unit, onStatus: (String) -> Unit): GnssRawRegistration {
        if (!permissions.hasFineLocation()) return GnssRawRegistration.Unavailable("precise location permission not granted")
        if (!LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER)) {
            return GnssRawRegistration.Unavailable("no GNSS receiver")
        }
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tb-gnss-raw").apply { isDaemon = true } }
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                val rows = event.toRows(elapsedRealtimeNanos())
                if (rows.isNotEmpty()) onRows(rows)
            }

            // The platform deprecated the status callback and its constants; it is still the only way to learn that raw
            // measurements are unsupported on this device.
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onStatusChanged(status: Int) {
                when (status) {
                    STATUS_NOT_SUPPORTED -> onStatus("raw measurements not supported by this device")
                    STATUS_NOT_ALLOWED -> onStatus("raw measurements not allowed (location permission or setting)")
                    STATUS_LOCATION_DISABLED -> onStatus("location is switched off")
                }
            }
        }
        val registered = try {
            LocationManagerCompat.registerGnssMeasurementsCallback(manager, executor, callback)
        } catch (_: SecurityException) {
            false
        }
        if (!registered) {
            executor.shutdownNow()
            return GnssRawRegistration.Unavailable("the system refused the raw measurements registration")
        }
        return GnssRawRegistration.Active(
            AutoCloseable {
                LocationManagerCompat.unregisterGnssMeasurementsCallback(manager, callback)
                executor.shutdown()
            },
        )
    }
}

/** One row per satellite signal in the event, each carrying the event's receiver clock fields. */
internal fun GnssMeasurementsEvent.toRows(callbackElapsedNs: Long): List<GnssMeasurementRow> {
    val clk = clock
    val clockElapsed = Build.VERSION.SDK_INT >= 29 && clk.hasElapsedRealtimeNanos()
    val elapsed = if (clockElapsed) clk.elapsedRealtimeNanos else callbackElapsedNs
    val clockTime = clk.timeNanos
    val fullBias = if (clk.hasFullBiasNanos()) clk.fullBiasNanos else null
    val bias = if (clk.hasBiasNanos()) clk.biasNanos else null
    return measurements.map { m ->
        GnssMeasurementRow(
            elapsedNs = elapsed,
            // getConstellationType is API 26; the platform can only say "unknown" (0) for older releases.
            constellation = if (Build.VERSION.SDK_INT >= 26) m.constellationType else 0,
            svid = m.svid,
            cn0DbHz = m.cn0DbHz,
            receivedSvTimeNs = m.receivedSvTimeNanos,
            receivedSvTimeUncertaintyNs = m.receivedSvTimeUncertaintyNanos,
            state = m.state,
            pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
            pseudorangeRateUncertaintyMps = m.pseudorangeRateUncertaintyMetersPerSecond,
            timeOffsetNs = m.timeOffsetNanos,
            accumulatedDeltaRangeM = m.accumulatedDeltaRangeMeters,
            accumulatedDeltaRangeState = m.accumulatedDeltaRangeState,
            carrierFrequencyHz = if (Build.VERSION.SDK_INT >= 26 && m.hasCarrierFrequencyHz()) m.carrierFrequencyHz else null,
            clockTimeNs = clockTime,
            fullBiasNs = fullBias,
            biasNs = bias,
            hardwareClockDiscontinuityCount = clk.hardwareClockDiscontinuityCount,
            note = if (clockElapsed) "" else "elapsed=callback",
        )
    }
}
