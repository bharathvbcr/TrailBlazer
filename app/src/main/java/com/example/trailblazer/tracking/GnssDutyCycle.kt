package com.example.trailblazer.tracking

import android.hardware.Sensor
import com.example.trailblazer.data.TrackingMode
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Reading
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
class MotionGate(
    private val stillForNs: Long = STILL_FOR_NS,
    private val thresholdMps2: Double = 0.5,
    private val maxSampleGapNs: Long = 5_000_000_000L,
) {
    var stationary = false
        private set
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var hasGravity = false
    private var lastNs: Long? = null
    private var stillSinceNs: Long? = null

    /** Feeds one sample and returns whether the phone is now stationary. Non-finite samples are ignored. */
    fun onSample(s: SensorSample): Boolean {
        if (s.values.size < 3) return stationary
        val x = s.values[0].toDouble()
        val y = s.values[1].toDouble()
        val z = s.values[2].toDouble()
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return stationary
        val t = s.timestampNs
        val prev = lastNs
        lastNs = t
        val gap = prev == null || t <= prev || t - prev > maxSampleGapNs
        if (!hasGravity) {
            gx = x; gy = y; gz = z; hasGravity = true
        }
        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        val moved = sqrt(dx * dx + dy * dy + dz * dz) > thresholdMps2
        gx += GRAVITY_ALPHA * dx; gy += GRAVITY_ALPHA * dy; gz += GRAVITY_ALPHA * dz
        when {
            moved -> { stationary = false; stillSinceNs = t }
            stationary -> Unit
            gap -> stillSinceNs = t
            else -> {
                val since = stillSinceNs ?: t.also { stillSinceNs = it }
                if (t - since >= stillForNs) stationary = true
            }
        }
        return stationary
    }

    /** Something outside the samples says the phone moved: a significant-motion trigger, or a fix that moved. */
    fun onWake() {
        stationary = false
        stillSinceNs = null
    }

    companion object {
        const val STILL_FOR_NS = 5 * 60 * 1_000_000_000L
        private const val GRAVITY_ALPHA = 0.1
    }
}

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
