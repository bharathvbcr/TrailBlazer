package com.example.trailblazer.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * On a phone with a gesture bar, the end of every tab's list must scroll fully clear of the floating tab pill.
 * Robolectric has no system bars by default, so this gives the window a real 48 dp navigation-bar inset.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class BottomInsetTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val navBarDp = 48

    private fun giveTheWindowAGestureBar() {
        rule.activityRule.scenario.onActivity { a ->
            val px = (navBarDp * a.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px))
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, px))
                .build()
            ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets)
        }
        rule.waitForIdle()
    }

    private fun assertEndOfListClearsThePill(tab: String, lastText: String) {
        rule.onAllNodesWithText(tab).onFirst().performClick()
        rule.waitForIdle()
        val density = rule.density.density
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = rule.onNodeWithTag("bottomPill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the inset did not reach the layout (pill bottom ${pill.bottom / density} dp, window ${root.bottom / density} dp)",
            root.bottom - pill.bottom >= navBarDp * density - 1f,
        )
        repeat(12) { rule.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        rule.waitForIdle()
        val last = rule.onAllNodesWithText(lastText, substring = true, useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }.boundsInRoot
        assertTrue(
            "$tab: '$lastText' ends at ${last.bottom / density} dp but the pill starts at ${pill.top / density} dp",
            last.bottom <= pill.top,
        )
    }

    @Test
    fun nowEndsAbovePill() {
        giveTheWindowAGestureBar()
        assertEndOfListClearsThePill("Now", "No barometer on this device")
    }

    @Test
    fun toolsEndsAbovePill() {
        giveTheWindowAGestureBar()
        assertEndOfListClearsThePill("Tools", "Every sensor on this phone")
    }
}
