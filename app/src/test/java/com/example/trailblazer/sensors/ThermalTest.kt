package com.example.trailblazer.sensors

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the sensor list a Pixel 10 Pro XL reports (`adb shell dumpsys sensorservice`). */
class ThermalTest {
    private fun info(type: Int, name: String, stringType: String?) = SensorInfo(type, name, "v", 1, 150f, 0.01f, 0.1f, 10_000, false, stringType)

    private val gyroTemp = info(65538, "ICM45631 Temperature", "com.google.sensor.gyro_temperature")
    private val baroTemp = info(65539, "SPL07003 Temperature", "com.google.sensor.pressure_temp")
    private val fir = info(131089, "MLX90632 FIR Temperature", "com.google.sensor.fir_temperature")
    private val firExt = info(131090, "MLX90632 FIR Extended Temperature", "com.google.sensor.fir_extended_temperature")
    private val ambient = info(Sensor.TYPE_AMBIENT_TEMPERATURE, "Ambient", "android.sensor.ambient_temperature")
    private val pressure = info(Sensor.TYPE_PRESSURE, "SPL07003 Pressure", "android.sensor.pressure")

    @Test
    fun onlyReadableChipTemperaturesAreOffered() {
        assertTrue(isChipTemperature(gyroTemp))
        assertTrue(isChipTemperature(baroTemp))
        assertFalse("infrared thermometer: permission no app can hold", isChipTemperature(fir))
        assertFalse(isChipTemperature(firExt))
        assertFalse("ambient is air temperature, handled elsewhere", isChipTemperature(ambient))
        assertFalse(isChipTemperature(pressure))
        assertFalse("no type name, no guess", isChipTemperature(info(70000, "Temperature", null)))
    }

    @Test
    fun labelsSayWhichChip() {
        assertEquals("Barometer chip", chipLabel(baroTemp))
        assertEquals("Gyroscope chip", chipLabel(gyroTemp))
        assertEquals("Mystery", chipLabel(info(70001, "Mystery", "vendor.odd_temperature")))
    }

    @Test
    fun impossibleReadingsAreNotTemperatures() {
        assertEquals(33.7, batteryCelsius(337)!!, 1e-9)
        assertNull("extra missing", batteryCelsius(Int.MIN_VALUE))
        assertNull(batteryCelsius(-18342)) // a real thermal-zone value on this phone that is not a temperature
        assertNull(batteryCelsius(5000))
        assertEquals(41.5, chipCelsius(41.5f)!!, 1e-6)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -273f, 400f, null)) assertNull("$bad", chipCelsius(bad))
    }
}
