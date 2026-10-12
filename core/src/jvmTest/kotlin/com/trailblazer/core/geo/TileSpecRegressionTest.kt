package com.trailblazer.core.geo

import com.trailblazer.core.geo.TileFixtures.Entry
import com.trailblazer.core.geo.TileFixtures.FeatureSpec
import com.trailblazer.core.geo.TileFixtures.Geom
import com.trailblazer.core.geo.TileFixtures.HeaderSpec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each test here pins a defect found in the first version of the tile code against the formats' own specifications.
 * They use only the long-standing TileUtils entry points, so they can be run against the code as it was before the
 * fixes to confirm that each one fails there.
 */
class TileSpecRegressionTest {

    @Test
    fun hilbertTileIdsMatchTheSpecTable() {
        // The table from the PMTiles v3 specification.
        assertEquals(0L, TileUtils.zxyToTileId(0, 0, 0))
        assertEquals(1L, TileUtils.zxyToTileId(1, 0, 0))
        assertEquals(2L, TileUtils.zxyToTileId(1, 0, 1))
        assertEquals(3L, TileUtils.zxyToTileId(1, 1, 1))
        assertEquals(4L, TileUtils.zxyToTileId(1, 1, 0))
        assertEquals(5L, TileUtils.zxyToTileId(2, 0, 0))
        assertEquals(19078479L, TileUtils.zxyToTileId(12, 3423, 1763))
        assertEquals(Triple(12, 3423, 1763), TileUtils.tileIdToZxy(19078479L))
    }

    @Test
    fun headerIsReadAtTheSpecifiedOffsets() {
        val h = TileUtils.parsePmtilesHeader(
            TileFixtures.pmtilesHeader(
                HeaderSpec(
                    rootOffset = 127, rootLength = 500, leafOffset = 700, leafLength = 800,
                    dataOffset = 1000, dataLength = 20000, tileType = 2, minZoom = 3, maxZoom = 15,
                    minLon = -122.5, minLat = 37.25, maxLon = -121.5, maxLat = 38.5,
                ),
            ),
        )
        assertEquals(127L, h.rootDirectoryOffset)
        assertEquals(500L, h.rootDirectoryLength)
        assertEquals(1000L, h.tileDataOffset)
        assertEquals(20000L, h.tileDataLength)
        assertEquals(TileUtils.TileFormat.RasterPng, h.tileType)
        assertEquals(3, h.minZoom)
        assertEquals(15, h.maxZoom)
        assertEquals(-122.5, h.minLon, 1e-6)
        assertEquals(37.25, h.minLat, 1e-6)
        assertEquals(-121.5, h.maxLon, 1e-6)
        assertEquals(38.5, h.maxLat, 1e-6)
    }

    @Test
    fun directoryIsDecodedColumnWise() {
        val src = listOf(
            Entry(tileId = 1, offset = 0, length = 100, runLength = 1),
            Entry(tileId = 2, offset = 100, length = 50, runLength = 3),
            Entry(tileId = 9, offset = 7000, length = 25, runLength = 1),
            Entry(tileId = 12, offset = 0, length = 100, runLength = 0),
        )
        val parsed = TileUtils.parsePmtilesDirectory(TileFixtures.directory(src))
        assertEquals(src.size, parsed.size)
        for ((a, b) in src.zip(parsed)) {
            assertEquals(a.tileId, b.tileId)
            assertEquals(a.offset, b.offset)
            assertEquals(a.length, b.length)
            assertEquals(a.runLength, b.runLength)
        }
    }

    @Test
    fun aRunOfTilesSharesOneBlob() {
        // Run length 3 means tiles 10, 11 and 12 are all stored once, at the same offset.
        val entries = listOf(TileUtils.PmtilesEntry(tileId = 10, offset = 500, length = 40, runLength = 3))
        for (id in 10L..12L) {
            val hit = assertNotNull(TileUtils.findPmtilesEntry(entries, id), "tile $id")
            assertEquals(500L, hit.offset, "tile $id must read the shared blob")
            assertEquals(40L, hit.length)
        }
        assertEquals(null, TileUtils.findPmtilesEntry(entries, 13L))
        assertEquals(null, TileUtils.findPmtilesEntry(entries, 9L))
    }

    @Test
    fun anAbsurdTileIdTerminates() {
        val outcome = arrayOfNulls<Any>(1)
        val worker = Thread {
            outcome[0] = try {
                TileUtils.tileIdToZxy(Long.MAX_VALUE)
            } catch (e: Exception) {
                e
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(3000)
        if (worker.isAlive) fail("tileIdToZxy(Long.MAX_VALUE) did not return within 3 s")
        assertTrue(outcome[0] is Exception, "an id past zoom 30 names no tile: ${outcome[0]}")
    }

    @Test
    fun aGzipBombIsRefusedNotInflated() {
        val bomb = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { gz ->
                val chunk = ByteArray(1 shl 20)
                repeat(64) { gz.write(chunk) }
            }
        }.toByteArray()
        assertTrue(bomb.size < 200_000, "fixture should be tiny: ${bomb.size}")
        assertFailsWith<IllegalArgumentException>("64 MiB of zeros must be refused") { TileUtils.parseMvt(bomb) }
    }

    @Test
    fun panningAcrossTheAntimeridianIsSafe() {
        val center = LatLon(10.0, 179.999)
        // Right edge of a 1080 px wide view at z12 is east of 180 degrees.
        val east = TileUtils.screenToLatLon(1080f, 600f, center, 12.0, 1080f, 1200f)
        assertTrue(east.lon in -180.0..180.0, "longitude must be wrapped, was ${east.lon}")
        assertTrue(east.lon < -179.0, "it is on the western side of the dateline: ${east.lon}")
        val range = TileUtils.visibleTileRange(center, 12.0, 1080f, 1200f)
        assertTrue(range.maxX - range.minX < 24, "a 1080 px view covers a handful of columns, not ${range.maxX - range.minX + 1}")
    }

    @Test
    fun trackPointsEitherSideOfTheAntimeridianProjectNextToEachOther() {
        val center = LatLon(0.0, 179.9)
        val a = TileUtils.latLonToScreen(LatLon(0.0, 179.9), center, 10.0, 800f, 800f)
        val b = TileUtils.latLonToScreen(LatLon(0.0, -179.9), center, 10.0, 800f, 800f)
        assertTrue(kotlin.math.abs(b.x - a.x) < 400f, "0.2 degrees apart, not ${kotlin.math.abs(b.x - a.x)} px")
    }

    @Test
    fun geometryCommandCountsThePayloadCannotHoldAreRejected() {
        // LineTo claiming 2^28 points with no coordinates behind it.
        val geom = Geom().moveTo(0, 0).rawInt((1L shl 28 shl 3) or 2L).build()
        val tile = TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, geom))))
        assertFailsWith<IllegalArgumentException> { TileUtils.parseMvt(tile) }
    }

    @Test
    fun truncatedTilesFailWithTheParsersOwnError() {
        val tile = TileFixtures.contourTile()
        var rejected = 0
        for (cut in 1 until tile.size) {
            try {
                TileUtils.parseMvt(tile.copyOf(cut))
            } catch (e: IllegalArgumentException) {
                rejected++
            }
        }
        assertTrue(rejected > tile.size / 2, "most truncations are structurally invalid, rejected $rejected of ${tile.size - 1}")
    }

    @Test
    fun anOversizedVarintIsRejected() {
        val bytes = ByteArray(12) { 0xFF.toByte() }
        assertFailsWith<IllegalArgumentException> { TileUtils.parseMvt(bytes) }
    }
}
