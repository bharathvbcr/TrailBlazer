package com.example.trailblazer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.geo.TileUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineMapManagerTest {
    private lateinit var context: Context
    private lateinit var prefsRepo: PrefsRepository
    private lateinit var mapManager: OfflineMapManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsRepo = PrefsRepository(context)
        mapManager = OfflineMapManager(context, prefsRepo)
        // Ensure clean test maps directory
        mapManager.mapsDir.deleteRecursively()
        mapManager.mapsDir.mkdirs()
    }

    @After
    fun tearDown() {
        mapManager.mapsDir.deleteRecursively()
    }

    @Test
    fun testMbtilesImportAndTileQuery() = runBlocking {
        // 1. Create a valid test .mbtiles SQLite file
        val tempFile = File(context.cacheDir, "sierra_topo.mbtiles")
        if (tempFile.exists()) tempFile.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(tempFile, null)
        db.execSQL("CREATE TABLE metadata (name text, value text);")
        db.execSQL("INSERT INTO metadata VALUES ('name', 'High Sierra Topo');")
        db.execSQL("INSERT INTO metadata VALUES ('format', 'png');")
        db.execSQL("INSERT INTO metadata VALUES ('minzoom', '10');")
        db.execSQL("INSERT INTO metadata VALUES ('maxzoom', '14');")
        db.execSQL("INSERT INTO metadata VALUES ('bounds', '-120.0,37.0,-119.0,38.0');")

        db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);")

        // Create a 1x1 sample PNG tile
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        // Tile at z=12, x=700, y=1500 -> in MBTiles row is tmsY: (1 shl 12) - 1 - 1500 = 4095 - 1500 = 2595
        val tmsY = TileUtils.xyzYToTmsY(1500, 12)
        val statement = db.compileStatement("INSERT INTO tiles VALUES (12, 700, ?, ?);")
        statement.bindLong(1, tmsY.toLong())
        statement.bindBlob(2, pngBytes)
        statement.executeInsert()
        db.close()

        // 2. Import via SAF Uri
        val uri = Uri.fromFile(tempFile)
        val result = mapManager.importMap(uri)
        assertTrue("Import must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val info = result.getOrThrow()
        assertEquals("High Sierra Topo", info.name)
        assertEquals(TileUtils.TileFormat.RasterPng, info.format)
        assertEquals(10, info.minZoom)
        assertEquals(14, info.maxZoom)
        val bounds = checkNotNull(info.bounds)
        assertEquals(37.0, bounds.minLat, 0.001)
        assertEquals(38.0, bounds.maxLat, 0.001)

        // 3. Verify active map set in prefs
        val activePath = prefsRepo.settings.first().activeOfflineMapPath
        assertNotNull(activePath)
        assertTrue(File(activePath!!).exists())

        // 4. Open source and query tile
        val source = mapManager.openActiveSource()
        assertNotNull(source)
        source!!.use { s ->
            val tile = s.getTile(12, 700, 1500)
            assertNotNull(tile)
            assertTrue(tile is DecodedTile.Raster)
            assertEquals(1, (tile as DecodedTile.Raster).bitmap.width)

            // Non-existent tile returns null
            val missing = s.getTile(12, 999, 999)
            assertEquals(null, missing)
        }

        // 5. Test listing and deletion
        val listed = mapManager.listMaps()
        assertEquals(1, listed.size)
        assertEquals("High Sierra Topo", listed.first().name)

        val deleted = mapManager.deleteMap(info.file)
        assertTrue(deleted)
        assertEquals(0, mapManager.listMaps().size)
    }

    @Test
    fun testPmtilesImport() = runBlocking {
        // Build a minimal valid PMTiles v3 file
        val tempFile = File(context.cacheDir, "test_contours.pmtiles")
        val headerBytes = ByteArray(127)
        headerBytes[0] = 'P'.code.toByte()
        headerBytes[1] = 'M'.code.toByte()
        headerBytes[2] = 3 // v3
        // rootDirectoryOffset = 127
        headerBytes[3] = 127
        // rootDirectoryLength = 0 (empty directory)
        headerBytes[11] = 0
        // tileType = 1 (VectorMvt)
        headerBytes[94] = 1
        // minZoom = 8, maxZoom = 16
        headerBytes[95] = 8
        headerBytes[96] = 16

        tempFile.writeBytes(headerBytes)

        val uri = Uri.fromFile(tempFile)
        val result = mapManager.importMap(uri)
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(TileUtils.TileFormat.VectorMvt, info.format)
        assertEquals(8, info.minZoom)
        assertEquals(16, info.maxZoom)

        val source = mapManager.openSource(info.file)
        assertNotNull(source)
        source!!.close()
    }

    @Test
    fun testInvalidFileRejected() = runBlocking {
        val badFile = File(context.cacheDir, "corrupt.mbtiles")
        badFile.writeText("Not a SQLite or PMTiles database")

        val result = mapManager.importMap(Uri.fromFile(badFile))
        assertFalse("Corrupt file must be rejected", result.isSuccess)
        assertEquals(0, mapManager.listMaps().size)
    }
}
