package com.example.trailblazer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.stars.AdaptRing
import com.example.trailblazer.ui.stars.MoonBadge
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Semantics that said the wrong thing: a chip that is a button doing nothing, and two badges whose picture and text
 * were read twice. A screen reader walks the merged tree, so these assert on that tree.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp")
class MisleadingSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) { content() } }

    @Test
    fun aFactChipIsNotAButtonAndHasNothingToClick() {
        themed { StatusChip("Relative only: no magnetometer") }
        val node = rule.onNodeWithText("Relative only: no magnetometer")
        node.assertHasNoClickAction()
        val config = node.fetchSemanticsNode().config
        assertNull("announced as a button", config.getOrNull(SemanticsProperties.Role))
        // Nothing in its ancestry is clickable either: the text itself is the whole announcement.
        var n = node.fetchSemanticsNode()
        while (n.parent != null) {
            n = n.parent!!
            assertFalse("a clickable ancestor ${n.id}", n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        }
    }

    @Test
    fun moonBadgeIsReadOnceNotAsAPictureAndTwoTexts() {
        themed { Box(Modifier.testTag("badge")) { MoonBadge(0.4) } }
        rule.onAllNodesWithContentDescription("Moon 40 percent lit").assertCountEquals(1)
        // The "40%" and "Moon" texts inside the badge are part of that one reading, not separate stops.
        rule.onAllNodesWithText("40%").assertCountEquals(0)
        rule.onAllNodesWithText("Moon").assertCountEquals(0)
    }

    @Test
    fun moonBadgeClampsAnOutOfRangeIllumination() {
        themed { MoonBadge(1.7) }
        rule.onAllNodesWithContentDescription("Moon 100 percent lit").assertCountEquals(1)
    }

    @Test
    fun adaptRingIsOneReadingWithItsCentreText() {
        themed { AdaptRing(progress = 0.4f, done = false, centre = "12 min") }
        rule.onAllNodesWithContentDescription("Dark adaptation 40 percent, 12 min").assertCountEquals(1)
        rule.onAllNodesWithText("12 min").assertCountEquals(0)
    }

    @Test
    fun adaptRingWhenDoneAndWhenProgressIsOutOfRange() {
        themed { AdaptRing(progress = 3f, done = true, centre = "Ready") }
        rule.onAllNodesWithContentDescription("Dark adaptation 100 percent, Ready").assertCountEquals(1)
    }

    @Test
    fun dialSaysWhereTheSunAndMoonAre() {
        themed {
            Dial(
                headingDeg = 10.0,
                markers = listOf(DialMarker(120.0, Color.Yellow, "Sun"), DialMarker(300.0, Color.Gray, "Moon")),
                modifier = Modifier.size(200.dp).testTag("dial"),
            )
        }
        val config = rule.onNodeWithTag("dial").fetchSemanticsNode().config
        assertEquals(listOf("Compass dial"), config[SemanticsProperties.ContentDescription])
        assertEquals("Sun at 120 degrees, Moon at 300 degrees", config[SemanticsProperties.StateDescription])
    }

    @Test
    fun dialWithNoMarkersHasNoStateToRead() {
        themed { Dial(headingDeg = 10.0, markers = emptyList(), modifier = Modifier.size(200.dp).testTag("dial")) }
        val config = rule.onNodeWithTag("dial").fetchSemanticsNode().config
        assertTrue(config.contains(SemanticsProperties.ContentDescription))
        assertNull(config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNode(hasContentDescription("Compass dial") and SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)).assertExists()
    }
}
