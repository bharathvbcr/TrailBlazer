package com.example.trailblazer.ui.trips

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.DecodedTile
import com.example.trailblazer.data.OfflineTileSource
import com.example.trailblazer.data.Waypoint
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp")
class OfflineMapCanvasTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun testEmptyMapNoticeAndImportAction() {
        var importClicked = false
        rule.setContent {
            OfflineMapCanvas(
                tileSource = null,
                onImportClick = { importClicked = true },
            )
        }

        rule.onNodeWithText("No Offline Topo Map Loaded").assertIsDisplayed()
        rule.onNodeWithText("Import map archive").assertIsDisplayed().performClick()
        assertTrue(importClicked)
    }

    @Test
    fun testOverlayTripsWaypointsAndTracks() {
        val stops = listOf(
            Stop("s1", "Trailhead", StopKind.Start, LatLon(46.0, 7.0)),
            Stop("s2", "Camp A", StopKind.Night, LatLon(46.05, 7.05)),
            Stop("s3", "Summit", StopKind.End, LatLon(46.10, 7.10)),
        )
        val waypoints = listOf(
            Waypoint("w1", "Water Spring", LatLon(46.02, 7.02), 1800.0, 1000L, null),
        )
        val tracks = listOf(
            listOf(LatLon(46.0, 7.0), LatLon(46.02, 7.01), LatLon(46.05, 7.05)),
        )

        rule.setContent {
            OfflineMapCanvas(
                tileSource = null,
                stops = stops,
                waypoints = waypoints,
                tracks = tracks,
                currentLocation = LatLon(46.01, 7.01),
                accuracyM = 15.0,
            )
        }

        rule.onNodeWithContentDescription("Offline topographical map canvas").assertIsDisplayed()
        rule.onNodeWithText("Trip").assertIsDisplayed()
        rule.onNodeWithText("Pins (1)").assertIsDisplayed()
        rule.onNodeWithText("Tracks").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom in").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom out").assertIsDisplayed()
        rule.onNodeWithContentDescription("My location").assertIsDisplayed()
        rule.onNodeWithContentDescription("Fit to content").assertIsDisplayed()
    }

    @Test
    fun testActiveTileSourceHUD() {
        val mockSource = object : OfflineTileSource {
            override val file = File("/dummy/test_topo.mbtiles")
            override val name = "Test Topo Map"
            override val format = TileUtils.TileFormat.RasterPng
            override val minZoom = 10
            override val maxZoom = 15
            override val bounds = TileUtils.TileBounds(45.0, 6.0, 47.0, 8.0)
            override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? = null
            override fun getTile(zoom: Int, x: Int, y: Int): DecodedTile? =
                DecodedTile.Raster(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
            override fun close() {}
        }

        rule.setContent {
            OfflineMapCanvas(
                tileSource = mockSource,
                initialCenter = LatLon(46.0, 7.0),
                initialZoom = 12.0,
            )
        }

        rule.onNodeWithContentDescription("Offline topographical map canvas").assertIsDisplayed()
        rule.onNodeWithText("Test Topo Map (z12.0)").assertIsDisplayed()
    }
}
