package com.trailblazer.core.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/** 5 000 seeded random cases plus the named edge cases from the test plan. */
class AstroPropertyTest {
    private fun dayWindow(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val s = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val e = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return s to e
    }

    private fun checkDay(lat: Double, lon: Double, date: LocalDate, zone: ZoneId, strictOrder: Boolean) {
        val (s, e) = dayWindow(date, zone)
        val d = SolarEvents.day(lat, lon, s, e)
        val ctx = "lat=$lat lon=$lon $date $zone"
        assertTrue(ctx, d.daylightMs in 0..(e - s))
        d.noonAltitudeDeg?.let { assertTrue(ctx, it.isFinite() && it in -90.0..90.0) }
        for (az in listOfNotNull(d.sunriseAzimuthDeg, d.sunsetAzimuthDeg)) assertTrue(ctx, az >= 0.0 && az < 360.0)
        listOfNotNull(d.sunriseMs, d.sunsetMs, d.solarNoonMs).forEach { assertTrue(ctx, it in s until e) }
        when (d.dayType) {
            DayType.PolarDay -> assertEquals(ctx, e - s, d.daylightMs)
            DayType.PolarNight -> assertEquals(ctx, 0L, d.daylightMs)
            DayType.Normal -> Unit
        }
        val rise = d.sunriseMs
        val noon = d.solarNoonMs
        val set = d.sunsetMs
        if (strictOrder && d.dayType == DayType.Normal && rise != null && set != null && noon != null) {
            assertTrue("$ctx rise<noon<set", rise < noon && noon < set)
        }
        val sun = SolarPosition.horizontal(s + 12 * 3_600_000L, lat, lon)
        assertTrue(ctx, sun.azimuthDeg >= 0.0 && sun.azimuthDeg < 360.0 && sun.altitudeDeg.isFinite())
        assertTrue(ctx, sun.apparentAltitudeDeg.isFinite())

        val m = LunarEvents.day(lat, lon, s, e)
        listOfNotNull(m.moonriseMs, m.moonsetMs, m.transitMs).forEach { assertTrue(ctx, it in s until e) }
        assertTrue(ctx, !(m.alwaysUp && m.alwaysDown))
        val mh = LunarPosition.horizontal(s, lat, lon)
        assertTrue(ctx, mh.azimuthDeg >= 0.0 && mh.azimuthDeg < 360.0 && mh.altitudeDeg in -91.0..91.0)
        val ph = LunarPhase.at(s)
        assertTrue(ctx, ph.illumination in 0.0..1.0 && ph.elongationDeg >= 0.0 && ph.elongationDeg < 360.0)
    }

    @Test
    fun fiveThousandRandomCases() {
        val rnd = Random(20260929)
        repeat(5000) {
            val lat = rnd.nextDouble(-90.0, 90.0)
            val lon = rnd.nextDouble(-180.0, 180.0)
            val date = LocalDate.of(2026, 1, 1).plusDays(rnd.nextLong(0, 365))
            val zone = ZoneOffset.ofTotalSeconds(((Math.round(lon / 15.0)) * 3600).toInt())
            checkDay(lat, lon, date, zone, strictOrder = kotlin.math.abs(lat) < 60)
        }
    }

    @Test
    fun namedEdgeCases() {
        val utc = ZoneOffset.UTC
        // Poles at both solstices.
        for (d in listOf(LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21), LocalDate.of(2026, 3, 20))) {
            checkDay(90.0, 0.0, d, utc, false)
            checkDay(-90.0, 0.0, d, utc, false)
        }
        // Date line, both sides.
        checkDay(-13.8333, -171.7667, LocalDate.of(2026, 10, 15), ZoneOffset.ofHours(13), true)
        checkDay(-18.1416, 178.4419, LocalDate.of(2026, 10, 15), ZoneOffset.ofHours(12), true)
        checkDay(0.0, 180.0, LocalDate.of(2026, 10, 15), ZoneOffset.ofHours(12), true)
        checkDay(0.0, -180.0, LocalDate.of(2026, 10, 15), ZoneOffset.ofHours(-12), true)
        // DST transition days (23 h and 25 h windows).
        val denver = ZoneId.of("America/Denver")
        val london = ZoneId.of("Europe/London")
        for (d in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) checkDay(39.7392, -104.9903, d, denver, true)
        for (d in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) checkDay(51.5074, -0.1278, d, london, true)
        val (s, e) = dayWindow(LocalDate.of(2026, 3, 8), denver)
        assertEquals(23 * 3_600_000L, e - s)
        // Leap day and century years.
        checkDay(45.0, 10.0, LocalDate.of(2028, 2, 29), ZoneOffset.ofHours(1), true)
        checkDay(45.0, 10.0, LocalDate.of(1900, 3, 1), ZoneOffset.ofHours(1), true)
        checkDay(45.0, 10.0, LocalDate.of(2100, 3, 1), ZoneOffset.ofHours(1), true)
    }
}
