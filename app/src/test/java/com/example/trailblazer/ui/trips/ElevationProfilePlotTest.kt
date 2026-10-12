package com.example.trailblazer.ui.trips

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.theme.TrailTheme
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.ElevationProfile
import com.trailblazer.core.trip.ProfileSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The trip page's Elevation card: the explicit empty state, the plot's spoken summary, and the warnings. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ElevationProfilePlotTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = Fmt(ApplicationProvider.getApplicationContext<Context>(), Settings())

    private fun profileOf(vararg heights: Double?, stepM: Double = 100.0, requested: Double = stepM) = ElevationProfile(
        heights.mapIndexed { i, h -> ProfileSample(i * stepM, LatLon(36.0, -118.0), h) },
        listOf(0.0, (heights.size - 1) * stepM),
        requested,
        stepM,
    )

    private fun ready(profile: ElevationProfile, steep: List<com.trailblazer.core.trip.SteepSection> = emptyList()) =
        TripElevation.Ready(profile, profile.stats(), profile.extrema(), steep)

    private fun show(state: TripElevation, onImport: () -> Unit = {}) = rule.setContent {
        TrailTheme(mode = ThemeMode.Light, dynamicColor = false, nightRed = false) {
            Column(Modifier.fillMaxWidth()) { ElevationSection(state, fmt, onImport) }
        }
    }

    @Test
    fun withNoTilesTheCardSaysSoAndOffersTheImport() {
        var imports = 0
        show(ready(profileOf(null, null, null, null)), onImport = { imports++ })
        rule.onNodeWithText("No elevation data for this route — import HGT tiles").assertExists()
        rule.onNodeWithText("Import HGT tiles").performClick()
        assertEquals(1, imports)
        // No plot and no fake 0 m figures.
        assertEquals(0, rule.onAllNodesWithDescription("Elevation profile").size)
        assertEquals(0, rule.onAllNodesWithTextCount("0 m"))
    }

    @Test
    fun thePlotDescribesItselfForScreenReaders() {
        show(ready(profileOf(1000.0, 1100.0, 1300.0, 1150.0, 1100.0)))
        val description = rule.onNodeWithContentDescription("Elevation profile over", substring = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString()
        assertTrue(description, description.contains("Lowest 1000 m, highest 1300 m, climb 300 m, descent 200 m."))
        assertTrue(description, description.contains("peak"))
        assertTrue(description, !description.contains("no elevation data"))
        rule.onNodeWithText("Climb / descent").assertExists()
        rule.onNodeWithText("↑300 m ↓200 m").assertExists()
        rule.onNodeWithText("Highest").assertExists()
        rule.onNodeWithText("Lowest").assertExists()
    }

    @Test
    fun gapsAreShownAndNamedAndTheDescriptionSaysTheyExist() {
        show(ready(profileOf(1000.0, 1100.0, null, null, 1500.0, 1600.0)))
        val description = rule.onNodeWithContentDescription("Elevation profile over", substring = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString()
        assertTrue(description, description.contains("Some of the route has no elevation data."))
        rule.onNodeWithText("No elevation data along about 33% of the route", substring = true).assertExists()
        // Climb counts only the parts that have data: 100 m, then 100 m, not 600 m.
        rule.onNodeWithText("↑200 m ↓0 m").assertExists()
    }

    @Test
    fun steepGroundAndACoarseSamplingAreWarnedAbout() {
        val profile = profileOf(1000.0, 1100.0, 1300.0, 1150.0, 1100.0, stepM = 500.0, requested = 90.0)
        show(ready(profile, listOf(com.trailblazer.core.trip.SteepSection(500.0, 900.0, 38.0))))
        rule.onNodeWithText("Steep ground: 1 stretch above 25% (steepest about 38%)", substring = true).assertExists()
        rule.onNodeWithText("sampled every", substring = true).assertExists()
    }

    @Test
    fun loadingNotEnoughStopsAndASinglePointOfDataAreHandled() {
        show(TripElevation.Loading)
        rule.onNodeWithText("Reading elevation…").assertExists()
    }

    @Test
    fun aRouteWithOnlyOneKnownPointSaysItCannotDrawAProfile() {
        show(ready(profileOf(null, 1200.0, null)))
        rule.onNodeWithText("Too little elevation data to draw a profile.").assertExists()
    }

    @Test
    fun nothingIsShownBeforeThereIsARoute() {
        show(TripElevation.NotEnoughStops)
        assertEquals(0, rule.onAllNodesWithTextCount("Elevation"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithDescription(prefix: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(prefix, substring = true)).fetchSemanticsNodes()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
