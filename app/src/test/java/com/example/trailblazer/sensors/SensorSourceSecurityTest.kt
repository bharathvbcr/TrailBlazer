package com.example.trailblazer.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

/**
 * Android 12+ throws a SecurityException from registerListener for a rate that needs HIGH_SAMPLING_RATE_SENSORS. Every
 * path built on the registration has to turn that into "no sensor", never into a crash of the collecting coroutine.
 */
@RunWith(AndroidJUnit4::class)
class SensorSourceSecurityTest {
    private fun source(): AndroidSensorSource {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(SensorManager::class.java)
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowOf(manager).addSensor(accelerometer)
        return AndroidSensorSource(manager) { _, _, _, _, _ ->
            throw SecurityException("Permission Denial: sampling rate needs HIGH_SAMPLING_RATE_SENSORS")
        }
    }

    @Test
    fun aRefusedRegistrationReturnsNullInsteadOfThrowing() {
        assertNull(source().register(Sensor.TYPE_ACCELEROMETER, 1_000, 0, null) { })
    }

    @Test
    fun theFlowsCompleteWithoutAnEmissionInsteadOfThrowingToTheCollector() = runBlocking {
        val src = source()
        withTimeout(10_000) {
            assertEquals(emptyList<SensorSample>(), src.samples(Sensor.TYPE_ACCELEROMETER, 1_000).toList())
            assertEquals(emptyList<SensorSample>(), src.batchedSamples(Sensor.TYPE_ACCELEROMETER, 1_000, 1_000_000).toList())
        }
    }
}
