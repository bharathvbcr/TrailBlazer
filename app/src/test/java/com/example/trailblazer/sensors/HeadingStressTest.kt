package com.example.trailblazer.sensors

import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.math.mod360
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * Adversarial stress testing for Heading, leveling logic, angle unwrapping, and boundary conditions.
 */
class HeadingStressTest {

    @Test
    fun adversarialInputsNeverEvaluateToLevel() {
        val nonFiniteCases = listOf(
            Double.NaN to 0.0,
            0.0 to Double.NaN,
            Double.NaN to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE to 0.0,
            0.0 to -Double.MAX_VALUE,
        )

        for ((p, r) in nonFiniteCases) {
            val h = Heading(
                magneticDeg = 0.0,
                declinationDeg = 0.0,
                source = HeadingSource.RotationVector,
                accuracyDeg = 1.0,
                upright = false,
                pitchDeg = p,
                rollDeg = r,
            )
            assertFalse("Non-finite or extreme pitch ($p) / roll ($r) must never report level", h.isLevel)
        }
    }

    @Test
    fun subnormalAndZeroInputsHandledGracefully() {
        val zero = Heading(
            magneticDeg = 0.0,
            declinationDeg = 0.0,
            source = HeadingSource.RotationVector,
            accuracyDeg = 0.0,
            upright = false,
            pitchDeg = 0.0,
            rollDeg = 0.0,
        )
        assertTrue(zero.isLevel)
        assertEquals(0.0, zero.tiltDeg, 1e-12)

        val tiny = Heading(
            magneticDeg = 0.0,
            declinationDeg = 0.0,
            source = HeadingSource.RotationVector,
            accuracyDeg = 0.0,
            upright = false,
            pitchDeg = 1e-15,
            rollDeg = -1e-15,
        )
        assertTrue(tiny.isLevel)
        assertTrue(tiny.tiltDeg < 1e-14)
    }

    @Test
    fun highVolumeFuzzingLevelVsTilt() {
        val rng = Random(42)
        repeat(10_000) {
            val pitch = rng.nextDouble(-90.0, 90.0)
            val roll = rng.nextDouble(-180.0, 180.0)
            val upright = rng.nextBoolean()
            val h = Heading(
                magneticDeg = rng.nextDouble(0.0, 360.0),
                declinationDeg = rng.nextDouble(-30.0, 30.0),
                source = HeadingSource.RotationVector,
                accuracyDeg = rng.nextDouble(0.1, 50.0),
                upright = upright,
                pitchDeg = pitch,
                rollDeg = roll,
            )

            val expectedLevel = !upright && abs(pitch) <= 2.0 && abs(roll) <= 2.0
            assertEquals(expectedLevel, h.isLevel)
            assertTrue("tiltDeg must be non-negative", h.tiltDeg >= 0.0)
            assertTrue("tiltDeg must be >= max component", h.tiltDeg >= abs(pitch) - 1e-9)
            assertTrue("tiltDeg must be >= max component", h.tiltDeg >= abs(roll) - 1e-9)
        }
    }

    @Test
    fun angleDiffContinuousUnwrappingAcrossNorth() {
        // Stress test 100,000 jitter steps around 0° / 360° to prove unwrapped angles never flip 360°
        var unwrapped = 0.0
        val rng = Random(12345)
        repeat(100_000) {
            // Rapid jitter across the 0° seam: [358°, 360) U [0, 2°]
            val jitter = if (rng.nextBoolean()) rng.nextDouble(358.0, 360.0) else rng.nextDouble(0.0, 2.0)
            val diff = angleDiff(unwrapped, jitter)
            assertTrue("diff must always be in (-180, 180]", diff > -180.0 && diff <= 180.0)
            assertTrue("step across north must be small, not full circle", abs(diff) <= 4.1)
            unwrapped += diff
            assertEquals(mod360(jitter), mod360(unwrapped), 1e-6)
        }
    }

    @Test
    fun concurrentAccessThreadSafety() {
        val executor = Executors.newFixedThreadPool(8)
        val h = Heading(
            magneticDeg = 180.0,
            declinationDeg = -12.5,
            source = HeadingSource.RotationVector,
            accuracyDeg = 2.5,
            upright = false,
            pitchDeg = 0.8,
            rollDeg = -1.1,
        )

        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(50_000) {
            executor.execute {
                try {
                    if (!h.isLevel) errors.incrementAndGet()
                    if (h.trueDeg != 167.5) errors.incrementAndGet()
                    if (h.tiltDeg <= 0.0) errors.incrementAndGet()
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals("Zero concurrency errors expected on immutable Heading", 0, errors.get())
    }

    @Test
    fun highVolumeCalibratedOffsetFuzzing() {
        val rng = Random(999)
        repeat(10_000) {
            val rawPitch = rng.nextDouble(-90.0, 90.0)
            val rawRoll = rng.nextDouble(-180.0, 180.0)
            val pitchOffset = rng.nextDouble(-10.0, 10.0)
            val rollOffset = rng.nextDouble(-10.0, 10.0)
            val upright = rng.nextBoolean()

            val h = Heading(
                magneticDeg = rng.nextDouble(0.0, 360.0),
                declinationDeg = null,
                source = HeadingSource.RotationVector,
                accuracyDeg = 1.0,
                upright = upright,
                pitchDeg = rawPitch,
                rollDeg = rawRoll,
                pitchOffsetDeg = pitchOffset,
                rollOffsetDeg = rollOffset,
            )

            val calP = rawPitch - pitchOffset
            val calR = rawRoll - rollOffset
            assertEquals(calP, h.calibratedPitchDeg, 1e-9)
            assertEquals(calR, h.calibratedRollDeg, 1e-9)

            val expectedLevel = !upright && abs(calP) <= 2.0 && abs(calR) <= 2.0
            assertEquals(expectedLevel, h.isLevel)
            assertEquals(pitchOffset != 0.0 || rollOffset != 0.0, h.isCalibrated)
            assertTrue("tiltDeg must be >= 0", h.tiltDeg >= 0.0)
            assertTrue("rawTiltDeg must be >= 0", h.rawTiltDeg >= 0.0)
        }
    }

    @Test
    fun adversarialOffsetsNeverCorruptLevelOrCrash() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE)) {
            val h = Heading(
                magneticDeg = 0.0,
                declinationDeg = null,
                source = HeadingSource.RotationVector,
                accuracyDeg = null,
                upright = false,
                pitchDeg = 0.0,
                rollDeg = 0.0,
                pitchOffsetDeg = bad,
                rollOffsetDeg = 0.0,
            )
            assertFalse("Non-finite offset must never report level", h.isLevel)
        }
    }
}
