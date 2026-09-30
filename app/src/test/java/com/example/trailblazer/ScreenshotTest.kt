package com.example.trailblazer

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

/**
 * Renders the main screens with Robolectric's native graphics in light, dark and night-red, saves them to
 * app/build/reports/screens/ for inspection, and checks every piece of text by its pixels: black text on a dark
 * card (or white on white) has no contrast inside its own bounds and fails, whatever caused it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val out = File("build/reports/screens").apply { mkdirs() }
    private val problems = ArrayList<String>()
    private var checked = 0

    private fun theme(mode: ThemeMode, nightRed: Boolean) = runBlocking {
        ApplicationProvider.getApplicationContext<TrailApp>().container.prefs.update {
            it.copy(theme = mode, dynamicColor = false, nightRed = nightRed)
        }
    }

    private fun luminance(argb: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /**
     * Checks each visible, enabled text node: the darkest and lightest pixels inside it must differ by a contrast
     * ratio of at least 1.8 (WCAG-style, either direction, so dark text on a light button passes too). Text that sits
     * under the frosted top bar or the floating tab pill is skipped, since the chrome covers it by design.
     */
    private fun checkText(screen: String) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        val topChrome = 120 * density
        val bottomChrome = root.bottom - 110 * density
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        for (i in 0 until count) {
            val node = nodes[i]
            val info = node.fetchSemanticsNode()
            val text = info.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }.orEmpty()
            val b = info.boundsInRoot
            if (text.isBlank() || b.width < 4 || b.height < 4) continue
            if (b.top < topChrome) continue
            if (b.bottom > bottomChrome || b.top < 0) continue
            if (SemanticsProperties.Disabled in info.config || info.parentDisabled()) continue
            val img = runCatching { node.captureToImage().asAndroidBitmap() }.getOrNull() ?: continue
            var lo = 1.0
            var hi = 0.0
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val l = luminance(img.getPixel(x, y))
                if (l < lo) lo = l
                if (l > hi) hi = l
            }
            checked++
            val contrast = (hi + 0.05) / (lo + 0.05)
            if (contrast < 1.8) problems += "$screen: '$text' darkest=${"%.3f".format(lo)} lightest=${"%.3f".format(hi)} contrast=${"%.2f".format(contrast)}"
        }
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.parentDisabled(): Boolean {
        var p = parent
        while (p != null) {
            if (SemanticsProperties.Disabled in p.config) return true
            p = p.parent
        }
        return false
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        checkText(name)
    }

    private fun tour(prefix: String) {
        shot("$prefix-now")
        rule.onAllNodesWithText("Sky").onFirst().performClick()
        shot("$prefix-sky")
        rule.onAllNodesWithText("Trips").onFirst().performClick()
        shot("$prefix-trips")
        rule.onAllNodesWithText("Tools").onFirst().performClick()
        shot("$prefix-tools")
        rule.onAllNodesWithText("Level & tilt").onFirst().performClick()
        shot("$prefix-level")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        shot("$prefix-settings")
        // Scrolled: content now passes under the seamless header, whose scrim must keep the title readable.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Developer docs"))
        rule.mainClock.advanceTimeBy(1_000)
        shot("$prefix-settings-scrolled")
        val title = rule.onAllNodesWithText("Settings", useUnmergedTree = true).onFirst().captureToImage().asAndroidBitmap()
        var lo = 1.0
        var hi = 0.0
        for (y in 0 until title.height) for (x in 0 until title.width) {
            val l = luminance(title.getPixel(x, y)); if (l < lo) lo = l; if (l > hi) hi = l
        }
        assertTrue("$prefix: header title contrast ${(hi + 0.05) / (lo + 0.05)} over scrolled content", (hi + 0.05) / (lo + 0.05) >= 3.0)
        assertTrue("no text was checked", checked > 20)
        assertTrue("Unreadable text:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun dark() { theme(ThemeMode.Dark, false); tour("dark") }
    @Test fun light() { theme(ThemeMode.Light, false); tour("light") }
    @Test fun nightRed() { theme(ThemeMode.Dark, true); tour("red") }
}
