package com.example.trailblazer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.plot.SkyBody
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Plots live inside scrolling pages. A vertical swipe that starts on a plot must scroll the page (the phone reported
 * "scroll issues on plots or graphs"); a sideways swipe must still scrub the plot.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp")
class PlotGesturesTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var list: LazyListState

    private fun page(plot: @Composable () -> Unit) {
        rule.setContent {
            list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item { Spacer(Modifier.height(200.dp)) }
                item { Box(Modifier.fillMaxWidth().testTag("plot")) { plot() } }
                items(30) { Text("Row $it", Modifier.height(60.dp)) }
            }
        }
    }

    private fun assertVerticalSwipeScrollsThePage() {
        rule.onNodeWithTag("plot").performTouchInput { swipeUp(startY = centerY + 40f, endY = centerY - 200f) }
        rule.waitForIdle()
        assertTrue(
            "a vertical swipe on the plot did not scroll the page (index ${list.firstVisibleItemIndex}, offset ${list.firstVisibleItemScrollOffset})",
            list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 100,
        )
    }

    private val series = List(48) { i -> 1000.0 + i * 0.3 }

    @Test
    fun sparklineLetsThePageScrollAndStillScrubsSideways() {
        page { Sparkline(series, valueText = { "value %.1f".format(it) }) }
        rule.onNodeWithTag("plot").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        // The swipe ends at the right edge: the readout shows one of the last samples.
        rule.onNodeWithText("value 101", substring = true).assertExists()
        assertVerticalSwipeScrollsThePage()
    }

    @Test
    fun rangeBarsLetThePageScroll() {
        page { RangeBars(List(7) { 10.0 + it }, List(7) { 20.0 + it }, emptyList()) { i, _ -> "day $i" } }
        rule.onNodeWithTag("plot").performTouchInput { swipeLeft(startX = right - 5f, endX = left + 5f) }
        rule.onNodeWithText("day 0").assertExists()
        assertVerticalSwipeScrollsThePage()
    }

    @Test
    fun satelliteSkyLetsThePageScroll() {
        page { SatelliteSky(List(12) { SkyBody(it * 30.0, 10.0 + it * 6, it % 2 == 0, "sat $it") }) }
        assertVerticalSwipeScrollsThePage()
    }
}
