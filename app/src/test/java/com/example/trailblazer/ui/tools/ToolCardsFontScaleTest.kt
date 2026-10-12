package com.example.trailblazer.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.components.Backdrop
import com.example.trailblazer.ui.nav.NavigationState
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.ToolsRoute
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val toolBodies = listOf(
    "Level & tilt" to "Bubble level, and pitch, roll and gradient for vehicles",
    "Altimeter" to "Barometric altitude, calibration, boiling point, density altitude",
    "Stargazing" to "Dark-sky times, planets, sky chart, meteor showers and polar alignment",
    "SOS" to "Torch morse, distress whistle and screen signal",
    "Sensors" to "Every sensor on this phone, satellites, 3D motion plot and sound meter",
)

/**
 * The tool cards were a fixed 180 dp, which clips their text once the user's font size is large. They must grow to
 * fit the text, and the two cards of a row must stay the same height.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ToolCardsFontScaleTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(fontScale: Float) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                    Backdrop { ToolsScreen(Navigator(NavigationState.single(ToolsRoute))) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun card(title: String): SemanticsNode = rule.onNode(hasClickAction() and hasText(title)).fetchSemanticsNode()

    private fun bodyNode(body: String): SemanticsNode = rule.onNode(hasText(body), useUnmergedTree = true).fetchSemanticsNode()

    private fun assertBodyFits(title: String, body: String) {
        val card = card(title).boundsInRoot
        val text = bodyNode(body)
        assertTrue("$title: text bottom ${text.boundsInRoot.bottom} is past the card bottom ${card.bottom}", text.boundsInRoot.bottom <= card.bottom)
        val layout = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout) }
        assertFalse("$title: the body text overflows its box", layout.single().didOverflowHeight)
    }

    @Test
    fun atTwiceTheFontSizeEveryCardGrowsToFitItsText() {
        show(fontScale = 2f)
        val minHeight = 180 * rule.density.density
        for ((title, body) in toolBodies.take(4)) assertBodyFits(title, body)
        // The long descriptions need more than the old fixed height; the card has to have grown for them.
        val tallest = toolBodies.take(4).maxOf { (title, _) -> card(title).boundsInRoot.height }
        assertTrue("no card grew past the old fixed ${minHeight}px (tallest $tallest)", tallest > minHeight)
    }

    @Test
    fun theTwoCardsOfARowStayTheSameHeight() {
        show(fontScale = 2f)
        assertEquals(card("Level & tilt").boundsInRoot.height, card("Altimeter").boundsInRoot.height, 0.5f)
        assertEquals(card("Stargazing").boundsInRoot.height, card("SOS").boundsInRoot.height, 0.5f)
    }

    @Test
    fun atNormalFontSizeTheCardsKeepTheirOldHeight() {
        show(fontScale = 1f)
        for ((title, body) in toolBodies.take(4)) {
            assertEquals("$title", 180 * rule.density.density, card(title).boundsInRoot.height, 1f)
            assertBodyFits(title, body)
        }
    }

    @Test
    fun aLoneLastCardIsStillReachableAtLargeFonts() {
        show(fontScale = 2f)
        rule.onNode(hasScrollAction()).performScrollTo("Sensors")
        assertBodyFits("Sensors", toolBodies.last().second)
    }

    private fun SemanticsNodeInteraction.performScrollTo(title: String) {
        performScrollToNode(hasClickAction() and hasText(title))
        rule.waitForIdle()
    }
}
