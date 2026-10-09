package com.trailblazer.core.geo

import com.trailblazer.core.io.decompressGzip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

object TileUtils {
    const val TILE_SIZE = 256
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 22

    data class TileCoord(val x: Int, val y: Int, val zoom: Int) {
        val tmsY: Int get() = (1 shl zoom) - 1 - y
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

    data class TileRange(val zoom: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

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

    fun lonToTileX(lon: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return (lon + 180.0) / 360.0 * n
    }

    fun latToTileY(lat: Double, zoom: Int): Double {
        val latRad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val sinLat = sin(latRad).coerceIn(-0.999999, 0.999999)
        val n = 1 shl zoom
        return (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * n
    }

    fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
        val n = (1 shl zoom).coerceAtLeast(1)
        val x = lonToTileX(lon, zoom).toInt().coerceIn(0, n - 1)
        val y = latToTileY(lat, zoom).toInt().coerceIn(0, n - 1)
        return TileCoord(x, y, zoom)
    }

    fun latLonToTile(pos: LatLon, zoom: Int): TileCoord = latLonToTile(pos.lat, pos.lon, zoom)

    fun tileXToLon(x: Double, zoom: Int): Double {
        val n = 1 shl zoom
        return (x / n) * 360.0 - 180.0
    }

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = 1 shl zoom
        val mercatorY = PI - 2.0 * PI * y / n
        return atan(sinh(mercatorY)) * 180.0 / PI
    }

    fun tileToLatLon(x: Double, y: Double, zoom: Int): LatLon =
        LatLon(tileYToLat(y, zoom), tileXToLon(x, zoom))

    fun tileBounds(x: Int, y: Int, zoom: Int): TileBounds {
        val maxLat = tileYToLat(y.toDouble(), zoom)
        val minLat = tileYToLat((y + 1).toDouble(), zoom)
        val minLon = tileXToLon(x.toDouble(), zoom)
        val maxLon = tileXToLon((x + 1).toDouble(), zoom)
        return TileBounds(minLat, minLon, maxLat, maxLon)
    }

    fun xyzYToTmsY(y: Int, zoom: Int): Int = (1 shl zoom) - 1 - y
    fun tmsYToXyzY(tmsY: Int, zoom: Int): Int = (1 shl zoom) - 1 - tmsY

    fun metersPerPixel(lat: Double, zoom: Double): Double {
        val latRad = lat * PI / 180.0
        return 156543.03392 * cos(latRad) / 2.0.pow(zoom)
    }

    // Viewport and Screen Calculations

    fun latLonToScreen(pos: LatLon, center: LatLon, zoom: Double, widthPx: Float, heightPx: Float): ScreenPoint {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val centerNormX = (center.lon + 180.0) / 360.0
        val centerLatRad = center.lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val centerSinLat = sin(centerLatRad).coerceIn(-0.999999, 0.999999)
        val centerNormY = 0.5 - ln((1.0 + centerSinLat) / (1.0 - centerSinLat)) / (4.0 * PI)

        val posNormX = (pos.lon + 180.0) / 360.0
        val posLatRad = pos.lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val posSinLat = sin(posLatRad).coerceIn(-0.999999, 0.999999)
        val posNormY = 0.5 - ln((1.0 + posSinLat) / (1.0 - posSinLat)) / (4.0 * PI)

        val screenX = (widthPx / 2.0 + (posNormX - centerNormX) * worldSize).toFloat()
        val screenY = (heightPx / 2.0 + (posNormY - centerNormY) * worldSize).toFloat()
        return ScreenPoint(screenX, screenY)
    }

    fun screenToLatLon(screenX: Float, screenY: Float, center: LatLon, zoom: Double, widthPx: Float, heightPx: Float): LatLon {
        val worldSize = TILE_SIZE * 2.0.pow(zoom)
        val centerNormX = (center.lon + 180.0) / 360.0
        val centerLatRad = center.lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val centerSinLat = sin(centerLatRad).coerceIn(-0.999999, 0.999999)
        val centerNormY = 0.5 - ln((1.0 + centerSinLat) / (1.0 - centerSinLat)) / (4.0 * PI)

        val posNormX = centerNormX + (screenX - widthPx / 2.0) / worldSize
        val posNormY = centerNormY + (screenY - heightPx / 2.0) / worldSize

        val lon = (posNormX * 360.0) - 180.0
        val mercatorY = PI - 2.0 * PI * posNormY
        val lat = atan(sinh(mercatorY)) * 180.0 / PI
        return LatLon(lat.coerceIn(-85.05112878, 85.05112878), lon)
    }

    fun fitViewport(points: List<LatLon>, widthPx: Int, heightPx: Int, paddingPx: Int = 40): Pair<LatLon, Double> {
        if (points.isEmpty()) return Pair(LatLon(0.0, 0.0), 2.0)
        if (points.size == 1) return Pair(points.first(), 13.0)

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }

        val center = LatLon((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)

        val availW = max(widthPx - 2 * paddingPx, 50).toDouble()
        val availH = max(heightPx - 2 * paddingPx, 50).toDouble()

        val lonSpan = max(abs(maxLon - minLon), 0.0001) / 360.0
        val y0 = latToTileY(maxLat, 0)
        val y1 = latToTileY(minLat, 0)
        val latSpan = max(abs(y1 - y0), 0.0001)

        val zoomX = ln(availW / (lonSpan * TILE_SIZE)) / ln(2.0)
        val zoomY = ln(availH / (latSpan * TILE_SIZE)) / ln(2.0)

        val zoom = min(zoomX, zoomY).coerceIn(1.0, 18.0)
        return Pair(center, zoom)
    }

    fun visibleTileRange(center: LatLon, zoom: Double, widthPx: Float, heightPx: Float): TileRange {
        val intZoom = floor(zoom).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
        val topLeft = screenToLatLon(0f, 0f, center, zoom, widthPx, heightPx)
        val bottomRight = screenToLatLon(widthPx, heightPx, center, zoom, widthPx, heightPx)

        val maxTileIndex = (1 shl intZoom) - 1
        val minX = lonToTileX(topLeft.lon, intZoom).toInt().coerceIn(0, maxTileIndex)
        val maxX = lonToTileX(bottomRight.lon, intZoom).toInt().coerceIn(0, maxTileIndex)
        val minY = latToTileY(topLeft.lat, intZoom).toInt().coerceIn(0, maxTileIndex)
        val maxY = latToTileY(bottomRight.lat, intZoom).toInt().coerceIn(0, maxTileIndex)

        return TileRange(intZoom, minOf(minX, maxX), maxOf(minX, maxX), minOf(minY, maxY), maxOf(minY, maxY))
    }

    // Hilbert Curve and PMTiles v3 Tile ID Calculations

    fun hilbertIndex(x: Int, y: Int, z: Int): Long {
        if (z == 0) return 0L
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
        if (z == 0) return Pair(0, 0)
        val n = 1 shl z
        var rx: Long
        var ry: Long
        var t = index
        var curX = 0
        var curY = 0
        var s = 1
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
            curX += (s * rx).toInt()
            curY += (s * ry).toInt()
            t /= 4
            s *= 2
        }
        return Pair(curX, curY)
    }

    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        var acc = 0L
        for (i in 0 until z) {
            acc += 1L shl (2 * i)
        }
        return acc + hilbertIndex(x, y, z)
    }

    fun tileIdToZxy(tileId: Long): Triple<Int, Int, Int> {
        var id = tileId
        var z = 0
        while (true) {
            val numTiles = 1L shl (2 * z)
            if (id < numTiles) break
            id -= numTiles
            z++
        }
        val (x, y) = hilbertCoord(id, z)
        return Triple(z, x, y)
    }

    // PMTiles v3 Header and Directory Parser

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
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val tileType: TileFormat,
        val internalCompression: Int,
        val tileCompression: Int,
    )

    data class PmtilesEntry(val tileId: Long, val offset: Long, val length: Long, val runLength: Int)

    fun parsePmtilesHeader(bytes: ByteArray): PmtilesHeader {
        require(bytes.size >= 127) { "PMTiles header must be at least 127 bytes" }
        require(bytes[0] == 'P'.code.toByte() && bytes[1] == 'M'.code.toByte()) { "Invalid PMTiles magic" }
        val version = bytes[2].toInt() and 0xFF
        require(version == 3) { "Unsupported PMTiles version: $version (expected 3)" }

        fun readU64(off: Int): Long {
            var res = 0L
            for (i in 0 until 8) {
                res = res or ((bytes[off + i].toLong() and 0xFFL) shl (i * 8))
            }
            return res
        }

        fun readI32(off: Int): Int {
            var res = 0
            for (i in 0 until 4) {
                res = res or ((bytes[off + i].toInt() and 0xFF) shl (i * 8))
            }
            return res
        }

        val rootOff = readU64(3)
        val rootLen = readU64(11)
        val dataOff = readU64(51)
        val dataLen = readU64(59)
        val internalComp = bytes[92].toInt() and 0xFF
        val tileComp = bytes[93].toInt() and 0xFF
        val tileTypeByte = bytes[94].toInt() and 0xFF
        val minZ = bytes[95].toInt() and 0xFF
        val maxZ = bytes[96].toInt() and 0xFF
        val minLon = readI32(97) / 10_000_000.0
        val minLat = readI32(101) / 10_000_000.0
        val maxLon = readI32(105) / 10_000_000.0
        val maxLat = readI32(109) / 10_000_000.0
        val centerZ = bytes[113].toInt() and 0xFF
        val centerLon = readI32(114) / 10_000_000.0
        val centerLat = readI32(118) / 10_000_000.0

        val format = when (tileTypeByte) {
            1 -> TileFormat.VectorMvt
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
            tileDataOffset = dataOff,
            tileDataLength = dataLen,
            tileType = format,
            internalCompression = internalComp,
            tileCompression = tileComp,
        )
    }

    fun parsePmtilesDirectory(bytes: ByteArray): List<PmtilesEntry> {
        val decompressed = decompressGzip(bytes)
        var pos = 0
        fun readVarint(): Long {
            var res = 0L
            var shift = 0
            while (pos < decompressed.size) {
                val b = decompressed[pos++].toLong()
                res = res or ((b and 0x7FL) shl shift)
                if ((b and 0x80L) == 0L) break
                shift += 7
            }
            return res
        }

        val entries = mutableListOf<PmtilesEntry>()
        if (pos >= decompressed.size) return entries
        val numEntries = readVarint().toInt()
        var lastTileId = 0L
        for (i in 0 until numEntries) {
            if (pos >= decompressed.size) break
            val delta = readVarint()
            val runLength = readVarint().toInt()
            val length = readVarint()
            val offset = readVarint()
            lastTileId += delta
            entries.add(PmtilesEntry(lastTileId, offset, length, runLength))
        }
        return entries
    }

    fun findPmtilesEntry(entries: List<PmtilesEntry>, tileId: Long): PmtilesEntry? {
        if (entries.isEmpty()) return null
        var low = 0
        var high = entries.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = entries[mid]
            if (tileId >= entry.tileId && tileId < entry.tileId + entry.runLength) {
                val offsetInRun = tileId - entry.tileId
                return PmtilesEntry(tileId, entry.offset + offsetInRun * entry.length, entry.length, 1)
            }
            if (tileId < entry.tileId) {
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return null
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

    data class VectorTile(val layers: List<VectorLayer>)

    fun parseMvt(bytes: ByteArray): VectorTile {
        val decompressed = decompressGzip(bytes)
        val layers = mutableListOf<VectorLayer>()
        var pos = 0

        fun readVarint(buf: ByteArray): Long {
            var res = 0L
            var shift = 0
            while (pos < buf.size) {
                val b = buf[pos++].toLong()
                res = res or ((b and 0x7FL) shl shift)
                if ((b and 0x80L) == 0L) break
                shift += 7
            }
            return res
        }

        fun decodeZigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)

        while (pos < decompressed.size) {
            val tag = readVarint(decompressed)
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (wireType != 2) {
                if (wireType == 0) readVarint(decompressed)
                continue
            }
            val length = readVarint(decompressed).toInt()
            val endPos = pos + length

            if (fieldNum == 3) {
                // Layer message
                var layerName = "unknown"
                var extent = 4096
                val keys = mutableListOf<String>()
                val values = mutableListOf<String>()
                val rawFeatures = mutableListOf<ByteArray>()

                while (pos < endPos && pos < decompressed.size) {
                    val lTag = readVarint(decompressed)
                    val lField = (lTag ushr 3).toInt()
                    val lWire = (lTag and 0x7L).toInt()
                    when (lWire) {
                        0 -> {
                            val v = readVarint(decompressed)
                            if (lField == 5) extent = v.toInt()
                        }
                        2 -> {
                            val lLen = readVarint(decompressed).toInt()
                            val chunkEnd = pos + lLen
                            when (lField) {
                                1 -> {
                                    val strBytes = decompressed.copyOfRange(pos, chunkEnd)
                                    layerName = strBytes.decodeToString()
                                    pos = chunkEnd
                                }
                                2 -> {
                                    rawFeatures.add(decompressed.copyOfRange(pos, chunkEnd))
                                    pos = chunkEnd
                                }
                                3 -> {
                                    val strBytes = decompressed.copyOfRange(pos, chunkEnd)
                                    keys.add(strBytes.decodeToString())
                                    pos = chunkEnd
                                }
                                4 -> {
                                    // Value message
                                    var strVal = ""
                                    while (pos < chunkEnd) {
                                        val vTag = readVarint(decompressed)
                                        val vField = (vTag ushr 3).toInt()
                                        val vWire = (vTag and 0x7L).toInt()
                                        if (vWire == 2 && vField == 1) {
                                            val sLen = readVarint(decompressed).toInt()
                                            strVal = decompressed.copyOfRange(pos, pos + sLen).decodeToString()
                                            pos += sLen
                                        } else if (vWire == 0) {
                                            val num = readVarint(decompressed)
                                            strVal = num.toString()
                                        } else if (vWire == 5) { // 32-bit float
                                            pos += 4
                                        } else if (vWire == 1) { // 64-bit double
                                            pos += 8
                                        }
                                    }
                                    values.add(strVal)
                                    pos = chunkEnd
                                }
                                else -> pos = chunkEnd
                            }
                        }
                        else -> break
                    }
                }

                // Parse features
                val features = mutableListOf<VectorFeature>()
                for (fBytes in rawFeatures) {
                    var fPos = 0
                    fun readFVarint(): Long {
                        var res = 0L
                        var shift = 0
                        while (fPos < fBytes.size) {
                            val b = fBytes[fPos++].toLong()
                            res = res or ((b and 0x7FL) shl shift)
                            if ((b and 0x80L) == 0L) break
                            shift += 7
                        }
                        return res
                    }

                    var featId = 0L
                    var geomType = GeometryType.Unknown
                    val tagsList = mutableListOf<Int>()
                    val geomCmds = mutableListOf<Int>()

                    while (fPos < fBytes.size) {
                        val fTag = readFVarint()
                        val fField = (fTag ushr 3).toInt()
                        val fWire = (fTag and 0x7L).toInt()
                        when (fWire) {
                            0 -> {
                                val v = readFVarint()
                                if (fField == 1) featId = v
                                if (fField == 3) {
                                    geomType = when (v.toInt()) {
                                        1 -> GeometryType.Point
                                        2 -> GeometryType.LineString
                                        3 -> GeometryType.Polygon
                                        else -> GeometryType.Unknown
                                    }
                                }
                            }
                            2 -> {
                                val fLen = readFVarint().toInt()
                                val fEnd = fPos + fLen
                                if (fField == 2) {
                                    while (fPos < fEnd) tagsList.add(readFVarint().toInt())
                                } else if (fField == 4) {
                                    while (fPos < fEnd) geomCmds.add(readFVarint().toInt())
                                } else {
                                    fPos = fEnd
                                }
                            }
                            else -> break
                        }
                    }

                    val props = mutableMapOf<String, String>()
                    var tIdx = 0
                    while (tIdx + 1 < tagsList.size) {
                        val kIdx = tagsList[tIdx]
                        val vIdx = tagsList[tIdx + 1]
                        if (kIdx in keys.indices && vIdx in values.indices) {
                            props[keys[kIdx]] = values[vIdx]
                        }
                        tIdx += 2
                    }

                    // Decode geometry commands
                    val geometry = mutableListOf<List<TilePoint>>()
                    var currentLine = mutableListOf<TilePoint>()
                    var cx = 0L
                    var cy = 0L
                    var gIdx = 0
                    while (gIdx < geomCmds.size) {
                        val cmdInt = geomCmds[gIdx++]
                        val cmd = cmdInt and 0x7
                        val count = cmdInt ushr 3
                        when (cmd) {
                            1 -> { // MoveTo
                                if (currentLine.isNotEmpty()) {
                                    geometry.add(currentLine)
                                    currentLine = mutableListOf()
                                }
                                for (c in 0 until count) {
                                    if (gIdx + 1 < geomCmds.size) {
                                        val dx = decodeZigzag(geomCmds[gIdx++].toLong())
                                        val dy = decodeZigzag(geomCmds[gIdx++].toLong())
                                        cx += dx; cy += dy
                                        currentLine.add(TilePoint(cx.toFloat() / extent, cy.toFloat() / extent))
                                    }
                                }
                            }
                            2 -> { // LineTo
                                for (c in 0 until count) {
                                    if (gIdx + 1 < geomCmds.size) {
                                        val dx = decodeZigzag(geomCmds[gIdx++].toLong())
                                        val dy = decodeZigzag(geomCmds[gIdx++].toLong())
                                        cx += dx; cy += dy
                                        currentLine.add(TilePoint(cx.toFloat() / extent, cy.toFloat() / extent))
                                    }
                                }
                            }
                            7 -> { // ClosePath
                                if (currentLine.isNotEmpty()) {
                                    currentLine.add(currentLine.first())
                                    geometry.add(currentLine)
                                    currentLine = mutableListOf()
                                }
                            }
                        }
                    }
                    if (currentLine.isNotEmpty()) geometry.add(currentLine)

                    features.add(VectorFeature(featId, geomType, geometry, props, layerName))
                }
                layers.add(VectorLayer(layerName, features, extent))
            }
            pos = endPos
        }

        return VectorTile(layers)
    }
}
