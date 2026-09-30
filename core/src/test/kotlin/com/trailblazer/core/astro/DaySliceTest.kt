package com.trailblazer.core.astro

import org.junit.Assert.assertEquals
import org.junit.Test

class DaySliceTest {
    private val h = 3_600_000L

    private fun day(type: DayType = DayType.Normal) = SolarDay(
        windowStartMs = 0L,
        windowEndMs = 24 * h,
        sunriseMs = 6 * h,
        sunsetMs = 18 * h,
        solarNoonMs = 12 * h,
        noonAltitudeDeg = 50.0,
        sunriseAzimuthDeg = 90.0,
        sunsetAzimuthDeg = 270.0,
        civil = Band(5 * h, 19 * h),
        nautical = Band(4 * h, 20 * h),
        astronomical = Band(3 * h, 21 * h),
        goldenMorning = Band(5 * h + h / 2, 7 * h),
        goldenEvening = Band(17 * h, 18 * h + h / 2),
        blueMorning = Band(5 * h, 5 * h + h / 2),
        blueEvening = Band(18 * h + h / 2, 19 * h),
        daylightMs = 12 * h,
        dayType = type,
    )

    @Test
    fun innermostBandWins() {
        val d = day()
        assertEquals(DaySlice.Night, SolarEvents.slice(d, 2 * h))
        assertEquals(DaySlice.Astronomical, SolarEvents.slice(d, 3 * h + h / 2))
        assertEquals(DaySlice.Nautical, SolarEvents.slice(d, 4 * h + h / 2))
        assertEquals(DaySlice.Blue, SolarEvents.slice(d, 5 * h + h / 5))
        assertEquals(DaySlice.Golden, SolarEvents.slice(d, 6 * h))
        assertEquals(DaySlice.Day, SolarEvents.slice(d, 10 * h))
        assertEquals(DaySlice.Golden, SolarEvents.slice(d, 17 * h + h / 2))
        assertEquals(DaySlice.Blue, SolarEvents.slice(d, 18 * h + (3 * h) / 4))
        assertEquals(DaySlice.Nautical, SolarEvents.slice(d, 19 * h + h / 2))
        assertEquals(DaySlice.Astronomical, SolarEvents.slice(d, 20 * h + h / 2))
        assertEquals(DaySlice.Night, SolarEvents.slice(d, 22 * h))
    }

    @Test
    fun polarDaysIgnoreTheClock() {
        assertEquals(DaySlice.PolarDay, SolarEvents.slice(day(DayType.PolarDay), 12 * h))
        assertEquals(DaySlice.PolarNight, SolarEvents.slice(day(DayType.PolarNight), 12 * h))
    }

    @Test
    fun openEndedBandFillsToTheWindowEdge() {
        val d = day().copy(
            civil = Band(5 * h, null),
            nautical = Band(null, null),
            astronomical = Band(null, null),
            blueMorning = null,
            blueEvening = null,
            goldenMorning = null,
            goldenEvening = null,
        )
        assertEquals(DaySlice.Civil, SolarEvents.slice(d, 23 * h))
        assertEquals(DaySlice.Night, SolarEvents.slice(d, 4 * h))
    }
}
