package com.example.trailblazer.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
     * Completes immediately when the sensor does not exist.
     */
    fun samples(type: Int, periodUs: Int): Flow<SensorSample>

    /**
     * Every sample for [type], not conflated, for logic that must not miss one (a collapsed batch could hide motion).
     * The sensor hub may hold samples for up to [maxReportLatencyUs] and deliver them together, each with its own
     * timestamp, so the phone can sleep in between. Samples that do not fit the buffer are dropped, which shows up as a
     * gap in the timestamps. Completes immediately when the sensor does not exist.
     */
    fun batchedSamples(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample>

    /**
     * One emission each time a one-shot trigger sensor such as significant motion fires; it is re-armed after every
     * trigger and disarmed when collection stops. Completes immediately when the sensor does not exist.
     */
    fun triggers(type: Int): Flow<Unit>
}

class AndroidSensorSource(private val manager: SensorManager) : SensorSource {
    override fun has(type: Int) = manager.getDefaultSensor(type) != null

    private fun Sensor.toInfo() = SensorInfo(type, name, vendor, version, maximumRange, resolution, power, minDelay, isWakeUpSensor, stringType)

    override fun info(type: Int): SensorInfo? = manager.getDefaultSensor(type)?.toInfo()

    override fun all(): List<SensorInfo> = manager.getSensorList(Sensor.TYPE_ALL).map { it.toInfo() }

    override fun samples(type: Int, periodUs: Int): Flow<SensorSample> = listen(type, periodUs, 0).buffer(Channel.CONFLATED)

    override fun batchedSamples(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample> =
        listen(type, periodUs, maxReportLatencyUs).buffer(BATCH_BUFFER)

    private fun listen(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample> = callbackFlow {
        val sensor = manager.getDefaultSensor(type)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(SensorSample(event.values.copyOf(), event.accuracy, event.timestamp))
            }

            // Every subsequent SensorEvent carries the new accuracy, so nothing to do here.
            override fun onAccuracyChanged(s: Sensor, newAccuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, sensor, periodUs, maxReportLatencyUs)) {
            close()
            return@callbackFlow
        }
        awaitClose { manager.unregisterListener(listener) }
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
