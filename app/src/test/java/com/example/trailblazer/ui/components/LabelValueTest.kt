package com.example.trailblazer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.theme.TrailTheme
import com.example.trailblazer.ui.tools.sensorNumber
import com.example.trailblazer.ui.tools.typeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * The Sensors screen showed "Range / resolution" one letter per line on the Pixel's Camera V-Sync and Step Counter
 * cards: their range is Float.MAX_VALUE, printed as 39 digits, and the row gave the value every pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class LabelValueTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aHugeValueCannotSqueezeTheLabel() {
        val huge = "340282346638528860000000000000000000000 / 0.0000"
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = false) {
                Column(Modifier.width(360.dp).testTag("card")) {
                    LabelValue("Range / resolution", huge)
                    LabelValue("Power", "0.0010 mA")
                }
            }
        }
        val row = rule.onNodeWithTag("card").getBoundsInRoot()
        val label = rule.onNodeWithText("Range / resolution").getBoundsInRoot()
        val value = rule.onNodeWithText(huge).getBoundsInRoot()
        val rowWidth = row.right - row.left
        // The label keeps its natural single-line width (~118 dp; it is shorter than 45 % of the row)…
        assertTrue("label width ${label.right - label.left} of $rowWidth", (label.right - label.left) > 100.dp)
        assertTrue("label is one line, height ${label.bottom - label.top}", (label.bottom - label.top).value < 30f)
        // …and the value wraps in the rest, right of the label, never over it.
        assertTrue(value.left >= label.right)
        assertTrue(value.right <= row.right + 1.dp)
        val bmp = rule.onNodeWithTag("card").captureToImage().asAndroidBitmap()
        File(File("build/reports/screens").apply { mkdirs() }, "label-value-huge.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun aShortValueStillSitsOnTheRightOfALongLabel() {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Light, dynamicColor = false, nightRed = false) {
                Column(Modifier.width(360.dp).testTag("card")) { LabelValue("Sea-level pressure (approx.)", "1013.2 hPa") }
            }
        }
        val row = rule.onNodeWithTag("card").getBoundsInRoot()
        val label = rule.onNodeWithText("Sea-level pressure (approx.)").getBoundsInRoot()
        val value = rule.onNodeWithText("1013.2 hPa").getBoundsInRoot()
        assertEquals(row.right.value, value.right.value, 1f)
        assertTrue("both on one line", (label.bottom - label.top).value < 30f && (value.bottom - value.top).value < 30f)
    }

    @Test
    fun anUnboundedParentGetsBothTextsSideBySide() {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Light, dynamicColor = false, nightRed = false) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(IntrinsicSize.Max).testTag("card")) { LabelValue("Range", "3.4 × 10³⁸") }
                }
            }
        }
        val label = rule.onNodeWithText("Range").getBoundsInRoot()
        val value = rule.onNodeWithText("3.4 × 10³⁸").getBoundsInRoot()
        assertTrue(value.left > label.right)
        assertEquals(label.top.value + (label.bottom - label.top).value / 2, value.top.value + (value.bottom - value.top).value / 2, 2f)
    }

    @Test
    fun sensorNumbersAreReadable() {
        val l = Locale.US
        assertEquals("3.4 × 10³⁸", sensorNumber(Float.MAX_VALUE, l))
        assertEquals("4.3 × 10⁹", sensorNumber(4.2949673E9f, l))
        assertEquals("1.0 × 10⁶", sensorNumber(1e6f, l))
        assertEquals("-2.0 × 10⁷", sensorNumber(-2e7f, l))
        assertEquals("999999", sensorNumber(999_999f, l))
        assertEquals("85.00", sensorNumber(85f, l))
        assertEquals("0.0010", sensorNumber(0.001f, l))
        assertEquals("1.5 × 10⁻⁵", sensorNumber(1.5e-5f, l))
        assertEquals("0.0001", sensorNumber(1e-4f, l))
        assertEquals("0", sensorNumber(0f, l))
        assertEquals("—", sensorNumber(Float.NaN, l))
        assertEquals("—", sensorNumber(Float.POSITIVE_INFINITY, l))
    }

    @Test
    fun vendorSensorsAreNamedFromTheirStringType() {
        assertEquals("Camera vsync (vendor)", typeName(65541, "com.google.sensor.camera_vsync"))
        assertEquals("Pressure temp (vendor)", typeName(65539, "com.google.sensor.pressure_temp"))
        assertEquals("Type 65541", typeName(65541, null))
        assertEquals("Type 65541", typeName(65541, "  "))
        assertEquals("Step counter", typeName(android.hardware.Sensor.TYPE_STEP_COUNTER, "android.sensor.step_counter"))
    }
}
