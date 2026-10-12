package com.trailblazer.core.geo

import com.trailblazer.core.geo.TileFixtures.Entry
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Deterministic fuzzing of everything that reads a user-supplied map file: vector tiles, PMTiles headers, directories
 * and whole archives. Fixed seeds, a few thousand inputs per target. The contract under test: a parser either returns
 * or throws [MalformedTileException], never anything else, and never takes long.
 */
class TileFuzzTest {
    private val perCallBudgetMs = 2_000L

    private fun hex(b: ByteArray, max: Int = 96): String =
        b.take(max).joinToString("") { "%02x".format(it) } + if (b.size > max) "...(${b.size} bytes)" else ""

    private class Tally { var accepted = 0; var rejected = 0; var slowestMs = 0L }

    private inline fun probe(label: String, tally: Tally, input: ByteArray, block: () -> Unit) {
        val t0 = System.nanoTime()
        try {
            block()
            tally.accepted++
        } catch (e: MalformedTileException) {
            tally.rejected++
        } catch (e: Throwable) {
            fail("$label threw ${e::class.simpleName}: ${e.message} on ${hex(input)}")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > tally.slowestMs) tally.slowestMs = ms
        assertTrue(ms < perCallBudgetMs, "$label took $ms ms on ${hex(input)}")
    }

    private fun mutate(rnd: Random, src: ByteArray): ByteArray {
        var b = src.copyOf()
        repeat(1 + rnd.nextInt(4)) {
            if (b.isEmpty()) return b
            when (rnd.nextInt(8)) {
                0 -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte()
                1 -> b[rnd.nextInt(b.size)] = (b[rnd.nextInt(b.size)].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                2 -> b = b.copyOf(rnd.nextInt(b.size + 1))
                3 -> { // splice in a run of 0xFF (maximal varints)
                    val at = rnd.nextInt(b.size)
                    val run = ByteArray(1 + rnd.nextInt(14)) { 0xFF.toByte() }
                    b = b.copyOfRange(0, at) + run + b.copyOfRange(at, b.size)
                }
                4 -> { // delete a slice
                    val a = rnd.nextInt(b.size)
                    val z = minOf(b.size, a + 1 + rnd.nextInt(8))
                    b = b.copyOfRange(0, a) + b.copyOfRange(z, b.size)
                }
                5 -> { // insert random bytes
                    val at = rnd.nextInt(b.size + 1)
                    b = b.copyOfRange(0, at) + ByteArray(1 + rnd.nextInt(8)) { rnd.nextInt(256).toByte() } + b.copyOfRange(at, b.size)
                }
                6 -> b[rnd.nextInt(b.size)] = intArrayOf(0, 0x7F, 0x80, 0xFF)[rnd.nextInt(4)].toByte()
                else -> { // duplicate a slice
                    val a = rnd.nextInt(b.size)
                    val z = minOf(b.size, a + 1 + rnd.nextInt(16))
                    b = b.copyOfRange(0, z) + b.copyOfRange(a, b.size)
                }
            }
        }
        return b
    }

    private fun corpus(): List<ByteArray> {
        val big = TileFixtures.Geom().moveTo(0, 0).lineTo(*LongArray(400) { (it % 50) * 7L }).build()
        return listOf(
            TileFixtures.contourTile(),
            TileFixtures.contourTile(TileFixtures.Pb().fixed64(3, 812.5.toBits()).build()),
            TileFixtures.mvtTile(
                TileFixtures.mvtLayer("water", listOf(TileFixtures.FeatureSpec(3, big)), extent = 512),
                TileFixtures.mvtLayer("roads", listOf(TileFixtures.FeatureSpec(2, big), TileFixtures.FeatureSpec(1, big)), keys = listOf("k"), values = listOf(TileFixtures.mvtValueString("v"))),
            ),
        )
    }

    @Test
    fun mvtDecoderOnMutatedAndRandomTiles() {
        val rnd = Random(20261012L)
        val tally = Tally()
        val base = corpus()
        // Every prefix of every valid tile.
        for (tile in base) for (cut in 0..tile.size) probe("prefix $cut", tally, tile.copyOf(cut)) { TileUtils.parseMvt(tile.copyOf(cut)) }
        // 4000 mutations, half of them gzip-wrapped (mutated before wrapping and after).
        repeat(4000) { i ->
            val m = mutate(rnd, base[i % base.size])
            val input = when (rnd.nextInt(3)) {
                0 -> m
                1 -> TileFixtures.gzip(m)
                else -> mutate(rnd, TileFixtures.gzip(base[i % base.size]))
            }
            probe("mutation #$i", tally, input) { TileUtils.parseMvt(input) }
        }
        // 1500 pure noise buffers of mixed sizes, plus ones that look like gzip.
        repeat(1500) { i ->
            val noise = ByteArray(rnd.nextInt(400)).also { rnd.nextBytes(it) }
            if (i % 5 == 0 && noise.size > 2) { noise[0] = 0x1F; noise[1] = 0x8B.toByte() }
            probe("noise #$i", tally, noise) { TileUtils.parseMvt(noise) }
        }
        println("fuzz mvt: accepted=${tally.accepted} rejected=${tally.rejected} slowest=${tally.slowestMs} ms")
        assertTrue(tally.accepted > 100, "the fuzzer must still hit valid tiles (accepted ${tally.accepted})")
        assertTrue(tally.rejected > 1000, "and mostly produce invalid ones (rejected ${tally.rejected})")
    }

    @Test
    fun pmtilesHeaderOnMutatedAndRandomBytes() {
        val rnd = Random(77L)
        val tally = Tally()
        val valid = TileFixtures.pmtilesHeader(TileFixtures.HeaderSpec(rootLength = 40, dataOffset = 200, dataLength = 100))
        repeat(4000) { i ->
            val input = if (i % 4 == 0) ByteArray(rnd.nextInt(200)).also { rnd.nextBytes(it) } else mutate(rnd, valid)
            probe("header #$i", tally, input) { TileUtils.parsePmtilesHeader(input) }
        }
        println("fuzz pmtiles header: accepted=${tally.accepted} rejected=${tally.rejected}")
        assertTrue(tally.accepted > 50 && tally.rejected > 500)
    }

    @Test
    fun pmtilesDirectoryOnMutatedAndRandomBytes() {
        val rnd = Random(4242L)
        val tally = Tally()
        val entries = (0 until 40).map { Entry(tileId = 1L + it * 3L, offset = it * 100L, length = 100, runLength = if (it % 7 == 0) 0 else 1 + it % 3) }
        val seeds = listOf(TileFixtures.directory(entries, 1), TileFixtures.directory(entries, 2))
        repeat(4000) { i ->
            val comp = if (i % 2 == 0) TileUtils.PMTILES_COMPRESSION_NONE else TileUtils.PMTILES_COMPRESSION_GZIP
            val input = if (i % 5 == 0) ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) } else mutate(rnd, seeds[i % 2])
            probe("directory #$i", tally, input) {
                val parsed = TileUtils.parsePmtilesDirectory(input, comp)
                // Whatever was accepted must be searchable without surprises.
                TileUtils.findPmtilesEntry(parsed, rnd.nextLong() and Long.MAX_VALUE)
                TileUtils.findPmtilesEntry(parsed, 1L)
            }
        }
        println("fuzz pmtiles directory: accepted=${tally.accepted} rejected=${tally.rejected}")
        assertTrue(tally.accepted > 20 && tally.rejected > 500)
    }

    @Test
    fun wholeArchivesWithAnyByteDamaged() {
        val rnd = Random(9001L)
        val tally = Tally()
        val tiles = (0 until 24).map { Triple(6, 10 + it % 6, 20 + it / 6) to TileFixtures.contourTile() }
        val seeds = listOf(TileFixtures.archive(tiles, leafSize = 0, maxZoom = 8), TileFixtures.archive(tiles, leafSize = 4, maxZoom = 8))
        val coords = listOf(Triple(6, 10, 20), Triple(6, 12, 22), Triple(6, 15, 23), Triple(0, 0, 0), Triple(8, 255, 255), Triple(30, 5, 5))
        repeat(2500) { i ->
            val input = mutate(rnd, seeds[i % 2])
            probe("archive #$i", tally, input) {
                val a = PmtilesArchive(input.size.toLong(), TileFixtures.readerOf(input))
                for ((z, x, y) in coords) {
                    a.getTile(z, x, y)?.let { tile ->
                        try { TileUtils.parseMvt(tile) } catch (_: MalformedTileException) {}
                    }
                }
            }
        }
        println("fuzz pmtiles archive: accepted=${tally.accepted} rejected=${tally.rejected} slowest=${tally.slowestMs} ms")
        assertTrue(tally.accepted > 100 && tally.rejected > 100)
    }

    @Test
    fun tileIdAndCoordinateConversionsOnHostileNumbers() {
        val rnd = Random(31L)
        val edge = longArrayOf(0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, (1L shl 53), (1L shl 53) + 1, (1L shl 62), (1L shl 62) - 1)
        val ids = edge.toList() + List(3000) { rnd.nextLong() } + List(3000) { (rnd.nextLong() ushr rnd.nextInt(64)) }
        val tally = Tally()
        for (id in ids) {
            val bytes = ByteArray(8) { ((id ushr (8 * it)) and 0xFF).toByte() }
            probe("tileIdToZxy($id)", tally, bytes) {
                val (z, x, y) = TileUtils.tileIdToZxy(id)
                assertTrue(TileUtils.isValidTile(z, x, y), "decoded $z/$x/$y is not a tile")
                assertTrue(TileUtils.zxyToTileId(z, x, y) == id, "round trip of $id")
            }
        }
        repeat(5000) {
            val z = if (it % 3 == 0) rnd.nextInt() else rnd.nextInt(40) - 2
            val x = if (it % 2 == 0) rnd.nextInt() else rnd.nextInt(1 shl 12)
            val y = if (it % 2 == 0) rnd.nextInt() else rnd.nextInt(1 shl 12)
            probe("zxyToTileId($z,$x,$y)", tally, byteArrayOf()) {
                val id = TileUtils.zxyToTileId(z, x, y)
                assertTrue(id >= 0L)
            }
            // Pure functions that feed SQL and the renderer must tolerate any zoom.
            TileUtils.xyzYToTmsY(y, z)
            TileUtils.latLonToTile(rnd.nextDouble() * 400 - 200, rnd.nextDouble() * 800 - 400, z)
            TileUtils.wrapTileX(x, z)
            TileUtils.isValidTile(z, x, y)
        }
        println("fuzz tile ids: accepted=${tally.accepted} rejected=${tally.rejected}")
        assertTrue(tally.accepted > 3000 && tally.rejected > 1000)
    }
}
