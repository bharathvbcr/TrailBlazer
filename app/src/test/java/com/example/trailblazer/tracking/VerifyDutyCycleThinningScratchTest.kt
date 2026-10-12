package com.example.trailblazer.tracking

import android.hardware.Sensor
import com.example.trailblazer.data.TrackingMode
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.FakeSensorSource
import com.trailblazer.core.sensors.Reading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifier scratch test for 10f6587: the new 400 ms thinning in GnssDutyCycle is unconditional. On a phone whose
 * accelerometer delivers faster than 2.5 Hz (here 200 Hz), a 150 ms knock that falls between two accepted samples is
 * never seen by the gate, so it no longer restarts the five-minute stillness count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyDutyCycleThinningScratchTest {
    private val accel = Sensor.TYPE_ACCELEROMETER
    private val sensors = FakeSensorSource(setOf(accel, Sensor.TYPE_SIGNIFICANT_MOTION))
    private val requests = ArrayList<Long>()
    private val live: (Long) -> Flow<Reading<Fix>> = { interval ->
        flow<Reading<Fix>> { emit(Reading.Acquiring); awaitCancellation() }
            .onStart { requests += interval }
            .onCompletion { requests -= interval }
    }

    @Test
    fun aKnockBetweenAcceptedSamplesStillRestartsTheStillnessCount() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            GnssDutyCycle(sensors).readings(MutableStateFlow(TrackingMode.Balanced), live).collect { }
        }
        runCurrent()
        var nowNs = 1_000_000_000_000L
        val stepNs = 5_000_000L // 200 Hz; the filter accepts every 80th sample (400 ms)
        val knockAt = 290 * 200 // 4 min 50 s in
        for (i in 0 until 310 * 200) { // 5 min 10 s
            nowNs += stepNs
            val k = i - knockAt
            // 150 ms knock (samples 10..39 after an accepted one): 3 m/s² on x, well above the 0.5 m/s² threshold.
            val x = if (k in 10 until 40) 3f else 0.02f
            sensors.emit(accel, x, 0f, 9.81f, timestampNs = nowNs)
            if (i % 100 == 0) runCurrent()
        }
        runCurrent()
        assertEquals("20 s after a knock the phone has not been still for 5 minutes", listOf(15_000L), requests)
    }
}
