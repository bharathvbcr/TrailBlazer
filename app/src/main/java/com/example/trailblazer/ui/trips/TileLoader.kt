package com.example.trailblazer.ui.trips

import android.graphics.Bitmap
import com.example.trailblazer.data.DecodedTile
import com.example.trailblazer.data.OfflineTileSource
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.geo.TileUtils.TileRange
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

/** One tile address as a map key (zoom and indices are far below the 28 bits each is given). */
internal fun tileKey(zoom: Int, x: Int, y: Int): Long = (zoom.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()

/** A decoded retina raster tile (512 x 512 ARGB) is 1 MiB; the cache is sized in these. */
internal const val RASTER_TILE_BYTES = 1L shl 20
internal const val MIN_RASTER_CACHE_BYTES = 24L * 1024 * 1024
internal const val VECTOR_TILE_BYTES = 512L * 1024
internal const val MIN_VECTOR_CACHE_BYTES = 16L * 1024 * 1024

/** Tiles that can be on screen at once at the worst (smallest) tile size: one extra row and column for the partial edges. */
internal fun worstCaseVisibleTiles(widthPx: Int, heightPx: Int): Long {
    val cols = ceil(widthPx.coerceAtLeast(1) / TileUtils.TILE_SIZE.toDouble()).toLong() + 1
    val rows = ceil(heightPx.coerceAtLeast(1) / TileUtils.TILE_SIZE.toDouble()).toLong() + 1
    return cols * rows
}

/**
 * Bytes of decoded raster tiles to keep for a [widthPx] x [heightPx] view: room for every tile of the worst-case screen
 * plus half again for the ancestors and the neighbours a pan is about to reveal, but never more than half of the
 * heap limit ([maxHeapBytes]). A cache smaller than one screen would evict tiles it had just decoded.
 */
internal fun rasterCacheBytes(widthPx: Int, heightPx: Int, maxHeapBytes: Long): Long {
    val wanted = worstCaseVisibleTiles(widthPx, heightPx) * RASTER_TILE_BYTES * 3 / 2
    return wanted.coerceAtMost(maxOf(maxHeapBytes / 2, MIN_RASTER_CACHE_BYTES)).coerceAtLeast(MIN_RASTER_CACHE_BYTES)
}

internal fun vectorCacheBytes(widthPx: Int, heightPx: Int, maxHeapBytes: Long): Long {
    val wanted = worstCaseVisibleTiles(widthPx, heightPx) * VECTOR_TILE_BYTES * 3 / 2
    return wanted.coerceAtMost(maxOf(maxHeapBytes / 4, MIN_VECTOR_CACHE_BYTES)).coerceAtLeast(MIN_VECTOR_CACHE_BYTES)
}

/**
 * A least-recently-used cache bounded by the bytes its values cost, not by how many there are: tiles vary from a few KB
 * of vector lines to a megabyte of bitmap. Safe to use from the loader (IO) and the draw pass (main) at once.
 *
 * Keys passed to [pin] are never evicted, whatever the budget says: they are the tiles the screen needs right now, and
 * evicting one just to make room for its neighbour would leave a hole that nothing re-requests.
 */
class TileCache<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<Long, V>()
    private var bytes = 0L
    private var pinned: Set<Long> = emptySet()

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

    /** Replaces the set of keys that must stay cached. */
    @Synchronized
    fun pin(keys: Set<Long>) {
        pinned = keys
    }

    /** Stores [value]; a value that alone exceeds the budget is not cached. Evicts oldest unpinned entries until within budget. */
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
            if (oldest.key == key || oldest.key in pinned) continue
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
 * ancestors are loaded so a stretched parent can stand in. Every tile of the range being loaded, and its ancestors, is
 * pinned in both caches for the duration, so a screenful always fits.
 */
class TileLoader internal constructor(
    private val source: OfflineTileSource,
    val rasters: TileCache<Bitmap>,
    internal val vectors: TileCache<PreparedVectorTile>,
) {
    private val misses: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

    fun raster(z: Int, x: Int, y: Int): Bitmap? = rasters.get(tileKey(z, x, y))
    internal fun vector(z: Int, x: Int, y: Int): PreparedVectorTile? = vectors.get(tileKey(z, x, y))

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
            is DecodedTile.Vector -> vectors.put(key, prepareVectorTile(decoded.vectorTile))
            null -> {
                if (misses.size >= MAX_REMEMBERED_MISSES) misses.clear()
                misses.add(key)
                return false
            }
        }
        return true
    }

    private fun pinRange(range: TileRange) {
        val z = range.zoom
        val keys = HashSet<Long>()
        for (y in range.minY..range.maxY) {
            for (x in range.minX..range.maxX) {
                var az = z
                var ax = TileUtils.wrapTileX(x, z)
                var ay = y
                keys.add(tileKey(az, ax, ay))
                for (d in 1..ANCESTOR_DEPTH) {
                    if (az <= 0) break
                    az--
                    ax = ax shr 1
                    ay = ay shr 1
                    keys.add(tileKey(az, ax, ay))
                }
            }
        }
        rasters.pin(keys)
        vectors.pin(keys)
    }

    /**
     * Loads every tile of [range] that is not cached yet; [onTile] runs after each newly cached tile so the view can
     * redraw progressively. Throws CancellationException as soon as the calling coroutine is cancelled.
     */
    suspend fun load(range: TileRange, onTile: () -> Unit) {
        val z = range.zoom
        pinRange(range)
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
