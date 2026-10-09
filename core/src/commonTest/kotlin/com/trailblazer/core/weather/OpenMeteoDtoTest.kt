package com.trailblazer.core.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenMeteoDtoTest {
    @Test
    fun sanitizedDropsImpossibleValues() {
        val raw = OmResponse(
            latitude = 999.0,
            longitude = -200.0,
            utcOffsetSeconds = 100_000,
            current = OmCurrent(
                temperatureC = 500.0,
                humidityPct = 150.0,
                weatherCode = 1234,
                windKmh = -10.0,
                windDirDeg = 725.0,
            ),
        )
        val clean = raw.sanitized()
        assertNull(clean.latitude)
        assertNull(clean.longitude)
        assertNull(clean.utcOffsetSeconds)

        val c = clean.current!!
        assertNull(c.temperatureC)
        assertNull(c.humidityPct)
        assertNull(c.weatherCode)
        assertNull(c.windKmh)
        assertEquals(5.0, c.windDirDeg) // 725 mod 360 = 5.0
    }

    @Test
    fun wmoCodeDescriptions() {
        assertEquals("Clear sky", WmoCode.describe(0))
        assertEquals("Rain", WmoCode.describe(63))
        assertEquals("Thunderstorm", WmoCode.describe(95))
        assertEquals("—", WmoCode.describe(null))
        assertEquals("Code 42", WmoCode.describe(42))
    }
}
