package com.example.trailblazer.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingLevelTest {

    @Test
    fun headingIsLevelWithinThresholdWhenFlat() {
        val level = Heading(
            magneticDeg = 45.0,
            declinationDeg = 5.0,
            source = HeadingSource.RotationVector,
            accuracyDeg = 3.0,
            upright = false,
            pitchDeg = 1.2,
            rollDeg = -0.8,
        )
        assertTrue("within 2 degrees should be level", level.isLevel)
        assertEquals(50.0, level.trueDeg ?: 0.0, 1e-6)
    }

    @Test
    fun headingIsNotLevelBeyondThreshold() {
        val tiltedPitch = Heading(
            magneticDeg = 45.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = 3.0,
            upright = false,
            pitchDeg = 2.5,
            rollDeg = 0.0,
        )
        assertFalse("pitch beyond 2 degrees is not level", tiltedPitch.isLevel)

        val tiltedRoll = Heading(
            magneticDeg = 45.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = 3.0,
            upright = false,
            pitchDeg = 0.0,
            rollDeg = -3.2,
        )
        assertFalse("roll beyond 2 degrees is not level", tiltedRoll.isLevel)
    }

    @Test
    fun uprightHeadingIsNeverLevel() {
        val upright = Heading(
            magneticDeg = 45.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = 3.0,
            upright = true,
            pitchDeg = 0.5,
            rollDeg = 0.5,
        )
        assertFalse("upright / camera mode is never flat level", upright.isLevel)
    }

    @Test
    fun tiltDegComputesMagnitude() {
        val h = Heading(
            magneticDeg = 0.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = null,
            upright = false,
            pitchDeg = 3.0,
            rollDeg = 4.0,
        )
        assertEquals(5.0, h.tiltDeg, 1e-6)
    }

    @Test
    fun levelThresholdBoundaryChecks() {
        val exactBoundary = Heading(
            magneticDeg = 0.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = null,
            upright = false,
            pitchDeg = 2.0,
            rollDeg = -2.0,
        )
        assertTrue("exact 2.0 degrees is level", exactBoundary.isLevel)

        val justOver = Heading(
            magneticDeg = 0.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = null,
            upright = false,
            pitchDeg = 2.05,
            rollDeg = 0.0,
        )
        assertFalse("2.05 degrees is not level", justOver.isLevel)
    }

    @Test
    fun pixel10ProXlCameraBumpTiltCalibratesToLevel() {
        // Physical resting angle of a Pixel device (Pixel 10 Pro XL) on a flat table due to the camera visor
        val restingOnTable = Heading(
            magneticDeg = 120.0,
            declinationDeg = 2.5,
            source = HeadingSource.RotationVector,
            accuracyDeg = 2.0,
            upright = false,
            pitchDeg = 3.2, // ~3.2° tilt from the camera visor
            rollDeg = 0.1,
            pitchOffsetDeg = 0.0,
            rollOffsetDeg = 0.0,
        )
        assertFalse("Uncalibrated Pixel on a table is seen as tilted", restingOnTable.isLevel)
        assertFalse(restingOnTable.isCalibrated)
        assertEquals(3.2, restingOnTable.rawTiltDeg, 0.01)

        // Once calibrated by setting zero on the table:
        val calibrated = restingOnTable.copy(pitchOffsetDeg = 3.2, rollOffsetDeg = 0.1)
        assertTrue("Calibrated Pixel on table is recognized as level", calibrated.isLevel)
        assertTrue(calibrated.isCalibrated)
        assertEquals(0.0, calibrated.calibratedPitchDeg, 1e-9)
        assertEquals(0.0, calibrated.calibratedRollDeg, 1e-9)
        assertEquals(0.0, calibrated.tiltDeg, 1e-9)
        assertEquals(3.2, calibrated.rawTiltDeg, 0.01)
    }

    @Test
    fun caseTiltWithBothPitchAndRollOffsets() {
        val bumpyCase = Heading(
            magneticDeg = 0.0,
            declinationDeg = null,
            source = HeadingSource.RotationVector,
            accuracyDeg = 1.5,
            upright = false,
            pitchDeg = 2.8,
            rollDeg = -1.9,
            pitchOffsetDeg = 2.5,
            rollOffsetDeg = -2.0,
        )
        assertTrue(bumpyCase.isCalibrated)
        assertEquals(0.3, bumpyCase.calibratedPitchDeg, 1e-6)
        assertEquals(0.1, bumpyCase.calibratedRollDeg, 1e-6)
        assertTrue("Effective tilt within 2° threshold is level", bumpyCase.isLevel)
    }
}
