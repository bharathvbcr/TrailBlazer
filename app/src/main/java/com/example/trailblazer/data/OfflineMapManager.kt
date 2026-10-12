package com.example.trailblazer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.trailblazer.core.geo.MalformedTileException
import com.trailblazer.core.geo.PmtilesArchive
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.geo.TileUtils.TileBounds
import com.trailblazer.core.geo.TileUtils.TileFormat
import com.trailblazer.core.geo.TileUtils.VectorTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.cancellation.CancellationException

sealed interface DecodedTile {
    data class Raster(val bitmap: Bitmap) : DecodedTile
    data class Vector(val vectorTile: VectorTile) : DecodedTile
}

interface OfflineTileSource : Closeable {
    val file: File
    val name: String
    val format: TileFormat
    val minZoom: Int
    val maxZoom: Int
    val bounds: TileBounds?

    fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray?

    /** The decoded tile, or null when the archive has none there or the stored bytes are unusable. Never throws. */
    fun getTile(zoom: Int, x: Int, y: Int): DecodedTile?
}

/** A stored tile larger than this is not read (the query itself refuses it, so a hostile blob is never loaded). */
internal const val MAX_TILE_BLOB_BYTES = 8 * 1024 * 1024

/** Raster tiles are decoded down to at most this many pixels on a side. */
internal const val MAX_RASTER_SIDE = 1024

/** A raster tile that declares more pixels than this (a decompression bomb) is not decoded at all. */
internal const val MAX_RASTER_PIXELS = 64_000_000L

/**
 * Decodes a raster tile with its size checked first: a PNG or JPEG header can declare any dimensions, so the bounds are
 * read before a pixel is allocated, absurd ones are refused, and large ones are sub-sampled to [MAX_RASTER_SIDE].
 */
internal fun decodeBitmapBounded(raw: ByteArray): Bitmap? {
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val sample = try {
        BitmapFactory.decodeByteArray(raw, 0, raw.size, probe)
        rasterSampleSize(probe.outWidth, probe.outHeight)
    } catch (_: RuntimeException) {
        null
    } ?: return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return try {
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * The power-of-two sub-sampling that brings a declared [width] x [height] raster within [MAX_RASTER_SIDE], or null when
 * the size is not real (zero, negative) or declares more than [MAX_RASTER_PIXELS] pixels (a decompression bomb).
 */
internal fun rasterSampleSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_RASTER_PIXELS) return null
    var sample = 1
    while (maxOf(width, height) / sample > MAX_RASTER_SIDE) sample *= 2
    return sample
}

private fun parseVector(raw: ByteArray): DecodedTile? =
    try {
        DecodedTile.Vector(TileUtils.parseMvt(raw))
    } catch (_: MalformedTileException) {
        null
    }

private fun decodeRaster(raw: ByteArray): DecodedTile? = decodeBitmapBounded(raw)?.let { DecodedTile.Raster(it) }

/**
 * Decodes one stored tile. What the bytes are decides how they are read, because archive metadata is often wrong (a
 * "png" archive of gzipped vector tiles, say); the declared [hintFormat] only breaks the tie for bytes with no
 * recognisable signature. Undecodable bytes give null, never an exception.
 */
internal fun decodeTileBytes(raw: ByteArray, hintFormat: TileFormat): DecodedTile? {
    if (raw.isEmpty()) return null
    return when (TileFormat.detect(raw)) {
        TileFormat.VectorMvt -> parseVector(raw)
        TileFormat.RasterPng, TileFormat.RasterJpg, TileFormat.RasterWebp -> decodeRaster(raw)
        TileFormat.Unknown -> when (hintFormat) {
            TileFormat.VectorMvt -> parseVector(raw)
            TileFormat.Unknown -> decodeRaster(raw) ?: parseVector(raw)
            else -> decodeRaster(raw)
        }
    }
}

private fun boundsOrNull(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): TileBounds? {
    val ok = minLon.isFinite() && minLat.isFinite() && maxLon.isFinite() && maxLat.isFinite() &&
        minLon in -180.0..180.0 && maxLon in -180.0..180.0 && minLat in -90.0..90.0 && maxLat in -90.0..90.0 &&
        minLon <= maxLon && minLat <= maxLat
    return if (ok) TileBounds(minLat, minLon, maxLat, maxLon) else null
}

/**
 * An MBTiles archive, opened read-only. Rows are stored TMS (y from the south); callers use XYZ and the flip happens
 * here. All SQL is fixed text with bound parameters, and a blob larger than [MAX_TILE_BLOB_BYTES] is never loaded.
 */
class MbtilesTileSource(override val file: File) : OfflineTileSource {
    private val db: SQLiteDatabase

    @Volatile
    private var closed = false

    override val name: String
    override val format: TileFormat
    override val minZoom: Int
    override val maxZoom: Int
    override val bounds: TileBounds?

    init {
        if (!file.exists() || file.length() < 16) {
            throw IllegalArgumentException("File is not a valid SQLite database")
        }
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        if (!header.contentEquals(magic)) {
            throw IllegalArgumentException("Invalid SQLite header for MBTiles file")
        }

        db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        var parsedName = file.nameWithoutExtension
        var parsedFormat = TileFormat.Unknown
        var parsedMinZ = 0
        var parsedMaxZ = 18
        var parsedBounds: TileBounds? = null

        try {
            // Capped in rows and in text length: a hostile metadata table must not be able to exhaust memory.
            db.rawQuery("SELECT substr(name, 1, 64), substr(value, 1, 4096) FROM metadata LIMIT 1000", null).use { c ->
                while (c.moveToNext()) {
                    val k = c.getString(0)?.lowercase()
                    val v = c.getString(1) ?: continue
                    when (k) {
                        "name" -> if (v.isNotBlank()) parsedName = v.trim()
                        "format" -> parsedFormat = TileFormat.fromMimeOrExtension(v)
                        "minzoom" -> parsedMinZ = v.trim().toIntOrNull() ?: parsedMinZ
                        "maxzoom" -> parsedMaxZ = v.trim().toIntOrNull() ?: parsedMaxZ
                        "bounds" -> {
                            val parts = v.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                            if (parts.size == 4) parsedBounds = boundsOrNull(parts[0], parts[1], parts[2], parts[3])
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // metadata is optional for reading tiles; the tiles table check below is what decides validity
        }

        try {
            val hasTiles = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE name = 'tiles' AND type IN ('table', 'view') LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            if (!hasTiles) throw IllegalArgumentException("File has no MBTiles tiles table")
            // Proves the four columns exist without reading any row.
            db.rawQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles LIMIT 0", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            try { db.close() } catch (_: Exception) {}
            throw if (e is IllegalArgumentException) e else IllegalArgumentException("File is not a valid MBTiles archive: ${e.message}")
        }

        if (parsedMinZ !in 0..TileUtils.MAX_ARCHIVE_ZOOM || parsedMaxZ !in 0..TileUtils.MAX_ARCHIVE_ZOOM || parsedMinZ > parsedMaxZ) {
            parsedMinZ = 0
            parsedMaxZ = 18
        }
        name = parsedName
        format = parsedFormat
        minZoom = parsedMinZ
        maxZoom = parsedMaxZ
        bounds = parsedBounds
    }

    override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? {
        if (closed || !TileUtils.isValidTile(zoom, x, y)) return null
        val tmsY = TileUtils.xyzYToTmsY(y, zoom)
        return try {
            db.rawQuery(TILE_SQL, arrayOf(zoom.toString(), x.toString(), tmsY.toString())).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun getTile(zoom: Int, x: Int, y: Int): DecodedTile? {
        val raw = getRawTile(zoom, x, y) ?: return null
        return decodeTileBytes(raw, format)
    }

    override fun close() {
        closed = true
        try {
            if (db.isOpen) db.close()
        } catch (_: Exception) {}
    }

    private companion object {
        // The size cap is a compile-time constant spliced into the text; every value that varies is bound.
        const val TILE_SQL = "SELECT CASE WHEN length(tile_data) <= $MAX_TILE_BLOB_BYTES THEN tile_data END " +
            "FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1"
    }
}

/** A PMTiles v3 archive read through [PmtilesArchive], which validates every offset against the file. */
class PmtilesTileSource(override val file: File) : OfflineTileSource {
    private val raf = RandomAccessFile(file, "r")
    private val archive: PmtilesArchive = try {
        PmtilesArchive(raf.length(), ::readAt)
    } catch (e: Throwable) {
        try { raf.close() } catch (_: Exception) {}
        throw e
    }

    override val name: String get() = file.nameWithoutExtension
    override val format: TileFormat get() = archive.header.tileType
    override val minZoom: Int get() = archive.header.minZoom
    override val maxZoom: Int get() = archive.header.maxZoom
    override val bounds: TileBounds?
        get() = archive.header.let { boundsOrNull(it.minLon, it.minLat, it.maxLon, it.maxLat) }

    private fun readAt(offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        synchronized(raf) {
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? =
        try {
            synchronized(archive) { archive.getTile(zoom, x, y) }
        } catch (_: MalformedTileException) {
            null
        } catch (_: IOException) {
            null
        }

    override fun getTile(zoom: Int, x: Int, y: Int): DecodedTile? {
        val raw = getRawTile(zoom, x, y) ?: return null
        return decodeTileBytes(raw, format)
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Exception) {}
    }
}

data class OfflineMapInfo(
    val file: File,
    val name: String,
    val format: TileFormat,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: TileBounds?,
    val sizeBytes: Long,
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }
}

/**
 * Copies [input] to [output], giving up with an IOException past [limit] bytes and with a CancellationException as soon
 * as the calling coroutine is cancelled (checked after every chunk).
 */
internal suspend fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, chunkBytes: Int = 256 * 1024): Long {
    val buf = ByteArray(chunkBytes)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return total
        total += n
        if (total > limit) throw IOException("File is larger than the ${limit / (1024 * 1024)} MB limit")
        output.write(buf, 0, n)
        currentCoroutineContext().ensureActive()
    }
}

/**
 * Owns the app's offline map archives. Imports copy the picked file into app storage (so no persistable SAF grant is
 * needed and a later-moved or deleted source cannot break the map), bounded in size, through a hidden temp file that is
 * validated before it is renamed into place, and removed on any failure or cancellation.
 */
class OfflineMapManager(
    private val context: Context,
    private val prefs: PrefsRepository,
    private val maxImportBytes: Long = DEFAULT_MAX_IMPORT_BYTES,
) {
    val mapsDir: File = File(context.filesDir, "maps").apply { mkdirs() }

    private val importLock = Mutex()

    fun listMaps(): List<OfflineMapInfo> {
        val files = mapsDir.listFiles { _, name ->
            name.endsWith(".mbtiles", ignoreCase = true) || name.endsWith(".pmtiles", ignoreCase = true)
        } ?: emptyArray()

        return files.mapNotNull { inspectFile(it) }.sortedBy { it.name }
    }

    fun inspectFile(file: File): OfflineMapInfo? = inspect(file, file.name.endsWith(".pmtiles", ignoreCase = true))

    private fun inspect(file: File, isPmtiles: Boolean): OfflineMapInfo? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            openAs(file, isPmtiles).use { s ->
                OfflineMapInfo(file, s.name, s.format, s.minZoom, s.maxZoom, s.bounds, file.length())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openAs(file: File, isPmtiles: Boolean): OfflineTileSource =
        if (isPmtiles) PmtilesTileSource(file) else MbtilesTileSource(file)

    private fun insideMapsDir(file: File): Boolean =
        try {
            file.canonicalFile.parentFile == mapsDir.canonicalFile
        } catch (_: IOException) {
            false
        }

    suspend fun importMap(uri: Uri): Result<OfflineMapInfo> = withContext(Dispatchers.IO) {
        importLock.withLock<Result<OfflineMapInfo>> {
            var temp: File? = null
            try {
                val resolver = context.contentResolver
                var originalName: String? = null
                var declaredSize: Long? = null
                try {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            originalName = cursor.getString(0)
                            if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
                        }
                    }
                } catch (_: Exception) {}

                val sourceName = originalName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "offline_map.mbtiles"
                val safeName = sourceName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val isPmtiles = safeName.endsWith(".pmtiles", ignoreCase = true)
                val ext = if (isPmtiles) ".pmtiles" else ".mbtiles"
                val baseName = safeName.removeSuffix(ext).removeSuffix(".mbtiles").removeSuffix(".pmtiles").take(100).ifEmpty { "offline_map" }

                declaredSize?.let { size ->
                    if (size > maxImportBytes) return@withLock Result.failure(IOException("File is ${size / MIB} MB; the limit is ${maxImportBytes / MIB} MB"))
                    if (size > mapsDir.usableSpace - STORAGE_RESERVE_BYTES) return@withLock Result.failure(IOException("Not enough free storage for ${size / MIB} MB"))
                }

                mapsDir.listFiles { _, n -> n.startsWith(TEMP_PREFIX) && n.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }

                val input = resolver.openInputStream(uri) ?: return@withLock Result.failure(IOException("Could not open file"))
                val staged = File(mapsDir, "$TEMP_PREFIX${System.nanoTime()}$TEMP_SUFFIX")
                temp = staged
                input.use { inStream ->
                    FileOutputStream(staged).use { outStream ->
                        copyBounded(inStream, outStream, maxImportBytes)
                        outStream.flush()
                        outStream.fd.sync()
                    }
                }

                if (inspect(staged, isPmtiles) == null) {
                    return@withLock Result.failure(IllegalArgumentException("File is not a valid MBTiles or PMTiles archive"))
                }

                var target = File(mapsDir, "$baseName$ext")
                var counter = 1
                while (target.exists()) {
                    target = File(mapsDir, "${baseName}_$counter$ext")
                    counter++
                }
                if (!staged.renameTo(target)) throw IOException("Could not move the map into place")
                temp = null

                val info = inspect(target, isPmtiles)
                if (info == null) {
                    target.delete()
                    return@withLock Result.failure(IllegalArgumentException("File is not a valid MBTiles or PMTiles archive"))
                }

                prefs.update { it.copy(activeOfflineMapPath = target.absolutePath) }
                Result.success(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                temp?.delete()
            }
        }
    }

    fun openSource(file: File): OfflineTileSource? {
        if (!file.exists()) return null
        return try {
            openAs(file, file.name.endsWith(".pmtiles", ignoreCase = true))
        } catch (_: Exception) {
            null
        }
    }

    /** The map chosen in settings, else the first installed one; null when none is usable. Runs off the main thread. */
    suspend fun openActiveSource(): OfflineTileSource? = withContext(Dispatchers.IO) {
        val currentPath = prefs.settings.first().activeOfflineMapPath
        if (currentPath != null) {
            val file = File(currentPath)
            if (insideMapsDir(file)) {
                openSource(file)?.let { return@withContext it }
            }
        }
        listMaps().firstOrNull()?.let { openSource(it.file) }
    }

    /** Deletes an installed map; if it was the active one the first remaining map becomes active. */
    suspend fun deleteMap(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!insideMapsDir(file)) return@withContext false
        val wasActive = prefs.settings.first().activeOfflineMapPath == file.absolutePath
        val deleted = file.delete()
        if (deleted && wasActive) {
            val next = listMaps().firstOrNull()?.file?.absolutePath
            prefs.update { it.copy(activeOfflineMapPath = next) }
        }
        deleted
    }

    companion object {
        const val DEFAULT_MAX_IMPORT_BYTES = 8L * 1024 * 1024 * 1024
        private const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
        private const val MIB = 1024L * 1024
        private const val TEMP_PREFIX = ".import-"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
