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
        bleSensors = BleSensorManager(transport, scope, clock)
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
        transport.emitConnected(setOf(BleServiceType.Environmental, BleServiceType.Pressure))

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

    @Test
    fun anEnvironmentalPeripheralWithoutPressureKeepsThePhoneBarometer() {
        val repo = BarometerRepository(fakeInternalSource, scope, clock, bleSensors)
        val seen = mutableListOf<Reading<Double>>()
        val job = scope.launch { repo.pressureHpa.collect { seen.add(it) } }

        // A temperature/humidity-only sensor: ESS is present, the Pressure characteristic is not.
        bleSensors.connect("11:22:33:44:55:66", "Thermo Hygro")
        transport.emitConnected(setOf(BleServiceType.Environmental, BleServiceType.Temperature, BleServiceType.Humidity))

        fakeInternalSource.emit(Sensor.TYPE_PRESSURE, 1007.5f, timestampNs = 1)
        val value = seen.filterIsInstance<Reading.Value<Double>>().lastOrNull()
        assertTrue("phone barometer must keep reporting", value != null)
        assertEquals(1007.5, value!!.value, 0.01)
        assertTrue("never parked on an Acquiring that no sample can resolve", seen.last() is Reading.Value<*>)
        job.cancel()
    }

    @Test
    fun blePeripheralTemperatureAndHumidityReplaceThePhoneSensorsOnlyForTheirOwnChannel() {
        val fakeSource = FakeSensorSource(setOf(Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY))
        val env = EnvironmentRepository(fakeSource, scope, clock, bleSensors)
        val temps = mutableListOf<Reading<Double>>()
        val hums = mutableListOf<Reading<Double>>()
        val jobs = listOf(
            scope.launch { env.temperatureC.collect { temps.add(it) } },
            scope.launch { env.humidityPct.collect { hums.add(it) } },
        )

        fakeSource.emit(Sensor.TYPE_AMBIENT_TEMPERATURE, 19.0f, timestampNs = 1)
        fakeSource.emit(Sensor.TYPE_RELATIVE_HUMIDITY, 60.0f, timestampNs = 1)
        assertEquals(19.0, (temps.last() as Reading.Value<Double>).value, 0.01)

        // The peripheral offers temperature only.
        bleSensors.connect("11:22:33:44:55:66", "Thermo")
        transport.emitConnected(setOf(BleServiceType.Environmental, BleServiceType.Temperature))
        transport.emitTemperature(4.5)
        transport.emitHumidity(99.0) // not a subscribed channel: must not surface
        assertEquals(4.5, (temps.last() as Reading.Value<Double>).value, 0.01)
        fakeSource.emit(Sensor.TYPE_RELATIVE_HUMIDITY, 61.0f, timestampNs = 2)
        assertEquals("humidity stays on the phone sensor", 61.0, (hums.last() as Reading.Value<Double>).value, 0.01)

        bleSensors.disconnect()
        fakeSource.emit(Sensor.TYPE_AMBIENT_TEMPERATURE, 20.0f, timestampNs = 3)
        assertEquals("falls back to the phone", 20.0, (temps.last() as Reading.Value<Double>).value, 0.01)
        jobs.forEach { it.cancel() }
    }
}
