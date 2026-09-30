package com.example.trailblazer.sensors

import android.hardware.Sensor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.motion.Vec3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SensorManager maths (Robolectric), fed hostile rotation-vector events. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OrientationRobustnessTest {
    @Test
    fun nonFiniteRotationVectorsNeverReachTheCompassAndDoNotPoisonIt() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_ROTATION_VECTOR))
        val repo = OrientationRepository(src, Clock { 1_000L }) { 0 }
        val seen = ArrayList<Reading<Orientation>>()
        val job = launch { repo.orientation(Hold.Flat).collect { seen += it } }
        while (src.listenerCount(Sensor.TYPE_ROTATION_VECTOR) < 1) runCurrent()
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0.3826834f, 0.9238795f) // 45° about the vertical axis
        runCurrent()
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            src.emit(Sensor.TYPE_ROTATION_VECTOR, bad, bad, bad, bad)
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, bad, 0.9f)
            runCurrent()
        }
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0.3826834f, 0.9238795f)
        runCurrent()
        job.cancel()
        val values = seen.filterIsInstance<Reading.Value<Orientation>>()
        assertTrue("expected the two good samples, got $seen", values.size >= 2)
        for (v in values) {
            val o = v.value
            assertTrue("non-finite reading leaked: $o", o.azimuthDeg.isFinite() && o.pitchDeg.isFinite() && o.rollDeg.isFinite())
            assertTrue(o.azimuthDeg >= 0.0 && o.azimuthDeg < 360.0)
        }
    }

    @Test
    fun nonFiniteStepCountIsNotReadAsACounterReset() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_STEP_COUNTER))
        val repo = StepRepository(src, backgroundScope, Clock { 1_000L }, kotlinx.coroutines.flow.flowOf(true))
        val seen = ArrayList<Reading<Long>>()
        val job = launch { repo.stepsSinceBoot.collect { seen += it } }
        runCurrent()
        src.emit(Sensor.TYPE_STEP_COUNTER, 5_000f)
        runCurrent()
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -3f)) {
            src.emit(Sensor.TYPE_STEP_COUNTER, bad)
            runCurrent()
        }
        job.cancel()
        val values = seen.filterIsInstance<Reading.Value<Long>>().map { it.value }
        assertTrue("expected only the real count, got $values", values.isNotEmpty() && values.all { it == 5_000L })
    }

    /** One NaN accelerometer sample used to poison the low-passed gravity fallback forever (g = g + 0.1·(NaN − g)). */
    @Test
    fun nonFiniteMotionSamplesAreDroppedAndCannotPoisonTheGravityFallback() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_ACCELEROMETER)) // no TYPE_GRAVITY: the low-pass path
        val repo = MotionRepository(src, backgroundScope, Clock { 1_000L })
        val gravity = ArrayList<Reading<Vec3>>()
        val accel = ArrayList<Reading<Vec3>>()
        val j1 = launch { repo.gravity.collect { gravity += it } }
        val j2 = launch { repo.acceleration.collect { accel += it } }
        while (src.listenerCount(Sensor.TYPE_ACCELEROMETER) < 2) runCurrent()
        src.emit(Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.81f)
        runCurrent()
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            src.emit(Sensor.TYPE_ACCELEROMETER, 0f, bad, 9.81f)
            runCurrent()
        }
        repeat(3) { src.emit(Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.81f); runCurrent() }
        j1.cancel()
        j2.cancel()
        for (list in listOf(gravity, accel)) {
            val values = list.filterIsInstance<Reading.Value<Vec3>>().map { it.value }
            assertTrue("expected readings, got $list", values.isNotEmpty())
            for (v in values) assertTrue("non-finite vector leaked: $v", v.x.isFinite() && v.y.isFinite() && v.z.isFinite())
        }
    }

    @Test
    fun autoHoldHysteresisPreventsChatterAtSixtyDegrees() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_ROTATION_VECTOR))
        val repo = OrientationRepository(src, Clock { 1_000L }) { 0 }
        val seen = ArrayList<Orientation>()
        val job = launch {
            repo.orientation(Hold.Auto).collect {
                if (it is Reading.Value) seen += it.value
            }
        }
        while (src.listenerCount(Sensor.TYPE_ROTATION_VECTOR) < 1) runCurrent()

        // Start flat: tilt = 10° (cos = 0.985)
        // Rotation vector quaternion for pitch = 10° around X axis:
        // sin(5°) = 0.0871557, cos(5°) = 0.9961947
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.0871557f, 0f, 0f, 0.9961947f)
        runCurrent()
        assertTrue("initially flat", seen.isNotEmpty() && !seen.last().upright)

        // Jitter around 60° tilt: 58° (cos = 0.530) <-> 62° (cos = 0.469)
        // Both are inside the [55°, 65°] hysteresis deadband!
        // So posture must STAY flat without chattering!
        // 58° pitch: sin(29°) = 0.4848, cos(29°) = 0.8746
        // 62° pitch: sin(31°) = 0.5150, cos(31°) = 0.8572
        repeat(100) {
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.4848f, 0f, 0f, 0.8746f)
            runCurrent()
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.5150f, 0f, 0f, 0.8572f)
            runCurrent()
        }
        val intermediate = seen.map { it.upright }
        assertTrue("all samples in deadband remain flat", intermediate.all { !it })

        // Now tilt past 65°: 70° pitch (sin(35°) = 0.5736, cos(35°) = 0.8192)
        // This triggers transition to upright!
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.5736f, 0f, 0f, 0.8192f)
        runCurrent()
        assertTrue("tilting past 65° switches to upright", seen.last().upright)

        // Now jitter again around 60° (58° <-> 62°): must STAY upright due to hysteresis!
        repeat(100) {
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.5150f, 0f, 0f, 0.8572f)
            runCurrent()
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.4848f, 0f, 0f, 0.8746f)
            runCurrent()
        }
        val postUpright = seen.takeLast(200).map { it.upright }
        assertTrue("samples remain upright until tilt drops below 55°", postUpright.all { it })

        // Drop below 55°: 45° pitch (sin(22.5°) = 0.3827, cos(22.5°) = 0.9239)
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.3827f, 0f, 0f, 0.9239f)
        runCurrent()
        assertTrue("dropping below 55° switches back to flat", !seen.last().upright)

        job.cancel()
    }

    @Test
    fun tiltDegradationBroadensAccuracyBeam() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_ROTATION_VECTOR))
        val repo = OrientationRepository(src, Clock { 1_000L }) { 0 }
        val seen = ArrayList<Orientation>()
        val job = launch {
            repo.orientation(Hold.Flat).collect {
                if (it is Reading.Value) seen += it.value
            }
        }
        try {
            while (src.listenerCount(Sensor.TYPE_ROTATION_VECTOR) < 1) runCurrent()

            // 1. Flat holding: yaw 0°, pitch 0°, accuracy = 2.0° (0.0349 rad)
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0f, 1f, 0.0349f)
            runCurrent()
            println("DEBUG: seen after sample 1: $seen")
            val flatAcc = seen.last().headingAccuracyDeg ?: 0.0
            assertTrue("flat accuracy should be sharp (~2°)", flatAcc in 1.8..2.2)

            // 2. Tilted 20° pitch (sin(10°) = 0.1736, cos(10°) = 0.9848) with same reported sensor accuracy
            src.emit(Sensor.TYPE_ROTATION_VECTOR, 0.1736f, 0f, 0f, 0.9848f, 0.0349f)
            runCurrent()
            println("DEBUG: seen after sample 2: $seen")
            val tiltedAcc = seen.last().headingAccuracyDeg ?: 0.0
            assertTrue("tilt degrades magnetic certainty and broadens beam ($tiltedAcc > $flatAcc)", tiltedAcc > flatAcc)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun degenerateZeroNormVectorsAreDropped() = runTest {
        val src = FakeSensorSource(setOf(Sensor.TYPE_ROTATION_VECTOR))
        val repo = OrientationRepository(src, Clock { 1_000L }) { 0 }
        val seen = ArrayList<Reading<Orientation>>()
        val job = launch { repo.orientation(Hold.Flat).collect { seen += it } }
        while (src.listenerCount(Sensor.TYPE_ROTATION_VECTOR) < 1) runCurrent()

        // All-zero vector is degenerate and must be dropped
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0f, 0f)
        runCurrent()

        // Valid sample
        src.emit(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0.3826834f, 0.9238795f)
        runCurrent()

        job.cancel()
        val values = seen.filterIsInstance<Reading.Value<Orientation>>()
        assertTrue("only valid sample passed", values.size == 1)
    }
}
