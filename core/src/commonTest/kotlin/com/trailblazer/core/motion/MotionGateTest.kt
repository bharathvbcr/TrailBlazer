package com.trailblazer.core.motion

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
    fun wakeResetsStationary() {
        val gate = MotionGate(stillForNs = 1_000_000_000L)
        gate.onSample(0L, floatArrayOf(0f, 0f, 9.81f))
        gate.onSample(2_000_000_000L, floatArrayOf(0f, 0f, 9.81f))
        assertTrue(gate.stationary)

        gate.onWake()
        assertFalse(gate.stationary)
    }
}
