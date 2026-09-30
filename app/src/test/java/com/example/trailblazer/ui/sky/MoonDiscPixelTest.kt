package com.example.trailblazer.ui.sky

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The lit share of a Moon disc's area is exactly its illuminated fraction (half a disc plus or minus half an
 * ellipse), so the drawing can be checked by counting lit pixels: a 90 % gibbous must not be drawn as a crescent.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoonDiscPixelTest {
    @get:Rule
    val rule = createComposeRule()

    private data class Measured(val litFraction: Double, val litRightShare: Double)

    private fun measure(illumination: Double, litOnRight: Boolean): Measured {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Light, dynamicColor = false, nightRed = false) {
                MoonDisc(illumination, litOnRight, Modifier.size(120.dp).testTag("moon"))
            }
        }
        val img = rule.onNodeWithTag("moon").captureToImage().asAndroidBitmap()
        val cx = img.width / 2.0
        val cy = img.height / 2.0
        val r = minOf(img.width, img.height) / 2.0 - 2
        var inside = 0
        var lit = 0
        var litRight = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val dx = x + 0.5 - cx
            val dy = y + 0.5 - cy
            if (dx * dx + dy * dy > r * r) continue
            inside++
            val p = img.getPixel(x, y)
            // The lit colour is a warm cream (0xFFF2EBD3); the shaded part is a cool grey from the theme.
            val red = p shr 16 and 0xFF
            val blue = p and 0xFF
            if (red - blue > 18) {
                lit++
                if (dx > 0) litRight++
            }
        }
        return Measured(lit.toDouble() / inside, if (lit == 0) 0.5 else litRight.toDouble() / lit)
    }

    private fun check(k: Double, right: Boolean) {
        val m = measure(k, right)
        assertEquals("lit share of the disc at k=$k", k, m.litFraction, 0.04)
        if (k in 0.05..0.95) assertTrue("k=$k lit on the ${if (right) "right" else "left"}: right share ${m.litRightShare}", if (right) m.litRightShare > 0.5 else m.litRightShare < 0.5)
    }

    @Test fun thinCrescent() = check(0.1, right = true)
    @Test fun wideCrescentLeft() = check(0.3, right = false)
    @Test fun quarter() = check(0.5, right = true)
    @Test fun gibbous() = check(0.86, right = false)
    @Test fun nearlyFull() = check(0.95, right = true)
    @Test fun new() = check(0.0, right = true)
    @Test fun full() = check(1.0, right = true)
}
