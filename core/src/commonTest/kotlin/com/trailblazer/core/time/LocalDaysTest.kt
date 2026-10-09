package com.trailblazer.core.time

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDaysTest {
    private val hour = 3_600_000L
    private val denver = PlatformTimeZone.of("America/Denver")
    private val sydney = PlatformTimeZone.of("Australia/Sydney")

    @Test
    fun ordinaryNightsAreTwentyFourHoursFromLocalNoon() {
        val day = CivilDate.daysFromCivil(2026, 9, 29)
        val (s, e) = LocalDays.noonToNoon(day, denver)
        assertEquals(24 * hour, e - s)
        // 12:00 MDT is 18:00 UTC.
        assertEquals(day * 24 * hour + 18 * hour, s)
    }

    /** The clocks change in the small hours, so the night that contains the change is 23 or 25 hours long. */
    @Test
    fun daylightSavingNightsAreShortOrLong() {
        val springDenver = CivilDate.daysFromCivil(2026, 3, 7) // night of 7–8 March, clocks go forward on the 8th
        assertEquals(23 * hour, LocalDays.noonToNoon(springDenver, denver).let { it.second - it.first })
        val autumnDenver = CivilDate.daysFromCivil(2026, 10, 31) // night of 31 Oct – 1 Nov
        assertEquals(25 * hour, LocalDays.noonToNoon(autumnDenver, denver).let { it.second - it.first })
        val autumnSydney = CivilDate.daysFromCivil(2026, 4, 4) // southern autumn: 5 April
        assertEquals(25 * hour, LocalDays.noonToNoon(autumnSydney, sydney).let { it.second - it.first })
    }

    @Test
    fun tonightBeforeNoonIsYesterdaysNight() {
        val day = CivilDate.daysFromCivil(2026, 9, 29)
        val (noon, _) = LocalDays.noonToNoon(day, denver)
        assertEquals(day - 1, LocalDays.nightOf(noon - 1, denver))
        assertEquals(day, LocalDays.nightOf(noon, denver))
        assertEquals(day, LocalDays.nightOf(noon + 13 * hour, denver)) // 01:00 the next morning
    }

    @Test
    fun dayWindowsStillStartAtMidnight() {
        val day = CivilDate.daysFromCivil(2026, 3, 8)
        val (s, e) = LocalDays.window(day, denver)
        assertEquals(23 * hour, e - s)
        assertEquals(day * 24 * hour + 7 * hour, s) // 00:00 MST is 07:00 UTC
    }
}
