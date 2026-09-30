package com.example.trailblazer.ui.stars

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.components.Backdrop
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.StargazeRoute
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the stargazing screen for a fixed place so its layout and colours can be inspected (app/build/reports/screens/). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class StargazeScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val out = File("build/reports/screens").apply { mkdirs() }

    private fun render(name: String, nightRed: Boolean) {
        val nav = Navigator(NavBackStack<NavKey>(StargazeRoute(39.74, -104.99, "Denver")))
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = nightRed) {
                Backdrop { StargazeScreen(nav, 39.74, -104.99, "Denver") }
            }
        }
        rule.waitUntil(20_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            rule.onAllNodes(hasText("Tonight") or hasContentDescription("Darkness", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        save("$name-top")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Planets tonight", substring = true, ignoreCase = true))
        rule.waitForIdle()
        save("$name-middle")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Dark adaptation", substring = true, ignoreCase = true))
        rule.waitForIdle()
        save("$name-bottom")
        assertTrue(rule.onAllNodesWithText("Polaris", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dark() = render("stars-dark", false)
    @Test fun red() = render("stars-red", true)
}
