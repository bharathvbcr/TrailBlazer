package com.example.trailblazer.ui.sky

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.TrailApp
import com.trailblazer.core.astro.MoonPhaseName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Moon card's "Moon phases" dropdown on the real Sky tab. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class MoonPhasesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()

    private fun withAFix() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    /** Sent once the Sky tab is listening; a fix delivered before that is not replayed to it. */
    private fun sendFix() {
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            shadowOf(manager).simulateLocation(Location(provider).apply {
                latitude = 33.19; longitude = -97.13; accuracy = 8f; time = System.currentTimeMillis()
            })
        }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/reports/screens").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theDropdownShowsTheCycleExplainsAPhaseAndListsWhatIsComing() {
        withAFix()
        rule.onAllNodesWithText("Sky").onFirst().performClick()
        rule.waitForIdle()
        sendFix()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("Waiting for a position fix", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Moon phases"))
        assertTrue("closed by default", rule.onAllNodesWithText("Coming up").fetchSemanticsNodes().isEmpty())

        rule.onNodeWithText("Moon phases").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Coming up"))
        save("sky-moon-phases")

        val names = MoonPhaseName.entries.map { phaseName(it) }.toSet()
        val pictures = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
            .filter { n -> n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.singleOrNull()?.substringBefore(",") in names }
        assertEquals("all eight phases are pictured", 8, pictures.size)
        assertEquals("exactly one is tonight's", 1, pictures.count { it.config[SemanticsProperties.ContentDescription].single().endsWith(", tonight") })
        assertEquals("the next four principal phases are listed", 4,
            listOf("New moon", "First quarter", "Full moon", "Last quarter").count { rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() })

        rule.onNodeWithContentDescription("Full moon", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(aboutPhase(MoonPhaseName.FullMoon)).assertExists()

        rule.onNodeWithText("Moon phases").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("closes again", rule.onAllNodesWithText("Coming up").fetchSemanticsNodes().isEmpty())
    }
}
