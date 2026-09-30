package com.example.trailblazer.ui.now

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.TrailApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The position card can be minimised to one line, and the choice survives the activity being recreated. */
@RunWith(AndroidJUnit4::class)
class PositionCardTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()

    private fun withAFix() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true)
        rule.activityRule.scenario.recreate() // permissions are read when the screen starts
        rule.waitForIdle()
        for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            shadowOf(manager).simulateLocation(Location(provider).apply {
                latitude = 46.5582; longitude = 7.8352; accuracy = 6f; time = System.currentTimeMillis()
            })
        }
        rule.waitForIdle()
    }

    /** Lets queued work run (the v2 rule runs coroutines on a test dispatcher) until [done], for at most 5 s. */
    private fun settleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            rule.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "condition not met within 5 s" }
            Thread.sleep(20)
        }
    }

    private fun compactSaved() = runBlocking { app.container.prefs.settings.first().compactPosition }

    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun positionMinimisesToOneLineAndStaysThatWay() {
        withAFix()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Waiting for satellites", substring = true))
        assertTrue("expanded card shows the satellite state", shown("Waiting for satellites"))

        rule.onNodeWithContentDescription("Show position on one line").performSemanticsAction(SemanticsActions.OnClick)
        // The choice is saved to DataStore on a background thread; the card follows the saved setting.
        settleUntil { !shown("Waiting for satellites") }
        assertTrue("one line holds the accuracy", shown("±"))
        assertTrue("minimised card hides the satellite section", !shown("Waiting for satellites"))
        assertEquals(true, runBlocking { app.container.prefs.settings.first().compactPosition })

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Position"))
        assertTrue("still minimised after recreation", rule.onAllNodesWithText("Waiting for satellites", substring = true).fetchSemanticsNodes().isEmpty())
        // Invoked as an action: after a recreate the list can stop with the button under the see-through header.
        rule.onNodeWithContentDescription("Show satellites and details").performSemanticsAction(SemanticsActions.OnClick)
        settleUntil { !compactSaved() }
        assertEquals(false, runBlocking { app.container.prefs.settings.first().compactPosition })
    }
}
