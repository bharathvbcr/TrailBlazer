package com.trailblazer.core.motion

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionGateTest {
    @Test
    fun gravityLowPassTracksSteadyGravity() {
        val filter = GravityLowPass(0.1)
        var g = filter.update(0.0, 0.0, 9.81)
        assertEquals(9.81, g.z, 1e-9)

        repeat(100) {
            g = filter.update(0.0, 0.0, 9.81)
        }
        assertEquals(0.0, g.x, 1e-9)
        assertEquals(0.0, g.y, 1e-9)
        assertEquals(9.81, g.z, 1e-9)
    }

    @Test
    fun gateDetectsStillnessAndMotion() {
        val gate = MotionGate(stillForNs = 10_000_000_000L)
        var t = 0L
        repeat(30) {
            t += 500_000_000L
            gate.onSample(t, floatArrayOf(0f, 0f, 9.81f))
        }
        assertTrue(gate.stationary)

        // Motion breaks stillness
        t += 500_000_000L
        assertFalse(gate.onSample(t, floatArrayOf(2.0f, 0f, 9.81f)))
        assertFalse(gate.stationary)
    }

    @Test
    fun gateIgnoresBrokenSamples() {
        val gate = MotionGate(stillForNs = 10_000_000_000L)
        var t = 0L
        repeat(30) {
            t += 500_000_000L
            gate.onSample(t, floatArrayOf(0f, 0f, 9.81f))
        }
        assertTrue(gate.stationary)

        t += 500_000_000L
        assertTrue(gate.onSample(t, floatArrayOf(Float.NaN, 0f, 9.81f)), "NaN is a driver glitch, not motion")
        assertTrue(gate.onSample(t, floatArrayOf(1f)), "too few axes")

        t -= 2_000_000_000L
        assertTrue(gate.onSample(t, floatArrayOf(0f, 0f, 9.81f)), "a timestamp going backwards is not motion")

        t += 4_000_000_000L
        assertFalse(gate.onSample(t, floatArrayOf(0f, 9.81f, 0f)), "turning the phone over is")
    }

    @Test
    fun gravityTrackingFollowsTimeNotSampleCount() {
        // A walker's 1 Hz, 1.2 m/s² sway sampled at 200 Hz: gravity tracking tuned for 2 Hz samples must not speed up
        // a hundredfold and follow the sway, which would read a walking user as still.
        val gate = MotionGate(stillForNs = 10_000_000_000L)
        var t = 0L
        repeat(60 * 200) { i ->
            t += 5_000_000L
            val sway = 1.2 * sin(2 * PI * i * 0.005)
            gate.onSample(t, 0.0, sway, 9.81)
            assertFalse(gate.stationary, "a minute of swaying is never stillness (sample $i)")
        }
    }

    @Test
    fun aRestSampledFastIsStillAfterTheSameTime() {
        val gate = MotionGate(stillForNs = 10_000_000_000L)
        var t = 0L
        repeat(9 * 200) { t += 5_000_000L; gate.onSample(t, 0.02, -0.02, 9.81) }
        assertFalse(gate.stationary)
        repeat(2 * 200) { t += 5_000_000L; gate.onSample(t, 0.02, -0.02, 9.81) }
        assertTrue(gate.stationary)
    }

    @Test
    fun wakeResetsStationary() {
        val gate = MotionGate(stillForNs = 1_000_000_000L)
        gate.onSample(0L, floatArrayOf(0f, 0f, 9.81f))
        gate.onSample(2_000_000_000L, floatArrayOf(0f, 0f, 9.81f))
        assertTrue(gate.stationary)

        gate.onWake()
        assertFalse(gate.stationary)
    }
}
