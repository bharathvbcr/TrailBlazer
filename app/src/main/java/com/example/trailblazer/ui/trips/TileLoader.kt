package com.example.trailblazer.ui.trips

import android.graphics.Bitmap
import com.example.trailblazer.data.DecodedTile
import com.example.trailblazer.data.OfflineTileSource
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.geo.TileUtils.TileRange
import com.trailblazer.core.geo.TileUtils.VectorTile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One tile address as a map key (zoom and indices are far below the 28 bits each is given). */
internal fun tileKey(zoom: Int, x: Int, y: Int): Long = (zoom.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()

/**
 * A least-recently-used cache bounded by the bytes its values cost, not by how many there are: tiles vary from a few KB
 * of vector lines to a megabyte of bitmap. Safe to use from the loader (IO) and the draw pass (main) at once.
 */
class TileCache<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<Long, V>()
    private var bytes = 0L

    val sizeBytes: Long @Synchronized get() = bytes
    val count: Int @Synchronized get() = map.size

    @Synchronized
    fun get(key: Long): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    @Synchronized
    fun contains(key: Long): Boolean = map.containsKey(key)

    /** Stores [value]; a value that alone exceeds the budget is not cached. Evicts oldest entries until within budget. */
    @Synchronized
    fun put(key: Long, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it).coerceAtLeast(1L) }
        val cost = sizeOf(value).coerceAtLeast(1L)
        if (cost > maxBytes) return
        map[key] = value
        bytes += cost
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val oldest = it.next()
            bytes -= sizeOf(oldest.value).coerceAtLeast(1L)
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0L
    }
}

/**
 * Reads the tiles a view needs from an [OfflineTileSource] into byte-bounded caches. The work is cooperative: between
 * tiles it checks for cancellation, so panning on abandons the old view's remaining tiles instead of finishing them.
 * Tiles the archive lacks are remembered (bounded) so they are not queried again on every frame; for those, the nearest
 * ancestors are loaded so a stretched parent can stand in.
 */
class TileLoader(
    private val source: OfflineTileSource,
    val rasters: TileCache<Bitmap>,
    val vectors: TileCache<VectorTile>,
) {
    private val misses: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

    fun raster(z: Int, x: Int, y: Int): Bitmap? = rasters.get(tileKey(z, x, y))
    fun vector(z: Int, x: Int, y: Int): VectorTile? = vectors.get(tileKey(z, x, y))

    private fun has(z: Int, x: Int, y: Int): Boolean {
        val k = tileKey(z, x, y)
        return rasters.contains(k) || vectors.contains(k)
    }

    private fun loadOne(z: Int, x: Int, y: Int): Boolean {
        val key = tileKey(z, x, y)
        if (key in misses) return false
        val decoded = try {
            source.getTile(z, x, y)
        } catch (_: Exception) {
            null
        }
        when (decoded) {
            is DecodedTile.Raster -> rasters.put(key, decoded.bitmap)
            is DecodedTile.Vector -> vectors.put(key, decoded.vectorTile)
            null -> {
                if (misses.size >= MAX_REMEMBERED_MISSES) misses.clear()
                misses.add(key)
                return false
            }
        }
        return true
    }

    /**
     * Loads every tile of [range] that is not cached yet; [onTile] runs after each newly cached tile so the view can
     * redraw progressively. Throws CancellationException as soon as the calling coroutine is cancelled.
     */
    suspend fun load(range: TileRange, onTile: () -> Unit) {
        val z = range.zoom
        for (y in range.minY..range.maxY) {
            for (x in range.minX..range.maxX) {
                currentCoroutineContext().ensureActive()
                val wx = TileUtils.wrapTileX(x, z)
                if (has(z, wx, y)) continue
                if (loadOne(z, wx, y)) {
                    onTile()
                    continue
                }
                var az = z
                var ax = wx
                var ay = y
                for (depth in 1..ANCESTOR_DEPTH) {
                    if (az <= source.minZoom || az <= 0) break
                    currentCoroutineContext().ensureActive()
                    az--
                    ax = ax shr 1
                    ay = ay shr 1
                    if (has(az, ax, ay)) break
                    if (loadOne(az, ax, ay)) {
                        onTile()
                        break
                    }
                }
            }
        }
    }

    companion object {
        /** How many levels up a missing tile may borrow its picture from. */
        const val ANCESTOR_DEPTH = 3
        private const val MAX_REMEMBERED_MISSES = 4096
    }
}
