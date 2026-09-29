package com.example.trailblazer

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSensorBridgeTest {

    private class TestContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun safeAreaInsets_initialValues_areZero() {
        val bridge = NativeSensorBridge(TestContext())
        assertEquals(0f, bridge.getSafeAreaTop(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaBottom(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaLeft(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaRight(), 0.001f)
    }

    @Test
    fun safeAreaInsets_updateValues_areReflected() {
        val bridge = NativeSensorBridge(TestContext())
        bridge.updateSafeAreaInsets(top = 48f, bottom = 24f, left = 0f, right = 0f)

        assertEquals(48f, bridge.getSafeAreaTop(), 0.001f)
        assertEquals(24f, bridge.getSafeAreaBottom(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaLeft(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaRight(), 0.001f)
    }

    @Test
    fun safeAreaInsets_adversarialInputs_sanitizedToZero() {
        val bridge = NativeSensorBridge(TestContext())
        bridge.updateSafeAreaInsets(
            top = Float.NaN,
            bottom = Float.NEGATIVE_INFINITY,
            left = -100f,
            right = Float.POSITIVE_INFINITY
        )

        assertEquals(0f, bridge.getSafeAreaTop(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaBottom(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaLeft(), 0.001f)
        assertEquals(0f, bridge.getSafeAreaRight(), 0.001f)
    }

    @Test
    fun sensorReadings_initialValues_areNaN() {
        val bridge = NativeSensorBridge(TestContext())
        assertTrue(bridge.getPressureHpa().isNaN())
        assertTrue(bridge.getMagneticFluxUt().isNaN())
        assertTrue(bridge.getLightLux().isNaN())
    }

    @Test
    fun vibrate_adversarialDurations_safelyHandledWithoutException() {
        val bridge = NativeSensorBridge(TestContext())
        // Negative duration
        bridge.vibrate(-1000L)
        // Zero duration
        bridge.vibrate(0L)
        // Excessive duration (should be clamped safely)
        bridge.vibrate(100_000_000L)
    }

    @Test
    fun lifecycle_startAndStop_safelyHandledWithoutException() {
        val bridge = NativeSensorBridge(TestContext())
        bridge.start()
        bridge.stop()
        // Ensure values reset to NaN on stop
        assertTrue(bridge.getPressureHpa().isNaN())
        assertTrue(bridge.getMagneticFluxUt().isNaN())
        assertTrue(bridge.getLightLux().isNaN())
    }
}
