package com.example.trailblazer.tracking

import android.hardware.Sensor
import com.example.trailblazer.data.TrackingMode
import com.example.trailblazer.location.Fix
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.sensors.SensorSample
import com.example.trailblazer.sensors.SensorSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlin.math.sqrt

/**
 * Decides from accelerometer samples whether the phone has lain still for [stillForNs]. Time is the samples' own
 * timestamps, so nothing depends on a timer that stops while the phone sleeps.
 *
 * Stillness is only credited for time it saw: a gap of more than [maxSampleGapNs] between samples (the phone slept
 * and the sensor hub's buffer overflowed, or a batch was dropped) restarts the count. Once still, a gap does not wake
 * it, because a gap is the expected state while the receiver sleeps; whatever wakes the phone (a significant-motion
 * trigger, [onWake]) or motion in the next samples does. A sample is motion when it differs from the slowly tracked
 * gravity vector by more than [thresholdMps2]: walking, lifting or turning the phone all do, a phone on a rock or in
 * a pack at rest does not.
 */
typealias MotionGate = com.trailblazer.core.motion.MotionGate

fun MotionGate.onSample(s: SensorSample): Boolean = onSample(s.timestampNs, s.values)

/**
 * Applies the [TrackingMode] to the GNSS receiver: location at the mode's interval, and in a motion-gated mode no
 * location request at all (the receiver sleeps) while [MotionGate] says the phone is still. The receiver only sleeps
 * when the phone can both see stillness (accelerometer) and be woken from deep sleep by moving again (significant
 * motion, a wake-up sensor); without either, a gated mode keeps the receiver on at its interval.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GnssDutyCycle(private val sensors: SensorSource, private val newGate: () -> MotionGate = { MotionGate() }) {
    val canSleep: Boolean get() = sensors.has(Sensor.TYPE_ACCELEROMETER) && sensors.has(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val fixMoved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * The receiver reported real movement (the recorder kept a fix). A canoe, a train or a smooth road can move the
     * phone with too little acceleration to see, so this restarts the stillness count as accelerometer motion would.
     */
    fun noteMovement() {
        fixMoved.tryEmit(Unit)
    }

    /**
     * Readings from [live] at the current mode's interval, or null while the receiver sleeps. A mode change applies at
     * once and restarts the stillness count.
     */
    fun readings(mode: Flow<TrackingMode>, live: (Long) -> Flow<Reading<Fix>>): Flow<Reading<Fix>?> =
        mode.distinctUntilChanged().flatMapLatest { m ->
            val stationary = if (m.motionGated && canSleep) stationary() else flowOf(false)
            stationary.onStart { emit(false) }.distinctUntilChanged().flatMapLatest { still ->
                if (still) flowOf(null) else live(m.intervalMs)
            }
        }

    /** Stillness from the accelerometer; the significant-motion trigger is armed only while still, to wake the phone. */
    private fun stationary(): Flow<Boolean> = flow {
        val gate = newGate()
        val still = MutableStateFlow(false)
        val wakes = still.flatMapLatest { if (it) sensors.triggers(Sensor.TYPE_SIGNIFICANT_MOTION) else emptyFlow() }
        val samples: Flow<SensorSample?> = sensors.batchedSamples(Sensor.TYPE_ACCELEROMETER, SAMPLE_PERIOD_US, MAX_REPORT_LATENCY_US)
        merge(samples, wakes.map { null }, fixMoved.map { null }).collect { s ->
            if (s == null) gate.onWake() else gate.onSample(s)
            still.value = gate.stationary
            emit(gate.stationary)
        }
    }

    private companion object {
        const val SAMPLE_PERIOD_US = 500_000
        const val MAX_REPORT_LATENCY_US = 10_000_000
    }
}
