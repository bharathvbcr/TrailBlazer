package com.trailblazer.core.geo

import com.trailblazer.core.geo.TileFixtures.Entry
import com.trailblazer.core.geo.TileFixtures.HeaderSpec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmtilesArchiveTest {

    private fun open(file: ByteArray) = PmtilesArchive(file.size.toLong(), TileFixtures.readerOf(file))

    private fun payload(i: Int) = "tile-$i".repeat(10).encodeToByteArray()

    private fun tiles(count: Int): List<Pair<Triple<Int, Int, Int>, ByteArray>> {
        // A contiguous block at z8 plus a few shallower tiles, so ids span several leaves.
        val out = ArrayList<Pair<Triple<Int, Int, Int>, ByteArray>>()
        var i = 0
        out.add(Triple(0, 0, 0) to payload(i++))
        out.add(Triple(1, 1, 0) to payload(i++))
        for (x in 100..110) for (y in 60..64) {
            if (out.size >= count) return out
            out.add(Triple(8, x, y) to payload(i++))
        }
        return out
    }

    @Test
    fun readsTilesFromAFlatRootDirectory() {
        val t = tiles(12)
        val a = open(TileFixtures.archive(t))
        for ((zxy, bytes) in t) {
            assertContentEquals(bytes, a.getTile(zxy.first, zxy.second, zxy.third), "tile $zxy")
        }
        assertNull(a.getTile(8, 0, 0))
        assertNull(a.getTile(2, 0, 0))
    }

    @Test
    fun readsTilesThroughLeafDirectories() {
        val t = tiles(40)
        val a = open(TileFixtures.archive(t, leafSize = 5))
        for ((zxy, bytes) in t) {
            assertContentEquals(bytes, a.getTile(zxy.first, zxy.second, zxy.third), "tile $zxy")
        }
        assertNull(a.getTile(8, 111, 64))
        // The archive is cached per leaf; asking again returns the same answers.
        for ((zxy, bytes) in t) assertContentEquals(bytes, a.getTile(zxy.first, zxy.second, zxy.third))
    }

    @Test
    fun rasterTilesWithNoCompressionAreReturnedAsStored() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val a = open(TileFixtures.archive(listOf(Triple(3, 2, 1) to png), tileType = 2, tileCompression = 1))
        assertContentEquals(png, a.getTile(3, 2, 1))
        assertEquals(TileUtils.TileFormat.RasterPng, a.header.tileType)
    }

    @Test
    fun requestsOutsideTheArchiveOrTheWorldAreMissesNotErrors() {
        val a = open(TileFixtures.archive(tiles(4), minZoom = 0, maxZoom = 8))
        assertNull(a.getTile(9, 0, 0))
        assertNull(a.getTile(-1, 0, 0))
        assertNull(a.getTile(8, -1, 0))
        assertNull(a.getTile(8, 256, 0))
        assertNull(a.getTile(8, 0, Int.MAX_VALUE))
        assertNull(a.getTile(31, 0, 0))
    }

    /** A gzip stream of [mib] MiB of zeros, a few KiB long. */
    private fun gzipOfZeros(mib: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            val chunk = ByteArray(1 shl 20)
            repeat(mib) { gz.write(chunk) }
        }
        return bos.toByteArray()
    }

    @Test
    fun aTileThatInflatesPastTheLimitIsRefused() {
        val bomb = gzipOfZeros(40)
        // Build an archive whose single tile is the raw bomb (so it is not re-wrapped).
        val id = TileUtils.zxyToTileId(2, 1, 1)
        val root = TileFixtures.directory(listOf(Entry(id, 0, bomb.size.toLong(), 1)))
        val header = TileFixtures.pmtilesHeader(
            HeaderSpec(rootOffset = 127, rootLength = root.size.toLong(), dataOffset = 127L + root.size, dataLength = bomb.size.toLong()),
        )
        val a = open(header + root + bomb)
        assertFailsWith<MalformedTileException> { a.getTile(2, 1, 1) }
    }

    @Test
    fun aLeafDirectoryPointingAtItselfIsDetectedNotFollowedForever() {
        // Leaf at offset 0 whose only entry is a leaf pointer to offset 0 again. Its own length is part of its content,
        // so iterate to the fixed point.
        val id = TileUtils.zxyToTileId(3, 4, 4)
        var len = 30L
        var leaf = TileFixtures.directory(listOf(Entry(id, 0, len, 0)))
        repeat(8) {
            len = leaf.size.toLong()
            leaf = TileFixtures.directory(listOf(Entry(id, 0, len, 0)))
        }
        assertEquals(len, leaf.size.toLong(), "fixed point")
        val root = TileFixtures.directory(listOf(Entry(id, 0, len, 0)))
        val header = TileFixtures.pmtilesHeader(
            HeaderSpec(
                rootOffset = 127, rootLength = root.size.toLong(),
                leafOffset = 127L + root.size, leafLength = leaf.size.toLong(),
                dataOffset = 127L + root.size + leaf.size, dataLength = 0,
            ),
        )
        val a = open(header + root + leaf)
        val e = assertFailsWith<MalformedTileException> { a.getTile(3, 4, 4) }
        assertTrue("points back" in e.message.orEmpty(), e.message)
    }

    @Test
    fun deeplyNestedLeafDirectoriesAreRefused() {
        val id = TileUtils.zxyToTileId(3, 4, 4)
        // Chain: root -> d1 -> d2 -> d3 -> d4 -> tile. Built from the deepest directory outwards so each pointer knows
        // its target's offset and length.
        val data = byteArrayOf(1, 2, 3)
        val leaves = ArrayList<ByteArray>()
        var offsets = ArrayList<Long>()
        var next = TileFixtures.directory(listOf(Entry(id, 0, data.size.toLong(), 1)))
        var cursor = 0L
        offsets.add(cursor); cursor += next.size; leaves.add(next)
        repeat(4) {
            next = TileFixtures.directory(listOf(Entry(id, offsets.last(), leaves.last().size.toLong(), 0)))
            offsets.add(cursor); cursor += next.size; leaves.add(next)
        }
        val root = TileFixtures.directory(listOf(Entry(id, offsets.last(), leaves.last().size.toLong(), 0)))
        val leafBytes = leaves.fold(ByteArray(0)) { acc, b -> acc + b }
        val header = TileFixtures.pmtilesHeader(
            HeaderSpec(
                rootOffset = 127, rootLength = root.size.toLong(),
                leafOffset = 127L + root.size, leafLength = leafBytes.size.toLong(),
                dataOffset = 127L + root.size + leafBytes.size, dataLength = data.size.toLong(), tileCompression = 1,
            ),
        )
        val a = open(header + root + leafBytes + data)
        val e = assertFailsWith<MalformedTileException> { a.getTile(3, 4, 4) }
        assertTrue("deeper" in e.message.orEmpty(), e.message)
    }

    @Test
    fun entriesPointingOutsideTheirSectionAreRefused() {
        val id = TileUtils.zxyToTileId(2, 1, 1)
        fun archiveWith(entry: Entry, dataLength: Long = 10): ByteArray {
            val root = TileFixtures.directory(listOf(entry))
            val header = TileFixtures.pmtilesHeader(
                HeaderSpec(rootOffset = 127, rootLength = root.size.toLong(), dataOffset = 127L + root.size, dataLength = dataLength, tileCompression = 1),
            )
            return header + root + ByteArray(dataLength.toInt())
        }
        assertFailsWith<MalformedTileException> { open(archiveWith(Entry(id, 5, 100, 1))).getTile(2, 1, 1) }
        assertFailsWith<MalformedTileException> { open(archiveWith(Entry(id, Long.MAX_VALUE - 1, 5, 1))).getTile(2, 1, 1) }
        assertFailsWith<MalformedTileException> { open(archiveWith(Entry(id, 0, Int.MAX_VALUE.toLong() * 4, 1))).getTile(2, 1, 1) }
        // A leaf pointer into a leaf section that does not exist.
        assertFailsWith<MalformedTileException> { open(archiveWith(Entry(id, 0, 20, 0))).getTile(2, 1, 1) }
        // In-bounds control.
        assertContentEquals(ByteArray(10), open(archiveWith(Entry(id, 0, 10, 1))).getTile(2, 1, 1))
    }

    @Test
    fun aHeaderThatClaimsMoreThanTheFileHoldsIsRefused() {
        val file = TileFixtures.archive(tiles(6))
        val cut = file.copyOf(file.size - 5)
        assertFailsWith<MalformedTileException> { open(cut) }
        assertFailsWith<MalformedTileException> { PmtilesArchive(10L, TileFixtures.readerOf(file)) }
        // Root directory length far past the end.
        val lie = TileFixtures.pmtilesHeader(HeaderSpec(rootOffset = 127, rootLength = 1L shl 40)) + ByteArray(100)
        assertFailsWith<MalformedTileException> { open(lie) }
    }

    @Test
    fun unsupportedCompressionAndZoomsAreNamedAtOpen() {
        fun header(h: HeaderSpec) = TileFixtures.pmtilesHeader(h) + ByteArray(64)
        val brotli = assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(header(HeaderSpec(rootLength = 10, tileCompression = 3))) }
        assertTrue("brotli" in brotli.message.orEmpty(), brotli.message)
        val zstd = assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(header(HeaderSpec(rootLength = 10, internalCompression = 4))) }
        assertTrue("zstd" in zstd.message.orEmpty(), zstd.message)
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(header(HeaderSpec(rootLength = 10, minZoom = 9, maxZoom = 3))) }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(header(HeaderSpec(rootLength = 10, maxZoom = 31))) }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(header(HeaderSpec(rootLength = 0))) }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(header(HeaderSpec(rootLength = 10, maxLat = 95.0))) }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(ByteArray(126)) }
        val wrongVersion = header(HeaderSpec(rootLength = 10)).also { it[7] = 2 }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(wrongVersion) }
        val badMagic = header(HeaderSpec(rootLength = 10)).also { it[0] = 'X'.code.toByte() }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(badMagic) }
        // Offsets with the top bit set are not real file positions.
        val huge = header(HeaderSpec(rootLength = 10)).also { it[15] = 0x80.toByte() }
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesHeader(huge) }
    }

    @Test
    fun directoriesWithImpossibleCountsOrValuesAreRefused() {
        fun dir(build: TileFixtures.Pb.() -> Unit) = TileFixtures.gzip(TileFixtures.Pb().apply(build).build())
        // Claims a million entries, supplies none.
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesDirectory(dir { varint(1_000_000) }) }
        // Count beyond the limit.
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesDirectory(dir { varint(Long.MAX_VALUE) }) }
        // Negative count (varint with the top bit set).
        assertFailsWith<MalformedTileException> { TileUtils.parsePmtilesDirectory(dir { varint(-1L) }) }
        // Non-increasing tile ids.
        assertFailsWith<MalformedTileException> {
            TileUtils.parsePmtilesDirectory(dir { varint(2); varint(5); varint(0); varint(1); varint(1); varint(1); varint(1); varint(1); varint(2) })
        }
        // Zero length.
        assertFailsWith<MalformedTileException> {
            TileUtils.parsePmtilesDirectory(dir { varint(1); varint(5); varint(1); varint(0); varint(1) })
        }
        // First offset marked "contiguous" has nothing to be contiguous with.
        assertFailsWith<MalformedTileException> {
            TileUtils.parsePmtilesDirectory(dir { varint(1); varint(5); varint(1); varint(10); varint(0) })
        }
        // An empty directory body is an empty directory.
        assertEquals(0, TileUtils.parsePmtilesDirectory(ByteArray(0), TileUtils.PMTILES_COMPRESSION_NONE).size)
    }

    @Test
    fun anUncompressedDirectoryThatStartsWithTheGzipMagicIsNotInflated() {
        // 31 entries (0x1F) whose first tile-id delta starts with 0x8B: legal when the directory is stored uncompressed.
        val entries = (0 until 31).map { Entry(tileId = 139L + it * 1000L, offset = it * 10L, length = 10, runLength = 1) }
        val raw = TileFixtures.directory(entries, compression = 1)
        assertEquals(0x1F.toByte(), raw[0])
        assertEquals(0x8B.toByte(), raw[1])
        val parsed = TileUtils.parsePmtilesDirectory(raw, TileUtils.PMTILES_COMPRESSION_NONE)
        assertEquals(31, parsed.size)
        assertEquals(entries.last().tileId, parsed.last().tileId)
    }

    @Test
    fun findEntryReturnsLeafPointersAndRespectsRunEnds() {
        val entries = listOf(
            TileUtils.PmtilesEntry(5, 0, 10, 1),
            TileUtils.PmtilesEntry(10, 0, 99, 0),
            TileUtils.PmtilesEntry(500, 20, 10, 2),
        )
        assertNull(TileUtils.findPmtilesEntry(entries, 4))
        assertEquals(5L, assertNotNull(TileUtils.findPmtilesEntry(entries, 5)).tileId)
        assertNull(TileUtils.findPmtilesEntry(entries, 6)) // run of 1 ended and no leaf covers it
        assertTrue(assertNotNull(TileUtils.findPmtilesEntry(entries, 400)).isLeaf)
        assertEquals(500L, assertNotNull(TileUtils.findPmtilesEntry(entries, 501)).tileId)
        assertNull(TileUtils.findPmtilesEntry(entries, 502))
        assertNull(TileUtils.findPmtilesEntry(emptyList(), 1))
    }
}
