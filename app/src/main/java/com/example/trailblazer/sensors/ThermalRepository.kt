package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.sensors.shareReading

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Temperatures from inside the phone: the chips that some manufacturers expose as their own sensor types (on Pixels,
 * the barometer's and the gyroscope's die temperature) and the battery. They follow the phone's own heat (charging,
 * the processor, a hand, a pocket), so they are shown as what they are and never used as air temperature, for
 * density altitude or for weather.
 *
 * Not included: the infrared thermometer on Pixel 8 Pro and later. Its sensor needs
 * `com.google.sensor.permission.FAR_INFRARED_TEMPERATURE`, which is `signature|preinstalled`, so no installed app can
 * be granted it.
 */
class ThermalRepository(private val context: Context, private val source: SensorSource, private val clock: Clock) {
    /** The chip-temperature sensors this phone exposes, in the order the system lists them. */
    fun chips(): List<SensorInfo> = source.all().filter(::isChipTemperature)

    fun chip(info: SensorInfo): Flow<Reading<Double>> =
        source.reading(info.type, SensorManager.SENSOR_DELAY_NORMAL, clock) { s -> chipCelsius(s.values.firstOrNull()) }

    /** The battery temperature from the sticky battery broadcast, every [periodMs]. Needs no permission. */
    fun battery(periodMs: Long = 10_000L): Flow<Reading<Double>> = flow {
        emit(Reading.Acquiring)
        while (true) {
            val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            emit(batteryCelsius(tenths)?.let { Reading.Value(it, Accuracy.Unknown, clock.nowMs()) } ?: Reading.Unavailable(UnavailableReason.NoHardware))
            delay(periodMs)
        }
    }
}

/**
 * A manufacturer's private temperature sensor. Standard ambient temperature (air) is a different type, and the
 * infrared thermometer is excluded because no app can be granted its permission.
 */
internal fun isChipTemperature(info: SensorInfo): Boolean {
    if (info.type < Sensor.TYPE_DEVICE_PRIVATE_BASE) return false
    val t = info.stringType?.lowercase() ?: return false
    if ("fir_" in t || "infrared" in t) return false
    return t.endsWith("temperature") || t.endsWith("_temp")
}

/** A chip's reading in °C, or null when it is not a real one. Silicon works from about −40 to 125 °C. */
internal fun chipCelsius(v: Float?): Double? = v?.toDouble()?.takeIf { it.isFinite() && it in -40.0..125.0 }

/** BatteryManager reports tenths of a degree; missing or impossible values are not a temperature. */
internal fun batteryCelsius(tenths: Int): Double? =
    if (tenths == Int.MIN_VALUE) null else (tenths / 10.0).takeIf { it in -40.0..100.0 }

/** A short name for a chip-temperature sensor, from its type name ("pressure_temp" becomes "Barometer chip"). */
internal fun chipLabel(info: SensorInfo): String {
    val t = info.stringType?.lowercase().orEmpty()
    return when {
        "pressure" in t || "baro" in t -> "Barometer chip"
        "gyro" in t -> "Gyroscope chip"
        "accel" in t -> "Accelerometer chip"
        "mag" in t -> "Compass chip"
        else -> info.name
    }
}
