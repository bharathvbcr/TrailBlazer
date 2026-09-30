package com.trailblazer.core.astro

import com.trailblazer.core.math.DEG
import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.time.JulianDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class NightSkyTest {
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private fun utc(y: Int, m: Int, d: Int, h: Int = 0) = CivilDate.daysFromCivil(y, m, d) * day + h * hour

    // ------------------------------------------------------------------ meteor showers

    /**
     * IMO 2026 calendar, Table 5: the maximum dates it lists for 2026. Only showers whose λ☉ is given to 0.1° or
     * better are checked to the day (an integer λ☉ is uncertain by ±12 h).
     */
    @Test
    fun meteorPeaksMatchTheImo2026Calendar() {
        val expected = mapOf(
            "QUA" to (1 to 3), "ACE" to (2 to 8), "LYR" to (4 to 22), "PPU" to (4 to 24), "ETA" to (5 to 6),
            "JBO" to (6 to 22), "GDR" to (7 to 28), "PER" to (8 to 13), "AUR" to (9 to 1), "SPE" to (9 to 9),
            "OCT" to (10 to 6), "DRA" to (10 to 9), "LEO" to (11 to 17), "AMO" to (11 to 22), "PHO" to (12 to 2),
            "GEM" to (12 to 14), "URS" to (12 to 22),
        )
        for ((code, md) in expected) {
            val shower = MeteorShowers.all.first { it.code == code }
            val peak = MeteorShowers.nextPeak(shower, utc(2026, 1, 1) - 5 * day)
            assertNotNull(code, peak)
            val (y, m, d) = CivilDate.civilFromDays(Math.floorDiv(peak!!, day))
            assertEquals("$code peak", Triple(2026, md.first, md.second), Triple(y, m, d))
        }
    }

    /** Integer-λ☉ showers must still land within a day of the listed date. */
    @Test
    fun coarseMeteorPeaksAreWithinADay() {
        val expected = mapOf("SDA" to utc(2026, 7, 31, 12), "CAP" to utc(2026, 7, 31, 12), "ORI" to utc(2026, 10, 21, 12), "GEM" to utc(2026, 12, 14, 12))
        for ((code, ms) in expected) {
            val peak = MeteorShowers.nextPeak(MeteorShowers.all.first { it.code == code }, utc(2026, 1, 1))!!
            assertTrue("$code off by ${(peak - ms) / hour} h", abs(peak - ms) <= 36 * hour)
        }
    }

    /** Referring λ☉ to J2000 matters: without the precession term the Perseid peak moves by about 9 hours. */
    @Test
    fun solarLongitudeIsReferredToJ2000() {
        val t = utc(2026, 8, 13)
        val jde = JulianDay.ephemeris(t)
        val ofDate = SolarPosition.ecliptic(jde).longitudeDeg
        val diff = angleDiff(ofDate, MeteorShowers.solarLongitudeJ2000(t))
        assertEquals(-Precession.longitudeDeg(jde), diff, 0.02)
        assertTrue(abs(diff) > 0.3)
    }

    @Test
    fun activityPeriodsWrapAroundNewYear() {
        val qua = MeteorShowers.all.first { it.code == "QUA" }
        assertTrue(MeteorShowers.isActive(qua, utc(2027, 1, 1)))
        assertTrue(MeteorShowers.isActive(qua, utc(2026, 12, 30)))
        assertFalse(MeteorShowers.isActive(qua, utc(2026, 1, 20)))
        val per = MeteorShowers.all.first { it.code == "PER" }
        assertTrue(MeteorShowers.isActive(per, utc(2026, 8, 12)))
        assertFalse(MeteorShowers.isActive(per, utc(2026, 9, 12)))
    }

    @Test
    fun upcomingIsSortedAndCoversEveryShowerOnce() {
        val list = MeteorShowers.upcoming(utc(2026, 9, 29))
        assertEquals(MeteorShowers.all.size, list.size)
        assertEquals(list.sortedBy { it.peakMs }, list)
        assertTrue(list.all { it.moonIllumination in 0.0..1.0 && it.peakMs >= utc(2026, 9, 26) })
    }

    @Test
    fun expectedRateFollowsRadiantAltitude() {
        val per = MeteorShowers.all.first { it.code == "PER" }
        assertEquals(0.0, MeteorShowers.expectedRate(per, -10.0)!!, 0.0)
        assertEquals(100.0, MeteorShowers.expectedRate(per, 90.0)!!, 1e-9)
        assertEquals(50.0, MeteorShowers.expectedRate(per, 30.0)!!, 1e-6)
        assertNull("variable showers have no rate", MeteorShowers.expectedRate(MeteorShowers.all.first { it.zhr == null }, 45.0))
    }

    // ------------------------------------------------------------------ intervals

    @Test
    fun intervalsAboveFindsEachPositiveStretch() {
        // sin with a 10-hour period over 24 h: positive for 0–5, 10–15, 20–24.
        val f = { t: Long -> sin(t.toDouble() / (10 * hour) * 2 * Math.PI) }
        val iv = Intervals.above(0, 24 * hour, f = f)
        assertEquals(3, iv.size)
        // f(0) is exactly 0, so the stretch opens at the detected rising crossing, within the finder's 30 s tolerance.
        assertEquals(0.0, iv[0].startMs.toDouble(), 30_000.0)
        assertEquals(5 * hour.toDouble(), iv[0].endMs.toDouble(), 60_000.0)
        assertEquals(10 * hour.toDouble(), iv[1].startMs.toDouble(), 60_000.0)
        assertEquals(24 * hour, iv[2].endMs)
        assertEquals(listOf(Interval(0, 24 * hour)), Intervals.above(0, 24 * hour) { 1.0 })
        assertTrue(Intervals.above(0, 24 * hour) { -1.0 }.isEmpty())
    }

    @Test
    fun intersectHandlesDisjointTouchingAndNested() {
        val a = listOf(Interval(0, 10), Interval(20, 30))
        assertEquals(listOf(Interval(5, 10), Interval(20, 25)), Intervals.intersect(a, listOf(Interval(5, 25))))
        assertTrue(Intervals.intersect(a, listOf(Interval(10, 20))).isEmpty())
        assertEquals(listOf(Interval(22, 24)), Intervals.intersect(a, listOf(Interval(22, 24))))
        assertTrue(Intervals.intersect(emptyList(), a).isEmpty())
    }

    // ------------------------------------------------------------------ night plans

    private fun noonToNoon(y: Int, m: Int, d: Int, lon: Double): Pair<Long, Long> {
        val start = utc(y, m, d, 12) - (lon / 15 * hour).toLong()
        return start to start + day
    }

    @Test
    fun midnightSunHasNoDarkness() {
        val (s, e) = noonToNoon(2026, 6, 21, 18.96)
        val p = NightSky.plan(69.65, 18.96, s, e)
        assertTrue(p.astronomicalDark.isEmpty())
        assertTrue(p.nauticalDark.isEmpty())
        assertTrue(p.moonlessDark.isEmpty())
        val jupiter = NightSky.planetNight(Planet.Jupiter, 69.65, 18.96, s, e)
        assertNull("no dark sky, so no best time", jupiter.bestTimeMs)
    }

    @Test
    fun equatorialNightIsAboutTenHoursOfFullDarkness() {
        val (s, e) = noonToNoon(2026, 3, 20, -78.5)
        val p = NightSky.plan(-0.18, -78.5, s, e)
        assertEquals(1, p.astronomicalDark.size)
        val h = p.astronomicalDarkMs.toDouble() / hour
        assertTrue("astro dark $h h", h in 9.5..10.5)
    }

    @Test
    fun southernWinterNightsAreLongerThanSummerNights() {
        val (s1, e1) = noonToNoon(2026, 6, 21, 151.2)
        val (s2, e2) = noonToNoon(2026, 12, 21, 151.2)
        assertTrue(NightSky.plan(-33.87, 151.2, s1, e1).astronomicalDarkMs > NightSky.plan(-33.87, 151.2, s2, e2).astronomicalDarkMs + 3 * hour)
    }

    /** Full moon (2026-08-28) keeps the sky moonlit nearly all night; new moon (2026-08-12) leaves it all dark. */
    @Test
    fun moonlessDarknessFollowsThePhase() {
        val (s1, e1) = noonToNoon(2026, 8, 12, -105.0)
        val newMoon = NightSky.plan(39.74, -105.0, s1, e1)
        assertTrue(newMoon.moonIllumination < 0.05)
        assertTrue(newMoon.moonlessDarkMs > newMoon.astronomicalDarkMs - hour)
        val (s2, e2) = noonToNoon(2026, 8, 27, -105.0)
        val full = NightSky.plan(39.74, -105.0, s2, e2)
        assertTrue(full.moonIllumination > 0.95)
        assertTrue(full.moonlessDarkMs < hour)
    }

    /** 2,000 seeded random places and nights: every interval is inside the window, ordered, and correctly nested. */
    @Test
    fun planInvariantsHoldEverywhere() {
        val rnd = Random(20260929)
        repeat(300) {
            val lat = rnd.nextDouble(-90.0, 90.0)
            val lon = rnd.nextDouble(-180.0, 180.0)
            val start = utc(2026, 1, 1) + rnd.nextLong(0, 365 * day)
            val p = NightSky.plan(lat, lon, start, start + day)
            for (list in listOf(p.astronomicalDark, p.nauticalDark, p.moonlessDark)) {
                assertTrue(list.all { it.startMs >= start && it.endMs <= start + day && it.endMs > it.startMs })
                assertTrue(list.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
            }
            assertTrue("moonless ⊆ astro", covered(p.moonlessDark, p.astronomicalDark))
            assertTrue("astro ⊆ nautical", covered(p.astronomicalDark, p.nauticalDark))
            assertTrue(p.moonIllumination in 0.0..1.0)
        }
    }

    private fun covered(inner: List<Interval>, outer: List<Interval>) =
        inner.all { i -> outer.any { o -> i.startMs >= o.startMs - 60_000 && i.endMs <= o.endMs + 60_000 } }

    @Test
    fun galacticCoreIsOverheadFromTheSouthAndLowFromTheNorth() {
        val (s, e) = noonToNoon(2026, 6, 20, 149.0)
        val south = NightSky.galacticCentreNight(-30.0, 149.0, s, e)
        assertTrue("best ${south.bestAltitudeDeg}", south.bestAltitudeDeg!! > 80)
        val (s2, e2) = noonToNoon(2026, 6, 20, 10.0)
        val north = NightSky.galacticCentreNight(60.0, 10.0, s2, e2)
        assertTrue((north.bestAltitudeDeg ?: 0.0) < 2.0)
    }

    @Test
    fun extremePlacesDoNotBreakThePlanner() {
        for ((lat, lon) in listOf(90.0 to 0.0, -90.0 to 0.0, 0.0 to 180.0, 0.0 to -180.0, 89.99 to 179.99, -66.56 to -180.0)) {
            val (s, e) = noonToNoon(2026, 12, 21, lon)
            NightSky.plan(lat, lon, s, e)
            for (planet in Planet.entries) NightSky.planetNight(planet, lat, lon, s, e)
            NightSky.polarAlignment(lat, lon, s)
        }
    }

    // ------------------------------------------------------------------ polar alignment

    /** Find the instant in a day when [f] is largest, to 1 minute. */
    private fun argMax(start: Long, f: (Long) -> Double): Long {
        var best = start
        var t = start
        while (t < start + day) {
            if (f(t) > f(best)) best = t
            t += 60_000L
        }
        return best
    }

    @Test
    fun polarisSitsAboveThePoleAtUpperCulmination() {
        val start = utc(2026, 9, 29)
        val t = argMax(start) { HorizontalTransform.toHorizontal(it, 45.0, 10.0, BrightStars.all.first { s -> s.hr == 424 }.apparent(JulianDay.ephemeris(it))).altitudeDeg }
        val pa = NightSky.polarAlignment(45.0, 10.0, t)!!
        assertEquals("Polaris", pa.starLabel)
        assertEquals(0.0, angleDiff(0.0, pa.angleFromUpDeg), 1.0)
        assertEquals(12.0, if (pa.clockHour > 11.5) pa.clockHour else pa.clockHour + 12, 0.1)
        // Polaris is 0.6–0.7° from the pole in the 2020s and closing (nearest around 2100).
        assertTrue(pa.poleDistanceDeg in 0.60..0.68)
        assertEquals(45.0, pa.poleAltitudeDeg, 0.0)
    }

    /** Six hours after culmination Polaris is west of the pole: on the left when you face north (9 o'clock). */
    @Test
    fun polarisMovesAnticlockwiseFacingNorth() {
        val start = utc(2026, 9, 29)
        val polaris = BrightStars.all.first { it.hr == 424 }
        val culm = argMax(start) { HorizontalTransform.toHorizontal(it, 45.0, 10.0, polaris.apparent(JulianDay.ephemeris(it))).altitudeDeg }
        val t = culm + (6 * 3_590_170L) // six sidereal hours
        val pa = NightSky.polarAlignment(45.0, 10.0, t)!!
        assertEquals(270.0, pa.angleFromUpDeg, 1.5)
        val az = HorizontalTransform.toHorizontal(t, 45.0, 10.0, polaris.apparent(JulianDay.ephemeris(t))).azimuthDeg
        assertTrue("west of north: $az", az in 355.0..360.0)
    }

    /** Facing south, σ Octantis moves clockwise: six hours after culmination it is west of the pole, on the right. */
    @Test
    fun sigmaOctantisMovesClockwiseFacingSouth() {
        val start = utc(2026, 9, 29)
        val sigma = BrightStars.all.first { it.hr == 7228 }
        val culm = argMax(start) { HorizontalTransform.toHorizontal(it, -33.0, 151.0, sigma.apparent(JulianDay.ephemeris(it))).altitudeDeg }
        val t = culm + (6 * 3_590_170L)
        val pa = NightSky.polarAlignment(-33.0, 151.0, t)!!
        assertEquals("Polaris Australis", pa.starLabel)
        assertEquals(90.0, pa.angleFromUpDeg, 1.5)
        val az = HorizontalTransform.toHorizontal(t, -33.0, 151.0, sigma.apparent(JulianDay.ephemeris(t))).azimuthDeg
        assertTrue("west of south: $az", az in 180.0..185.0)
    }

    @Test
    fun noPolarAlignmentOnTheEquator() {
        assertNull(NightSky.polarAlignment(0.0, 0.0, utc(2026, 1, 1)))
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    fun catalogueIsCleanAndComplete() {
        val stars = BrightStars.all
        assertEquals(284, stars.size)
        assertEquals(stars.size, stars.map { it.hr }.toSet().size)
        assertTrue(stars.all { it.raJ2000Deg in 0.0..<360.0 && it.decJ2000Deg in -90.0..90.0 })
        assertTrue(stars.filter { it.hr != 7228 }.all { it.vmag < 3.5 })
        assertEquals(stars.sortedBy { it.vmag }, stars)
        assertTrue("T CrB is a nova listed at maximum", stars.none { it.hr == 5958 })
        assertEquals("Sirius", stars.first().label)
        assertEquals(stars.mapNotNull { it.name }.size, stars.mapNotNull { it.name }.toSet().size)
    }

    @Test
    fun bayerDesignationsBecomeGreek() {
        assertEquals("γ Per", Bayer.greek("Gam Per"))
        assertEquals("α² Cen", Bayer.greek("Alp2Cen"))
        assertEquals("π³ Ori", Bayer.greek("Pi 3Ori"))
        assertEquals("μ UMa", Bayer.greek("Mu  UMa"))
        assertNull(Bayer.greek(""))
        assertNull(Bayer.greek("Foo Bar Baz"))
        assertTrue(BrightStars.all.all { it.label.isNotBlank() })
    }

    /** Proper motion is applied per year: Arcturus (−1.09″, −2.00″ a year) moves about 1.5′ between 2000 and 2040. */
    @Test
    fun properMotionMovesFastStars() {
        val arcturus = BrightStars.all.first { it.name == "Arcturus" }
        val jde = JulianDay.J2000 + 40 * 365.25
        val moved = arcturus.apparent(jde)
        val still = ApparentPlace.fromJ2000(arcturus.raJ2000Deg, arcturus.decJ2000Deg, jde)
        val sepArcsec = Math.toDegrees(
            kotlin.math.acos(
                (sin(moved.decDeg * DEG) * sin(still.decDeg * DEG) +
                    kotlin.math.cos(moved.decDeg * DEG) * kotlin.math.cos(still.decDeg * DEG) * kotlin.math.cos((moved.raDeg - still.raDeg) * DEG)).coerceIn(-1.0, 1.0),
            ),
        ) * 3600
        assertEquals(40 * kotlin.math.hypot(1.093, 1.998), sepArcsec, 2.0)
    }

    // ------------------------------------------------------------------ sky chart projection

    @Test
    fun skyChartFollowsThePlanisphereConvention() {
        // Facing south (the northern default): south at the bottom, north at the top, east on the LEFT.
        val f = SkyProjection.defaultFacing(45.0)
        assertEquals(180.0, f, 0.0)
        val south = SkyProjection.project(180.0, 0.0, f)!!
        assertEquals(0.0, south.x, 1e-9); assertEquals(1.0, south.y, 1e-9)
        val north = SkyProjection.project(0.0, 0.0, f)!!
        assertEquals(-1.0, north.y, 1e-9)
        val east = SkyProjection.project(90.0, 0.0, f)!!
        assertEquals(-1.0, east.x, 1e-9)
        val west = SkyProjection.project(270.0, 0.0, f)!!
        assertEquals(1.0, west.x, 1e-9)
        // Facing north, east is on the right, as it is for a person standing there.
        assertEquals(1.0, SkyProjection.project(90.0, 0.0, 0.0)!!.x, 1e-9)
        assertEquals(0.0, SkyProjection.defaultFacing(-33.0), 0.0)
    }

    @Test
    fun skyChartRadiusIsZenithDistance() {
        val z = SkyProjection.project(123.0, 90.0, 0.0)!!
        assertEquals(0.0, kotlin.math.hypot(z.x, z.y), 1e-9)
        val p = SkyProjection.project(40.0, 45.0, 10.0)!!
        assertEquals(0.5, kotlin.math.hypot(p.x, p.y), 1e-9)
        assertNull(SkyProjection.project(10.0, -0.1, 0.0))
        assertNull(SkyProjection.project(Double.NaN, 10.0, 0.0))
        assertNull(SkyProjection.project(10.0, 10.0, Double.POSITIVE_INFINITY))
        assertEquals(0.0, kotlin.math.hypot(SkyProjection.project(0.0, 95.0, 0.0)!!.x, SkyProjection.project(0.0, 95.0, 0.0)!!.y), 1e-9)
    }

    @Test
    fun theMoonIsLitOnTheOtherSideSouthOfTheEquator() {
        assertTrue(LunarPhase.litOnRight(waxing = true, latDeg = 51.5))
        assertFalse(LunarPhase.litOnRight(waxing = false, latDeg = 51.5))
        assertFalse(LunarPhase.litOnRight(waxing = true, latDeg = -33.9))
        assertTrue(LunarPhase.litOnRight(waxing = false, latDeg = -33.9))
        assertTrue(LunarPhase.litOnRight(waxing = true, latDeg = 0.0))
    }
}
