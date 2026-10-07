package com.example.trailblazer.tracking

import android.hardware.Sensor
import com.example.trailblazer.data.TrackingMode
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.FakeSensorSource
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sensors.SensorSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GnssDutyCycleTest {
    private val accel = Sensor.TYPE_ACCELEROMETER
    private val sigMotion = Sensor.TYPE_SIGNIFICANT_MOTION
    private val sensors = FakeSensorSource(setOf(accel, sigMotion))
    private var nowNs = 1_000_000_000_000L

    /** Intervals of the location requests currently registered: what the GNSS receiver is being asked for. */
    private val requests = ArrayList<Long>()
    private val live: (Long) -> Flow<Reading<Fix>> = { interval ->
        flow<Reading<Fix>> { emit(Reading.Acquiring); awaitCancellation() }
            .onStart { requests += interval }
            .onCompletion { requests -= interval }
    }

    private fun TestScope.still(seconds: Int, hz: Int = 2) = repeat(seconds * hz) { i ->
        nowNs += 1_000_000_000L / hz
        // A phone on a rock: gravity plus a few hundredths of sensor noise.
        val n = if (i % 2 == 0) 0.02f else -0.02f
        sensors.emit(accel, n, -n, 9.81f + n, timestampNs = nowNs)
        runCurrent()
    }

    private fun TestScope.walk(seconds: Int) = repeat(seconds * 2) { i ->
        nowNs += 500_000_000L
        sensors.emit(accel, if (i % 2 == 0) 1.8f else -1.2f, 0.4f, if (i % 2 == 0) 12.5f else 7.6f, timestampNs = nowNs)
        runCurrent()
    }

    private fun TestScope.collect(mode: MutableStateFlow<TrackingMode>, cycle: GnssDutyCycle = GnssDutyCycle(sensors)): MutableList<Reading<Fix>?> {
        val seen = ArrayList<Reading<Fix>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cycle.readings(mode, live).collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test
    fun gpsSleepsAfterFiveMinutesStillAndWakesOnSignificantMotion() = runTest {
        val seen = collect(MutableStateFlow(TrackingMode.Balanced))
        assertEquals("receiver on at the Balanced interval", listOf(15_000L), requests)
        assertEquals("the wake-up trigger is armed only while asleep", 0, sensors.listenerCount(sigMotion))

        still(299)
        assertEquals("4 min 59 s still is not enough", listOf(15_000L), requests)
        still(2)
        assertTrue("no location request while asleep", requests.isEmpty())
        assertNull("the service is told the receiver sleeps", seen.last())
        assertEquals(1, sensors.listenerCount(sigMotion))

        // The phone slept; nothing arrives from the accelerometer, then significant motion wakes it.
        assertTrue(sensors.trigger(sigMotion))
        runCurrent()
        assertEquals(listOf(15_000L), requests)
        assertEquals(Reading.Acquiring, seen.last())
        assertEquals(0, sensors.listenerCount(sigMotion))

        // Stillness is counted again from the wake-up, not from before it.
        still(200)
        assertEquals(listOf(15_000L), requests)
        still(101)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun motionSeenInTheSamplesWakesTheReceiverToo() = runTest {
        collect(MutableStateFlow(TrackingMode.Expedition))
        still(301)
        assertTrue(requests.isEmpty())
        walk(1)
        assertEquals(listOf(60_000L), requests)
    }

    @Test
    fun movingFixesKeepTheReceiverOnWhenTheAccelerometerFeelsNothing() = runTest {
        // A canoe drifting on flat water: no acceleration to speak of, but the position advances.
        val cycle = GnssDutyCycle(sensors)
        collect(MutableStateFlow(TrackingMode.Balanced), cycle)
        still(299)
        cycle.noteMovement()
        runCurrent()
        still(2)
        assertEquals(listOf(15_000L), requests)
        still(298)
        assertEquals("five minutes are counted from the last moving fix", listOf(15_000L), requests)
        still(2)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun walkingKeepsTheReceiverOn() = runTest {
        collect(MutableStateFlow(TrackingMode.ExpeditionLong))
        repeat(10) { still(60); walk(2) }
        assertEquals(listOf(300_000L), requests)
    }

    @Test
    fun unobservedTimeIsNeverCountedAsStillness() = runTest {
        collect(MutableStateFlow(TrackingMode.Balanced))
        still(180)
        nowNs += 60_000_000_000L // a minute with no samples: the phone may have moved
        still(180)
        assertEquals("only 3 of the last 6 minutes were observed", listOf(15_000L), requests)
        still(121)
        assertTrue(requests.isEmpty())
        // Once asleep, the expected gap while the phone sleeps does not wake it; motion after the gap does.
        nowNs += 3_600_000_000_000L
        still(5)
        assertTrue(requests.isEmpty())
        walk(1)
        assertEquals(listOf(15_000L), requests)
    }

    @Test
    fun continuousModeNeverSleeps() = runTest {
        collect(MutableStateFlow(TrackingMode.Continuous))
        still(900)
        assertEquals(listOf(1_000L), requests)
        assertEquals("no accelerometer listener in Continuous mode", 0, sensors.listenerCount(accel))
    }

    @Test
    fun withoutAWakeUpSensorTheReceiverStaysOn() = runTest {
        val noSigMotion = FakeSensorSource(setOf(accel))
        val cycle = GnssDutyCycle(noSigMotion)
        assertFalse(cycle.canSleep)
        assertFalse(GnssDutyCycle(FakeSensorSource(setOf(sigMotion))).canSleep)
        collect(MutableStateFlow(TrackingMode.ExpeditionLong), cycle)
        repeat(600 * 2) {
            nowNs += 500_000_000L
            noSigMotion.emit(accel, 0f, 0f, 9.81f, timestampNs = nowNs)
        }
        runCurrent()
        assertEquals(listOf(300_000L), requests)
    }

    @Test
    fun changingTheModeAppliesAtOnceEvenWhileAsleep() = runTest {
        val mode = MutableStateFlow(TrackingMode.Balanced)
        collect(mode)
        still(301)
        assertTrue(requests.isEmpty())
        mode.value = TrackingMode.Continuous
        runCurrent()
        assertEquals(listOf(1_000L), requests)
        mode.value = TrackingMode.ExpeditionLong
        runCurrent()
        assertEquals(listOf(300_000L), requests)
    }

    @Test
    fun gateIgnoresBrokenSamples() {
        val gate = MotionGate(stillForNs = 10_000_000_000L)
        var t = 0L
        fun s(vararg v: Float) = SensorSample(v, 3, t)
        repeat(30) { t += 500_000_000L; gate.onSample(s(0f, 0f, 9.81f)) }
        assertTrue(gate.stationary)
        t += 500_000_000L
        assertTrue("NaN is a driver glitch, not motion", gate.onSample(s(Float.NaN, 0f, 9.81f)))
        assertTrue("too few axes", gate.onSample(s(1f)))
        t -= 2_000_000_000L
        assertTrue("a timestamp going backwards is not motion", gate.onSample(s(0f, 0f, 9.81f)))
        t += 4_000_000_000L
        assertFalse("turning the phone over is", gate.onSample(s(0f, 9.81f, 0f)))
    }
}
