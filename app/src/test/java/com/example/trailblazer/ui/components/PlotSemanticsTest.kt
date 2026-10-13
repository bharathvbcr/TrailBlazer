package com.example.trailblazer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.sky.SkyArc
import com.example.trailblazer.ui.sky.skyTimeAdjust
import com.example.trailblazer.ui.theme.TrailTheme
import com.trailblazer.core.astro.Horizontal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sin

private val hasRangeInfo = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

/**
 * The scrubbable plots take touch input only, which leaves TalkBack with a picture it cannot use. They must also be
 * adjustable (the semantics of a slider: a range and a SetProgress action), and adjusting must move the same readout
 * a finger would.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp")
class PlotSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    private val series = List(48) { i -> 1000.0 + i * 0.3 }

    private fun rangeCurrent(tag: String? = null): Float {
        val node = if (tag == null) rule.onNode(hasRangeInfo) else rule.onNodeWithTag(tag)
        return node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    }

    private fun state(tag: String? = null): String {
        val node = if (tag == null) rule.onNode(hasRangeInfo) else rule.onNodeWithTag(tag)
        return node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    }

    @Test
    fun sparklineIsAdjustableAndAdjustingMovesTheReadout() {
        rule.setContent { Box { Sparkline(series, valueText = { "value %.1f".format(it) }) } }

        rule.onNode(hasRangeInfo).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        // The old description is kept: it is how the plot is named.
        rule.onNode(hasContentDescription("Chart, 48 points")).assertExists()
        assertEquals("nothing picked yet", "No point selected", state())
        assertEquals(0f, rangeCurrent(), 0f)

        rule.onNode(hasRangeInfo).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.waitForIdle()
        // 48 samples are 47 steps apart; halfway rounds to sample 24.
        rule.onNodeWithText("value 1007.2").assertExists()
        assertEquals("value 1007.2", state())
        assertEquals(24f / 47f, rangeCurrent(), 1e-4f)

        rule.onNode(hasRangeInfo).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.waitForIdle()
        rule.onNodeWithText("value 1014.1").assertExists()
        assertEquals(1f, rangeCurrent(), 1e-4f)
    }

    @Test
    fun sparklineRejectsANonNumberAndClampsOutOfRange() {
        rule.setContent { Box { Sparkline(series, valueText = { "value %.1f".format(it) }) } }
        val action = rule.onNode(hasRangeInfo).fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        var accepted = true
        rule.runOnUiThread { accepted = action(Float.NaN) }
        assertFalse("NaN must not move the readout", accepted)
        rule.onAllNodesWithText("value", substring = true).assertCountEquals(0)

        rule.runOnUiThread { accepted = action(7f) }
        rule.waitForIdle()
        assertTrue(accepted)
        rule.onNodeWithText("value 1014.1").assertExists()
        rule.runOnUiThread { action(-3f) }
        rule.waitForIdle()
        rule.onNodeWithText("value 1000.0").assertExists()
    }

    @Test
    fun sparklineWithGapsStepsOverTheSamplesThatExistInsteadOfStalling() {
        // Samples at 0, 4 and 5 only. Stepping by index would sit still on 0 for two steps; stepping by sample cannot.
        rule.setContent { Box { Sparkline(listOf(10.0, null, null, null, 50.0, 60.0), valueText = { "value %.0f".format(it) }) } }
        val node = rule.onNode(hasRangeInfo)
        assertEquals(1, node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.waitForIdle()
        rule.onNodeWithText("value 50").assertExists()
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.waitForIdle()
        rule.onNodeWithText("value 60").assertExists()
    }

    @Test
    fun rangeBarsStepOverTheDaysWithData() {
        rule.setContent {
            Box {
                RangeBars(listOf(1.0, null, 3.0, 4.0), listOf(11.0, null, 13.0, 14.0), emptyList()) { i, _ -> "day $i" }
            }
        }
        rule.onNode(hasRangeInfo).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        rule.onNodeWithText("day 0").assertExists()
        rule.onNode(hasRangeInfo).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.waitForIdle()
        // Days 0, 2 and 3 have data: halfway is the middle one.
        rule.onNodeWithText("day 2").assertExists()
        assertEquals("day 2", state())
    }

    @Test
    fun skyArcIsAdjustableByTheHourAndAdjustingSetsTheTime() {
        val start = 1_790_000_000_000L
        val hour = 3_600_000L
        val path = List(25) { i -> Horizontal(90.0 + i * 7.5, 50.0 * sin(Math.PI * i / 24) - 10) }
        var at = start
        lateinit var fmt: Fmt
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                fmt = Fmt(LocalContext.current, Settings())
                var now by remember { mutableLongStateOf(start) }
                at = now
                SkyArc(
                    path = path, sunPath = path, windowStartMs = start, stepMs = hour, atMs = now, fmt = fmt,
                    lineColor = Color.Yellow, description = "Sun's height through the day",
                    modifier = Modifier.size(300.dp, 150.dp).testTag("arc"),
                    windowEndMs = start + 24 * hour,
                    onAtMs = { now = it },
                ) { _, _, _ -> }
            }
        }
        rule.onNodeWithTag("arc").assert(hasRangeInfo and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        // The description that names the plot survives next to the new semantics.
        rule.onNodeWithTag("arc").assert(hasContentDescription("Sun's height through the day"))
        val info = rule.onNodeWithTag("arc").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        // 24 hours: 25 positions, 23 steps strictly between the ends.
        assertEquals(23, info.steps)
        assertEquals(0f, info.current, 0f)

        rule.onNodeWithTag("arc").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.waitForIdle()
        assertTrue("noon of the window, not $at", abs(at - (start + 12 * hour)) < 1_000)
        assertTrue("the state names the time: ${state("arc")}", state("arc").startsWith(fmt.time(at)))
        assertTrue("and where the body is", state("arc").endsWith("degrees up"))
        assertEquals(0.5f, rangeCurrent("arc"), 1e-3f)

        // One step is one hour, not a continuous slide.
        rule.onNodeWithTag("arc").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f + 1f / 24f + 0.01f) }
        rule.waitForIdle()
        assertTrue("13:00 of the window, not $at", abs(at - (start + 13 * hour)) < 1_000)

        // Night-time: below the horizon is said, not a negative height.
        rule.onNodeWithTag("arc").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.waitForIdle()
        assertTrue(state("arc"), state("arc").endsWith("below the horizon"))
    }

    @Test
    fun skyArcWithoutAHandlerStaysAPlainPicture() {
        val start = 1_790_000_000_000L
        val path = List(25) { i -> Horizontal(0.0, i.toDouble()) }
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                SkyArc(
                    path = path, sunPath = path, windowStartMs = start, stepMs = 3_600_000L, atMs = start,
                    fmt = Fmt(LocalContext.current, Settings()), lineColor = Color.Yellow, description = "Static arc",
                    modifier = Modifier.size(300.dp, 150.dp).testTag("arc"),
                ) { _, _, _ -> }
            }
        }
        val config = rule.onNodeWithTag("arc").fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun horizonCompassCanBeAdjustedThroughTheSameChosenTime() {
        val start = 1_790_000_000_000L
        val hour = 3_600_000L
        val path = List(25) { i -> Horizontal(i * 15.0, 30.0) }
        var at = start
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                val fmt = Fmt(LocalContext.current, Settings())
                var now by remember { mutableLongStateOf(start) }
                at = now
                com.example.trailblazer.ui.sky.HorizonCompass(
                    path = path, riseAzDeg = 90.0, setAzDeg = 270.0, now = null, bodyColor = Color.Yellow,
                    description = "Rises in the E, sets in the W",
                    modifier = Modifier.size(132.dp).testTag("compass").adjustable(
                        skyTimeAdjust(path, start, start + 24 * hour, hour, now, fmt, withDirection = true),
                    ) { now = com.trailblazer.core.plot.SeriesPlot.fractionToEpoch(it, start, start + 24 * hour) },
                ) { _, _, _ -> }
            }
        }
        rule.onNodeWithTag("compass").assert(hasContentDescription("Rises in the E, sets in the W") and hasRangeInfo)
        rule.onNodeWithTag("compass").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        rule.waitForIdle()
        assertTrue("six hours in, not $at", abs(at - (start + 6 * hour)) < 1_000)
        // 6 h is path sample 6: 90 degrees, due east, 30 degrees up.
        assertTrue(state("compass"), state("compass").endsWith("30 degrees up in the E"))
    }

    @Test
    fun snapToStepIsExactForSteppedPlotsAndPassesContinuousOnesThrough() {
        assertEquals(0.37, snapToStep(0.37f, 0)!!, 1e-6)
        assertNull(snapToStep(Float.NaN, 0))
        assertNull(snapToStep(Float.POSITIVE_INFINITY, 5))
        assertEquals(0.0, snapToStep(-1f, 5)!!, 1e-5)
        assertEquals(1.0, snapToStep(2f, 5)!!, 0.0)
        // Four positions (steps = 2 means 0, 1/3, 2/3, 1): 0.4 is nearest 1/3.
        assertEquals(1.0 / 3, snapToStep(0.4f, 2)!!, 1e-5)
        // Truncating a snapped fraction to a slot must land on the slot, whatever the float error.
        for (count in 2..200) {
            for (slot in 0 until count) {
                val f = snapToStep(slot / (count - 1f), count - 2)!!
                assertEquals("count $count slot $slot", slot, com.trailblazer.core.plot.SeriesPlot.scrubIndex(f, count))
            }
        }
    }

    @Test
    fun dialMarkersAreSpokenWithTheirBearings() {
        assertNull(dialMarkerText(emptyList()))
        val text = dialMarkerText(
            listOf(
                DialMarker(120.4, Color.Yellow, "Sun"),
                DialMarker(-60.0, Color.Gray, "Moon"),
                DialMarker(360.0, Color.Red, "Camp"),
                DialMarker(Double.NaN, Color.Red, "Lost"),
            ),
        )
        assertEquals("Sun at 120 degrees, Moon at 300 degrees, Camp at 0 degrees, Lost, direction unknown", text)
        assertNotNull(text)
    }
}
