package com.trailblazer.core.astro

import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.time.Iso8601
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 5 000 seeded random cases plus the named edge cases from the test plan. */
class AstroPropertyTest {

    private fun checkDay(lat: Double, lon: Double, s: Long, e: Long, desc: String, strictOrder: Boolean) {
        val d = SolarEvents.day(lat, lon, s, e)
        val ctx = "lat=$lat lon=$lon $desc"
        assertTrue(d.daylightMs in 0..(e - s), ctx)
        d.noonAltitudeDeg?.let { assertTrue(it.isFinite() && it in -90.0..90.0, ctx) }
        for (az in listOfNotNull(d.sunriseAzimuthDeg, d.sunsetAzimuthDeg)) assertTrue(az >= 0.0 && az < 360.0, ctx)
        listOfNotNull(d.sunriseMs, d.sunsetMs, d.solarNoonMs).forEach { assertTrue(it in s until e, ctx) }
        when (d.dayType) {
            DayType.PolarDay -> assertEquals(e - s, d.daylightMs, ctx)
            DayType.PolarNight -> assertEquals(0L, d.daylightMs, ctx)
            DayType.Normal -> Unit
        }
        val rise = d.sunriseMs
        val noon = d.solarNoonMs
        val set = d.sunsetMs
        if (strictOrder && d.dayType == DayType.Normal && rise != null && set != null && noon != null) {
            assertTrue(rise < noon && noon < set, "$ctx rise<noon<set")
        }
        val sun = SolarPosition.horizontal(s + 12 * 3_600_000L, lat, lon)
        assertTrue(sun.azimuthDeg >= 0.0 && sun.azimuthDeg < 360.0 && sun.altitudeDeg.isFinite(), ctx)
        assertTrue(sun.apparentAltitudeDeg.isFinite(), ctx)

        val m = LunarEvents.day(lat, lon, s, e)
        listOfNotNull(m.moonriseMs, m.moonsetMs, m.transitMs).forEach { assertTrue(it in s until e, ctx) }
        assertTrue(!(m.alwaysUp && m.alwaysDown), ctx)
        val mh = LunarPosition.horizontal(s, lat, lon)
        assertTrue(mh.azimuthDeg >= 0.0 && mh.azimuthDeg < 360.0 && mh.altitudeDeg in -91.0..91.0, ctx)
        val ph = LunarPhase.at(s)
        assertTrue(ph.illumination in 0.0..1.0 && ph.elongationDeg >= 0.0 && ph.elongationDeg < 360.0, ctx)
    }

    private fun window(y: Int, m: Int, d: Int, tzOffsetSeconds: Int = 0): Pair<Long, Long> {
        val days = CivilDate.daysFromCivil(y, m, d)
        val s = (days * 86_400L - tzOffsetSeconds) * 1000L
        return s to s + 86_400_000L
    }

    @Test
    fun fiveThousandRandomCases() {
        val rnd = Random(20260929)
        val startDay = CivilDate.daysFromCivil(2026, 1, 1)
        repeat(5000) {
            val lat = rnd.nextDouble(-90.0, 90.0)
            val lon = rnd.nextDouble(-180.0, 180.0)
            val day = startDay + rnd.nextLong(0, 365)
            val zoneSec = ((lon / 15.0).roundToLong() * 3600).toInt()
            val s = (day * 86_400L - zoneSec) * 1000L
            val e = s + 86_400_000L
            checkDay(lat, lon, s, e, "day=$day zoneSec=$zoneSec", strictOrder = abs(lat) < 60)
        }
    }

    @Test
    fun namedEdgeCases() {
        // Poles at both solstices and equinox.
        for ((y, m, d) in listOf(Triple(2026, 6, 21), Triple(2026, 12, 21), Triple(2026, 3, 20))) {
            val (s, e) = window(y, m, d)
            checkDay(90.0, 0.0, s, e, "$y-$m-$d poleN", false)
            checkDay(-90.0, 0.0, s, e, "$y-$m-$d poleS", false)
        }
        // Date line, both sides.
        val (s1, e1) = window(2026, 10, 15, 13 * 3600)
        checkDay(-13.8333, -171.7667, s1, e1, "Samoa +13", true)
        val (s2, e2) = window(2026, 10, 15, 12 * 3600)
        checkDay(-18.1416, 178.4419, s2, e2, "Fiji +12", true)
        checkDay(0.0, 180.0, s2, e2, "180E", true)
        val (s3, e3) = window(2026, 10, 15, -12 * 3600)
        checkDay(0.0, -180.0, s3, e3, "180W", true)

        // DST transition days (Denver America/Denver, London Europe/London).
        // Denver 2026-03-08 spring forward: 23h window
        val ds1 = Iso8601.parse("2026-03-08T07:00:00Z")!!
        val de1 = Iso8601.parse("2026-03-09T06:00:00Z")!!
        assertEquals(23 * 3_600_000L, de1 - ds1)
        checkDay(39.7392, -104.9903, ds1, de1, "Denver spring DST", true)

        // Denver 2026-11-01 fall back: 25h window
        val ds2 = Iso8601.parse("2026-11-01T06:00:00Z")!!
        val de2 = Iso8601.parse("2026-11-02T07:00:00Z")!!
        assertEquals(25 * 3_600_000L, de2 - ds2)
        checkDay(39.7392, -104.9903, ds2, de2, "Denver fall DST", true)

        // London 2026-03-29 spring forward: 23h window
        val ls1 = Iso8601.parse("2026-03-29T00:00:00Z")!!
        val le1 = Iso8601.parse("2026-03-29T23:00:00Z")!!
        assertEquals(23 * 3_600_000L, le1 - ls1)
        checkDay(51.5074, -0.1278, ls1, le1, "London spring DST", true)

        // London 2026-10-25 fall back: 25h window
        val ls2 = Iso8601.parse("2026-10-24T23:00:00Z")!!
        val le2 = Iso8601.parse("2026-10-26T00:00:00Z")!!
        assertEquals(25 * 3_600_000L, le2 - ls2)
        checkDay(51.5074, -0.1278, ls2, le2, "London fall DST", true)

        // Leap day and century years.
        val (ls, le) = window(2028, 2, 29, 3600)
        checkDay(45.0, 10.0, ls, le, "Leap day 2028", true)
        val (c1s, c1e) = window(1900, 3, 1, 3600)
        checkDay(45.0, 10.0, c1s, c1e, "1900-03-01", true)
        val (c2s, c2e) = window(2100, 3, 1, 3600)
        checkDay(45.0, 10.0, c2s, c2e, "2100-03-01", true)
    }
}
