package com.example.trailblazer.ui.tools

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.testing.awaitMainLooper
import com.example.trailblazer.ui.nav.LevelRoute
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.theme.TrailTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.time.Duration
import kotlin.math.cos
import kotlin.math.sin

/** The real Level screen, fed gravity vectors, with the haptic engine replaced by a recorder. */
@RunWith(AndroidJUnit4::class)
class LevelHapticsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()
    private val manager = app.getSystemService(SensorManager::class.java)
    private val gravity: Sensor = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY)
    private val felt = ArrayList<HapticFeedbackType>()

    private val recorder = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            felt += hapticFeedbackType
        }
    }

    @Before
    fun setUp() {
        // Added before the screen first asks for motion, so the app sees a gravity sensor.
        shadowOf(manager).addSensor(gravity)
        // DataStore is a process-wide singleton, so a setting written by an earlier test is still there: start from on.
        runBlocking { app.container.prefs.update { it.copy(levelHaptics = true) } }
        val nav = Navigator(NavBackStack<NavKey>(LevelRoute))
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = false) {
                CompositionLocalProvider(LocalHapticFeedback provides recorder) { LevelScreen(nav) }
            }
        }
        rule.waitForIdle()
    }

    /** Lays the phone flat with a pitch of [deg] (rotation about its short axis). */
    private fun tilt(deg: Double) {
        val r = Math.toRadians(deg)
        val event = SensorEventBuilder.newBuilder()
            .setSensor(gravity)
            .setValues(floatArrayOf(0f, (9.81 * sin(r)).toFloat(), (9.81 * cos(r)).toFloat()))
            .setTimestamp(System.nanoTime())
            .build()
        shadowOf(manager).sendSensorEventToListeners(event)
        rule.waitForIdle()
    }

    /** Robolectric's uptime clock only moves when the looper is advanced; the cues are rate-limited on it. */
    private fun walk(vararg degrees: Double) = degrees.forEach {
        tilt(it)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
    }

    @Test
    fun findingLevelByFeel() {
        walk(6.0, 4.5, 3.5, 2.5, 1.5, 0.8, 0.1, 0.12, 0.08)
        assertEquals("one confirm on reaching level: $felt", 1, felt.count { it == HapticFeedbackType.Confirm })
        assertTrue("ticks on the way in: $felt", felt.count { it == HapticFeedbackType.SegmentTick } >= 3)
        assertEquals("the flat level never warns", 0, felt.count { it == HapticFeedbackType.Reject })
    }

    @Test
    fun hapticsCanBeTurnedOffAndStayOff() {
        // The switch merges into its row; its own description is on the unmerged node.
        rule.onNodeWithContentDescription("Level haptics", useUnmergedTree = true).performClick()
        awaitMainLooper(5_000) { runBlocking { !app.container.prefs.settings.first().levelHaptics } }
        rule.waitForIdle()
        felt.clear()
        walk(5.0, 3.0, 1.0, 0.1)
        assertTrue("nothing is felt with haptics off: $felt", felt.isEmpty())
        rule.onNodeWithText("Haptics off.").assertExists()
    }
}
