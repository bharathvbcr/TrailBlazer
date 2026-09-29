package com.example.trailblazer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Launches the real activity on a Robolectric device that has no sensors, no location permission and
 * no network: every reading must say why it is unavailable, and nothing may be requested at launch.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWithoutRequestingPermissionsAndShowsHonestStates() {
        rule.onNodeWithText("Mark waypoint").assertIsDisplayed()
        assertNull("no permission dialog at launch", shadowOf(rule.activity).lastRequestedPermission)
        // Lazy list: check the top card before scrolling it out of composition.
        assertTrue(rule.onAllNodesWithText("No compass on this device").fetchSemanticsNodes().isNotEmpty())
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("No barometer on this device"))
        assertTrue(rule.onAllNodesWithText("No barometer on this device").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun tabsNavigateAndBackReturnsToNow() {
        rule.onAllNodesWithText("Sky").onFirst().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Online forecast").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Change place").fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("Tools").onFirst().performClick()
        rule.onNodeWithText("Level & tilt").performClick()
        rule.onNodeWithText("Set zero").assertIsDisplayed()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Altimeter").assertIsDisplayed()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Mark waypoint").assertIsDisplayed()
    }

    @Test
    fun navigationSurvivesActivityRecreation() {
        rule.onAllNodesWithText("Tools").onFirst().performClick()
        rule.onNodeWithText("Level & tilt").performClick()
        rule.onNodeWithText("Set zero").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Set zero").assertIsDisplayed()
    }

    @Test
    fun sosSignalStopsWhenTheAppLeavesTheForeground() {
        rule.onAllNodesWithText("Tools").onFirst().performClick()
        rule.onNodeWithText("SOS").performClick()
        rule.onNodeWithText("Screen").performClick()
        rule.onNodeWithText("Stop").assertIsDisplayed()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Choose a signal").assertIsDisplayed()
        rule.onNodeWithText("Screen").assertIsDisplayed()
    }

    @Test
    fun bundledDeveloperDocsOpenFromSettings() {
        rule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Developer docs"))
        rule.onNodeWithText("Developer docs").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("ARCHITECTURE").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("ARCHITECTURE").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Modules").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun forecastIsOffUntilTheUserTurnsItOn() {
        rule.onAllNodesWithText("Sky").onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Turn on forecast…"))
        rule.onNodeWithText("Turn on forecast…").assertIsDisplayed()
    }
}
