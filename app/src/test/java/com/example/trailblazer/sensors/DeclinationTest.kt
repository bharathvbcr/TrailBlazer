package com.example.trailblazer.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.WorldMagneticModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DeclinationTest {

    @Before
    @After
    fun resetFallbackFlag() {
        Declination.forceSystemFallback = false
    }

    private fun createFix(
        lat: Double = 46.5582,
        lon: Double = 7.8352,
        altM: Double? = 1200.0,
        timeMs: Long = 1775000000000L, // 2026-03-31 (within WMM2025 epoch)
    ): Fix = Fix(
        position = LatLon(lat, lon),
        accuracyM = 5.0,
        altitudeM = altM,
        altitudeDatum = AltitudeDatum.Ellipsoid,
        verticalAccuracyM = 3.0,
        speedMps = 1.2,
        bearingDeg = 45.0,
        timeMs = timeMs,
        provider = "gps",
    )

    @Test
    fun bundledModelCalculatesDeclinationForCurrentFix() {
        val fix = createFix()
        val declination = Declination.degrees(fix)

        // Verify it matches WorldMagneticModel directly
        val expected = WorldMagneticModel.declination(
            fix.position.lat,
            fix.position.lon,
            fix.altitudeM ?: 0.0,
            fix.timeMs,
        )
        assertEquals(expected, declination, 1e-9)
        assertTrue(declination.isFinite())
    }

    @Test
    fun fallbackGracefullyToSystemWhenForced() {
        val fix = createFix()
        val systemVal = Declination.systemGeomagneticField(
            fix.position.lat,
            fix.position.lon,
            fix.altitudeM ?: 0.0,
            fix.timeMs,
        )

        Declination.forceSystemFallback = true
        val result = Declination.degrees(fix)
        assertEquals(systemVal, result, 1e-9)
    }

    @Test
    fun fallbackGracefullyWhenDateIsOutsideEpoch() {
        // Date in 2022 (Unix ms ~1650000000000L), outside WMM2025 epoch
        val oldFix = createFix(timeMs = 1650000000000L)
        val systemExpected = Declination.systemGeomagneticField(
            oldFix.position.lat,
            oldFix.position.lon,
            oldFix.altitudeM ?: 0.0,
            oldFix.timeMs,
        )

        val result = Declination.degrees(oldFix)
        assertEquals(systemExpected, result, 1e-9)
    }

    @Test
    fun bundledModelAndSystemFieldAreConsistentWithinPlausibleDrift() {
        val fix = createFix(lat = 37.7749, lon = -122.4194) // San Francisco
        val bundledDecl = Declination.degrees(fix)
        val systemDecl = Declination.systemGeomagneticField(
            fix.position.lat,
            fix.position.lon,
            fix.altitudeM ?: 0.0,
            fix.timeMs,
        )

        // Differences between WMM2020 (in system firmware) and WMM2025 (bundled) are typically < 0.5°
        val diff = abs(bundledDecl - systemDecl)
        assertTrue("Difference between bundled and system declination ($diff°) should be reasonable", diff < 1.0)
    }

    @Test
    fun trueHeadingUsesDeclinationProperly() {
        val heading = Heading(
            magneticDeg = 100.0,
            declinationDeg = 5.5,
            source = HeadingSource.RotationVector,
            accuracyDeg = 1.0,
            upright = false,
        )
        assertEquals(105.5, heading.trueDeg!!, 1e-6)
    }
}
