package com.trailblazer.core.astro

import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.time.JulianDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Golden values from primary sources:
 *  - Meeus, "Astronomical Algorithms" 2nd ed., worked examples 12.a, 25.a, 47.a, 48.a, 49.a.
 *  - US Naval Observatory, fetched 2026-09-29 from
 *    https://aa.usno.navy.mil/api/rstt/oneday?date=<date>&coords=<lat>,<lon>&tz=<tz>
 *    (times are rounded to the minute by USNO).
 */
class AstroGoldenTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun localDay(date: String, tzHours: Int): Pair<Long, Long> {
        val start = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.ofHours(tzHours)).toEpochMilli()
        return start to start + 86_400_000L
    }

    private fun localHm(date: String, tzHours: Int, hm: String): Long {
        val (h, m) = hm.split(":").map { it.toInt() }
        return localDay(date, tzHours).first + (h * 60 + m) * 60_000L
    }

    private fun assertNear(expectedMs: Long, actualMs: Long?, tolMinutes: Double, what: String) {
        assertNotNull("$what missing", actualMs)
        val diffMin = abs(actualMs!! - expectedMs) / 60_000.0
        assertTrue("$what off by $diffMin min", diffMin <= tolMinutes)
    }

    @Test
    fun meeus12a_greenwichMeanSiderealTime() {
        val jd = JulianDay.fromEpochMillis(utc(1987, 4, 10))
        assertEquals(197.693195, Sidereal.greenwichMean(jd), 1e-5)
    }

    @Test
    fun meeus25a_sunApparentPosition() {
        // 1992 Oct 13, 0h TD → RA 198.38083°, Dec −7.78507°. Our input is UT, so subtract ΔT (~59 s).
        val jdeMs = utc(1992, 10, 13) - 59_000
        val eq = SolarPosition.equatorial(jdeMs)
        assertEquals(198.38083, eq.raDeg, 0.01)
        assertEquals(-7.78507, eq.decDeg, 0.01)
    }

    @Test
    fun meeus47a_moonPosition() {
        // 1992 April 12, 0h TD: λ = 133.162655°, β = −3.229126°, Δ = 368409.7 km, RA 134.688470°, Dec 13.768368°.
        val jde = 2448724.5
        val ecl = LunarPosition.ecliptic(jde)
        assertEquals(133.162655, ecl.longitudeDeg, 0.01)
        assertEquals(-3.229126, ecl.latitudeDeg, 0.01)
        assertEquals(368409.7, ecl.distanceKm, 50.0)
        val ms = JulianDay.toEpochMillis(jde) - 59_000
        val eq = LunarPosition.equatorial(ms)
        assertEquals(134.688470, eq.raDeg, 0.02)
        assertEquals(13.768368, eq.decDeg, 0.02)
    }

    @Test
    fun meeus48a_illuminatedFraction() {
        val ms = JulianDay.toEpochMillis(2448724.5) - 59_000
        assertEquals(0.6786, LunarPhase.at(ms).illumination, 0.003)
    }

    @Test
    fun meeus49a_newMoonFebruary1977() {
        // JDE 2443192.65118 = 1977 Feb 18 03:37:42 TD; ΔT ≈ 48 s.
        val expected = JulianDay.toEpochMillis(2443192.65118) - 48_000
        val found = LunarPhase.next(utc(1977, 2, 10), 0.0)
        assertNear(expected, found, 3.0, "new moon")
    }

    @Test
    fun usnoPhaseInstants2026() {
        assertNear(utc(2026, 3, 19, 1, 23), LunarPhase.next(utc(2026, 3, 10), 0.0), 3.0, "new moon 2026-03-19")
        assertNear(utc(2026, 6, 21, 21, 55), LunarPhase.next(utc(2026, 6, 15), 90.0), 3.0, "first quarter 2026-06-21")
        assertNear(utc(2026, 9, 26, 16, 49), LunarPhase.next(utc(2026, 9, 20), 180.0), 3.0, "full moon 2026-09-26")
        assertNear(utc(2026, 12, 17, 5, 42), LunarPhase.next(utc(2026, 12, 10), 90.0), 3.0, "first quarter 2026-12-17")
    }

    private data class UsnoCase(
        val name: String, val date: String, val lat: Double, val lon: Double, val tz: Int,
        val civilDawn: String, val rise: String, val transit: String, val set: String, val civilDusk: String,
        val moonRise: String?, val moonSet: String?,
    )

    private val usno = listOf(
        UsnoCase("Denver solstice", "2026-06-21", 39.7392, -104.9903, -6, "05:00", "05:32", "13:02", "20:31", "21:04", "12:59", "00:35"),
        UsnoCase("Greenwich equinox", "2026-03-20", 51.4779, 0.0, 0, "05:30", "06:03", "12:07", "18:13", "18:46", "06:16", "20:35"),
        UsnoCase("Sydney December", "2026-12-15", -33.8688, 151.2093, 10, "04:09", "04:38", "11:50", "19:02", "19:31", "09:47", "23:05"),
        UsnoCase("Tromsø equinox", "2026-03-20", 69.6492, 18.9553, 1, "04:44", "05:44", "11:52", "18:02", "19:02", "05:09", "21:43"),
        UsnoCase("Quito", "2026-09-29", -0.1807, -78.4678, -5, "05:40", "06:01", "12:04", "18:07", "18:28", "20:47", "08:17"),
    )

    @Test
    fun usnoSunEvents() {
        for (c in usno) {
            val (start, end) = localDay(c.date, c.tz)
            val d = SolarEvents.day(c.lat, c.lon, start, end)
            assertEquals(c.name, DayType.Normal, d.dayType)
            assertNear(localHm(c.date, c.tz, c.rise), d.sunriseMs, 1.5, "${c.name} sunrise")
            assertNear(localHm(c.date, c.tz, c.set), d.sunsetMs, 1.5, "${c.name} sunset")
            assertNear(localHm(c.date, c.tz, c.transit), d.solarNoonMs, 1.5, "${c.name} transit")
            assertNear(localHm(c.date, c.tz, c.civilDawn), d.civil.startMs, 1.5, "${c.name} civil dawn")
            assertNear(localHm(c.date, c.tz, c.civilDusk), d.civil.endMs, 1.5, "${c.name} civil dusk")
        }
    }

    @Test
    fun usnoMoonEvents() {
        for (c in usno) {
            val (start, end) = localDay(c.date, c.tz)
            val m = LunarEvents.day(c.lat, c.lon, start, end)
            c.moonRise?.let { assertNear(localHm(c.date, c.tz, it), m.moonriseMs, 3.0, "${c.name} moonrise") }
            c.moonSet?.let { assertNear(localHm(c.date, c.tz, it), m.moonsetMs, 3.0, "${c.name} moonset") }
        }
    }

    @Test
    fun sunAzimuthAtUpperTransitPointsSouthInNorthAndNorthInSouth() {
        // Regression for the web app's +180° error: Denver at solar noon read 358.5° instead of ~180°.
        val (s, e) = localDay("2026-06-21", -6)
        val north = SolarEvents.day(39.7392, -104.9903, s, e)
        val azN = SolarPosition.horizontal(north.solarNoonMs!!, 39.7392, -104.9903).azimuthDeg
        assertEquals(0.0, angleDiff(180.0, azN), 0.5)

        val (s2, e2) = localDay("2026-12-15", 10)
        val south = SolarEvents.day(-33.8688, 151.2093, s2, e2)
        val azS = SolarPosition.horizontal(south.solarNoonMs!!, -33.8688, 151.2093).azimuthDeg
        assertEquals(0.0, angleDiff(0.0, azS), 0.5)
    }

    @Test
    fun sunriseAzimuthsFollowTheSeasons() {
        val (s, e) = localDay("2026-06-21", -6)
        val summer = SolarEvents.day(39.7392, -104.9903, s, e)
        assertTrue(summer.sunriseAzimuthDeg!! in 55.0..65.0)
        assertTrue(summer.sunsetAzimuthDeg!! in 295.0..305.0)
    }

    @Test
    fun polarDayAndNightAtTromsoAndThePoles() {
        val (s, e) = localDay("2026-06-21", 2)
        val midnightSun = SolarEvents.day(69.6492, 18.9553, s, e)
        assertEquals(DayType.PolarDay, midnightSun.dayType)
        assertEquals(e - s, midnightSun.daylightMs)
        assertNull(midnightSun.sunriseMs)
        assertEquals(DaylightStatus.PolarDay, SolarEvents.status(midnightSun, s + 1000))

        val (s2, e2) = localDay("2026-12-21", 1)
        val polarNight = SolarEvents.day(69.6492, 18.9553, s2, e2)
        assertEquals(DayType.PolarNight, polarNight.dayType)
        assertEquals(0L, polarNight.daylightMs)

        // Regression: the web app reported 0 minutes of daylight at the pole in midsummer.
        val (s3, e3) = localDay("2026-06-21", 0)
        val pole = SolarEvents.day(90.0, 0.0, s3, e3)
        assertEquals(DayType.PolarDay, pole.dayType)
        assertEquals(86_400_000L, pole.daylightMs)
        val southPole = SolarEvents.day(-90.0, 0.0, s3, e3)
        assertEquals(DayType.PolarNight, southPole.dayType)
    }

    @Test
    fun daylightStatusAcrossTheDay() {
        val (s, e) = localDay("2026-03-20", 0)
        val d = SolarEvents.day(51.4779, 0.0, s, e)
        val rise = d.sunriseMs!!
        val set = d.sunsetMs!!
        assertTrue(SolarEvents.status(d, rise - 60_000) is DaylightStatus.UntilSunrise)
        val left = SolarEvents.status(d, rise + 60_000) as DaylightStatus.DaylightLeft
        assertEquals(set - rise - 60_000, left.ms)
        assertEquals(DaylightStatus.AfterSunset, SolarEvents.status(d, set + 1))
    }

    @Test
    fun goldenAndBlueHourAreOrdered() {
        val (s, e) = localDay("2026-06-21", -6)
        val d = SolarEvents.day(39.7392, -104.9903, s, e)
        val gm = d.goldenMorning!!
        val bm = d.blueMorning!!
        val rise = d.sunriseMs!!
        val set = d.sunsetMs!!
        assertTrue(bm.startMs!! < bm.endMs!!)
        assertEquals(bm.endMs, gm.startMs)
        assertTrue(gm.startMs!! < rise && rise < gm.endMs!!)
        val ge = d.goldenEvening!!
        assertTrue(ge.startMs!! < set && set < ge.endMs!!)
    }

    @Test
    fun phaseNameIlluminationAndAgeAgree() {
        for (day in 0 until 60) {
            val t = utc(2026, 1, 1) + day * 86_400_000L
            val p = LunarPhase.at(t)
            when (p.name) {
                MoonPhaseName.NewMoon -> assertTrue(p.illumination < 0.05)
                MoonPhaseName.FullMoon -> assertTrue(p.illumination > 0.95)
                MoonPhaseName.FirstQuarter, MoonPhaseName.LastQuarter -> assertTrue(p.illumination in 0.3..0.7)
                MoonPhaseName.WaxingCrescent, MoonPhaseName.WaningCrescent -> assertTrue(p.illumination < 0.55)
                MoonPhaseName.WaxingGibbous, MoonPhaseName.WaningGibbous -> assertTrue(p.illumination > 0.45)
            }
            assertEquals(p.waxing, p.elongationDeg < 180)
            assertTrue(p.ageDays in 0.0..LunarPhase.SYNODIC_MONTH_DAYS)
        }
    }
}
