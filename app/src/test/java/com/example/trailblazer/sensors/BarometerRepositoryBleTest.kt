package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.sensors.shareReading

import android.hardware.Sensor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarometerRepositoryBleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = Clock { 1700000000000L }
    private lateinit var transport: FakeBleTransport
    private lateinit var bleSensors: BleSensorManager
    private lateinit var fakeInternalSource: FakeSensorSource

    @Before
    fun setUp() {
        transport = FakeBleTransport()
        bleSensors = BleSensorManager(androidx.test.core.app.ApplicationProvider.getApplicationContext(), transport, scope, clock)
        fakeInternalSource = FakeSensorSource(setOf(Sensor.TYPE_PRESSURE))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun externalBleBarometerStreamsPressureAndFallsBackToInternalOnDisconnect() {
        val repo = BarometerRepository(fakeInternalSource, scope, clock, bleSensors)
        val seen = mutableListOf<Reading<Double>>()
        val job = scope.launch { repo.pressureHpa.collect { seen.add(it) } }

        // Initially internal sensor produces 1013.25 hPa
        fakeInternalSource.emit(Sensor.TYPE_PRESSURE, 1013.25f, timestampNs = 1)
        val firstValue = seen.filterIsInstance<Reading.Value<Double>>().lastOrNull()
        assertEquals(1013.25, firstValue!!.value, 0.01)

        // Connect external BLE environmental sensor
        bleSensors.connect("11:22:33:44:55:66", "Nordic Barometer")
        transport.emitConnected(setOf(BleServiceType.Environmental))

        // Emit external pressure: 850.0 hPa
        transport.emitPressure(850.0)
        val bleValue = seen.filterIsInstance<Reading.Value<Double>>().last()
        assertEquals(850.0, bleValue.value, 0.01)

        // Disconnect external sensor - verify graceful fallback to internal phone barometer!
        bleSensors.disconnect()
        fakeInternalSource.emit(Sensor.TYPE_PRESSURE, 1020.0f, timestampNs = 2)
        val fallbackValue = seen.filterIsInstance<Reading.Value<Double>>().last()
        assertEquals(1020.0, fallbackValue.value, 0.01)

        job.cancel()
    }
}
