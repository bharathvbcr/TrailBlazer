package com.trailblazer.core.geo

import com.trailblazer.core.geo.TileUtils.TileBounds
import com.trailblazer.core.geo.TileUtils.TileCoord
import com.trailblazer.core.geo.TileUtils.TileFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TileUtilsTest {

    @Test
    fun testLatLonToTileAndBack() {
        // Zoom 0 has only 1 tile: (0, 0)
        val t0 = TileUtils.latLonToTile(0.0, 0.0, 0)
        assertEquals(0, t0.x)
        assertEquals(0, t0.y)
        assertEquals(0, t0.zoom)

        // San Francisco ~ 37.7749, -122.4194 at zoom 10
        val tSf = TileUtils.latLonToTile(37.7749, -122.4194, 10)
        assertEquals(10, tSf.zoom)
        assertTrue(tSf.x in 0 until (1 shl 10))
        assertTrue(tSf.y in 0 until (1 shl 10))

        // Center of that tile converted back to LatLon should be very close
        val center = TileUtils.tileToLatLon(tSf.x + 0.5, tSf.y + 0.5, 10)
        val bounds = TileUtils.tileBounds(tSf.x, tSf.y, 10)
        assertTrue(bounds.contains(center))
        assertTrue(bounds.contains(37.7749, -122.4194))
    }

    @Test
    fun testTileBounds() {
        val bounds = TileUtils.tileBounds(10, 15, 6)
        assertTrue(bounds.minLat < bounds.maxLat)
        assertTrue(bounds.minLon < bounds.maxLon)

        val center = bounds.center()
        assertTrue(bounds.contains(center))
        assertTrue(bounds.contains(LatLon(center.lat, center.lon)))
    }

    @Test
    fun testTmsConversion() {
        // Zoom 1: 2x2 grid. In XYZ, y=0 is North, y=1 is South. In TMS, y=0 is South, y=1 is North.
        assertEquals(1, TileUtils.xyzYToTmsY(0, 1))
        assertEquals(0, TileUtils.xyzYToTmsY(1, 1))
        assertEquals(0, TileUtils.tmsYToXyzY(1, 1))
        assertEquals(1, TileUtils.tmsYToXyzY(0, 1))

        // Round-trip at zoom 14
        val y = 5432
        val tmsY = TileUtils.xyzYToTmsY(y, 14)
        assertEquals(y, TileUtils.tmsYToXyzY(tmsY, 14))

        val coord = TileCoord(1234, y, 14)
        assertEquals(tmsY, coord.tmsY)
    }

    @Test
    fun testMetersPerPixel() {
        val mpp0 = TileUtils.metersPerPixel(0.0, 0.0)
        assertTrue(mpp0 in 150000.0..160000.0)

        val mpp1 = TileUtils.metersPerPixel(0.0, 1.0)
        assertEquals(mpp0 / 2.0, mpp1, 1.0)

        // MPP at 60 deg latitude is half of equator (cos 60 = 0.5)
        val mpp60 = TileUtils.metersPerPixel(60.0, 0.0)
        assertEquals(mpp0 * 0.5, mpp60, 50.0)
    }

    @Test
    fun testFitViewport() {
        val points = listOf(
            LatLon(37.7, -122.5),
            LatLon(37.8, -122.4),
            LatLon(37.9, -122.3),
        )
        val (center, zoom) = TileUtils.fitViewport(points, 800, 600, paddingPx = 40)
        assertEquals(37.8, center.lat, 0.05)
        assertEquals(-122.4, center.lon, 0.05)
        assertTrue(zoom in 8.0..13.0)
    }

    @Test
    fun testHilbertAndPmtilesTileId() {
        // Tile 0 at zoom 0 has ID 0
        assertEquals(0L, TileUtils.zxyToTileId(0, 0, 0))
        assertEquals(Triple(0, 0, 0), TileUtils.tileIdToZxy(0L))

        // Zoom 1 has 4 tiles with IDs 1, 2, 3, 4
        val idsZ1 = mutableSetOf<Long>()
        for (x in 0..1) {
            for (y in 0..1) {
                val id = TileUtils.zxyToTileId(1, x, y)
                assertTrue(id in 1L..4L)
                idsZ1.add(id)
                assertEquals(Triple(1, x, y), TileUtils.tileIdToZxy(id))
            }
        }
        assertEquals(4, idsZ1.size)

        // Roundtrip tests across diverse zoom levels
        val testCases = listOf(
            Triple(2, 1, 3),
            Triple(5, 12, 18),
            Triple(10, 512, 345),
            Triple(14, 2620, 6331),
        )
        for ((z, x, y) in testCases) {
            val id = TileUtils.zxyToTileId(z, x, y)
            val roundtrip = TileUtils.tileIdToZxy(id)
            assertEquals(Triple(z, x, y), roundtrip)
        }
    }

    @Test
    fun testTileFormatDetection() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(TileFormat.RasterPng, TileFormat.detect(pngBytes))

        val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(TileFormat.RasterJpg, TileFormat.detect(jpgBytes))

        val webpBytes = "RIFF1234WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals(TileFormat.RasterWebp, TileFormat.detect(webpBytes))

        val gzipBytes = byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08, 0x00)
        assertEquals(TileFormat.VectorMvt, TileFormat.detect(gzipBytes))

        val unknownBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertEquals(TileFormat.Unknown, TileFormat.detect(unknownBytes))
    }

    @Test
    fun testPmtilesHeaderParsing() {
        val buffer = ByteArray(127)
        buffer[0] = 'P'.code.toByte()
        buffer[1] = 'M'.code.toByte()
        buffer[2] = 3 // version 3
        // rootDirectoryOffset = 127 (at offset 3, uint64 LE)
        buffer[3] = 127
        // rootDirectoryLength = 500 (at offset 11)
        buffer[11] = 0xF4.toByte()
        buffer[12] = 0x01
        // tileDataOffset = 1000 (at offset 51)
        buffer[51] = 0xE8.toByte()
        buffer[52] = 0x03
        // tileDataLength = 20000 (at offset 59)
        buffer[59] = 0x20
        buffer[60] = 0x4E
        // minZoom = 2 (at offset 95)
        buffer[95] = 2
        // maxZoom = 14 (at offset 96)
        buffer[96] = 14
        // minLon = -122.5 * 10_000_000 = -1225000000 (at offset 97, int32 LE)
        val minLonI = -1225000000
        buffer[97] = (minLonI and 0xFF).toByte()
        buffer[98] = ((minLonI shr 8) and 0xFF).toByte()
        buffer[99] = ((minLonI shr 16) and 0xFF).toByte()
        buffer[100] = ((minLonI shr 24) and 0xFF).toByte()

        val header = TileUtils.parsePmtilesHeader(buffer)
        assertNotNull(header)
        assertEquals(2, header.minZoom)
        assertEquals(14, header.maxZoom)
        assertEquals(127L, header.rootDirectoryOffset)
        assertEquals(500L, header.rootDirectoryLength)
        assertEquals(1000L, header.tileDataOffset)
        assertEquals(-122.5, header.minLon, 0.0001)
    }

    @Test
    fun testScreenCoordinates() {
        val center = LatLon(46.0, 7.0)
        val zoom = 12.0
        val width = 800f
        val height = 600f

        // Center must project to center of screen (width/2, height/2)
        val centerScreen = TileUtils.latLonToScreen(center, center, zoom, width, height)
        assertEquals(400f, centerScreen.x, 0.1f)
        assertEquals(300f, centerScreen.y, 0.1f)

        // North point must have smaller Y (higher on screen)
        val northPt = LatLon(46.05, 7.0)
        val northScreen = TileUtils.latLonToScreen(northPt, center, zoom, width, height)
        assertEquals(400f, northScreen.x, 0.1f)
        assertTrue(northScreen.y < 300f)

        // East point must have larger X (to the right)
        val eastPt = LatLon(46.0, 7.05)
        val eastScreen = TileUtils.latLonToScreen(eastPt, center, zoom, width, height)
        assertTrue(eastScreen.x > 400f)
        assertEquals(300f, eastScreen.y, 0.1f)

        // Roundtrip screen to LatLon
        val roundtrip = TileUtils.screenToLatLon(eastScreen.x, eastScreen.y, center, zoom, width, height)
        assertEquals(eastPt.lat, roundtrip.lat, 0.0001)
        assertEquals(eastPt.lon, roundtrip.lon, 0.0001)
    }

    @Test
    fun testMvtVectorTileParsing() {
        // Construct a minimal protobuf-encoded MVT tile
        // Layer: name = "contours", extent = 4096
        // Key 0: "ele"
        // Value 0: string "1200"
        // Feature: LineString with points (0, 0) to (1000, 500)
        val bytes = buildSampleMvtBytes()
        val tile = TileUtils.parseMvt(bytes)
        assertNotNull(tile)
        assertEquals(1, tile.layers.size)
        val layer = tile.layers[0]
        assertEquals("contours", layer.name)
        assertEquals(1, layer.features.size)
        val feature = layer.features[0]
        assertEquals(TileUtils.GeometryType.LineString, feature.type)
        assertEquals("1200", feature.properties["ele"])
        assertEquals(1200.0, feature.elevationM)
        assertTrue(feature.isContour)
        assertEquals(1, feature.geometry.size)
        assertEquals(2, feature.geometry[0].size)
        assertEquals(0f, feature.geometry[0][0].x, 0.001f)
        assertEquals(0f, feature.geometry[0][0].y, 0.001f)
        assertEquals(1000f / 4096f, feature.geometry[0][1].x, 0.001f)
        assertEquals(500f / 4096f, feature.geometry[0][1].y, 0.001f)
    }

    private fun buildSampleMvtBytes(): ByteArray {
        // Proto wire encoding:
        // Value message: field 1 string "1200"
        val valMsg = byteArrayOf(0x0A, 0x04, '1'.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '0'.code.toByte())
        // Geometry: MoveTo(1, count=1) -> cmd 9 -> (0,0) -> 0, 0
        // LineTo(2, count=1) -> cmd 10 -> (1000, 500) zigzag:
        // 1000 -> (1000 << 1) = 2000 -> varint: 0xD0, 0x0F
        // 500 -> (500 << 1) = 1000 -> varint: 0xE8, 0x07
        val geom = byteArrayOf(0x09, 0x00, 0x00, 0x0A, 0xD0.toByte(), 0x0F, 0xE8.toByte(), 0x07)
        // Feature message:
        // field 2 (tags): tag 0 (key 0), val 0 -> [0, 0]
        // field 3 (type): LineString = 2
        // field 4 (geometry): geom
        val featBody = mutableListOf<Byte>()
        // tags: field 2, length-delimited
        featBody.add(0x12)
        featBody.add(0x02)
        featBody.add(0x00)
        featBody.add(0x00)
        // geom type: field 3, varint
        featBody.add(0x18)
        featBody.add(0x02)
        // geometry: field 4, length-delimited
        featBody.add(0x22)
        featBody.add(geom.size.toByte())
        geom.forEach { featBody.add(it) }

        // Layer message:
        // field 1 (name): "contours" -> 0x0A, 0x08, ...
        // field 2 (feature): featBody
        // field 3 (keys): "ele" -> 0x1A, 0x03, 'e','l','e'
        // field 4 (values): valMsg -> 0x22, length, valMsg
        // field 5 (extent): 4096 -> 0x28, varint 4096 (0x80, 0x20)
        val layerBody = mutableListOf<Byte>()
        // name
        layerBody.add(0x0A)
        layerBody.add(0x08)
        "contours".forEach { layerBody.add(it.code.toByte()) }
        // feature
        layerBody.add(0x12)
        layerBody.add(featBody.size.toByte())
        layerBody.addAll(featBody)
        // key
        layerBody.add(0x1A)
        layerBody.add(0x03)
        "ele".forEach { layerBody.add(it.code.toByte()) }
        // value
        layerBody.add(0x22)
        layerBody.add(valMsg.size.toByte())
        valMsg.forEach { layerBody.add(it) }
        // extent: 4096 = 0x1000 -> 0x80, 0x20
        layerBody.add(0x28)
        layerBody.add(0x80.toByte())
        layerBody.add(0x20)

        // Tile message: field 3 (layers): repeated Layer
        val tileBody = mutableListOf<Byte>()
        tileBody.add(0x1A)
        tileBody.add(layerBody.size.toByte())
        tileBody.addAll(layerBody)

        return tileBody.toByteArray()
    }
}
