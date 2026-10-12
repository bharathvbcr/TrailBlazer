package com.example.trailblazer.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the user chose on a tab is still there when they come back to it. Switching tabs used to clear the back stack,
 * which released the tab's screen state and ViewModel, so the Sky day, place and scroll started over every time.
 */
@RunWith(AndroidJUnit4::class)
class TabStateSurvivalTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun pill(label: String) = rule.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("bottomPill"))).onFirst()

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) = rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            rule.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "$what not within 10 s" }
            Thread.sleep(20)
        }
    }

    private fun openSkyAndStepAhead() {
        pill("Sky").performClick()
        until("the Sky screen") { described("Next day") }
        assertFalse("starts on today", shown("Today"))
        rule.onAllNodesWithContentDescription("Next day").onFirst().performClick()
        until("tomorrow") { shown("Today") }
    }

    @Test
    fun theSkyDayIsStillThereAfterVisitingAnotherTab() {
        openSkyAndStepAhead()

        pill("Trips").performClick()
        until("the Trips tab") { !described("Next day") }
        pill("Sky").performClick()
        until("the Sky screen again") { described("Next day") }

        assertTrue("the day the user stepped to must survive the visit to Trips", shown("Today"))
    }

    @Test
    fun theSkyDaySurvivesAVisitToEveryOtherTab() {
        openSkyAndStepAhead()
        for (tab in listOf("Tools", "Now", "Trips")) {
            pill(tab).performClick()
            until("the $tab tab") { !described("Next day") }
        }
        pill("Sky").performClick()
        until("the Sky screen again") { described("Next day") }
        assertTrue(shown("Today"))
    }
}
