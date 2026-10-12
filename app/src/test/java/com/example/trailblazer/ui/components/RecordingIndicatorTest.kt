package com.example.trailblazer.ui.components

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.container
import com.example.trailblazer.sensors.LoggerState
import com.example.trailblazer.sensors.robolectricAccelerometer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

/** A sensor recording started anywhere shows "Recording sensors · time · Stop" on every screen, and Stop works there. */
@RunWith(AndroidJUnit4::class)
class RecordingIndicatorTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theIndicatorFollowsTheUserToOtherScreensAndStopsTheRecording() {
        shadowOf(rule.activity.getSystemService(SensorManager::class.java)).addSensor(robolectricAccelerometer())
        val logger = rule.activity.container.sensorLogger
        rule.onNodeWithTag("recordingIndicator").assertDoesNotExist()

        assertNull(logger.start())
        rule.waitForIdle()
        rule.onNodeWithTag("recordingIndicator").assertIsDisplayed()
        rule.onNodeWithContentDescription("Stop recording sensors").assertIsDisplayed()

        // A screen that has nothing to do with Diagnostics.
        rule.onAllNodesWithText("Sky").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("recordingIndicator").assertIsDisplayed()

        rule.onNodeWithContentDescription("Stop recording sensors").performClick()
        repeat(500) {
            rule.waitForIdle()
            if (logger.state.value is LoggerState.Finished) return@repeat
            Thread.sleep(20)
        }
        assertTrue("stopped from the indicator", logger.state.value is LoggerState.Finished)
        rule.waitForIdle()
        rule.onNodeWithTag("recordingIndicator").assertDoesNotExist()
    }

}
