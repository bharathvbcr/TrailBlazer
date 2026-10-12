package com.example.trailblazer.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** A copy of one SensorEvent (Android reuses event objects, so values must be copied). */
class SensorSample(val values: FloatArray, val accuracy: Int, val timestampNs: Long)

/** Static description of a hardware sensor, for the diagnostics screen. */
data class SensorInfo(
    val type: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val powerMa: Float,
    val minDelayUs: Int,
    val isWakeUp: Boolean,
    /** The sensor's type name, e.g. "android.sensor.pressure" or a vendor's "com.google.sensor.pressure_temp". */
    val stringType: String? = null,
)

/** Seam between repositories and Android's SensorManager; tests substitute a fake. */
interface SensorSource {
    fun has(type: Int): Boolean
    fun info(type: Int): SensorInfo?
    fun all(): List<SensorInfo>

    /**
     * Samples for [type] at roughly [periodUs]. The flow is conflated: a slow collector sees the
     * latest sample instead of an unbounded queue. Unregisters the listener when collection stops.
     * Completes immediately, emitting nothing, when the sensor does not exist or the system refuses the registration
     * (including a SecurityException, which is caught and never thrown to the collector).
     */
    fun samples(type: Int, periodUs: Int): Flow<SensorSample>

    /**
     * Every sample for [type], not conflated, for logic that must not miss one (a collapsed batch could hide motion).
     * The sensor hub may hold samples for up to [maxReportLatencyUs] and deliver them together, each with its own
     * timestamp, so the phone can sleep in between. Samples that do not fit the buffer are dropped, which shows up as a
     * gap in the timestamps. Completes immediately, emitting nothing, when the sensor does not exist or the system
     * refuses the registration (including a SecurityException, which is caught and never thrown to the collector).
     * A listener registered by another part of the app (the sensor recorder asks for 200 Hz) can make the hardware run
     * faster than [periodUs], and Android may deliver faster than requested: callers must not assume the rate.
     */
    fun batchedSamples(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample>

    /**
     * The registration both flows above are built on: [onSample] runs for every event, unconflated, on [handler]'s
     * thread (the main thread when null). It must not block and must copy nothing it keeps ([SensorSample] already holds
     * a copy). Closing the returned handle unregisters the listener. Returns null, registering nothing, when the sensor
     * does not exist or the system refuses the registration (including a [SecurityException] for a rate that needs a
     * permission). A caller that must account for every event (the recorder) uses this instead of a flow, so it controls
     * where an overflowing queue drops.
     */
    fun register(type: Int, periodUs: Int, maxReportLatencyUs: Int, handler: Handler?, onSample: (SensorSample) -> Unit): AutoCloseable?

    /**
     * One emission each time a one-shot trigger sensor such as significant motion fires; it is re-armed after every
     * trigger and disarmed when collection stops. Completes immediately when the sensor does not exist.
     */
    fun triggers(type: Int): Flow<Unit>
}

class AndroidSensorSource(
    private val manager: SensorManager,
    /** The call that registers a listener; replaceable so a test can make the system refuse it as Android 12+ does. */
    private val registrar: (SensorEventListener, Sensor, Int, Int, Handler?) -> Boolean = { l, s, period, latency, handler ->
        if (handler == null) manager.registerListener(l, s, period, latency) else manager.registerListener(l, s, period, latency, handler)
    },
) : SensorSource {
    override fun has(type: Int) = manager.getDefaultSensor(type) != null

    private fun Sensor.toInfo() = SensorInfo(type, name.orEmpty(), vendor.orEmpty(), version, maximumRange, resolution, power, minDelay, isWakeUpSensor, stringType)

    override fun info(type: Int): SensorInfo? = manager.getDefaultSensor(type)?.toInfo()

    override fun all(): List<SensorInfo> = manager.getSensorList(Sensor.TYPE_ALL).map { it.toInfo() }

    override fun samples(type: Int, periodUs: Int): Flow<SensorSample> = listen(type, periodUs, 0).buffer(Channel.CONFLATED)

    override fun batchedSamples(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample> =
        listen(type, periodUs, maxReportLatencyUs).buffer(BATCH_BUFFER)

    override fun register(type: Int, periodUs: Int, maxReportLatencyUs: Int, handler: Handler?, onSample: (SensorSample) -> Unit): AutoCloseable? {
        val sensor = manager.getDefaultSensor(type) ?: return null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onSample(SensorSample(event.values.copyOf(), event.accuracy, event.timestamp))
            }

            // Every subsequent SensorEvent carries the new accuracy, so nothing to do here.
            override fun onAccuracyChanged(s: Sensor, newAccuracy: Int) = Unit
        }
        val registered = try {
            registrar(listener, sensor, periodUs, maxReportLatencyUs, handler)
        } catch (_: SecurityException) {
            false
        }
        return if (registered) AutoCloseable { manager.unregisterListener(listener) } else null
    }

    private fun listen(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample> = callbackFlow {
        val registration = register(type, periodUs, maxReportLatencyUs, null) { trySend(it) }
        if (registration == null) {
            close()
            return@callbackFlow
        }
        awaitClose { registration.close() }
    }

    override fun triggers(type: Int): Flow<Unit> = callbackFlow {
        val sensor = manager.getDefaultSensor(type)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                trySend(Unit)
                // A trigger request is cancelled once it fires; ask again to keep listening.
                manager.requestTriggerSensor(this, sensor)
            }
        }
        if (!manager.requestTriggerSensor(listener, sensor)) {
            close()
            return@callbackFlow
        }
        awaitClose { manager.cancelTriggerSensor(listener, sensor) }
    }

    private companion object {
        /** About eight minutes of 2 Hz samples. A bigger batch loses its newest samples, which readers see as a gap. */
        const val BATCH_BUFFER = 1_024
    }
}
