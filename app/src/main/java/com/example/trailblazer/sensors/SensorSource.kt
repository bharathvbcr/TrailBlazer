package com.example.trailblazer.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
}

class AndroidSensorSource(private val manager: SensorManager) : SensorSource {
    override fun has(type: Int) = manager.getDefaultSensor(type) != null

    private fun Sensor.toInfo() = SensorInfo(type, name, vendor, version, maximumRange, resolution, power, minDelay, isWakeUpSensor, stringType)

    override fun info(type: Int): SensorInfo? = manager.getDefaultSensor(type)?.toInfo()

    override fun all(): List<SensorInfo> = manager.getSensorList(Sensor.TYPE_ALL).map { it.toInfo() }

    override fun samples(type: Int, periodUs: Int): Flow<SensorSample> = callbackFlow {
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
        if (!manager.registerListener(listener, sensor, periodUs)) {
            close()
            return@callbackFlow
        }
        awaitClose { manager.unregisterListener(listener) }
    }.buffer(Channel.CONFLATED)
}
