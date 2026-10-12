package com.example.trailblazer.ui.trips

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.DecodedTile
import com.example.trailblazer.data.OfflineTileSource
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.geo.TileUtils.TileRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TileLoaderTest {

    private class FakeSource(
        override val minZoom: Int = 0,
        override val maxZoom: Int = 14,
        private val has: (Int, Int, Int) -> Boolean = { _, _, _ -> true },
        private val bitmapSide: Int = 64,
        private val onRequest: (Int, Int, Int) -> Unit = { _, _, _ -> },
        private val explode: Boolean = false,
    ) : OfflineTileSource {
        val requests = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        override val file = File("/fake/map.mbtiles")
        override val name = "fake"
        override val format = TileUtils.TileFormat.RasterPng
        override val bounds: TileUtils.TileBounds? = null
        override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? = null
        override fun getTile(zoom: Int, x: Int, y: Int): DecodedTile? {
            requests.add(Triple(zoom, x, y))
            onRequest(zoom, x, y)
            if (explode) throw IllegalStateException("source blew up")
            return if (has(zoom, x, y)) DecodedTile.Raster(Bitmap.createBitmap(bitmapSide, bitmapSide, Bitmap.Config.ARGB_8888)) else null
        }
        override fun close() {}
    }

    private fun loader(source: OfflineTileSource, rasterBytes: Long = 64L * 1024 * 1024) =
        TileLoader(source, TileCache<Bitmap>(rasterBytes) { it.allocationByteCount.toLong() }, TileCache(1L shl 20) { it.estimatedBytes })

    // ---- cache ----

    @Test
    fun theCacheEvictsLeastRecentlyUsedFirstAndStaysWithinItsBytes() {
        val cache = TileCache<ByteArray>(100) { it.size.toLong() }
        cache.put(1, ByteArray(40))
        cache.put(2, ByteArray(40))
        assertNotNull(cache.get(1)) // 1 is now the most recent
        cache.put(3, ByteArray(40)) // 120 > 100: evicts 2, the least recently used
        assertNull(cache.get(2))
        assertNotNull(cache.get(1))
        assertNotNull(cache.get(3))
        assertTrue(cache.sizeBytes <= 100)
        assertEquals(2, cache.count)
        cache.put(4, ByteArray(101)) // larger than the whole budget: not cached, nothing evicted
        assertNull(cache.get(4))
        assertEquals(2, cache.count)
        cache.put(1, ByteArray(10)) // replacing releases the old size
        assertEquals(50L, cache.sizeBytes)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun theCacheHoldsUpUnderConcurrentUse() {
        val cache = TileCache<ByteArray>(10_000) { it.size.toLong() }
        val threads = (0 until 6).map { t ->
            Thread {
                val rnd = java.util.Random(t.toLong())
                repeat(20_000) {
                    val k = rnd.nextInt(300).toLong()
                    if (rnd.nextBoolean()) cache.put(k, ByteArray(1 + rnd.nextInt(500))) else cache.get(k)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(30_000) }
        assertTrue("budget respected: ${cache.sizeBytes}", cache.sizeBytes <= 10_000)
        assertTrue(cache.count > 0)
    }

    @Test
    fun manyTilesStayInsideTheBitmapBudget() = runBlocking {
        val side = 256 // 256 KiB each
        val src = FakeSource(bitmapSide = side)
        val ld = loader(src, rasterBytes = 4L * 1024 * 1024)
        ld.load(TileRange(10, 0, 23, 0, 23)) { } // 576 tiles = 144 MiB if all were kept
        assertTrue("held ${ld.rasters.sizeBytes} bytes", ld.rasters.sizeBytes <= 4L * 1024 * 1024)
        assertTrue(ld.rasters.count in 1..16)
        assertEquals(576, src.requests.size)
    }

    // ---- loading ----

    @Test
    fun aCancelledViewStopsLoadingItsRemainingTiles() = runBlocking {
        val calls = AtomicInteger()
        lateinit var job: Job
        val src = FakeSource(onRequest = { _, _, _ -> if (calls.incrementAndGet() == 3) job.cancel() })
        job = launch(Dispatchers.Default) {
            loader(src).load(TileRange(12, 0, 5, 0, 5)) { }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("tile 3 triggered the cancel and tile 4 was never asked for", 3, src.requests.size)
    }

    @Test
    fun aSourceThatThrowsIsAHoleInTheMapNotACrash() = runBlocking {
        val src = FakeSource(explode = true)
        val ld = loader(src)
        var drawn = 0
        ld.load(TileRange(12, 100, 102, 200, 201)) { drawn++ }
        assertEquals(0, drawn)
        assertEquals(0, ld.rasters.count)
        val asked = src.requests.size
        ld.load(TileRange(12, 100, 102, 200, 201)) { drawn++ }
        assertEquals("known holes are not asked for again", asked, src.requests.size)
    }

    @Test
    fun cancellationStillPropagatesWhenTheSourceMisbehaves() = runBlocking {
        val src = FakeSource(explode = true)
        val job = launch(Dispatchers.Default) { loader(src).load(TileRange(12, 0, 2000, 0, 0)) { } }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("stopped early: ${src.requests.size}", src.requests.size < 2000)
    }

    @Test
    fun columnsPastTheAntimeridianAreFetchedAsTheirRealTiles() = runBlocking {
        val src = FakeSource()
        val ld = loader(src)
        val n = 1 shl 12
        ld.load(TileRange(12, n - 1, n + 1, 5, 5)) { }
        assertEquals(setOf(Triple(12, n - 1, 5), Triple(12, 0, 5), Triple(12, 1, 5)), src.requests.toSet())
        assertNotNull(ld.raster(12, 0, 5))
        ld.load(TileRange(12, -1, 0, 5, 5)) { }
        assertNotNull(ld.raster(12, n - 1, 5))
    }

    @Test
    fun aMissingTileBorrowsItsNearestAncestorOnce() = runBlocking {
        // The archive only has z10; the view wants z12.
        val src = FakeSource(minZoom = 8, has = { z, _, _ -> z <= 10 })
        val ld = loader(src)
        var loaded = 0
        ld.load(TileRange(12, 800, 803, 400, 403)) { loaded++ }
        assertNull(ld.raster(12, 800, 400))
        assertNotNull("the z10 ancestor stands in", ld.raster(10, 200, 100))
        assertTrue(loaded > 0)
        val asked = src.requests.size
        ld.load(TileRange(12, 800, 803, 400, 403)) { }
        assertEquals("nothing is requested twice", asked, src.requests.size)
        // The ancestor search never goes above the archive's shallowest level.
        assertTrue(src.requests.none { it.first < 8 })
    }

    @Test
    fun theVisibleRangeForARealViewIsSmall() {
        val src = FakeSource()
        val tz = TileUtils.renderTileZoom(13.4, src.minZoom, src.maxZoom)!!
        val range = TileUtils.visibleTileRange(LatLon(46.0, 7.0), 13.4, 1080f, 2200f, tz)
        val count = (range.maxX - range.minX + 1) * (range.maxY - range.minY + 1)
        assertTrue("$count tiles for one screen", count <= 6 * 11)
        assertFalse(count == 0)
    }

    @Test
    fun cancellationExceptionIsTheOnlyThingEscaping() = runBlocking {
        val src = FakeSource()
        val job = launch(Dispatchers.Default) {
            try {
                loader(src).load(TileRange(5, 0, 9, 0, 9)) { throw CancellationException("stop") }
            } catch (e: CancellationException) {
                throw e
            }
        }
        job.join()
        assertTrue(job.isCancelled)
    }
}
