package com.example.trailblazer.ui

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system back gesture as Android 14+ delivers it: started, progressed, then either cancelled (the user changed
 * their mind) or committed. A cancelled gesture must leave the screen where it was; a committed one pops it.
 * This characterises the gesture path rather than failing on the old code: NavDisplay already handled it, and the
 * change here is the animation that follows the finger, which is checked on a device.
 */
@RunWith(AndroidJUnit4::class)
class PredictiveBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shown(text: String) = rule.onAllNodesWithText(text, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun gesture(commit: Boolean) {
        rule.activityRule.scenario.onActivity { a ->
            val d = a.onBackPressedDispatcher
            d.dispatchOnBackStarted(BackEventCompat(10f, 900f, 0f, BackEventCompat.EDGE_LEFT))
            for (p in listOf(0.2f, 0.5f, 0.8f)) d.dispatchOnBackProgressed(BackEventCompat(10f + 300 * p, 900f, p, BackEventCompat.EDGE_LEFT))
            if (commit) d.onBackPressed() else d.dispatchOnBackCancelled()
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    fun cancelledGestureStaysCommittedGesturePops() {
        rule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        rule.waitForIdle()
        assertTrue("Settings opened", shown("Developer docs") || shown("Units"))

        gesture(commit = false)
        assertTrue("a cancelled back gesture must not leave Settings", shown("Units"))

        gesture(commit = true)
        assertTrue("a completed back gesture returns to Now", shown("Mark waypoint"))
        assertTrue("and Settings is gone", !shown("Units"))
    }

    @Test
    fun backFromAnotherTabRootGoesToNowBeforeLeaving() {
        rule.onAllNodesWithText("Tools").onFirst().performClick()
        rule.waitForIdle()
        gesture(commit = true)
        assertTrue(shown("Mark waypoint"))
        assertTrue("the app is still open", !rule.activity.isFinishing)
    }
}
