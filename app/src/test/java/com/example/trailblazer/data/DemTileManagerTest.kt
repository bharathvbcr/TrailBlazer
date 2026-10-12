package com.example.trailblazer.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.geo.DemResolution
import com.trailblazer.core.geo.DemTileId
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class DemTileManagerTest {
    private lateinit var context: Context
    private lateinit var manager: DemTileManager
    private lateinit var scratch: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = DemTileManager(context)
        manager.demDir.deleteRecursively()
        manager.demDir.mkdirs()
        scratch = File(context.cacheDir, "dem-fixtures").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        manager.demDir.deleteRecursively()
        scratch.deleteRecursively()
    }

    /** A `.hgt` file of the exact size of [resolution], all posts [fill] metres (written big-endian). */
    private fun hgt(name: String, resolution: DemResolution = DemResolution.ThreeArcSecond, fill: Int = 0, bytes: Long = resolution.fileBytes): File {
        val f = File(scratch, name)
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(bytes)
            if (fill != 0) {
                val row = ByteArray(resolution.side * 2) { i -> if (i % 2 == 0) (fill shr 8).toByte() else fill.toByte() }
                for (r in 0 until resolution.side) {
                    raf.seek(r.toLong() * row.size)
                    raf.write(row)
                }
            }
        }
        return f
    }

    private fun dir() = manager.demDir.list()!!.sorted()

    @Test
    fun aThreeArcSecondTileImportsUnderItsCanonicalName() = runBlocking {
        val f = hgt("N36W119.SRTMGL1.hgt")
        val before = manager.revision.value
        val info = manager.importTile(Uri.fromFile(f)).getOrThrow()
        assertEquals(DemTileId(36, -119), info.id)
        assertEquals(DemResolution.ThreeArcSecond, info.resolution)
        assertEquals(listOf("N36W119.hgt"), dir())
        assertEquals(listOf(info.id), manager.listTiles().map { it.id })
        assertEquals(before + 1, manager.revision.value)
    }

    @Test
    fun aOneArcSecondTileImportsToo() = runBlocking {
        val info = manager.importTile(Uri.fromFile(hgt("S33W071.hgt", DemResolution.OneArcSecond))).getOrThrow()
        assertEquals(DemResolution.OneArcSecond, info.resolution)
        assertEquals(DemTileId(-33, -71), info.id)
        assertEquals(DemResolution.OneArcSecond.fileBytes, File(manager.demDir, "S33W071.hgt").length())
    }

    @Test
    fun aFileOfTheWrongSizeIsRefusedAndNothingIsLeftBehind() = runBlocking {
        for (size in listOf(0L, 2_884_801L, 2_884_803L, 25_934_401L, 1201L * 1201)) {
            val r = manager.importTile(Uri.fromFile(hgt("N36W119.hgt", bytes = size)))
            assertTrue("size $size", r.isFailure)
            assertTrue(r.exceptionOrNull()!!.message!!.contains("not a 1 or 3 arc-second SRTM tile"))
            assertEquals(emptyList<String>(), dir())
        }
        assertEquals(0, manager.revision.value)
    }

    @Test
    fun aZipIsRefusedWithAClearMessageBeforeAnyByteIsCopied() = runBlocking {
        val uri = Uri.parse("content://dem.test/N36W119.hgt.zip")
        var reads = 0
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int { reads++; return -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return -1 }
        })
        val r = manager.importTile(uri)
        assertTrue(r.isFailure)
        val message = r.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("compressed archive") && message.contains("Unzip"))
        assertEquals("the stream was never read", 0, reads)
        assertEquals(emptyList<String>(), dir())
        // Even a zip named like a tile of the right size.
        assertTrue(manager.importTile(Uri.fromFile(hgt("N36W119.hgt.zip"))).isFailure)
        assertTrue(manager.importTile(Uri.fromFile(hgt("N36W119.hgt.gz"))).isFailure)
    }

    @Test
    fun aNameThatCannotBePlacedIsRefused() = runBlocking {
        val r = manager.importTile(Uri.fromFile(hgt("elevation.hgt")))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("N36W119.hgt"))
        assertTrue(manager.importTile(Uri.fromFile(hgt("S00E010.hgt"))).isFailure)
        assertEquals(emptyList<String>(), dir())
    }

    @Test
    fun anEndlessStreamIsStoppedAtTheLargestTileSize() = runBlocking {
        val uri = Uri.parse("content://dem.test/N10E010.hgt")
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 1); return len }
        })
        val r = manager.importTile(uri)
        assertTrue(r.isFailure)
        assertEquals("the partial copy was removed", emptyList<String>(), dir())
    }

    @Test
    fun aStreamThatEndsShortIsRefusedAfterTheCopy() = runBlocking {
        val uri = Uri.parse("content://dem.test/N10E010.hgt")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(1_000_000)))
        val r = manager.importTile(uri)
        assertTrue(r.isFailure)
        assertEquals(emptyList<String>(), dir())
    }

    @Test
    fun aCancelledImportLeavesNothingBehind() = runBlocking {
        val uri = Uri.parse("content://dem.test/N36W119.hgt")
        lateinit var job: kotlinx.coroutines.Job
        var reads = 0
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads == 3) job.cancel()
                if (reads > 8) return -1
                java.util.Arrays.fill(b, off, off + len, 9)
                return len
            }
        })
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.importTile(uri) }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("reading stopped right after the cancel", 3, reads)
        assertEquals(emptyList<String>(), dir())
        assertEquals(emptyList<DemTileId>(), manager.listTiles().map { it.id })
        assertEquals(0, manager.revision.value)
    }

    @Test
    fun importingTheSameTileAgainReplacesItAndAFailedReplacementKeepsTheOldOne() = runBlocking {
        manager.importTile(Uri.fromFile(hgt("N36W119.hgt", fill = 100))).getOrThrow()
        manager.importTile(Uri.fromFile(hgt("N36W119.hgt", fill = 200))).getOrThrow()
        assertEquals(listOf("N36W119.hgt"), dir())
        RandomAccessFile(File(manager.demDir, "N36W119.hgt"), "r").use { assertEquals(0, it.readUnsignedByte()); assertEquals(200, it.readUnsignedByte()) }
        assertTrue(manager.importTile(Uri.fromFile(hgt("N36W119.hgt", bytes = 5))).isFailure)
        assertEquals(listOf("N36W119.hgt"), dir())
        assertEquals(DemResolution.ThreeArcSecond.fileBytes, File(manager.demDir, "N36W119.hgt").length())
    }

    @Test
    fun staleStagingFilesAreSweptAndNeverListedAsTiles() = runBlocking {
        File(manager.demDir, ".import-1.tmp").writeBytes(ByteArray(10))
        File(manager.demDir, "notes.txt").writeText("x")
        File(manager.demDir, "N36W119.hgt").writeBytes(ByteArray(10)) // truncated: not a tile
        assertEquals(emptyList<DemTileId>(), manager.listTiles().map { it.id })
        manager.importTile(Uri.fromFile(hgt("N40E010.hgt"))).getOrThrow()
        assertFalse(dir().contains(".import-1.tmp"))
    }

    @Test
    fun deletingATileRemovesItAndBumpsTheRevision() = runBlocking {
        manager.importTile(Uri.fromFile(hgt("N36W119.hgt"))).getOrThrow()
        val rev = manager.revision.value
        assertTrue(manager.deleteTile(DemTileId(36, -119)))
        assertEquals(emptyList<String>(), dir())
        assertEquals(rev + 1, manager.revision.value)
        assertFalse(manager.deleteTile(DemTileId(36, -119)))
    }

    @Test
    fun importAllSaysWhatWentInAndWhatDidNot() = runBlocking {
        val summary = manager.importAll(listOf(Uri.fromFile(hgt("N36W119.hgt")), Uri.fromFile(hgt("N36W118.hgt.zip")), Uri.fromFile(hgt("N37W119.hgt"))))
        assertTrue(summary, summary.startsWith("Imported 2 elevation tiles; 1 failed:"))
        assertEquals(listOf("N36W119.hgt", "N37W119.hgt"), dir())
        assertEquals("No elevation tiles were picked.", manager.importAll(emptyList()))
    }

    @Test
    fun importSummaryWording() {
        val one = DemTileInfo(DemTileId(36, -119), DemResolution.ThreeArcSecond, File("x"))
        assertEquals("Imported 1 elevation tile: N36W119.", importSummary(listOf(one), emptyList()))
        assertEquals("Elevation import failed: nope", importSummary(emptyList(), listOf("nope", "other")))
        assertEquals("No elevation tiles were picked.", importSummary(emptyList(), emptyList()))
    }

    // ---- reading what was imported ---------------------------------------------------------------------------

    /** Mount Whitney's four surrounding posts in a 3 arc-second N36W119 tile: rows 505-506 and columns 849-850 are 4421 m. */
    private fun whitneyTile(): File {
        val f = hgt("N36W119.hgt", fill = 1500)
        RandomAccessFile(f, "rw").use { raf ->
            for (row in 505..506) for (col in 849..850) {
                raf.seek((row.toLong() * 1201 + col) * 2)
                raf.write(byteArrayOf((4421 shr 8).toByte(), 4421.toByte()))
            }
        }
        return f
    }

    @Test
    fun aProfileReadsTheImportedTileLazilyAndMarksWhatIsOffTheTileAsUnknown() = runBlocking {
        manager.importTile(Uri.fromFile(whitneyTile())).getOrThrow()
        val summit = LatLon(36.5785, -118.2923)
        val profile = manager.profile(listOf(summit, LatLon(36.5785, -118.2923 + 0.02), LatLon(37.2, -118.29)))!!
        assertEquals(4421.0, profile.samples.first().elevationM!!, 1e-6)
        assertEquals(90.0, profile.spacingM, 0.0)
        assertTrue("the leg north of 37 N has no tile", profile.samples.last().elevationM == null)
        assertTrue(profile.stats().hasGaps)
        assertTrue(profile.stats().hasData)
    }

    @Test
    fun aProfileWithNoTilesInstalledHasNoElevationAtAll() = runBlocking {
        val profile = manager.profile(listOf(LatLon(36.0, -118.0), LatLon(36.1, -118.0)))!!
        assertTrue(profile.samples.all { it.elevationM == null })
        assertFalse(profile.stats().hasData)
        assertNull(manager.profile(listOf(LatLon(36.0, -118.0))))
    }

    @Test
    fun aOneArcSecondTileMakesTheRouteBeSampledEveryThirtyMetres() = runBlocking {
        manager.importTile(Uri.fromFile(hgt("N36W119.hgt", DemResolution.OneArcSecond))).getOrThrow()
        val profile = manager.profile(listOf(LatLon(36.5, -118.5), LatLon(36.51, -118.5)))!!
        assertEquals(30.0, profile.spacingM, 0.0)
    }
}
