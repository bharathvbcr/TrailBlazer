package com.example.trailblazer.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.MapArchiveFixtures.MbtilesSpec
import com.trailblazer.core.geo.TileUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineMapHardeningTest {
    private lateinit var context: Context
    private lateinit var prefs: PrefsRepository
    private lateinit var manager: OfflineMapManager
    private lateinit var scratch: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PrefsRepository(context)
        manager = OfflineMapManager(context, prefs)
        manager.mapsDir.deleteRecursively()
        manager.mapsDir.mkdirs()
        // The settings store outlives a single test in this JVM; start each one with no active map.
        runBlocking { prefs.update { it.copy(activeOfflineMapPath = null) } }
        scratch =File(context.cacheDir, "map-fixtures").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        manager.mapsDir.deleteRecursively()
        scratch.deleteRecursively()
    }

    private fun mbtiles(name: String, spec: MbtilesSpec = MbtilesSpec()) = MapArchiveFixtures.mbtiles(File(scratch, name), spec)

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun mapsDirNames() = manager.mapsDir.list()!!.sorted()

    // ---- MBTiles ----

    @Test
    fun rowsAreFlippedBetweenXyzAndTms() {
        val a = byteArrayOf(1, 1, 1)
        val b = byteArrayOf(2, 2, 2)
        // XYZ row 2 and row 5 at z3 are different tiles; stored as TMS rows 5 and 2 respectively.
        val f = mbtiles("flip.mbtiles", MbtilesSpec(tiles = listOf(Triple(3, 4, 2) to a, Triple(3, 4, 5) to b)))
        MbtilesTileSource(f).use { s ->
            assertArrayEquals(a, s.getRawTile(3, 4, 2))
            assertArrayEquals(b, s.getRawTile(3, 4, 5))
            assertNull(s.getRawTile(3, 4, 3))
        }
    }

    @Test
    fun metadataIsOptionalButATilesTableIsNot() {
        val noMeta = mbtiles("nometa.mbtiles", MbtilesSpec(metadata = null, tiles = listOf(Triple(1, 0, 0) to byteArrayOf(9))))
        MbtilesTileSource(noMeta).use { s ->
            assertEquals("nometa", s.name)
            assertEquals(0, s.minZoom)
            assertEquals(18, s.maxZoom)
            assertNull(s.bounds)
            assertArrayEquals(byteArrayOf(9), s.getRawTile(1, 0, 0))
        }
        val noTiles = mbtiles("notiles.mbtiles", MbtilesSpec(tilesTable = false))
        assertNull("metadata alone is not a map", manager.inspectFile(noTiles))
        // A tiles table with the wrong columns.
        val wrong = File(scratch, "wrong.mbtiles")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(wrong, null).apply {
            execSQL("CREATE TABLE tiles (a integer, b integer);")
            close()
        }
        assertNull(manager.inspectFile(wrong))
        // Not leaving a handle behind: the file can be replaced and reopened.
        assertTrue(wrong.delete())
    }

    @Test
    fun malformedMetadataFallsBackToSafeDefaults() {
        val f = mbtiles(
            "bad.mbtiles",
            MbtilesSpec(
                metadata = listOf(
                    "name" to "x'; DROP TABLE tiles;--",
                    "format" to "png",
                    "minzoom" to "abc",
                    "maxzoom" to "99",
                    "bounds" to "1,2,3",
                ),
                tiles = listOf(Triple(1, 0, 0) to byteArrayOf(7)),
            ),
        )
        MbtilesTileSource(f).use { s ->
            assertEquals("x'; DROP TABLE tiles;--", s.name)
            assertEquals(0, s.minZoom)
            assertEquals(18, s.maxZoom)
            assertNull(s.bounds)
            assertNotNull("the tiles table survived the hostile name", s.getRawTile(1, 0, 0))
        }
        val reversed = mbtiles("rev.mbtiles", MbtilesSpec(metadata = listOf("minzoom" to "12", "maxzoom" to "3", "bounds" to "10,50,-10,40"), tiles = listOf(Triple(1, 0, 0) to byteArrayOf(7))))
        MbtilesTileSource(reversed).use { s ->
            assertTrue("min above max falls back", s.minZoom <= s.maxZoom)
            assertNull("bounds with min > max are ignored", s.bounds)
        }
    }

    @Test
    fun theArchiveIsOpenedReadOnlyAndNeverModified() {
        val f = mbtiles("ro.mbtiles", MbtilesSpec(tiles = listOf(Triple(2, 1, 1) to byteArrayOf(5, 5))))
        val before = sha(f)
        val siblingsBefore = scratch.list()!!.sorted()
        MbtilesTileSource(f).use { s ->
            repeat(20) { s.getRawTile(2, 1, 1); s.getRawTile(2, 3, 3) }
        }
        assertEquals(before, sha(f))
        assertEquals("no -journal or -wal files appear", siblingsBefore, scratch.list()!!.sorted())
    }

    @Test
    fun anOversizedBlobIsNeverLoadedAndBadCoordinatesAreMisses() {
        val big = ByteArray(MAX_TILE_BLOB_BYTES + 1024) { 1 }
        val f = mbtiles("big.mbtiles", MbtilesSpec(tiles = listOf(Triple(2, 0, 0) to big, Triple(2, 1, 1) to byteArrayOf(3))))
        MbtilesTileSource(f).use { s ->
            assertNull("an 8 MiB+ blob is refused by the query", s.getRawTile(2, 0, 0))
            assertArrayEquals(byteArrayOf(3), s.getRawTile(2, 1, 1))
            for ((z, x, y) in listOf(Triple(-1, 0, 0), Triple(31, 0, 0), Triple(2, 4, 0), Triple(2, 0, -1), Triple(2, Int.MAX_VALUE, Int.MIN_VALUE), Triple(Int.MAX_VALUE, 0, 0))) {
                assertNull("$z/$x/$y", s.getRawTile(z, x, y))
                assertNull("$z/$x/$y", s.getTile(z, x, y))
            }
        }
    }

    @Test
    fun aClosedSourceAnswersNullInsteadOfThrowing() {
        val f = mbtiles("closed.mbtiles", MbtilesSpec(tiles = listOf(Triple(1, 0, 0) to byteArrayOf(1))))
        val s = MbtilesTileSource(f)
        s.close()
        assertNull(s.getRawTile(1, 0, 0))
        assertNull(s.getTile(1, 0, 0))
        s.close()
        val p = File(scratch, "c.pmtiles").apply { writeBytes(MapArchiveFixtures.pmtiles(listOf(Triple(0, 0, 0) to MapArchiveFixtures.contourTile()))) }
        val ps = PmtilesTileSource(p)
        assertNotNull(ps.getTile(0, 0, 0))
        ps.close()
        assertNull(ps.getTile(0, 0, 0))
    }

    // ---- tile decoding ----

    @Test
    fun vectorTilesDecodeWhateverTheMetadataSays() {
        val mvtGz = MapArchiveFixtures.gzip(MapArchiveFixtures.contourTile())
        val f = mbtiles("pbf.mbtiles", MbtilesSpec(metadata = listOf("format" to "pbf", "maxzoom" to "14"), tiles = listOf(Triple(10, 3, 4) to mvtGz)))
        MbtilesTileSource(f).use { s ->
            assertEquals(TileUtils.TileFormat.VectorMvt, s.format)
            val t = s.getTile(10, 3, 4) as DecodedTile.Vector
            val feature = t.vectorTile.layers.single().features.single()
            assertEquals(1200.0, feature.elevationM)
            assertTrue(feature.isContour)
        }
        // The metadata claims PNG but the tile is a vector tile: sniffed, not crashed on.
        val liar = mbtiles("liar.mbtiles", MbtilesSpec(metadata = listOf("format" to "png"), tiles = listOf(Triple(1, 0, 0) to mvtGz)))
        MbtilesTileSource(liar).use { assertTrue(it.getTile(1, 0, 0) is DecodedTile.Vector) }
    }

    @Test
    fun garbageTilesAreAbsentNotFatal() {
        val tiles = listOf(
            Triple(1, 0, 0) to ByteArray(0),
            Triple(1, 1, 0) to ByteArray(40) { 0xFF.toByte() },
            Triple(1, 0, 1) to byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0),
            Triple(1, 1, 1) to "not a tile at all".toByteArray(),
        )
        val f = mbtiles("junk.mbtiles", MbtilesSpec(tiles = tiles))
        MbtilesTileSource(f).use { s ->
            for ((zxy, _) in tiles) assertNull("$zxy", s.getTile(zxy.first, zxy.second, zxy.third))
        }
    }

    @Test
    fun largeRasterTilesAreSubsampledAndPixelBombsAreRefused() {
        val wide = MapArchiveFixtures.png(3000, 600)
        val decoded = decodeBitmapBounded(wide)!!
        assertTrue("max side ${maxOf(decoded.width, decoded.height)}", maxOf(decoded.width, decoded.height) <= MAX_RASTER_SIDE)
        val normal = decodeBitmapBounded(MapArchiveFixtures.png(256, 256))!!
        assertEquals(256, normal.width)

        val bomb = MapArchiveFixtures.pngDeclaring(60_000, 60_000)
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bomb, 0, bomb.size, probe)
        assertEquals("the fixture really declares 60000 px", 60_000, probe.outWidth)
        assertNull("3.6 billion pixels are never allocated", decodeBitmapBounded(bomb))
        assertNull(decodeTileBytes(bomb, TileUtils.TileFormat.RasterPng))
    }

    @Test
    fun theSubsamplingRuleIsPowersOfTwoUnderTheSideAndPixelCaps() {
        assertEquals(1, rasterSampleSize(256, 256))
        assertEquals(1, rasterSampleSize(1024, 1024))
        assertEquals(2, rasterSampleSize(1025, 10))
        assertEquals(4, rasterSampleSize(3000, 600))
        assertEquals(8, rasterSampleSize(8000, 8000))
        assertNull("a 64,000,001-pixel declaration", rasterSampleSize(8000, 8001))
        assertNull(rasterSampleSize(60_000, 60_000))
        assertNull(rasterSampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(rasterSampleSize(0, 256))
        assertNull(rasterSampleSize(-5, 256))
    }

    // ---- PMTiles ----

    @Test
    fun aSpecLayoutPmtilesArchiveImportsAndServesTiles() = runBlocking {
        val mvt = MapArchiveFixtures.contourTile()
        val png = MapArchiveFixtures.png(8, 8)
        val vec = File(scratch, "contours.pmtiles").apply {
            writeBytes(MapArchiveFixtures.pmtiles(listOf(Triple(0, 0, 0) to mvt, Triple(1, 1, 0) to mvt), tileType = 1))
        }
        val info = manager.importMap(Uri.fromFile(vec)).getOrThrow()
        assertEquals(TileUtils.TileFormat.VectorMvt, info.format)
        assertEquals(0, info.minZoom)
        assertEquals(1, info.maxZoom)
        assertEquals(-122.5, info.bounds!!.minLon, 1e-6)
        assertEquals(38.0, info.bounds!!.maxLat, 1e-6)
        manager.openSource(info.file)!!.use { s ->
            assertTrue(s.getTile(0, 0, 0) is DecodedTile.Vector)
            assertTrue(s.getTile(1, 1, 0) is DecodedTile.Vector)
            assertNull(s.getTile(1, 0, 0))
            assertNull(s.getTile(2, 0, 0))
        }
        val leafy = File(scratch, "photo.pmtiles").apply {
            writeBytes(MapArchiveFixtures.pmtiles(listOf(Triple(1, 0, 1) to png), tileType = 2, viaLeaf = true))
        }
        val info2 = manager.importMap(Uri.fromFile(leafy)).getOrThrow()
        manager.openSource(info2.file)!!.use { s ->
            assertTrue("a tile behind a leaf directory", s.getTile(1, 0, 1) is DecodedTile.Raster)
        }
    }

    @Test
    fun corruptOrUnsupportedPmtilesAreRejectedAtImport() = runBlocking {
        val good = MapArchiveFixtures.pmtiles(listOf(Triple(0, 0, 0) to MapArchiveFixtures.contourTile()))
        val cases = mapOf(
            "truncated.pmtiles" to good.copyOf(good.size - 10),
            "shorter-than-header.pmtiles" to good.copyOf(100),
            "brotli.pmtiles" to good.copyOf().also { it[98] = 3 },
            "badmagic.pmtiles" to good.copyOf().also { it[0] = 'Q'.code.toByte() },
            "zoom99.pmtiles" to good.copyOf().also { it[101] = 99 },
        )
        for ((name, bytes) in cases) {
            val f = File(scratch, name).apply { writeBytes(bytes) }
            val r = manager.importMap(Uri.fromFile(f))
            assertTrue("$name must be rejected", r.isFailure)
        }
        assertEquals("nothing left behind", emptyList<String>(), mapsDirNames())
    }

    // ---- import ----

    @Test
    fun aFailedImportLeavesNoFilesAndKeepsTheActiveMap() = runBlocking {
        val ok = mbtiles("keep.mbtiles", MbtilesSpec(tiles = listOf(Triple(1, 0, 0) to byteArrayOf(1))))
        val kept = manager.importMap(Uri.fromFile(ok)).getOrThrow()
        val junk = File(scratch, "junk.mbtiles").apply { writeText("this is not sqlite, but it is long enough to look like a file") }
        assertTrue(manager.importMap(Uri.fromFile(junk)).isFailure)
        assertTrue(manager.importMap(Uri.fromFile(File(scratch, "missing.mbtiles"))).isFailure)
        assertEquals(listOf(kept.file.name), mapsDirNames())
        assertEquals(kept.file.absolutePath, prefs.settings.first().activeOfflineMapPath)
    }

    @Test
    fun importsAreCappedInSize() = runBlocking {
        val small = OfflineMapManager(context, prefs, maxImportBytes = 4096)
        val f = mbtiles("bulky.mbtiles", MbtilesSpec(tiles = listOf(Triple(1, 0, 0) to ByteArray(40_000) { it.toByte() })))
        assertTrue(f.length() > 4096)
        val r = small.importMap(Uri.fromFile(f))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("limit"))
        assertEquals("the partial copy was removed", emptyList<String>(), mapsDirNames())
    }

    @Test
    fun aCancelledImportCleansUpAfterItself() = runBlocking {
        // The copy loop itself: cancelled after the third chunk, it stops reading at once.
        lateinit var job: kotlinx.coroutines.Job
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads == 3) job.cancel()
                if (reads > 50) return -1
                java.util.Arrays.fill(b, off, off + len, 7)
                return len
            }
        }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { copyBounded(stream, java.io.ByteArrayOutputStream(), Long.MAX_VALUE, 1024) }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("one chunk after the cancel at most", 3, reads)

        // The whole import: a 200 MB file is cancelled once its staging copy exists; nothing is left behind.
        val big = File(scratch, "huge.mbtiles")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(200L * 1024 * 1024) }
        val import = launch(Dispatchers.Default) { manager.importMap(Uri.fromFile(big)) }
        val deadline = System.currentTimeMillis() + 20_000
        while (import.isActive && manager.mapsDir.list()!!.none { it.startsWith(".import-") } && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(1)
        }
        import.cancelAndJoin()
        assertEquals("the staging copy is gone", emptyList<String>(), mapsDirNames())
        assertNull(prefs.settings.first().activeOfflineMapPath)
    }

    @Test
    fun concurrentImportsEachGetTheirOwnFile() = runBlocking {
        val f = mbtiles("same.mbtiles", MbtilesSpec(tiles = listOf(Triple(1, 0, 0) to byteArrayOf(1))))
        val results = (1..6).map { async(Dispatchers.Default) { manager.importMap(Uri.fromFile(f)) } }.awaitAll()
        assertTrue(results.all { it.isSuccess })
        assertEquals(6, results.map { it.getOrThrow().file.name }.toSet().size)
        assertEquals(6, mapsDirNames().size)
        assertTrue("no temp files remain", mapsDirNames().none { it.endsWith(".tmp") })
    }

    @Test
    fun deletingAnInactiveMapDoesNotChangeTheActiveOne() = runBlocking {
        val infos = listOf("a_map", "b_map", "c_map").map { n ->
            manager.importMap(Uri.fromFile(mbtiles("$n.mbtiles", MbtilesSpec(metadata = listOf("name" to n), tiles = listOf(Triple(1, 0, 0) to byteArrayOf(1)))))).getOrThrow()
        }
        val active = infos.last().file.absolutePath
        assertEquals(active, prefs.settings.first().activeOfflineMapPath)
        assertTrue(manager.deleteMap(infos[1].file))
        assertEquals("deleting b leaves c active", active, prefs.settings.first().activeOfflineMapPath)
        assertTrue(manager.deleteMap(infos[2].file))
        assertEquals("deleting the active map falls back to the first remaining", infos[0].file.absolutePath, prefs.settings.first().activeOfflineMapPath)
        assertTrue(manager.deleteMap(infos[0].file))
        assertNull(prefs.settings.first().activeOfflineMapPath)
        assertNull(manager.openActiveSource())
    }

    @Test
    fun onlyFilesInsideTheMapsFolderCanBeDeletedOrOpenedAsTheActiveMap() = runBlocking {
        val outside = mbtiles("outside.mbtiles", MbtilesSpec(tiles = listOf(Triple(1, 0, 0) to byteArrayOf(1))))
        assertFalse(manager.deleteMap(outside))
        assertTrue(outside.exists())
        prefs.update { it.copy(activeOfflineMapPath = outside.absolutePath) }
        assertNull("a path outside maps/ in settings is not opened", manager.openActiveSource())
    }

    @Test
    fun theActiveMapIsOpenedOffTheCallersThread() = runBlocking {
        val info = manager.importMap(Uri.fromFile(mbtiles("t.mbtiles", MbtilesSpec(tiles = listOf(Triple(1, 0, 0) to byteArrayOf(1)))))).getOrThrow()
        val source = manager.openActiveSource()!!
        source.use { assertEquals(info.file.absolutePath, it.file.absolutePath) }
        manager.deleteMap(info.file)
        assertNull("a deleted active map leaves nothing to open", manager.openActiveSource())
    }
}
