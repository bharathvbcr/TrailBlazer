package com.example.trailblazer.ui.stars

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.skyChartSide
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sky chart is pixels with a tap handler, so TalkBack saw one picture and nothing to pick. Each named object above
 * the horizon is also a focusable target, brightest first, and activating it picks it as a tap does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class SkyChartAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val objects = listOf(
        ChartObject("Polaris", "Polaris", 0.0, 40.0, 2.0, ChartKind.Star),
        ChartObject("Sirius", "Sirius", 180.0, 45.0, -1.4, ChartKind.Star),
        ChartObject("Hidden", "Hidden star", 90.0, -10.0, 0.5, ChartKind.Star),
        ChartObject(null, "Faint star", 200.0, 50.0, 5.0, ChartKind.Star),
    )

    private fun show() {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                val fmt = Fmt(LocalContext.current, Settings())
                val chart = ChartState(0L, objects, null, -30.0)
                var picked by remember { mutableStateOf<String?>(null) }
                Column {
                    SkyChart(chart, 0.0, picked, fmt, { picked = it }, Modifier.size(320.dp))
                    PickedLine(chart, picked, fmt)
                }
            }
        }
        rule.waitForIdle()
    }

    private val target = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)

    @Test
    fun everyNamedObjectAboveTheHorizonIsATargetAndNothingElseIs() {
        show()
        rule.onAllNodes(target).assertCountEquals(2)
        rule.onAllNodes(hasContentDescription("Sirius", substring = true) and target).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription("Polaris", substring = true) and target).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription("Hidden", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("Faint", substring = true)).assertCountEquals(0)
    }

    @Test
    fun targetsAreVisitedBrightestFirstAndSayWhereTheObjectIs() {
        show()
        val sirius = rule.onAllNodes(hasContentDescription("Sirius", substring = true) and target).fetchSemanticsNodes().single().config
        val polaris = rule.onAllNodes(hasContentDescription("Polaris", substring = true) and target).fetchSemanticsNodes().single().config
        assertTrue(sirius[SemanticsProperties.TraversalIndex] < polaris[SemanticsProperties.TraversalIndex])
        assertTrue(sirius[SemanticsProperties.ContentDescription].single().startsWith("Sirius · 45° up"))
    }

    @Test
    fun activatingATargetPicksTheObjectAndTheLineBelowReadsItOut() {
        show()
        rule.onNodeWithText("Tap a dot to name it").assertExists()
        rule.onAllNodes(hasContentDescription("Sirius", substring = true) and target)
            .onFirstNode().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onAllNodes(hasText("Sirius · 45° up", substring = true)).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription("Sirius", substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
    }

    @Test
    fun theChartFitsLandscapeWindowsAndKeepsItsOldSizeOnPortraitOnes() {
        // Landscape phone, 412 dp tall: the old fixed 420 dp chart was taller than the window.
        assertTrue(skyChartSide(412.dp) < 412.dp)
        assertEquals(420.dp, skyChartSide(860.dp))
        assertEquals(420.dp, skyChartSide(10_000.dp))
        // A tiny or odd window never makes it unusably small.
        assertEquals(220.dp, skyChartSide(0.dp))
        assertEquals(220.dp, skyChartSide((-5).dp))
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirstNode() = get(0)
