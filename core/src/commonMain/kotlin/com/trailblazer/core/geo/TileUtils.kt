package com.trailblazer.core.geo

import com.trailblazer.core.io.DecompressionLimitException
import com.trailblazer.core.io.decompressGzip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * The one error the tile parsers raise for input that is not a valid tile, archive header or directory. Map archives
 * come from the user's file picker and may be corrupt or hostile, so callers treat this as "this tile/archive is
 * unusable" and nothing else escapes the parsers.
 */
class MalformedTileException(message: String) : IllegalArgumentException(message)

object TileUtils {
    const val TILE_SIZE = 256
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 22

    /** Highest zoom a PMTiles Hilbert tile id can address without overflowing a signed 64-bit integer. */
    const val MAX_ARCHIVE_ZOOM = 30

    /** Web Mercator's latitude limit (the square world). */
    const val MAX_LAT = 85.05112878

    /** A tile, directory or header section may inflate to at most this many bytes. */
    const val MAX_INFLATED_BYTES = 16 * 1024 * 1024

    /** Hard ceilings on what one vector tile may ask the decoder to allocate. */
    const val MAX_MVT_LAYERS = 128
    const val MAX_MVT_FEATURES = 250_000
    const val MAX_MVT_POINTS = 500_000
    const val MAX_MVT_STRINGS = 100_000
    const val MAX_MVT_EXTENT = 1 shl 20

    /** No more tiles than this are ever requested along one axis for a single frame. */
    const val MAX_TILE_SPAN = 24

    /** Overzoom: tiles of the deepest level are stretched up to this many zoom levels past it. */
    const val OVERZOOM_LEVELS = 3.0

    data class TileCoord(val x: Int, val y: Int, val zoom: Int) {
        val tmsY: Int get() = xyzYToTmsY(y, zoom)
        fun toLatLon(): LatLon = tileToLatLon(x.toDouble(), y.toDouble(), zoom)
        fun bounds(): TileBounds = tileBounds(x, y, zoom)
    }

    data class TileBounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    ) {
        fun contains(lat: Double, lon: Double): Boolean =
            lat in minLat..maxLat && lon in minLon..maxLon

        fun contains(pos: LatLon): Boolean = contains(pos.lat, pos.lon)

        fun center(): LatLon = LatLon((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    }

    data class ScreenPoint(val x: Float, val y: Float)

    /**
     * Tiles covering the screen at [zoom]. `minX..maxX` is NOT clamped to the world: it runs past 0 / past 2^zoom when
     * the view crosses the antimeridian, so tiles are drawn side by side across it; fetch them with [wrapTileX].
     * `minY..maxY` is clamped to the world (there is nothing north of 85.05 degrees).
     */
    data class TileRange(val zoom: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    /** A map view: what is at the centre of the screen and how far in. */
    data class MapView(val center: LatLon, val zoom: Double)

    enum class TileFormat(val extension: String, val isRaster: Boolean) {
        RasterPng("png", true),
        RasterJpg("jpg", true),
        RasterWebp("webp", true),
        VectorMvt("pbf", false),
        Unknown("bin", false);

        companion object {
            fun detect(bytes: ByteArray): TileFormat {
                if (bytes.size < 4) return Unknown
                if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                    return RasterPng
                }
                if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                    return RasterJpg
                }
                if (bytes.size >= 12 &&
                    bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                    bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
                ) {
                    return RasterWebp
                }
                if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
                    return VectorMvt
                }
                if (bytes[0] == 0x1A.toByte()) {
                    return VectorMvt
                }
                return Unknown
            }

            fun fromMimeOrExtension(hint: String): TileFormat {
                val lower = hint.lowercase()
                return when {
                    lower.contains("png") -> RasterPng
                    lower.contains("jpg") || lower.contains("jpeg") -> RasterJpg
                    lower.contains("webp") -> RasterWebp
                    lower.contains("pbf") || lower.contains("mvt") || lower.contains("vector") -> VectorMvt
                    else -> Unknown
                }
            }
        }
    }

    // Coordinate conversions (Web Mercator EPSG:3857)

    /** 2^zoom as a Double, for any Int: zoom is clamped so a hostile or corrupt level can never overflow a shift. */
    private fun worldTiles(zoom: Int): Double = 2.0.pow(zoom.coerceIn(0, MAX_ARCHIVE_ZOOM))

    private fun normX(lon: Double): Double = (lon + 180.0) / 360.0

    private fun normY(lat: Double): Double {
        val latRad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        val sinLat = sin(latRad).coerceIn(-0.999999, 0.999999)
        return 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)
    }

    private fun latOfNormY(ny: Double): Double = atan(sinh(PI - 2.0 * PI * ny)) * 180.0 / PI

    /** Longitude folded into [-180, 180). Non-finite input is returned unchanged for the caller to reject. */
    fun wrapLon(lon: Double): Double {
        if (!lon.isFinite()) return lon
        var l = (lon + 180.0) % 360.0
        if (l < 0.0) l += 360.0
        return l - 180.0
    }

    /** A tile column folded into the world, so columns past the antimeridian address the real tile. */
    fun wrapTileX(x: Int, zoom: Int): Int {
        val n = 1L shl zoom.coerceIn(0, MAX_ARCHIVE_ZOOM)
        return (((x.toLong() % n) + n) % n).toInt()
    }

    fun lonToTileX(lon: Double, zoom: Int): Double = normX(lon) * worldTiles(zoom)

    fun latToTileY(lat: Double, zoom: Int): Double = normY(lat) * worldTiles(zoom)

    fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
        val last = worldTiles(zoom) - 1.0
        // +180 is the east edge of the last column; wrapLon would fold it onto the first.
        val x = lonToTileX(if (lon == 180.0) lon else wrapLon(lon), zoom).coerceIn(0.0, last).toInt()
        val y = latToTileY(lat, zoom).coerceIn(0.0, last).toInt()
        return TileCoord(x, y, zoom)
    }

    fun latLonToTile(pos: LatLon, zoom: Int): TileCoord = latLonToTile(pos.lat, pos.lon, zoom)

    fun tileXToLon(x: Double, zoom: Int): Double = (x / worldTiles(zoom)) * 360.0 - 180.0

    fun tileYToLat(y: Double, zoom: Int): Double = latOfNormY(y / worldTiles(zoom))

    /** Position of a (fractional) tile corner; columns past the antimeridian wrap, rows past the poles clamp. */
    fun tileToLatLon(x: Double, y: Double, zoom: Int): LatLon =
        LatLon(tileYToLat(y, zoom).coerceIn(-MAX_LAT, MAX_LAT), wrapLon(tileXToLon(x, zoom)))

    fun tileBounds(x: Int, y: Int, zoom: Int): TileBounds {
        val maxLat = tileYToLat(y.toDouble(), zoom)
        val minLat = tileYToLat((y + 1).toDouble(), zoom)
        val minLon = tileXToLon(x.toDouble(), zoom)
        val maxLon = tileXToLon((x + 1).toDouble(), zoom)
        return TileBounds(minLat, minLon, maxLat, maxLon)
    }

    /** MBTiles stores rows from the south (TMS); XYZ counts from the north. The two are mirror images. */
    fun xyzYToTmsY(y: Int, zoom: Int): Int = ((1L shl zoom.coerceIn(0, MAX_ARCHIVE_ZOOM)) - 1 - y).toInt()
    fun tmsYToXyzY(tmsY: Int, zoom: Int): Int = xyzYToTmsY(tmsY, zoom)

    /** True when (zoom, x, y) names a real tile of the world. */
    fun isValidTile(zoom: Int, x: Int, y: Int): Boolean =
        zoom in 0..MAX_ARCHIVE_ZOOM && x >= 0 && y >= 0 && x.toLong() < (1L shl zoom) && y.toLong() < (1L shl zoom)

    /** Ground resolution at [lat] for a 256 px tile world; latitude is held to Mercator's limit so the poles stay finite. */
    fun metersPerPixel(lat: Double, zoom: Double): Double {
        val latRad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return 156543.03392 * cos(latRad) / 2.0.pow(zoom)
    }

    // Viewport and Screen Calculations

    /**
     * Screen position of [pos]. The longitude difference to [center] is taken the short way round the globe, so a
     * track that crosses the antimeridian is continuous on screen instead of streaking across the whole world.
     */
    fun latLonToScreen(pos: LatLon, center: LatLon, zoom: Double, widthPx: Float, heightPx: Float): ScreenPoint {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        var dx = normX(pos.lon) - normX(center.lon)
        dx -= floor(dx + 0.5)
        val dy = normY(pos.lat) - normY(center.lat)
        return ScreenPoint((widthPx / 2.0 + dx * worldSize).toFloat(), (heightPx / 2.0 + dy * worldSize).toFloat())
    }

    /** Top-left corner on screen of tile column [x] (unwrapped, may be negative or past the world) and row [y]. */
    fun tileOriginOnScreen(x: Int, y: Int, tileZoom: Int, center: LatLon, zoom: Double, widthPx: Float, heightPx: Float): ScreenPoint {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val n = worldTiles(tileZoom)
        val sx = widthPx / 2.0 + (x / n - normX(center.lon)) * worldSize
        val sy = heightPx / 2.0 + (y / n - normY(center.lat)) * worldSize
        return ScreenPoint(sx.toFloat(), sy.toFloat())
    }

    fun screenToLatLon(screenX: Float, screenY: Float, center: LatLon, zoom: Double, widthPx: Float, heightPx: Float): LatLon {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val nx = normX(center.lon) + (screenX - widthPx / 2.0) / worldSize
        val ny = normY(center.lat) + (screenY - heightPx / 2.0) / worldSize
        return LatLon(latOfNormY(ny).coerceIn(-MAX_LAT, MAX_LAT), wrapLon(nx * 360.0 - 180.0))
    }

    /** New zoom after a pinch of factor [scale] (a ratio of finger spans): zoom is logarithmic, so scales add as log2. */
    fun zoomAfterPinch(zoom: Double, scale: Double, minZoom: Double, maxZoom: Double): Double {
        val z = if (scale.isFinite() && scale > 0.0) zoom + log2(scale) else zoom
        return z.coerceIn(minZoom, max(minZoom, maxZoom))
    }

    /**
     * One step of a pan/pinch gesture about the point under the fingers ([centroidX], [centroidY]): the place that was
     * under the fingers stays under them (moved by [panX], [panY]) while the zoom changes by [zoomChange].
     */
    fun applyGesture(
        center: LatLon,
        zoom: Double,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
        widthPx: Float,
        heightPx: Float,
        minZoom: Double,
        maxZoom: Double,
    ): MapView {
        val newZoom = zoomAfterPinch(zoom, zoomChange.toDouble(), minZoom, maxZoom)
        val oldWorld = TILE_SIZE * 2.0.pow(zoom)
        val newWorld = TILE_SIZE * 2.0.pow(newZoom)
        val px = if (panX.isFinite()) panX else 0f
        val py = if (panY.isFinite()) panY else 0f
        val anchorX = normX(center.lon) + (centroidX - widthPx / 2.0) / oldWorld
        val anchorY = normY(center.lat) + (centroidY - heightPx / 2.0) / oldWorld
        val cx = anchorX - (centroidX + px - widthPx / 2.0) / newWorld
        val cy = (anchorY - (centroidY + py - heightPx / 2.0) / newWorld).coerceIn(0.0, 1.0)
        return MapView(LatLon(latOfNormY(cy).coerceIn(-MAX_LAT, MAX_LAT), wrapLon(cx * 360.0 - 180.0)), newZoom)
    }

    /**
     * Centre and zoom that fit [points]. Longitudes are measured the short way round from the first point, so a route
     * over the antimeridian fits as a small window rather than the whole globe. Non-finite points are ignored.
     */
    fun fitViewport(points: List<LatLon>, widthPx: Int, heightPx: Int, paddingPx: Int = 40): Pair<LatLon, Double> {
        if (points.isEmpty()) return Pair(LatLon(0.0, 0.0), 2.0)
        if (points.size == 1) return Pair(points.first(), 13.0)

        val refLon = points.first().lon
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minRel = Double.POSITIVE_INFINITY
        var maxRel = Double.NEGATIVE_INFINITY
        for (p in points) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            val rel = wrapLon(p.lon - refLon)
            minRel = min(minRel, rel)
            maxRel = max(maxRel, rel)
        }

        val center = LatLon((minLat + maxLat) / 2.0, wrapLon(refLon + (minRel + maxRel) / 2.0))

        val availW = max(widthPx - 2 * paddingPx, 50).toDouble()
        val availH = max(heightPx - 2 * paddingPx, 50).toDouble()

        val lonSpan = max(maxRel - minRel, 0.0001) / 360.0
        val y0 = latToTileY(maxLat, 0)
        val y1 = latToTileY(minLat, 0)
        val latSpan = max(abs(y1 - y0), 0.0001)

        val zoomX = ln(availW / (lonSpan * TILE_SIZE)) / ln(2.0)
        val zoomY = ln(availH / (latSpan * TILE_SIZE)) / ln(2.0)

        val zoom = min(zoomX, zoomY).coerceIn(1.0, 18.0)
        return Pair(center, zoom)
    }

    /**
     * The tile level to draw at [zoom] from an archive holding [archiveMin]..[archiveMax]: the level just below the
     * view's zoom, held inside the archive (deeper views stretch the deepest tiles, "overzoom"). Null when the view is
     * zoomed out more than one level past the archive's shallowest tiles, where nothing useful can be drawn.
     */
    fun renderTileZoom(zoom: Double, archiveMin: Int, archiveMax: Int): Int? {
        val lo = archiveMin.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val hi = max(lo, archiveMax.coerceIn(MIN_ZOOM, MAX_ZOOM))
        val z = floor(zoom).toInt()
        if (z < lo - 1) return null
        return z.coerceIn(lo, hi)
    }

    /** The widest zoom the view may reach on an archive: its deepest level plus [OVERZOOM_LEVELS], never past [MAX_ZOOM]. */
    fun maxViewZoom(archiveMax: Int): Double = min(archiveMax.coerceIn(MIN_ZOOM, MAX_ZOOM) + OVERZOOM_LEVELS, MAX_ZOOM.toDouble())

    /**
     * Tiles at [tileZoom] (default: the level just below [zoom]) that cover the screen. Computed from unwrapped world
     * coordinates so a view across the antimeridian yields a short run of columns instead of the whole world, and
     * capped at [MAX_TILE_SPAN] per axis around the centre so no viewport can ask for an unbounded number of tiles.
     */
    fun visibleTileRange(
        center: LatLon,
        zoom: Double,
        widthPx: Float,
        heightPx: Float,
        tileZoom: Int = floor(zoom).toInt(),
    ): TileRange {
        val tz = tileZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val n = worldTiles(tz)
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val cx = normX(center.lon)
        val cy = normY(center.lat)
        val left = (cx + (0.0 - widthPx / 2.0) / worldSize) * n
        val right = (cx + (widthPx / 2.0) / worldSize) * n
        val top = (cy + (0.0 - heightPx / 2.0) / worldSize) * n
        val bottom = (cy + (heightPx / 2.0) / worldSize) * n
        val lastRow = (n - 1.0)

        var minX = floor(left).coerceIn(-1e9, 1e9).toInt()
        var maxX = floor(right).coerceIn(-1e9, 1e9).toInt()
        var minY = floor(top).coerceIn(0.0, lastRow).toInt()
        var maxY = floor(bottom).coerceIn(0.0, lastRow).toInt()
        if (maxX - minX + 1 > MAX_TILE_SPAN) {
            val mid = floor(cx * n).coerceIn(-1e9, 1e9).toInt()
            minX = mid - MAX_TILE_SPAN / 2
            maxX = minX + MAX_TILE_SPAN - 1
        }
        if (maxY - minY + 1 > MAX_TILE_SPAN) {
            val mid = floor(cy * n).coerceIn(0.0, lastRow).toInt()
            minY = (mid - MAX_TILE_SPAN / 2).coerceAtLeast(0)
            maxY = minOf(minY + MAX_TILE_SPAN - 1, lastRow.toInt())
        }
        return TileRange(tz, minX, maxX, minY, maxY)
    }

    /**
     * Projects [points] to screen coordinates for drawing a polyline, writing x,y pairs into the result. Points that
     * land within [minStepPx] of the previous kept point (and non-finite points) are dropped; the last point is always
     * kept so the end marker is exact. A 1M-point track costs one pass and draws only what is distinguishable.
     */
    fun projectPolyline(
        points: List<LatLon>,
        center: LatLon,
        zoom: Double,
        widthPx: Float,
        heightPx: Float,
        minStepPx: Float = 1.5f,
    ): FloatArray {
        if (points.isEmpty()) return FloatArray(0)
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val cx = normX(center.lon)
        val cy = normY(center.lat)
        val halfW = widthPx / 2.0
        val halfH = heightPx / 2.0
        val out = FloatArray(points.size * 2)
        var count = 0
        var lastX = 0f
        var lastY = 0f
        var pendingX = 0f
        var pendingY = 0f
        var pendingDropped = false
        val step2 = minStepPx * minStepPx
        for (p in points) {
            var dx = normX(p.lon) - cx
            dx -= floor(dx + 0.5)
            val x = (halfW + dx * worldSize).toFloat()
            val y = (halfH + (normY(p.lat) - cy) * worldSize).toFloat()
            if (!x.isFinite() || !y.isFinite()) continue
            if (count == 0 || (x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) >= step2) {
                out[count * 2] = x
                out[count * 2 + 1] = y
                count++
                lastX = x
                lastY = y
                pendingDropped = false
            } else {
                pendingX = x
                pendingY = y
                pendingDropped = true
            }
        }
        if (pendingDropped) {
            out[count * 2] = pendingX
            out[count * 2 + 1] = pendingY
            count++
        }
        return out.copyOf(count * 2)
    }

    // Hilbert Curve and PMTiles v3 Tile ID Calculations

    fun hilbertIndex(x: Int, y: Int, z: Int): Long {
        if (z <= 0) return 0L
        require(z <= MAX_ARCHIVE_ZOOM) { "zoom $z exceeds $MAX_ARCHIVE_ZOOM" }
        val n = 1 shl z
        var rx: Long
        var ry: Long
        var s = n / 2
        var d = 0L
        var curX = x
        var curY = y
        while (s > 0) {
            rx = if ((curX and s) > 0) 1L else 0L
            ry = if ((curY and s) > 0) 1L else 0L
            d += s.toLong() * s.toLong() * ((3L * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    curX = s - 1 - curX
                    curY = s - 1 - curY
                }
                val t = curX
                curX = curY
                curY = t
            }
            s /= 2
        }
        return d
    }

    fun hilbertCoord(index: Long, z: Int): Pair<Int, Int> {
        if (z <= 0) return Pair(0, 0)
        require(z <= MAX_ARCHIVE_ZOOM) { "zoom $z exceeds $MAX_ARCHIVE_ZOOM" }
        val n = 1L shl z
        var rx: Long
        var ry: Long
        var t = index
        var curX = 0L
        var curY = 0L
        var s = 1L
        while (s < n) {
            rx = 1L and (t / 2)
            ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    curX = s - 1 - curX
                    curY = s - 1 - curY
                }
                val tmp = curX
                curX = curY
                curY = tmp
            }
            curX += s * rx
            curY += s * ry
            t /= 4
            s *= 2
        }
        return Pair(curX.toInt(), curY.toInt())
    }

    /** PMTiles tile id of a tile; throws [MalformedTileException] for a zoom past [MAX_ARCHIVE_ZOOM] or x/y outside the world. */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        if (!isValidTile(z, x, y)) throw MalformedTileException("tile $z/$x/$y is outside the world")
        var acc = 0L
        for (i in 0 until z) {
            acc += 1L shl (2 * i)
        }
        return acc + hilbertIndex(x, y, z)
    }

    /** Inverse of [zxyToTileId]; throws [MalformedTileException] for a negative id or one past zoom [MAX_ARCHIVE_ZOOM]. */
    fun tileIdToZxy(tileId: Long): Triple<Int, Int, Int> {
        if (tileId < 0L) throw MalformedTileException("negative tile id")
        var id = tileId
        var z = 0
        while (true) {
            val numTiles = 1L shl (2 * z)
            if (id < numTiles) break
            id -= numTiles
            z++
            if (z > MAX_ARCHIVE_ZOOM) throw MalformedTileException("tile id $tileId is past zoom $MAX_ARCHIVE_ZOOM")
        }
        val (x, y) = hilbertCoord(id, z)
        return Triple(z, x, y)
    }

    // PMTiles v3 Header and Directory Parser (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md)

    const val PMTILES_HEADER_BYTES = 127
    const val PMTILES_COMPRESSION_UNKNOWN = 0
    const val PMTILES_COMPRESSION_NONE = 1
    const val PMTILES_COMPRESSION_GZIP = 2

    /** Most entries a single directory may hold; real leaf directories hold a few thousand. */
    const val MAX_DIRECTORY_ENTRIES = 1_000_000

    data class PmtilesHeader(
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
        val centerLon: Double,
        val centerLat: Double,
        val centerZoom: Int,
        val rootDirectoryOffset: Long,
        val rootDirectoryLength: Long,
        val leafDirectoriesOffset: Long,
        val leafDirectoriesLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val tileType: TileFormat,
        val internalCompression: Int,
        val tileCompression: Int,
    )

    /**
     * One directory row. `runLength > 0`: tile data at [offset] (from the tile data section), shared by [runLength]
     * consecutive tile ids. `runLength == 0`: [offset]/[length] address a leaf directory (from the leaf section).
     */
    data class PmtilesEntry(val tileId: Long, val offset: Long, val length: Long, val runLength: Int) {
        val isLeaf: Boolean get() = runLength == 0
    }

    private fun compressionName(c: Int): String = when (c) {
        3 -> "brotli"
        4 -> "zstd"
        else -> "compression $c"
    }

    fun parsePmtilesHeader(bytes: ByteArray): PmtilesHeader {
        if (bytes.size < PMTILES_HEADER_BYTES) throw MalformedTileException("PMTiles header must be at least $PMTILES_HEADER_BYTES bytes")
        val magic = "PMTiles"
        for (i in magic.indices) {
            if (bytes[i] != magic[i].code.toByte()) throw MalformedTileException("Invalid PMTiles magic")
        }
        val version = bytes[7].toInt() and 0xFF
        if (version != 3) throw MalformedTileException("Unsupported PMTiles version: $version (expected 3)")

        fun readU64(off: Int, what: String): Long {
            var res = 0L
            for (i in 0 until 8) {
                res = res or ((bytes[off + i].toLong() and 0xFFL) shl (i * 8))
            }
            if (res < 0L) throw MalformedTileException("PMTiles $what is past 2^63")
            return res
        }

        fun readI32(off: Int): Int {
            var res = 0
            for (i in 0 until 4) {
                res = res or ((bytes[off + i].toInt() and 0xFF) shl (i * 8))
            }
            return res
        }

        fun coord(off: Int, limit: Double, what: String): Double {
            val v = readI32(off) / 10_000_000.0
            if (v < -limit || v > limit) throw MalformedTileException("PMTiles $what out of range: $v")
            return v
        }

        val rootOff = readU64(8, "root directory offset")
        val rootLen = readU64(16, "root directory length")
        val leafOff = readU64(40, "leaf directories offset")
        val leafLen = readU64(48, "leaf directories length")
        val dataOff = readU64(56, "tile data offset")
        val dataLen = readU64(64, "tile data length")
        val internalComp = bytes[97].toInt() and 0xFF
        val tileComp = bytes[98].toInt() and 0xFF
        val tileTypeByte = bytes[99].toInt() and 0xFF
        val minZ = bytes[100].toInt() and 0xFF
        val maxZ = bytes[101].toInt() and 0xFF
        if (rootLen == 0L) throw MalformedTileException("PMTiles root directory is empty")
        if (minZ > maxZ) throw MalformedTileException("PMTiles min zoom $minZ is above max zoom $maxZ")
        if (maxZ > MAX_ARCHIVE_ZOOM) throw MalformedTileException("PMTiles max zoom $maxZ is past $MAX_ARCHIVE_ZOOM")
        if (internalComp > PMTILES_COMPRESSION_GZIP) throw MalformedTileException("PMTiles directories use ${compressionName(internalComp)}, which is not supported")
        if (tileComp > PMTILES_COMPRESSION_GZIP) throw MalformedTileException("PMTiles tiles use ${compressionName(tileComp)}, which is not supported")

        val minLon = coord(102, 180.0, "min longitude")
        val minLat = coord(106, 90.0, "min latitude")
        val maxLon = coord(110, 180.0, "max longitude")
        val maxLat = coord(114, 90.0, "max latitude")
        val centerZ = bytes[118].toInt() and 0xFF
        val centerLon = coord(119, 180.0, "center longitude")
        val centerLat = coord(123, 90.0, "center latitude")

        val format = when (tileTypeByte) {
            1, 6 -> TileFormat.VectorMvt
            2 -> TileFormat.RasterPng
            3 -> TileFormat.RasterJpg
            4 -> TileFormat.RasterWebp
            else -> TileFormat.Unknown
        }

        return PmtilesHeader(
            minZoom = minZ,
            maxZoom = maxZ,
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
            centerLon = centerLon,
            centerLat = centerLat,
            centerZoom = centerZ,
            rootDirectoryOffset = rootOff,
            rootDirectoryLength = rootLen,
            leafDirectoriesOffset = leafOff,
            leafDirectoriesLength = leafLen,
            tileDataOffset = dataOff,
            tileDataLength = dataLen,
            tileType = format,
            internalCompression = internalComp,
            tileCompression = tileComp,
        )
    }

    /**
     * Inflates a section with its declared compression. "None" really is none (a directory may legitimately start with
     * the bytes 1F 8B); "unknown" sniffs the gzip magic. Anything corrupt or oversized is a [MalformedTileException].
     */
    internal fun inflate(bytes: ByteArray, compression: Int, what: String): ByteArray {
        if (compression == PMTILES_COMPRESSION_NONE) {
            if (bytes.size > MAX_INFLATED_BYTES) throw MalformedTileException("$what is larger than $MAX_INFLATED_BYTES bytes")
            return bytes
        }
        return try {
            decompressGzip(bytes, MAX_INFLATED_BYTES)
        } catch (e: DecompressionLimitException) {
            throw MalformedTileException("$what inflates past $MAX_INFLATED_BYTES bytes")
        } catch (e: Exception) {
            throw MalformedTileException("$what is not a valid gzip stream")
        }
    }

    /**
     * Decodes a v3 directory: entry count, then tile id deltas, run lengths, lengths and offsets as separate columns
     * (offset 0 means "straight after the previous entry", otherwise offset + 1). Every count and value is validated
     * against the bytes actually present.
     */
    fun parsePmtilesDirectory(bytes: ByteArray, internalCompression: Int = PMTILES_COMPRESSION_GZIP): List<PmtilesEntry> {
        val data = inflate(bytes, internalCompression, "PMTiles directory")
        val r = ProtoReader(data, 0, data.size)
        if (!r.hasMore()) return emptyList()
        val count = r.varint()
        if (count == 0L) return emptyList()
        if (count < 0L || count > MAX_DIRECTORY_ENTRIES || count * 4L > r.remaining()) {
            throw MalformedTileException("PMTiles directory claims $count entries")
        }
        val n = count.toInt()
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) {
            val delta = r.varint()
            if (delta < 0L || (i > 0 && delta == 0L)) throw MalformedTileException("PMTiles tile ids are not increasing")
            last += delta
            if (last < 0L) throw MalformedTileException("PMTiles tile id overflows")
            ids[i] = last
        }
        val runs = IntArray(n)
        for (i in 0 until n) {
            val v = r.varint()
            if (v < 0L || v > Int.MAX_VALUE) throw MalformedTileException("PMTiles run length out of range")
            runs[i] = v.toInt()
        }
        val lengths = LongArray(n)
        for (i in 0 until n) {
            val v = r.varint()
            if (v <= 0L) throw MalformedTileException("PMTiles entry length must be positive")
            lengths[i] = v
        }
        val entries = ArrayList<PmtilesEntry>(n)
        var prevEnd = 0L
        for (i in 0 until n) {
            val v = r.varint()
            if (v < 0L) throw MalformedTileException("PMTiles offset out of range")
            val offset = if (v == 0L) {
                if (i == 0) throw MalformedTileException("PMTiles first offset cannot be contiguous")
                prevEnd
            } else {
                v - 1
            }
            val end = offset + lengths[i]
            if (end < 0L) throw MalformedTileException("PMTiles offset overflows")
            prevEnd = end
            entries.add(PmtilesEntry(ids[i], offset, lengths[i], runs[i]))
        }
        return entries
    }

    /**
     * The entry covering [tileId]: the last entry at or before it, if it is a leaf pointer or its run reaches the id.
     * Null when the directory has nothing for that tile. A leaf result must be descended into by the caller.
     */
    fun findPmtilesEntry(entries: List<PmtilesEntry>, tileId: Long): PmtilesEntry? {
        var low = 0
        var high = entries.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (entries[mid].tileId <= tileId) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null
        val e = entries[found]
        if (e.isLeaf) return e
        return if (tileId - e.tileId < e.runLength) e else null
    }

    // Vector Tile (MVT) Parser

    enum class GeometryType { Unknown, Point, LineString, Polygon }

    data class TilePoint(val x: Float, val y: Float)

    data class VectorFeature(
        val id: Long,
        val type: GeometryType,
        val geometry: List<List<TilePoint>>,
        val properties: Map<String, String>,
        val layerName: String,
    ) {
        val isContour: Boolean
            get() = layerName.contains("contour", ignoreCase = true) ||
                layerName.contains("elevation", ignoreCase = true) ||
                properties.containsKey("ele") ||
                properties.containsKey("height")

        val elevationM: Double?
            get() = properties["ele"]?.toDoubleOrNull() ?: properties["height"]?.toDoubleOrNull()
    }

    data class VectorLayer(
        val name: String,
        val features: List<VectorFeature>,
        val extent: Int,
    )

    data class VectorTile(val layers: List<VectorLayer>) {
        /** Rough heap cost, for sizing caches: points dominate. */
        val estimatedBytes: Long by lazy {
            var total = 64L
            for (layer in layers) {
                for (f in layer.features) {
                    total += 96L + f.properties.size * 96L
                    for (line in f.geometry) total += 32L + line.size * 24L
                }
            }
            total
        }
    }

    /** Bounds-checked protobuf cursor over `buf[pos, end)`: every read either succeeds inside the range or throws. */
    private class ProtoReader(private val buf: ByteArray, var pos: Int, val end: Int) {
        fun hasMore(): Boolean = pos < end
        fun remaining(): Long = (end - pos).toLong()

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw MalformedTileException("truncated varint")
                val b = buf[pos++].toInt()
                if (shift == 63) {
                    if ((b and 0xFE) != 0) throw MalformedTileException("varint longer than 10 bytes")
                    return result or ((b.toLong() and 1L) shl 63)
                }
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
        }

        /** A length prefix, which must fit in what is left of the range. */
        fun length(): Int {
            val v = varint()
            if (v < 0L || v > remaining()) throw MalformedTileException("length $v runs past the end of the data")
            return v.toInt()
        }

        fun advance(n: Int) {
            if (n < 0 || n > remaining()) throw MalformedTileException("fixed-width field runs past the end of the data")
            pos += n
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
                else -> throw MalformedTileException("unsupported wire type $wire")
            }
        }

        fun fixed32(): Int {
            if (remaining() < 4) throw MalformedTileException("truncated fixed32")
            var r = 0
            for (i in 0 until 4) r = r or ((buf[pos + i].toInt() and 0xFF) shl (i * 8))
            pos += 4
            return r
        }

        fun fixed64(): Long {
            if (remaining() < 8) throw MalformedTileException("truncated fixed64")
            var r = 0L
            for (i in 0 until 8) r = r or ((buf[pos + i].toLong() and 0xFFL) shl (i * 8))
            pos += 8
            return r
        }

        fun string(len: Int): String {
            val s = buf.decodeToString(pos, pos + len)
            pos += len
            return s
        }

        /** A reader over an earlier range of the same buffer (already length-checked when it was recorded). */
        fun at(start: Int, stop: Int): ProtoReader = ProtoReader(buf, start, stop)

        /** A reader over the next [len] bytes, which are consumed from this one. */
        fun sub(len: Int): ProtoReader {
            val r = ProtoReader(buf, pos, pos + len)
            pos += len
            return r
        }
    }

    private class MvtBudget {
        var features = 0
        var points = 0
    }

    private fun fieldOf(tag: Long): Int = if (tag ushr 3 > 4096L) -1 else (tag ushr 3).toInt()

    private fun formatNumber(d: Double): String =
        if (d.isFinite() && d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun decodeZigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)

    private fun parseMvtValue(r: ProtoReader): String {
        var out = ""
        while (r.hasMore()) {
            val tag = r.varint()
            val wire = (tag and 0x7L).toInt()
            when (fieldOf(tag)) {
                1 -> if (wire == 2) out = r.string(r.length()) else r.skip(wire)
                2 -> if (wire == 5) out = formatNumber(Float.fromBits(r.fixed32()).toDouble()) else r.skip(wire)
                3 -> if (wire == 1) out = formatNumber(Double.fromBits(r.fixed64())) else r.skip(wire)
                4 -> if (wire == 0) out = r.varint().toString() else r.skip(wire)
                5 -> if (wire == 0) out = r.varint().toULong().toString() else r.skip(wire)
                6 -> if (wire == 0) out = decodeZigzag(r.varint()).toString() else r.skip(wire)
                7 -> if (wire == 0) out = (r.varint() != 0L).toString() else r.skip(wire)
                else -> r.skip(wire)
            }
        }
        return out
    }

    private fun parseMvtGeometry(g: ProtoReader, extent: Int, budget: MvtBudget): List<List<TilePoint>> {
        val geometry = ArrayList<List<TilePoint>>()
        var current = ArrayList<TilePoint>()
        var cx = 0L
        var cy = 0L
        while (g.hasMore()) {
            val cmdInt = g.varint()
            if (cmdInt < 0L || cmdInt > 0xFFFFFFFFL) throw MalformedTileException("geometry command out of range")
            val cmd = (cmdInt and 0x7L).toInt()
            val count = cmdInt ushr 3
            when (cmd) {
                1, 2 -> {
                    // Each coordinate pair is at least two bytes, so a count the buffer cannot hold is a lie.
                    if (count * 2L > g.remaining()) throw MalformedTileException("geometry command count $count exceeds the buffer")
                    if (cmd == 1 && current.isNotEmpty()) {
                        geometry.add(current)
                        current = ArrayList()
                    }
                    budget.points += count.toInt()
                    if (budget.points > MAX_MVT_POINTS) throw MalformedTileException("tile has more than $MAX_MVT_POINTS points")
                    for (c in 0 until count.toInt()) {
                        val dx = g.varint()
                        val dy = g.varint()
                        if (dx < 0L || dx > 0xFFFFFFFFL || dy < 0L || dy > 0xFFFFFFFFL) throw MalformedTileException("geometry parameter out of range")
                        cx += decodeZigzag(dx)
                        cy += decodeZigzag(dy)
                        current.add(TilePoint(cx.toFloat() / extent, cy.toFloat() / extent))
                    }
                }
                7 -> {
                    if (current.isNotEmpty()) {
                        current.add(current.first())
                        geometry.add(current)
                        current = ArrayList()
                    }
                }
                else -> throw MalformedTileException("unknown geometry command $cmd")
            }
        }
        if (current.isNotEmpty()) geometry.add(current)
        return geometry
    }

    private fun parseMvtFeature(
        r: ProtoReader,
        extent: Int,
        keys: List<String>,
        values: List<String>,
        layerName: String,
        budget: MvtBudget,
    ): VectorFeature {
        var id = 0L
        var type = GeometryType.Unknown
        var tags: ProtoReader? = null
        var geom: ProtoReader? = null
        while (r.hasMore()) {
            val tag = r.varint()
            val wire = (tag and 0x7L).toInt()
            when (fieldOf(tag)) {
                1 -> if (wire == 0) id = r.varint() else r.skip(wire)
                2 -> if (wire == 2) tags = r.sub(r.length()) else r.skip(wire)
                3 -> if (wire == 0) {
                    type = when (r.varint()) {
                        1L -> GeometryType.Point
                        2L -> GeometryType.LineString
                        3L -> GeometryType.Polygon
                        else -> GeometryType.Unknown
                    }
                } else {
                    r.skip(wire)
                }
                4 -> if (wire == 2) geom = r.sub(r.length()) else r.skip(wire)
                0 -> throw MalformedTileException("field number 0")
                else -> r.skip(wire)
            }
        }
        val props = LinkedHashMap<String, String>()
        if (tags != null) {
            while (tags.hasMore()) {
                val k = tags.varint()
                if (!tags.hasMore()) break
                val v = tags.varint()
                if (k in 0L until keys.size.toLong() && v in 0L until values.size.toLong()) {
                    props[keys[k.toInt()]] = values[v.toInt()]
                }
            }
        }
        val geometry = if (geom != null) parseMvtGeometry(geom, extent, budget) else emptyList()
        return VectorFeature(id, type, geometry, props, layerName)
    }

    private fun parseMvtLayer(r: ProtoReader, budget: MvtBudget): VectorLayer {
        var name = "unknown"
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val featureRanges = ArrayList<IntArray>()
        while (r.hasMore()) {
            val tag = r.varint()
            val wire = (tag and 0x7L).toInt()
            when (fieldOf(tag)) {
                1 -> if (wire == 2) name = r.string(r.length()) else r.skip(wire)
                2 -> if (wire == 2) {
                    val len = r.length()
                    if (++budget.features > MAX_MVT_FEATURES) throw MalformedTileException("tile has more than $MAX_MVT_FEATURES features")
                    featureRanges.add(intArrayOf(r.pos, r.pos + len))
                    r.advance(len)
                } else {
                    r.skip(wire)
                }
                3 -> if (wire == 2) {
                    if (keys.size >= MAX_MVT_STRINGS) throw MalformedTileException("layer has too many keys")
                    keys.add(r.string(r.length()))
                } else {
                    r.skip(wire)
                }
                4 -> if (wire == 2) {
                    if (values.size >= MAX_MVT_STRINGS) throw MalformedTileException("layer has too many values")
                    values.add(parseMvtValue(r.sub(r.length())))
                } else {
                    r.skip(wire)
                }
                5 -> if (wire == 0) {
                    val e = r.varint()
                    if (e <= 0L || e > MAX_MVT_EXTENT) throw MalformedTileException("extent $e out of range")
                    extent = e.toInt()
                } else {
                    r.skip(wire)
                }
                0 -> throw MalformedTileException("field number 0")
                else -> r.skip(wire)
            }
        }
        val features = ArrayList<VectorFeature>(featureRanges.size)
        for (range in featureRanges) {
            features.add(parseMvtFeature(r.at(range[0], range[1]), extent, keys, values, name, budget))
        }
        return VectorLayer(name, features, extent)
    }

    /**
     * Decodes a Mapbox Vector Tile (gzip-compressed or plain). Input is untrusted: inflation, layers, features, points
     * and strings are all capped, lengths and geometry counts are checked against the bytes present, and the only
     * exception that can escape is [MalformedTileException].
     */
    fun parseMvt(bytes: ByteArray): VectorTile {
        val data = inflate(bytes, PMTILES_COMPRESSION_UNKNOWN, "vector tile")
        val r = ProtoReader(data, 0, data.size)
        val layers = ArrayList<VectorLayer>()
        val budget = MvtBudget()
        while (r.hasMore()) {
            val tag = r.varint()
            val wire = (tag and 0x7L).toInt()
            when (fieldOf(tag)) {
                3 -> if (wire == 2) {
                    if (layers.size >= MAX_MVT_LAYERS) throw MalformedTileException("tile has more than $MAX_MVT_LAYERS layers")
                    layers.add(parseMvtLayer(r.sub(r.length()), budget))
                } else {
                    r.skip(wire)
                }
                0 -> throw MalformedTileException("field number 0")
                else -> r.skip(wire)
            }
        }
        return VectorTile(layers)
    }
}
