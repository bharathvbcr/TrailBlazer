package com.example.trailblazer.ui

import android.os.Bundle
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The process being killed while the app is in the background: the activity's saved instance state is all that
 * comes back, with a new activity, a new ViewModelStore and no ViewModel kept. Each test builds that situation
 * the way the system does: save, destroy, then create a new activity from the saved Bundle.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    @get:Rule
    val rule = createEmptyComposeRule()

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

    private fun start(saved: Bundle? = null): ActivityController<MainActivity> {
        val c = Robolectric.buildActivity(MainActivity::class.java)
        if (saved == null) c.setup() else c.create(saved).start().restoreInstanceState(saved).resume().visible()
        return c
    }

    /** Backgrounds [c], saves its state and destroys it; nothing of the old activity or its ViewModels survives. */
    private fun kill(c: ActivityController<MainActivity>): Bundle {
        val out = Bundle()
        c.pause().saveInstanceState(out).stop().destroy()
        return out
    }

    private fun pill(label: String) = rule.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("bottomPill"))).onFirst()

    @Test
    fun theSkyDayAndTheTabComeBackAfterTheProcessIsKilled() {
        val first = start()
        pill("Sky").performClick()
        until("the Sky screen") { described("Next day") }
        rule.onAllNodesWithContentDescription("Next day").onFirst().performClick()
        until("tomorrow") { shown("Today") }

        val saved = kill(first)

        start(saved)
        until("the Sky screen restored") { described("Next day") }
        assertTrue("the day stepped to must come back with the process", shown("Today"))
    }

    @Test
    fun anUnsavedTripEditComesBackAfterTheProcessIsKilled() {
        val first = start()
        pill("Trips").performClick()
        until("the Trips screen") { shown("New trip") }
        rule.onAllNodesWithText("New trip").onFirst().performClick()
        until("the editor") { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Alpine loop")
        until("the typed name") { shown("Alpine loop") }

        val saved = kill(first)

        start(saved)
        until("the editor restored") { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        until("the name restored") { shown("Alpine loop") }
        assertEquals("one text field, holding the edit", 1, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }
}
