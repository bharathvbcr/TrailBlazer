package com.example.trailblazer.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.TrailApp
import com.example.trailblazer.data.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings offers the four fix intervals, and the one picked is saved for the recording service. */
@RunWith(AndroidJUnit4::class)
class TrackingModeSettingTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()

    // DataStore outlives each test's Application, so start from the default.
    @Before
    fun setUp() = runBlocking { app.container.prefs.update { it.copy(trackingMode = TrackingMode.Continuous) } }

    private fun saved() = runBlocking { app.container.prefs.settings.first().trackingMode }

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun settleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            rule.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "condition not met within 5 s" }
            Thread.sleep(20)
        }
    }

    @Test
    fun everyIntervalIsOfferedAndThePickIsSaved() {
        rule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("GPS fix interval"))
        rule.onAllNodesWithText("Continuous (1 s)").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        for (option in listOf("Balanced (15 s)", "Expedition (60 s)", "Expedition (5 min)")) {
            assertTrue("$option is offered", shown(option))
        }
        rule.onAllNodesWithText("Expedition (5 min)").onFirst().performClick()
        settleUntil { saved() == TrackingMode.ExpeditionLong }
        assertEquals(300_000L, saved().intervalMs)
        assertTrue("the chip shows the choice", shown("Expedition (5 min)"))
    }
}
