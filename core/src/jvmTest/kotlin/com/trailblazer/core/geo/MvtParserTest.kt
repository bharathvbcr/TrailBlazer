package com.trailblazer.core.geo

import com.trailblazer.core.geo.TileFixtures.FeatureSpec
import com.trailblazer.core.geo.TileFixtures.Geom
import com.trailblazer.core.geo.TileFixtures.Pb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MvtParserTest {

    private fun eleOf(valueMessage: ByteArray): String? {
        val tile = TileUtils.parseMvt(TileFixtures.contourTile(valueMessage))
        return tile.layers.single().features.first().properties["ele"]
    }

    @Test
    fun decodesLinesPolygonsAndProperties() {
        val tile = TileUtils.parseMvt(TileFixtures.contourTile())
        val layer = tile.layers.single()
        assertEquals("contours", layer.name)
        assertEquals(4096, layer.extent)
        assertEquals(2, layer.features.size)
        val line = layer.features[0]
        assertEquals(TileUtils.GeometryType.LineString, line.type)
        assertEquals(1200.0, line.elevationM)
        assertEquals(listOf(listOf(
            TileUtils.TilePoint(0f, 0f),
            TileUtils.TilePoint(1000f / 4096f, 500f / 4096f),
            TileUtils.TilePoint(2000f / 4096f, 700f / 4096f),
        )), line.geometry)
        val ring = layer.features[1]
        assertEquals(TileUtils.GeometryType.Polygon, ring.type)
        assertEquals(4, ring.geometry.single().size, "three vertices plus the closing point")
        assertEquals(ring.geometry.single().first(), ring.geometry.single().last())
    }

    @Test
    fun aGzippedTileDecodesLikeThePlainOne() {
        val plain = TileFixtures.contourTile()
        assertEquals(TileUtils.parseMvt(plain), TileUtils.parseMvt(TileFixtures.gzip(plain)))
    }

    @Test
    fun everyValueEncodingYieldsTheElevation() {
        assertEquals("1200", eleOf(Pb().string(1, "1200").build()))
        assertEquals("1200", eleOf(Pb().fixed32(2, 1200f.toBits()).build()))
        assertEquals("1200", eleOf(Pb().fixed64(3, 1200.0.toBits()).build()))
        assertEquals("1200", eleOf(Pb().uint(4, 1200).build()))
        assertEquals("1200", eleOf(Pb().uint(5, 1200).build()))
        assertEquals("-1200", eleOf(Pb().uint(6, TileFixtures.zigzag(-1200)).build()), "sint is zigzag encoded")
        assertEquals("true", eleOf(Pb().uint(7, 1).build()))
        assertEquals("1234.5", eleOf(Pb().fixed64(3, 1234.5.toBits()).build()))
        assertEquals("-5", eleOf(Pb().uint(4, -5L).build()), "int64 is two's complement")
    }

    @Test
    fun zigzagExtremesLandWhereTheArithmeticSays() {
        // Delta -2^31 then +2^31 - 1: the full signed 32-bit range in one coordinate.
        val geom = Geom().moveTo(-2147483648L, 0).lineTo(-1L, 5L).build()
        val tile = TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, geom)))))
        val pts = tile.layers.single().features.single().geometry.single()
        assertEquals(-2147483648f / 4096f, pts[0].x)
        assertEquals(-1f / 4096f, pts[1].x)
        assertEquals(5f / 4096f, pts[1].y)
        val up = Geom().moveTo(2147483647L, 2147483647L).lineTo(0L, 0L).build()
        val t2 = TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, up)))))
        val p2 = t2.layers.single().features.single().geometry.single()
        assertTrue(p2.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun extentMustBePositiveAndBounded() {
        for (extent in listOf(0, -1, TileUtils.MAX_MVT_EXTENT + 1)) {
            val tile = TileFixtures.mvtTile(Pb().string(1, "l").uint(5, extent.toLong()).build())
            assertFailsWith<MalformedTileException>("extent $extent") { TileUtils.parseMvt(tile) }
        }
        val ok = TileFixtures.mvtTile(Pb().string(1, "l").uint(5, 512).build())
        assertEquals(512, TileUtils.parseMvt(ok).layers.single().extent)
    }

    @Test
    fun structuralLiesAreRejected() {
        // Length prefix longer than the buffer, negative (10-byte) length, unknown wire types, field number 0.
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(Pb().tag(3, 2).varint(1000).raw(byteArrayOf(1, 2)).build()) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(Pb().tag(3, 2).varint(-1L).build()) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(Pb().tag(3, 3).build()) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(Pb().tag(1, 7).build()) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(Pb().varint(0).build()) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(ByteArray(11) { 0x80.toByte() } + byteArrayOf(0)) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(Pb().tag(1, 5).raw(byteArrayOf(1, 2)).build()) }
        // A geometry command outside 1, 2, 7.
        val bad = Geom().rawInt(3L).build()
        assertFailsWith<MalformedTileException> {
            TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, bad)))))
        }
        // A LineTo whose count the buffer cannot hold; and parameters that do not fit 32 bits.
        val liar = Geom().moveTo(0, 0).rawInt((1L shl 28 shl 3) or 2L).build()
        assertFailsWith<MalformedTileException> {
            TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, liar)))))
        }
        val wide = Geom().rawInt((1L shl 3) or 1L).rawInt(1L shl 40).rawInt(0).build()
        assertFailsWith<MalformedTileException> {
            TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, wide)))))
        }
    }

    @Test
    fun unknownFieldsOfEveryValidWireTypeAreSkipped() {
        val layer = Pb().string(1, "l").uint(99, 5).fixed32(98, 1).fixed64(97, 2).string(96, "x").build()
        val tile = Pb().uint(1, 7).fixed32(2, 3).bytes(3, layer).string(5, "tail").build()
        assertEquals("l", TileUtils.parseMvt(tile).layers.single().name)
    }

    @Test
    fun tagsPointingOutsideTheTablesAreIgnored() {
        val line = Geom().moveTo(0, 0).lineTo(5, 5).build()
        val layer = TileFixtures.mvtLayer(
            "l",
            listOf(FeatureSpec(2, line, tags = listOf(0, 0, 7, 0, 0, 9, 1))),
            keys = listOf("a"),
            values = listOf(TileFixtures.mvtValueString("v")),
        )
        val f = TileUtils.parseMvt(TileFixtures.mvtTile(layer)).layers.single().features.single()
        assertEquals(mapOf("a" to "v"), f.properties)
    }

    @Test
    fun tooManyLayersOrPointsAreRefusedBeforeTheyAreAllocated() {
        val layers = Array(TileUtils.MAX_MVT_LAYERS + 1) { TileFixtures.mvtLayer("l$it", emptyList()) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(TileFixtures.mvtTile(*layers)) }
        val ok = Array(TileUtils.MAX_MVT_LAYERS) { TileFixtures.mvtLayer("l$it", emptyList()) }
        assertEquals(TileUtils.MAX_MVT_LAYERS, TileUtils.parseMvt(TileFixtures.mvtTile(*ok)).layers.size)

        val xy = LongArray((TileUtils.MAX_MVT_POINTS + 1) * 2) { 1L }
        val geom = Geom().moveTo(0, 0).lineTo(*xy).build()
        assertFailsWith<MalformedTileException> {
            TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, geom)))))
        }
    }

    @Test
    fun gzipBombsAndCorruptGzipAreRefusedWithTheTypedError() {
        val corrupt = TileFixtures.gzip(TileFixtures.contourTile()).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(corrupt) }
        assertFailsWith<MalformedTileException> { TileUtils.parseMvt(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)) }
    }

    @Test
    fun anEmptyTileIsAnEmptyTile() {
        assertEquals(0, TileUtils.parseMvt(ByteArray(0)).layers.size)
        assertEquals(0, TileUtils.parseMvt(TileFixtures.mvtTile()).layers.size)
    }

    @Test
    fun theTileCostEstimateGrowsWithItsPoints() {
        val small = TileUtils.parseMvt(TileFixtures.contourTile())
        val xy = LongArray(20_000) { it.toLong() % 100 }
        val geom = Geom().moveTo(0, 0).lineTo(*xy).build()
        val big = TileUtils.parseMvt(TileFixtures.mvtTile(TileFixtures.mvtLayer("l", listOf(FeatureSpec(2, geom)))))
        assertTrue(big.estimatedBytes > small.estimatedBytes + 9_000 * 24)
    }
}
