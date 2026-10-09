package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.sensors.shareReading

import android.hardware.Sensor
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorRepositoryTest {
    private val clock = Clock { 1_000L }

    @Test
    fun missingBarometerIsUnavailableNeverANumber() = runTest {
        val repo = BarometerRepository(FakeSensorSource(emptySet()), backgroundScope, clock)
        repo.pressureHpa.test {
            assertEquals(Reading.Acquiring, awaitItem())
            assertEquals(Reading.Unavailable(UnavailableReason.NoHardware), awaitItem())
        }
    }

    @Test
    fun acquiringThenMedianFilteredValuesWithAccuracy() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_PRESSURE))
        val repo = BarometerRepository(src, backgroundScope, clock)
        repo.pressureHpa.test {
            assertEquals(Reading.Acquiring, awaitItem())
            runCurrent()
            src.emit(Sensor.TYPE_PRESSURE, 1000f, accuracy = 1)
            val first = awaitItem() as Reading.Value
            assertEquals(1000.0, first.value, 1e-6)
            assertEquals(Accuracy.Low, first.accuracy)
            // A single spike is rejected by the median of 5.
            listOf(1000.2f, 1500f, 1000.1f, 1000.3f).forEach { src.emit(Sensor.TYPE_PRESSURE, it, accuracy = 3); runCurrent() }
            val values = cancelAndConsumeRemainingEvents().mapNotNull { (it as? app.cash.turbine.Event.Item)?.value as? Reading.Value }
            assertTrue(values.all { it.value < 1001.0 })
            assertEquals(Accuracy.High, values.last().accuracy)
        }
    }

    @Test
    fun outOfRangeSamplesAreDropped() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_PRESSURE))
        val repo = BarometerRepository(src, backgroundScope, clock)
        repo.pressureHpa.test {
            awaitItem()
            runCurrent()
            src.emit(Sensor.TYPE_PRESSURE, Float.NaN)
            src.emit(Sensor.TYPE_PRESSURE, 5f)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun listenerIsReleasedFiveSecondsAfterLastSubscriberAndValueReturnsStale() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_PRESSURE))
        val repo = BarometerRepository(src, backgroundScope, clock)
        val job = launch { repo.pressureHpa.collect {} }
        runCurrent()
        assertEquals(1, src.listenerCount(Sensor.TYPE_PRESSURE))
        src.emit(Sensor.TYPE_PRESSURE, 990f)
        runCurrent()
        job.cancel()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("still shared within the 5 s grace period", 1, src.listenerCount(Sensor.TYPE_PRESSURE))
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(0, src.listenerCount(Sensor.TYPE_PRESSURE))

        repo.pressureHpa.test {
            // StateFlow still holds the last value; on restart the flow re-emits it marked stale.
            val items = listOf(awaitItem(), awaitItem())
            val stale = items.filterIsInstance<Reading.Value<Double>>().last()
            assertTrue(stale.stale)
            assertEquals(990.0, stale.value, 1e-6)
            runCurrent()
            src.emit(Sensor.TYPE_PRESSURE, 991f)
            val fresh = awaitItem() as Reading.Value
            assertTrue(!fresh.stale)
        }
    }

    @Test
    fun floodOfEventsIsConflatedNotQueued() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_LIGHT))
        val repo = EnvironmentRepository(src, backgroundScope, clock)
        var received = 0
        val job = launch { repo.lightLux.collect { received++ } }
        runCurrent()
        repeat(10_000) { src.emit(Sensor.TYPE_LIGHT, it.toFloat()) }
        // runCurrent, not advanceUntilIdle: the latter stops without draining backgroundScope work.
        runCurrent()
        val last = repo.lightLux.first() as Reading.Value
        assertEquals(9_999.0, last.value, 0.0)
        assertTrue("received $received", received < 10_000)
        job.cancel()
    }

    @Test
    fun headingFallbackChain() {
        fun src(vararg t: Int) = OrientationRepository(FakeSensorSource(t.toSet()), clock) { 0 }.headingSource
        assertEquals(HeadingSource.RotationVector, src(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD))
        assertEquals(HeadingSource.AccelMag, src(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GAME_ROTATION_VECTOR))
        assertEquals(HeadingSource.GameRotation, src(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ACCELEROMETER))
        assertEquals(null, src(Sensor.TYPE_ACCELEROMETER))
    }

    @Test
    fun noCompassHardwareIsUnavailable() = runTest {
        val repo = OrientationRepository(FakeSensorSource(setOf(Sensor.TYPE_ACCELEROMETER)), clock) { 0 }
        assertEquals(Reading.Unavailable(UnavailableReason.NoHardware), repo.orientation(Hold.Flat).first())
    }

    @Test
    fun stepsNeedPermissionAndRestartWhenGranted() = runTest {
        val permitted = kotlinx.coroutines.flow.MutableStateFlow(false)
        val src = FakeSensorSource(setOf(Sensor.TYPE_STEP_COUNTER))
        val repo = StepRepository(src, backgroundScope, clock, permitted)
        repo.stepsSinceBoot.test {
            assertEquals(Reading.Acquiring, awaitItem())
            assertEquals(Reading.Unavailable(UnavailableReason.PermissionDenied), awaitItem())
            permitted.value = true
            assertEquals(Reading.Acquiring, awaitItem())
            runCurrent()
            src.emit(Sensor.TYPE_STEP_COUNTER, 1234f)
            assertEquals(1234L, (awaitItem() as Reading.Value).value)
        }
    }
}
