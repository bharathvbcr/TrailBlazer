package com.trailblazer.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldMagneticModelTest {

    private data class NoaaTestCase(
        val decimalYear: Double,
        val altKm: Double,
        val latDeg: Double,
        val lonDeg: Double,
        val expectedDeclDeg: Double,
        val expectedInclDeg: Double,
        val expectedH: Double,
        val expectedF: Double,
    )

    private val noaaTestPoints = listOf(
        NoaaTestCase(decimalYear = 2025.0, altKm = 28.0, latDeg = 89.0, lonDeg = -121.0, expectedDeclDeg = -99.77, expectedInclDeg = 88.47, expectedH = 1504.298146, expectedF = 56214.419888),
        NoaaTestCase(decimalYear = 2025.0, altKm = 51.0, latDeg = -33.0, lonDeg = 109.0, expectedDeclDeg = -5.49, expectedInclDeg = -67.5, expectedH = 21838.046477, expectedF = 57054.752538),
        NoaaTestCase(decimalYear = 2025.0, altKm = 66.0, latDeg = 14.0, lonDeg = 143.0, expectedDeclDeg = -0.19, expectedInclDeg = 12.82, expectedH = 35003.635649, expectedF = 35898.700342),
        NoaaTestCase(decimalYear = 2025.5, altKm = 69.0, latDeg = 38.0, lonDeg = -144.0, expectedDeclDeg = 12.93, expectedInclDeg = 56.97, expectedH = 23096.337032, expectedF = 42373.774537),
        NoaaTestCase(decimalYear = 2025.5, altKm = 22.0, latDeg = -37.0, lonDeg = 140.0, expectedDeclDeg = 9.28, expectedInclDeg = -68.62, expectedH = 21688.84759, expectedF = 59492.006517),
        NoaaTestCase(decimalYear = 2026.0, altKm = 74.0, latDeg = -57.0, lonDeg = 3.0, expectedDeclDeg = -22.51, expectedInclDeg = -58.65, expectedH = 14362.206593, expectedF = 27606.226129),
        NoaaTestCase(decimalYear = 2026.0, altKm = 47.0, latDeg = -72.0, lonDeg = -22.0, expectedDeclDeg = -6.32, expectedInclDeg = -61.16, expectedH = 18392.516222, expectedF = 38127.112857),
        NoaaTestCase(decimalYear = 2026.0, altKm = 34.0, latDeg = -19.0, lonDeg = 43.0, expectedDeclDeg = -14.98, expectedInclDeg = -52.33, expectedH = 20212.448819, expectedF = 33076.961273),
        NoaaTestCase(decimalYear = 2026.5, altKm = 44.0, latDeg = -46.0, lonDeg = -42.0, expectedDeclDeg = -11.36, expectedInclDeg = -54.39, expectedH = 14140.316272, expectedF = 24285.511845),
        NoaaTestCase(decimalYear = 2026.5, altKm = 12.0, latDeg = -79.0, lonDeg = 115.0, expectedDeclDeg = -137.58, expectedInclDeg = -77.37, expectedH = 13023.480845, expectedF = 59545.961164),
        NoaaTestCase(decimalYear = 2027.0, altKm = 37.0, latDeg = -66.0, lonDeg = -5.0, expectedDeclDeg = -17.22, expectedInclDeg = -59.04, expectedH = 17159.8365, expectedF = 33360.029814),
        NoaaTestCase(decimalYear = 2027.0, altKm = 57.0, latDeg = -43.0, lonDeg = 50.0, expectedDeclDeg = -48.27, expectedInclDeg = -63.13, expectedH = 16833.417225, expectedF = 37242.759503),
        NoaaTestCase(decimalYear = 2027.0, altKm = 61.0, latDeg = 59.0, lonDeg = -77.0, expectedDeclDeg = -16.48, expectedInclDeg = 78.68, expectedH = 10884.812802, expectedF = 55476.03437),
        NoaaTestCase(decimalYear = 2027.5, altKm = 98.0, latDeg = -5.0, lonDeg = 159.0, expectedDeclDeg = 7.79, expectedInclDeg = -23.22, expectedH = 33857.496691, expectedF = 36841.937629),
        NoaaTestCase(decimalYear = 2027.5, altKm = 96.0, latDeg = -46.0, lonDeg = -85.0, expectedDeclDeg = 17.93, expectedInclDeg = -47.37, expectedH = 19914.457641, expectedF = 29402.458634),
        NoaaTestCase(decimalYear = 2028.0, altKm = 49.0, latDeg = 20.0, lonDeg = 167.0, expectedDeclDeg = 5.1, expectedInclDeg = 26.82, expectedH = 30251.262453, expectedF = 33898.298187),
        NoaaTestCase(decimalYear = 2028.0, altKm = 30.0, latDeg = -36.0, lonDeg = -64.0, expectedDeclDeg = -4.65, expectedInclDeg = -40.08, expectedH = 17398.651081, expectedF = 22738.551012),
        NoaaTestCase(decimalYear = 2028.0, altKm = 45.0, latDeg = -46.0, lonDeg = -41.0, expectedDeclDeg = -11.68, expectedInclDeg = -54.96, expectedH = 13897.562372, expectedF = 24203.798713),
        NoaaTestCase(decimalYear = 2028.5, altKm = 39.0, latDeg = -65.0, lonDeg = -88.0, expectedDeclDeg = 29.45, expectedInclDeg = -60.2, expectedH = 20609.270068, expectedF = 41466.964689),
        NoaaTestCase(decimalYear = 2028.5, altKm = 55.0, latDeg = 86.0, lonDeg = 70.0, expectedDeclDeg = 67.64, expectedInclDeg = 87.57, expectedH = 2370.361097, expectedF = 55976.36393),
        NoaaTestCase(decimalYear = 2029.0, altKm = 95.0, latDeg = -60.0, lonDeg = -59.0, expectedDeclDeg = 8.58, expectedInclDeg = -55.17, expectedH = 18095.598879, expectedF = 31687.013474),
        NoaaTestCase(decimalYear = 2029.0, altKm = 57.0, latDeg = 34.0, lonDeg = -13.0, expectedDeclDeg = -1.89, expectedInclDeg = 45.74, expectedH = 28257.277809, expectedF = 40488.595068),
        NoaaTestCase(decimalYear = 2029.0, altKm = 41.0, latDeg = 42.0, lonDeg = -19.0, expectedDeclDeg = -4.13, expectedInclDeg = 56.44, expectedH = 24503.475397, expectedF = 44319.720622),
        NoaaTestCase(decimalYear = 2029.5, altKm = 51.0, latDeg = -76.0, lonDeg = 40.0, expectedDeclDeg = -56.34, expectedInclDeg = -66.22, expectedH = 18517.441118, expectedF = 45917.898858),
        NoaaTestCase(decimalYear = 2029.5, altKm = 18.0, latDeg = 9.0, lonDeg = -172.0, expectedDeclDeg = 9.24, expectedInclDeg = 15.85, expectedH = 30922.51441, expectedF = 32144.689001),
    )

    @Test
    fun verifyDeclinationAgainstNoaaWmmTestValues() {
        for (test in noaaTestPoints) {
            val elements = WorldMagneticModel.calculate(
                latDeg = test.latDeg,
                lonDeg = test.lonDeg,
                altitudeMeters = test.altKm * 1000.0,
                decimalYear = test.decimalYear,
            )

            // Sub-0.1° declination accuracy anywhere on Earth (NOAA test values match within 0.01°)
            var declError = abs(elements.declinationDeg - test.expectedDeclDeg)
            if (declError > 180.0) declError = 360.0 - declError
            assertTrue(
                declError < 0.05,
                "Declination error $declError exceeds 0.05° at year=${test.decimalYear} lat=${test.latDeg} lon=${test.lonDeg} (got ${elements.declinationDeg}, expected ${test.expectedDeclDeg})",
            )

            // Inclination accuracy within 0.05°
            val inclError = abs(elements.inclinationDeg - test.expectedInclDeg)
            assertTrue(
                inclError < 0.05,
                "Inclination error $inclError exceeds 0.05° at year=${test.decimalYear} lat=${test.latDeg} lon=${test.lonDeg}",
            )

            // Field strength accuracy (H and F within 2 nT of printed rounded test values)
            val hError = abs(elements.horizontalFieldNt - test.expectedH)
            assertTrue(
                hError < 2.0,
                "Horizontal field H error $hError exceeds 2 nT at year=${test.decimalYear} lat=${test.latDeg} lon=${test.lonDeg}",
            )

            val fError = abs(elements.totalFieldNt - test.expectedF)
            assertTrue(
                fError < 2.0,
                "Total field F error $fError exceeds 2 nT at year=${test.decimalYear} lat=${test.latDeg} lon=${test.lonDeg}",
            )
        }
    }

    @Test
    fun verifyNoaaPrimaryStandardPoints() {
        // Official primary NOAA standard test points from WMM2025_TEST_VALUES.txt
        // 2025.0 Sea level, 80°N, 0°E
        val p1 = WorldMagneticModel.calculate(80.0, 0.0, 0.0, 2025.0)
        assertEquals(1.28, p1.declinationDeg, 0.01)
        assertEquals(83.21, p1.inclinationDeg, 0.01)
        assertEquals(6523.2, p1.horizontalFieldNt, 0.5)
        assertEquals(55178.5, p1.totalFieldNt, 0.5)

        // 2025.0 Sea level, Equator (0°N, 120°E)
        val p2 = WorldMagneticModel.calculate(0.0, 120.0, 0.0, 2025.0)
        assertEquals(-0.16, p2.declinationDeg, 0.01)
        assertEquals(-14.93, p2.inclinationDeg, 0.01)
        assertEquals(39677.9, p2.horizontalFieldNt, 0.5)
        assertEquals(41064.3, p2.totalFieldNt, 0.5)

        // 2025.0 Sea level, -80°S, 240°E
        val p3 = WorldMagneticModel.calculate(-80.0, 240.0, 0.0, 2025.0)
        assertEquals(68.78, p3.declinationDeg, 0.01)
        assertEquals(-72.00, p3.inclinationDeg, 0.01)
        assertEquals(16898.1, p3.horizontalFieldNt, 0.5)
        assertEquals(54698.2, p3.totalFieldNt, 0.5)

        // 2027.5 At 100 km altitude, 80°N, 0°E
        val p4 = WorldMagneticModel.calculate(80.0, 0.0, 100_000.0, 2027.5)
        assertEquals(2.16, p4.declinationDeg, 0.01)
        assertEquals(83.29, p4.inclinationDeg, 0.01)
        assertEquals(6201.1, p4.horizontalFieldNt, 0.5)
        assertEquals(53034.3, p4.totalFieldNt, 0.5)

        // 2027.5 Sea level, Equator (0°N, 120°E)
        val p5 = WorldMagneticModel.calculate(0.0, 120.0, 0.0, 2027.5)
        assertEquals(-0.24, p5.declinationDeg, 0.01)
        assertEquals(-14.65, p5.inclinationDeg, 0.01)
        assertEquals(39702.0, p5.horizontalFieldNt, 0.5)
        assertEquals(41036.9, p5.totalFieldNt, 0.5)
    }

    @Test
    fun epochValidationLimits() {
        assertTrue(WorldMagneticModel.isEpochValid(2025.0))
        assertTrue(WorldMagneticModel.isEpochValid(2027.5))
        assertTrue(WorldMagneticModel.isEpochValid(2030.0))
        assertFalse(WorldMagneticModel.isEpochValid(2024.99))
        assertFalse(WorldMagneticModel.isEpochValid(2030.01))
    }

    @Test
    fun timestampToDecimalYearConversion() {
        // 2025-01-01 00:00:00 UTC = 1735689600000 ms
        val ms2025 = 1735689600000L
        val year2025 = WorldMagneticModel.decimalYearFromEpochMillis(ms2025)
        assertEquals(2025.0, year2025, 0.0001)
        assertTrue(WorldMagneticModel.isDateValid(ms2025))

        // 2026-01-01 00:00:00 UTC = 1767225600000 ms
        val ms2026 = 1767225600000L
        val year2026 = WorldMagneticModel.decimalYearFromEpochMillis(ms2026)
        assertEquals(2026.0, year2026, 0.0001)
        assertTrue(WorldMagneticModel.isDateValid(ms2026))

        // An older timestamp in 2022 (outside WMM2025 epoch)
        val ms2022 = 1650000000000L
        assertFalse(WorldMagneticModel.isDateValid(ms2022))
        assertNull(WorldMagneticModel.declinationOrNull(45.0, 10.0, 0.0, ms2022))

        // A valid timestamp in 2026 returns declination
        assertNotNull(WorldMagneticModel.declinationOrNull(45.0, 10.0, 0.0, ms2026))
    }

    @Test
    fun polarBoundarySafety() {
        // Exact 90° and -90° are clamped safely away from singularity
        val northPole = WorldMagneticModel.calculate(90.0, 0.0, 0.0, 2026.0)
        assertTrue(northPole.declinationDeg.isFinite())
        assertTrue(northPole.totalFieldNt.isFinite())

        val southPole = WorldMagneticModel.calculate(-90.0, 0.0, 0.0, 2026.0)
        assertTrue(southPole.declinationDeg.isFinite())
        assertTrue(southPole.totalFieldNt.isFinite())
    }
}
