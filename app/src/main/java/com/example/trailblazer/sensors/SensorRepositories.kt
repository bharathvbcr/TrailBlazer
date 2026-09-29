package com.example.trailblazer.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import com.trailblazer.core.math.MedianFilter
import com.trailblazer.core.motion.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.sqrt

/** Wall-clock source, injectable for tests. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Builds a Reading flow for one sensor type: [Reading.Unavailable] when the hardware is missing,
 * [Reading.Acquiring] until the first sample, then values. [convert] may return null to drop a sample
 * (e.g. an out-of-range value), which never becomes a number on screen.
 */
internal fun <T> SensorSource.reading(
    type: Int,
    periodUs: Int,
    clock: Clock,
    convert: (SensorSample) -> T?,
): Flow<Reading<T>> {
    if (!has(type)) return flowOf(Reading.Unavailable(UnavailableReason.NoHardware))
    return flow {
        emit(Reading.Acquiring)
        samples(type, periodUs).collect { s ->
            val v = convert(s) ?: return@collect
            emit(Reading.Value(v, accuracyOf(s.accuracy), clock.nowMs()))
        }
    }
}

/** Station pressure in hPa, median-of-5 filtered to reject spikes (door slams, car ventilation). */
class BarometerRepository(source: SensorSource, scope: CoroutineScope, clock: Clock) {
    val pressureHpa: StateFlow<Reading<Double>> = flow {
        val median = MedianFilter(5)
        emitAll(
            source.reading(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL, clock) { s ->
                s.values.firstOrNull()?.toDouble()?.takeIf { it.isFinite() && it in 300.0..1100.0 }?.let { median.update(it) }
            },
        )
    }.shareReading(scope)
}

/** Magnetic field strength (µT) with Android's calibration accuracy, driving the calibration prompt. */
class MagneticRepository(source: SensorSource, scope: CoroutineScope, clock: Clock) {
    val fieldMicroTesla: StateFlow<Reading<Double>> =
        source.reading(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_UI, clock) { s ->
            if (s.values.size < 3) null else {
                val (x, y, z) = Triple(s.values[0].toDouble(), s.values[1].toDouble(), s.values[2].toDouble())
                sqrt(x * x + y * y + z * z).takeIf { it.isFinite() }
            }
        }.shareReading(scope)
}

/** Ambient sensors that only some phones have; each is independently Unavailable when absent. */
class EnvironmentRepository(private val source: SensorSource, private val scope: CoroutineScope, private val clock: Clock) {
    private fun single(type: Int, range: ClosedFloatingPointRange<Double>) =
        source.reading(type, SensorManager.SENSOR_DELAY_NORMAL, clock) { s ->
            s.values.firstOrNull()?.toDouble()?.takeIf { it.isFinite() && it in range }
        }.shareReading(scope)

    val lightLux: StateFlow<Reading<Double>> = single(Sensor.TYPE_LIGHT, 0.0..200_000.0)
    val temperatureC: StateFlow<Reading<Double>> = single(Sensor.TYPE_AMBIENT_TEMPERATURE, -60.0..70.0)
    val humidityPct: StateFlow<Reading<Double>> = single(Sensor.TYPE_RELATIVE_HUMIDITY, 0.0..100.0)
}

/** Raw motion vectors for the level, vehicle tilt and the 3D plot. */
class MotionRepository(private val source: SensorSource, scope: CoroutineScope, private val clock: Clock) {
    private fun vec(type: Int, periodUs: Int) = source.reading(type, periodUs, clock) { s ->
        if (s.values.size < 3) null else Vec3(s.values[0].toDouble(), s.values[1].toDouble(), s.values[2].toDouble())
    }

    val acceleration: StateFlow<Reading<Vec3>> = vec(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME).shareReading(scope)
    val gyroscope: StateFlow<Reading<Vec3>> = vec(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME).shareReading(scope)

    /** Gravity: the fused TYPE_GRAVITY sensor when present, else a low-passed accelerometer. */
    val gravity: StateFlow<Reading<Vec3>> = if (source.has(Sensor.TYPE_GRAVITY)) {
        vec(Sensor.TYPE_GRAVITY, SensorManager.SENSOR_DELAY_UI).shareReading(scope)
    } else {
        flow {
            var g: Vec3? = null
            vec(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_UI).collect { r ->
                if (r is Reading.Value) {
                    val prev = g
                    val a = 0.1
                    val next = if (prev == null) r.value else Vec3(
                        prev.x + a * (r.value.x - prev.x), prev.y + a * (r.value.y - prev.y), prev.z + a * (r.value.z - prev.z),
                    )
                    g = next
                    emit(r.copy(value = next))
                } else emit(r)
            }
        }.shareReading(scope)
    }
}

/**
 * Step counter since boot (TYPE_STEP_COUNTER). The app shows a difference from a baseline it records,
 * never an absolute count. Needs ACTIVITY_RECOGNITION on API 29+, requested only when the user enables steps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepRepository(source: SensorSource, scope: CoroutineScope, clock: Clock, permitted: Flow<Boolean>) {
    val stepsSinceBoot: StateFlow<Reading<Long>> = permitted.flatMapLatest { ok ->
        if (!ok) flowOf(Reading.Unavailable(UnavailableReason.PermissionDenied))
        else source.reading(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL, clock) { s ->
            s.values.firstOrNull()?.toLong()?.takeIf { it >= 0 }
        }
    }.shareReading(scope)
}
