package com.example.trailblazer.ui.sky

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Settings
import com.example.trailblazer.ui.Fmt
import com.trailblazer.core.astro.Band
import com.trailblazer.core.astro.SolarDay
import com.trailblazer.core.astro.SolarEvents
import com.trailblazer.core.time.Iso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the daylight strip draws for the bands of a day, including bands that are open, all-day or wrapped. */
@RunWith(AndroidJUnit4::class)
class DayStripBandsTest {
    private val hour = 3_600_000L

    private fun day(lat: Double, lon: Double, date: String, tz: Int): SolarDay {
        val start = Iso8601.parse("${date}T00:00:00Z")!! - tz * hour
        return SolarEvents.day(lat, lon, start, start + 24 * hour)
    }

    private fun SolarDay.spans(b: Band?) = bandSpans(b, windowStartMs, windowEndMs)

    private fun List<ClosedFloatingPointRange<Float>>.covered() = sumOf { (it.endInclusive - it.start).toDouble() }

    @Test
    fun whiteNightBandsThatHoldAllDayFillTheStrip() {
        // 60N: the Sun bottoms out near -6.6° — nautical and astronomical twilight never end, civil does.
        val d60 = day(60.0, 25.0, "2026-06-21", 3)
        assertEquals(listOf(0f..1f), d60.spans(d60.nautical))
        assertEquals(listOf(0f..1f), d60.spans(d60.astronomical))
        assertTrue(d60.spans(d60.civil).covered() < 1.0)
        // 64N: the Sun never gets below -2.6°, so civil twilight holds all day too.
        val d64 = day(64.0, 25.0, "2026-06-21", 3)
        assertEquals(listOf(0f..1f), d64.spans(d64.civil))
        assertEquals(listOf(0f..1f), d64.spans(d64.nautical))
    }

    @Test
    fun aBandThatNeverHappensDrawsNothing() {
        val polarNight = day(78.0, 15.0, "2026-12-21", 1)
        assertTrue(polarNight.spans(polarNight.civil).isEmpty())
        assertTrue(polarNight.spans(null).isEmpty())
    }

    @Test
    fun reykjavikMidsummerEveningGoldenHourRunsThroughMidnightAndDrawsBothHalves() {
        val d = day(64.1466, -21.9426, "2026-06-21", 0)
        val evening = d.goldenEvening!!
        val start = evening.startMs!!
        val end = evening.endMs!!
        assertTrue("the band wraps through midnight", end < start)
        val parts = d.spans(evening)
        assertEquals(2, parts.size)
        assertEquals(1f, parts[0].endInclusive, 0f)
        assertEquals(0f, parts[1].start, 0f)
        assertTrue(parts.all { it.endInclusive > it.start })
        // The two halves together are exactly the time from start to end across midnight.
        val expected = ((d.windowEndMs - start) + (end - d.windowStartMs)).toDouble() / (24 * hour)
        assertEquals(expected, parts.covered(), 1e-4)
        assertTrue("about three hours, not nothing", parts.covered() > 0.1)
    }

    @Test
    fun aNormalDayIsDrawnAsOneSpanPerBandAtTheSameFractionsAsBefore() {
        val d = day(39.7392, -104.9903, "2026-06-21", -6)
        val golden = d.goldenEvening!!
        val part = d.spans(golden).single()
        assertEquals((golden.startMs!! - d.windowStartMs).toDouble() / (24 * hour), part.start.toDouble(), 1e-6)
        assertEquals((golden.endMs!! - d.windowStartMs).toDouble() / (24 * hour), part.endInclusive.toDouble(), 1e-6)
        assertEquals(1, d.spans(d.civil).size)
        assertEquals(1, d.spans(d.nautical).size)
    }

    @Test
    fun openEndedBandsRunToTheEdgeOfTheDay() {
        val from = bandSpans(Band(6 * hour, null), 0L, 24 * hour).single()
        assertEquals(0.25f, from.start, 1e-6f)
        assertEquals(1f, from.endInclusive, 0f)
        val until = bandSpans(Band(null, 6 * hour), 0L, 24 * hour).single()
        assertEquals(0f, until.start, 0f)
        assertEquals(0.25f, until.endInclusive, 1e-6f)
        assertTrue(bandSpans(Band(null, null), 0L, 24 * hour).isEmpty())
    }

    @Test
    fun bandTextReadsAllDayWrappedAndOpenBands() {
        val fmt = Fmt(ApplicationProvider.getApplicationContext(), Settings())
        assertEquals("All day", bandText(Band(null, null, allDay = true), fmt))
        assertNull(bandText(Band(null, null), fmt))
        assertTrue(bandText(Band(6 * hour, null), fmt)!!.startsWith("from "))
        assertTrue(bandText(Band(null, 6 * hour), fmt)!!.startsWith("until "))
        val wrapped = bandText(Band(22 * hour, hour), fmt)!!
        assertTrue(wrapped, wrapped.contains("–") && !wrapped.startsWith("from") && !wrapped.startsWith("until"))
    }
}
