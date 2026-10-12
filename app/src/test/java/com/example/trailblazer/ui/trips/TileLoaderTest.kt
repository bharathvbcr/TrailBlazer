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
        TileLoader(source, TileCache<Bitmap>(rasterBytes) { it.allocationByteCount.toLong() }, TileCache<PreparedVectorTile>(1L shl 20) { it.bytes })

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
        // A pan across 36 screens of 4 x 4 tiles: 576 tiles = 144 MiB in all. Each screen fits the budget, so the
        // cache holds the current one (plus what is left of the last) and never the whole trip.
        for (sx in 0 until 6) for (sy in 0 until 6) {
            ld.load(TileRange(10, sx * 4, sx * 4 + 3, sy * 4, sy * 4 + 3)) { }
            assertTrue("held ${ld.rasters.sizeBytes} bytes", ld.rasters.sizeBytes <= 4L * 1024 * 1024)
        }
        assertTrue(ld.rasters.count in 1..16)
        assertEquals(576, src.requests.size)
    }

    // ---- a screenful always fits ----

    private val viewports = listOf(1080 to 2340, 2560 to 1600, 720 to 1280, 1440 to 3120)
    private val centres = listOf(LatLon(46.0, 7.0), LatLon(-33.9, 151.2), LatLon(64.1, -21.9), LatLon(0.0, 179.9))
    private val zooms = listOf(12.0, 12.25, 12.5, 12.99, 13.0, 4.0, 15.0)

    @Test
    fun theBudgetHoldsAWorstCaseScreenWithHeadroom() {
        val heap = 512L * 1024 * 1024
        for ((w, h) in viewports) {
            val budget = rasterCacheBytes(w, h, heap)
            for (c in centres) for (zoom in zooms) {
                val tz = TileUtils.renderTileZoom(zoom, 0, 22)!!
                val r = TileUtils.visibleTileRange(c, zoom, w.toFloat(), h.toFloat(), tz)
                val tiles = (r.maxX - r.minX + 1).toLong() * (r.maxY - r.minY + 1)
                assertTrue("$tiles tiles at $w x $h z$zoom exceed the worst case ${worstCaseVisibleTiles(w, h)}", tiles <= worstCaseVisibleTiles(w, h))
                assertTrue("budget $budget cannot hold $tiles tiles at $w x $h z$zoom with headroom", tiles * RASTER_TILE_BYTES * 3 / 2 <= budget)
            }
        }
        // The budget is still capped by the heap on a small device.
        assertEquals(128L * 1024 * 1024, rasterCacheBytes(1440, 3120, 256L * 1024 * 1024))
        assertEquals(MIN_RASTER_CACHE_BYTES, rasterCacheBytes(100, 100, 16L * 1024 * 1024))
    }

    @Test
    fun afterALoadEveryVisibleTileIsCached() = runBlocking {
        val heap = 512L * 1024 * 1024
        for ((w, h) in listOf(1080 to 2340, 2560 to 1600)) {
            for (zoom in listOf(12.0, 12.5)) {
                val src = FakeSource(bitmapSide = 512) // 1 MiB each, as a retina tile decodes
                val ld = TileLoader(
                    src,
                    TileCache<Bitmap>(rasterCacheBytes(w, h, heap)) { it.allocationByteCount.toLong() },
                    TileCache<PreparedVectorTile>(vectorCacheBytes(w, h, heap)) { it.bytes },
                )
                val tz = TileUtils.renderTileZoom(zoom, 0, 14)!!
                val r = TileUtils.visibleTileRange(LatLon(46.0, 7.0), zoom, w.toFloat(), h.toFloat(), tz)
                ld.load(r) { }
                var missing = 0
                for (x in r.minX..r.maxX) for (y in r.minY..r.maxY) {
                    if (ld.raster(tz, TileUtils.wrapTileX(x, tz), y) == null) missing++
                }
                val visible = (r.maxX - r.minX + 1) * (r.maxY - r.minY + 1)
                assertEquals("$missing of $visible visible tiles were evicted at $w x $h z$zoom", 0, missing)
            }
        }
    }

    @Test
    fun tilesOfTheViewBeingLoadedSurviveEvenWhenTheyExceedTheBudget() = runBlocking {
        val src = FakeSource(bitmapSide = 512)
        val ld = loader(src, rasterBytes = 4L * 1024 * 1024)
        val a = TileRange(10, 0, 4, 0, 3) // 20 tiles = 20 MiB against a 4 MiB budget
        ld.load(a) { }
        assertEquals(20, ld.rasters.count)
        // Moving on releases them: the next view's tiles stay, the old ones are evicted down to the budget.
        val b = TileRange(10, 100, 101, 100, 100)
        ld.load(b) { }
        for (x in 100..101) assertNotNull(ld.raster(10, x, 100))
        assertTrue("held ${ld.rasters.sizeBytes}", ld.rasters.sizeBytes <= 4L * 1024 * 1024)
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
