package com.trailblazer.core.sensors.log

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SensorCsvLocaleTest {
    @Test
    fun numbersAreIdenticalUnderCommaDecimalLocales() {
        val anchor = ClockAnchor(1_700_000_000_000L, 0L)
        val event = SensorEventRow(1_500_000L, LogSensor.Accelerometer, floatArrayOf(1.5f, -9.81f, 0.001f), 3)
        val reference = SensorCsv.rowLine(event, anchor)
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
                Locale.setDefault(locale)
                assertEquals(reference, SensorCsv.rowLine(event, anchor), "row under $locale")
                assertEquals("9.81", SensorCsv.number(9.81f))
            }
        } finally {
            Locale.setDefault(saved)
        }
        assertFalse(reference.contains("1,5"), "no decimal comma")
        assertEquals("2023-11-14T22:13:20.001500Z,1500000,ACCELEROMETER,1.5,-9.81,0.001,3", reference.substringBefore(",,"))
    }
}
