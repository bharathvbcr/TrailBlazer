package com.trailblazer.core.astro

import com.trailblazer.core.time.Iso8601
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SolarEvents.slice] against the Sun's actual altitude. The bands are derived from threshold crossings, and
 * at high latitude a threshold may be never reached (or never left) inside the day window; the slice there
 * must still agree with where the Sun really is.
 */
class SolarDayBandsTest {
    private val hour = 3_600_000L

    private fun localDay(date: String, tzHours: Int): Pair<Long, Long> {
        val start = Iso8601.parse("${date}T00:00:00Z")!! - tzHours * hour
        return start to start + 24 * hour
    }

    private fun day(lat: Double, lon: Double, date: String, tz: Int): SolarDay {
        val (s, e) = localDay(date, tz)
        return SolarEvents.day(lat, lon, s, e)
    }

    private fun at(date: String, tz: Int, hh: Int, mm: Int): Long = localDay(date, tz).first + (hh * 60L + mm) * 60_000L

    private fun altitude(lat: Double, lon: Double, ms: Long) = SolarPosition.horizontal(ms, lat, lon).altitudeDeg

    /** What the slice of a Normal day must be for a Sun at [alt]: golden −4…+6, blue −6…−4, then the twilights. */
    private fun expected(alt: Double): DaySlice = when {
        alt > 6.0 -> DaySlice.Day
        alt > -4.0 -> DaySlice.Golden
        alt > -6.0 -> DaySlice.Blue
        alt > -12.0 -> DaySlice.Nautical
        alt > -18.0 -> DaySlice.Astronomical
        else -> DaySlice.Night
    }

    private val thresholds = listOf(6.0, -4.0, -6.0, -12.0, -18.0)

    // ---- F13: a golden band with one end that the Sun never reaches

    @Test
    fun polarTwilightDecember66NorthIsNotGoldenAtMidnight() {
        val d = day(66.0, 25.0, "2026-12-21", 2)
        assertEquals(DayType.Normal, d.dayType)
        val t = at("2026-12-21", 2, 0, 30)
        assertTrue(altitude(66.0, 25.0, t) < -40.0)
        assertEquals(DaySlice.Night, SolarEvents.slice(d, t))
        assertEquals(DaySlice.Night, SolarEvents.slice(d, at("2026-12-21", 2, 23, 30)))
    }

    @Test
    fun polarTwilightDecember66NorthIsGoldenAroundNoonOnly() {
        val d = day(66.0, 25.0, "2026-12-21", 2)
        // The Sun peaks near +0.6°, so the whole of -4…+6 around noon is golden and nothing is plain Day.
        val noon = assertNotNull(d.solarNoonMs)
        assertEquals(DaySlice.Golden, SolarEvents.slice(d, noon))
        val gm = assertNotNull(d.goldenMorning)
        val ge = assertNotNull(d.goldenEvening)
        assertEquals(noon, gm.endMs)
        assertEquals(noon, ge.startMs)
        assertTrue(gm.startMs != null && ge.endMs != null)
    }

    // ---- F14: a threshold never crossed is either always above or always below

    @Test
    fun whiteNightJune60NorthShowsTwilightAtSolarMidnight() {
        // Lowest altitude is about −6.6°: below civil (−6°) but well above nautical (−12°).
        val lat = 60.0
        val lon = 25.0
        val d = day(lat, lon, "2026-06-21", 3)
        assertEquals(DayType.Normal, d.dayType)
        val t = (0..1440 step 5).map { at("2026-06-21", 3, 0, 0) + it * 60_000L }.minBy { altitude(lat, lon, it) }
        assertTrue(altitude(lat, lon, t) in -7.0..-6.2, "alt ${altitude(lat, lon, t)}")
        assertEquals(DaySlice.Nautical, SolarEvents.slice(d, t))
    }

    @Test
    fun allDayAndAlwaysBelowAreDistinguishable() {
        val white = day(60.0, 25.0, "2026-06-21", 3)
        assertTrue(white.nautical.allDay && white.astronomical.allDay)
        assertTrue(!white.civil.allDay)
        val winter = day(66.0, 25.0, "2026-12-21", 2)
        assertTrue(!winter.goldenMorning!!.allDay)
        // Sun never above -18° anywhere near: nothing is "always above" and the band is empty, not full.
        val polarNight = day(78.0, 15.0, "2026-12-21", 1)
        assertEquals(DayType.PolarNight, polarNight.dayType)
        assertTrue(!polarNight.civil.allDay)
        assertNull(polarNight.civil.startMs)
        assertNull(polarNight.civil.endMs)
    }

    // ---- named latitudes: polar night/day, equator, mid-latitude

    @Test
    fun polarNightAndMidnightSunAt78North() {
        val night = day(78.0, 15.0, "2026-12-21", 1)
        assertEquals(DayType.PolarNight, night.dayType)
        assertEquals(DaySlice.PolarNight, SolarEvents.slice(night, at("2026-12-21", 1, 12, 0)))
        val sun = day(78.0, 15.0, "2026-06-21", 2)
        assertEquals(DayType.PolarDay, sun.dayType)
        assertEquals(DaySlice.PolarDay, SolarEvents.slice(sun, at("2026-06-21", 2, 0, 30)))
    }

    @Test
    fun equatorEquinoxHasEveryBandAndSliceMatchesAltitude() {
        val d = day(0.0, 0.0, "2026-03-20", 0)
        for (b in listOf(d.civil, d.nautical, d.astronomical)) assertTrue(b.startMs != null && b.endMs != null && !b.allDay)
        assertSlicesMatchAltitude(0.0, 0.0, "2026-03-20", 0)
    }

    @Test
    fun midLatitudeDayKeepsItsBandOrder() {
        val d = day(39.7392, -104.9903, "2026-06-21", -6)
        assertTrue(d.civil.startMs != null && d.civil.endMs != null)
        assertTrue(d.goldenMorning != null && d.goldenEvening != null && d.blueMorning != null && d.blueEvening != null)
        assertTrue(!d.civil.allDay && !d.nautical.allDay && !d.astronomical.allDay)
        assertSlicesMatchAltitude(39.7392, -104.9903, "2026-06-21", -6)
    }

    // ---- the sweep

    private fun mismatches(lat: Double, lon: Double, date: String, tz: Int): List<String> {
        val d = day(lat, lon, date, tz)
        if (d.dayType != DayType.Normal) return emptyList()
        val out = ArrayList<String>()
        val start = localDay(date, tz).first
        for (minute in 0 until 1440 step 10) {
            val t = start + minute * 60_000L
            val alt = altitude(lat, lon, t)
            if (thresholds.any { abs(alt - it) < 0.3 }) continue
            val got = SolarEvents.slice(d, t)
            val want = expected(alt)
            if (got != want) out += "lat=$lat lon=$lon $date +${minute}min alt=${(alt * 10).roundToInt() / 10.0} got=$got want=$want"
        }
        return out
    }

    private fun assertSlicesMatchAltitude(lat: Double, lon: Double, date: String, tz: Int) {
        val bad = mismatches(lat, lon, date, tz)
        assertTrue(bad.isEmpty(), "${bad.size} mismatches, first: ${bad.take(5)}")
    }

    @Test
    fun sliceAgreesWithTheSunAtEveryLatitudeAndSeason() {
        val dates = listOf("2026-01-10", "2026-03-20", "2026-05-15", "2026-06-21", "2026-08-01", "2026-09-23", "2026-11-05", "2026-12-21")
        val bad = ArrayList<String>()
        var checked = 0
        for (lon in listOf(25.0, -105.0, 150.0)) {
            val tz = (lon / 15.0).roundToInt()
            for (latStep in -17..17) {
                val lat = latStep * 5.0
                for (date in dates) {
                    bad += mismatches(lat, lon, date, tz)
                    checked++
                }
            }
        }
        assertTrue(checked > 0)
        assertTrue(bad.isEmpty(), "${bad.size} mismatches over $checked days, first: ${bad.take(8)}")
    }
}
