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
                routes = listOf(stops),
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

    private class RecordingSource(
        override val minZoom: Int,
        override val maxZoom: Int,
        private val fail: Boolean = false,
    ) : OfflineTileSource {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        override val file = File("/dummy/rec.mbtiles")
        override val name = "Recording"
        override val format = TileUtils.TileFormat.RasterPng
        override val bounds: TileUtils.TileBounds? = null
        override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? = null
        override fun getTile(zoom: Int, x: Int, y: Int): DecodedTile? {
            requests.add(Triple(zoom, x, y))
            if (fail) throw IllegalStateException("corrupt archive")
            return DecodedTile.Raster(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888))
        }
        override fun close() {}
    }

    @Test
    fun aViewDeeperThanTheArchiveStretchesItsDeepestTiles() {
        val src = RecordingSource(minZoom = 8, maxZoom = 12)
        rule.setContent {
            OfflineMapCanvas(tileSource = src, initialCenter = LatLon(46.0, 7.0), initialZoom = 20.0)
        }
        rule.waitUntil(10_000) { src.requests.isNotEmpty() }
        rule.waitForIdle()
        assertTrue("never asked past z12: ${src.requests.map { it.first }.toSet()}", src.requests.all { it.first == 12 })
        // The HUD shows the view clamped to the archive's deepest level + 3 overzoom levels, not 20.
        rule.onNodeWithText("Recording (z15.0)").assertIsDisplayed()
        assertTrue("a handful of tiles for one screen, not ${src.requests.size}", src.requests.size <= 80)
    }

    @Test
    fun aViewFarOutsideTheArchiveRequestsNothing() {
        val src = RecordingSource(minZoom = 10, maxZoom = 14)
        rule.setContent {
            OfflineMapCanvas(tileSource = src, initialCenter = LatLon(46.0, 7.0), initialZoom = 4.0)
        }
        rule.onNodeWithText("Recording (z4.0)").assertIsDisplayed()
        // Zoom in one level at a time. Up to z8 (more than one level above the archive's z10) nothing can be drawn...
        repeat(4) { rule.onNodeWithContentDescription("Zoom in").performClick() }
        rule.onNodeWithText("Recording (z8.0)").assertIsDisplayed()
        rule.waitForIdle()
        // ...and one more level reaches the archive. Waiting for that first request (no sleeping) means any request
        // wrongly issued while zoomed out would already be in the list by now.
        rule.onNodeWithContentDescription("Zoom in").performClick()
        rule.waitUntil(10_000) { src.requests.isNotEmpty() }
        rule.waitForIdle()
        assertTrue("only the archive's own level was requested: ${src.requests.map { it.first }.toSet()}", src.requests.all { it.first == 10 })
    }

    @Test
    fun aSourceThatFailsOnEveryTileLeavesTheMapUsable() {
        val src = RecordingSource(minZoom = 0, maxZoom = 14, fail = true)
        rule.setContent {
            OfflineMapCanvas(
                tileSource = src,
                tracks = listOf(listOf(LatLon(46.0, 7.0))),
                initialCenter = LatLon(46.0, 7.0),
                initialZoom = 12.0,
            )
        }
        rule.waitUntil(10_000) { src.requests.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Offline topographical map canvas").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom in").performClick()
        rule.onNodeWithText("Recording (z13.0)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom out").performClick()
        rule.onNodeWithText("Recording (z12.0)").assertIsDisplayed()
    }

    @Test
    fun overlaysTolerateEmptySinglePointAndAntimeridianTracks() {
        rule.setContent {
            OfflineMapCanvas(
                tileSource = null,
                tracks = listOf(
                    emptyList(),
                    listOf(LatLon(10.0, 10.0)),
                    listOf(LatLon(-17.0, 179.9), LatLon(-17.0, -179.9), LatLon(-17.1, 179.95)),
                ),
                routes = listOf(emptyList(), listOf(Stop("a", "A", StopKind.Start, LatLon(-17.0, 179.9)), Stop("b", "B", StopKind.End, LatLon(-17.0, -179.9)))),
                waypoints = listOf(Waypoint("w", "Pole", LatLon(90.0, 0.0), 0.0, 1L, null)),
                currentLocation = LatLon(-85.0, 180.0),
                accuracyM = 1e9,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Offline topographical map canvas").assertIsDisplayed()
        rule.onNodeWithText("Tracks").assertIsDisplayed()
    }
}
