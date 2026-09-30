package com.example.trailblazer.weather

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.TimeZone

/** Open-Meteo sends "2026-09-30" and "2026-09-29T21:00" in the place's own time; these became "09-30" and "30" on screen. */
@RunWith(AndroidJUnit4::class)
class ForecastTimesTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int = 0) =
        java.util.GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test
    fun hoursBecomeRealInstantsUsingThePlacesOffset() {
        // Denver, UTC−6 in September: 21:00 there is 03:00 UTC the next day.
        assertEquals(utc(2026, 9, 30, 3), ForecastTimes.epochMs("2026-09-29T21:00", -21_600))
        assertEquals(utc(2026, 9, 29, 19), ForecastTimes.epochMs("2026-09-29T21:00", 7_200))
        assertNull("no offset, no guess", ForecastTimes.epochMs("2026-09-29T21:00", null))
    }

    @Test
    fun daysAreNamedFromThePlacesToday() {
        val en = Locale.UK
        assertEquals("Today", ForecastTimes.dayLabel("2026-09-29", "2026-09-29", en))
        assertEquals("Tomorrow", ForecastTimes.dayLabel("2026-09-30", "2026-09-29", en))
        assertEquals("Thu", ForecastTimes.dayLabel("2026-10-01", "2026-09-29", en))
        assertEquals("Wed 30 Sept", ForecastTimes.dayLong("2026-09-30", en)?.replace(",", ""))
        // Across a month and a year end, and a leap day.
        assertEquals(1L, ForecastTimes.daysBetween("2026-12-31", "2027-01-01"))
        assertEquals("Tue", ForecastTimes.dayLabel("2028-02-29", "2028-02-01", en))
    }

    @Test
    fun malformedTimesGiveNothingRatherThanAWrongDay() {
        for (bad in listOf("", "2026-13-01", "2026-02-30", "2026-09-29T25:00", "29/09/2026", "2026-9-29", "2026-09-29T21:00:00Z")) {
            assertNull(bad, ForecastTimes.epochMs(bad, 0))
            assertNull(bad, ForecastTimes.dayLabel(bad, "2026-09-29", Locale.UK))
        }
        assertEquals("a malformed today still names the day", "Wed", ForecastTimes.dayLabel("2026-09-30", "garbage", Locale.UK))
    }

    @Test
    fun saysWhenThePlaceKeepsADifferentClock() {
        val now = utc(2026, 9, 29, 12)
        assertFalse(ForecastTimes.differsFromPhone(-18_000, now, TimeZone.getTimeZone("America/Chicago")))
        assertTrue(ForecastTimes.differsFromPhone(7_200, now, TimeZone.getTimeZone("America/Chicago")))
        assertFalse(ForecastTimes.differsFromPhone(null, now))
    }
}
