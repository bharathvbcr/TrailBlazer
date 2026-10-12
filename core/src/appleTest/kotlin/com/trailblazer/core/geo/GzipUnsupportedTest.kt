package com.trailblazer.core.geo

import com.trailblazer.core.io.GzipUnsupportedException
import com.trailblazer.core.io.decompressGzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Apple has no gzip inflater here, so gzip data must be refused by name, never handed on still compressed. */
class GzipUnsupportedTest {
    // The gzip of an empty stream; only the magic matters, because the platform refuses before inflating.
    private val gzipBytes = byteArrayOf(
        0x1F, 0x8B.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(),
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    @Test
    fun gzipInputIsRefusedByName() {
        assertFailsWith<GzipUnsupportedException> { decompressGzip(gzipBytes, 1 shl 20) }
    }

    @Test
    fun plainBytesPassThroughWithinTheLimit() {
        val plain = byteArrayOf(0x1A, 0x02, 0x08, 0x01)
        assertContentEquals(plain, decompressGzip(plain, 1 shl 20))
    }

    @Test
    fun theTileParsersReportItAsTheirOwnTypedError() {
        val e = assertFailsWith<MalformedTileException> { TileUtils.parseMvt(gzipBytes) }
        assertTrue("platform" in e.message.orEmpty(), e.message)
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesDirectory(gzipBytes, TileUtils.PMTILES_COMPRESSION_GZIP) }
    }
}
