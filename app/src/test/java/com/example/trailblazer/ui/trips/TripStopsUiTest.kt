package com.example.trailblazer.ui.trips

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The new-trip page: add stops without a hidden menu, see each leg between stops, and undo a removal. */
@RunWith(AndroidJUnit4::class)
class TripStopsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun count(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /** Adds a stop through the place picker: coordinates are read on the phone, nothing is searched. */
    private fun paste(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Find a place"))
        rule.onNodeWithText("Find a place").performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput(text) // the picker field, after the trip name
        rule.onNodeWithText("Go").performClick()
        rule.waitForIdle()
    }

    @Test
    fun addStopsSeeTheLegAndUndoARemoval() {
        rule.onAllNodesWithText("Trips").onFirst().performClick()
        rule.onNodeWithText("New trip").performClick()
        rule.waitForIdle()
        assertTrue("the add actions are visible without a menu", count("Find a place") > 0 && count("Here") > 0)
        assertTrue("the first prompt asks for the start", count("Add the start") == 1)

        paste("46.5582, 7.8352")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add the destination")) // throws if the prompt did not change
        paste("46.0207, 7.7491")

        // Grindelwald to Zermatt: about 60 km in a straight line, shown between the two stops.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(" km · ", substring = true))
        assertEquals("one leg between two stops", 1, count(" km · "))

        rule.onAllNodesWithContentDescription("Remove stop").onLast().performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Undo"))
        assertEquals("one stop left, no leg", 0, count(" km · "))
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(" km · ", substring = true))
        assertEquals("the stop and its leg are back", 1, count(" km · "))
        assertEquals("the undo is used up", 0, count("Undo"))
    }
}
