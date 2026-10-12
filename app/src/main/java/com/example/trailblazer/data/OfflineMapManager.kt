package com.example.trailblazer.data

import android.annotation.SuppressLint
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
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
internal const val MAX_RASTER_SIDE = 512

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
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        // JPEG has no alpha, so half the memory costs nothing visible; PNG and WebP may be translucent.
        if (probe.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
    }
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

private fun readHeader(file: File, length: Int): ByteArray? =
    try {
        // readFully: a bare read() may legally return fewer bytes than asked for.
        ByteArray(length).also { buf -> java.io.DataInputStream(file.inputStream()).use { it.readFully(buf) } }
    } catch (_: IOException) {
        null
    }

private fun hasSqliteMagic(file: File): Boolean =
    readHeader(file, 16)?.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)) == true

/** True when the file starts with the PMTiles magic: what a file is is decided by its bytes, not its name. */
internal fun hasPmtilesMagic(file: File): Boolean =
    readHeader(file, 7)?.contentEquals("PMTiles".toByteArray(Charsets.US_ASCII)) == true

/** True when the SQLite header says the database is in write-ahead-log mode (read/write format version 2, bytes 18/19). */
internal fun isWalDatabase(file: File): Boolean {
    val h = readHeader(file, 20) ?: return false
    return h[18].toInt() == 2 || h[19].toInt() == 2
}

private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

/** Removes SQLite's -wal / -shm / -journal files beside [file] (they are never wanted once the main file is gone or replaced). */
private fun deleteSqliteSidecars(file: File) {
    for (suffix in SIDECAR_SUFFIXES) File(file.path + suffix).delete()
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
        if (!hasSqliteMagic(file)) throw IllegalArgumentException("Invalid SQLite header for MBTiles file")

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
    /** Something the user should know about this import (for example a WAL-mode source), shown after it. */
    val note: String? = null,
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

private val backgroundCloser = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Closes the source on an IO thread. Closing a SQLite database or file can block, so UI callbacks (a ViewModel's
 * `onCleared`, a composable's dispose) use this instead of calling [close] on the main thread.
 */
fun OfflineTileSource.closeInBackground() {
    backgroundCloser.launch { close() }
}

/**
 * Owns the app's offline map archives. Imports copy the picked file into app storage (so no persistable SAF grant is
 * needed and a later-moved or deleted source cannot break the map), bounded in size, through a hidden temp file that is
 * validated before it is renamed into place, and removed on any failure or cancellation.
 *
 * What a file is is decided by its bytes (the "PMTiles" magic, the SQLite header), not by its name; the stored copy gets
 * the matching extension.
 */
class OfflineMapManager(
    private val context: Context,
    private val prefs: PrefsRepository,
    private val maxImportBytes: Long = DEFAULT_MAX_IMPORT_BYTES,
    /** Replaces how an installed archive is opened; tests use it to observe opens. */
    private val sourceOpener: ((File) -> OfflineTileSource?)? = null,
) {
    val mapsDir: File = File(context.filesDir, "maps").apply { mkdirs() }

    private val importLock = Mutex()

    fun listMaps(): List<OfflineMapInfo> {
        val files = mapsDir.listFiles { _, name ->
            name.endsWith(".mbtiles", ignoreCase = true) || name.endsWith(".pmtiles", ignoreCase = true)
        } ?: emptyArray()

        return files.mapNotNull { inspectFile(it) }.sortedBy { it.name }
    }

    fun inspectFile(file: File): OfflineMapInfo? = inspect(file)

    /** PMTiles by magic bytes; anything else with a SQLite header is MBTiles; an unrecognisable file falls back to its extension. */
    private fun isPmtilesFile(file: File): Boolean =
        hasPmtilesMagic(file) || (!hasSqliteMagic(file) && file.name.endsWith(".pmtiles", ignoreCase = true))

    private fun inspect(file: File, note: String? = null): OfflineMapInfo? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            openAs(file).use { s ->
                OfflineMapInfo(file, s.name, s.format, s.minZoom, s.maxZoom, s.bounds, file.length(), note)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openAs(file: File): OfflineTileSource =
        if (isPmtilesFile(file)) PmtilesTileSource(file) else MbtilesTileSource(file)

    private fun insideMapsDir(file: File): Boolean =
        try {
            file.canonicalFile.parentFile == mapsDir.canonicalFile
        } catch (_: IOException) {
            false
        }

    // File.getUsableSpace is the right question here: a quick "will the copy plausibly fit" pre-check against what this
    // app can actually write. (StorageManager.getAllocatableBytes also counts other apps' cache that we would still have
    // to ask the system to free.) A copy that runs out anyway fails cleanly with an IOException.
    @SuppressLint("UsableSpace")
    private fun freeBytes(): Long = mapsDir.usableSpace

    /** Switches a copied WAL-mode MBTiles to the rollback journal so it opens cleanly read-only and leaves no sidecars. */
    private fun convertToRollbackJournal(file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        }
        deleteSqliteSidecars(file)
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
                val baseName = safeName.removeSuffix(".mbtiles").removeSuffix(".pmtiles").take(100).ifEmpty { "offline_map" }

                declaredSize?.let { size ->
                    if (size > maxImportBytes) return@withLock Result.failure(IOException("File is ${size / MIB} MB; the limit is ${maxImportBytes / MIB} MB"))
                    if (size > freeBytes() - STORAGE_RESERVE_BYTES) return@withLock Result.failure(IOException("Not enough free storage for ${size / MIB} MB"))
                }

                // Leftovers of an import that was killed mid-copy (this process holds the only import lock).
                mapsDir.listFiles { _, n -> n.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }

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

                val isPmtiles = isPmtilesFile(staged)
                var note: String? = null
                if (!isPmtiles && hasSqliteMagic(staged) && isWalDatabase(staged)) {
                    // Only the main file was copied. Rows still sitting in the source's separate -wal file are not in it.
                    convertToRollbackJournal(staged)
                    note = WAL_NOTE
                }

                if (inspect(staged) == null) {
                    return@withLock Result.failure(IllegalArgumentException("File is not a valid MBTiles or PMTiles archive"))
                }
                deleteSqliteSidecars(staged)

                val ext = if (isPmtiles) ".pmtiles" else ".mbtiles"
                var target = File(mapsDir, "$baseName$ext")
                var counter = 1
                while (target.exists()) {
                    target = File(mapsDir, "${baseName}_$counter$ext")
                    counter++
                }
                if (!staged.renameTo(target)) throw IOException("Could not move the map into place")
                temp = null

                val info = inspect(target, note)
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
                temp?.let {
                    it.delete()
                    deleteSqliteSidecars(it)
                }
            }
        }
    }

    fun openSource(file: File): OfflineTileSource? {
        if (!file.exists()) return null
        return try {
            sourceOpener?.invoke(file) ?: openAs(file)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The map chosen in settings, else the first installed one; null when none is usable. Opens on an IO thread, and
     * if the caller is cancelled while that is happening the source that was just opened is closed, not leaked.
     */
    suspend fun openActiveSource(): OfflineTileSource? {
        val opened = AtomicReference<OfflineTileSource?>(null)
        try {
            return withContext(Dispatchers.IO) {
                pickActiveSource().also { opened.set(it) }
            }
        } catch (e: CancellationException) {
            // withContext waits for its block, so by now the block has either published a source or never opened one.
            opened.getAndSet(null)?.close()
            throw e
        }
    }

    private suspend fun pickActiveSource(): OfflineTileSource? {
        val currentPath = prefs.settings.first().activeOfflineMapPath
        if (currentPath != null) {
            val file = File(currentPath)
            if (insideMapsDir(file)) {
                openSource(file)?.let { return it }
            }
        }
        return listMaps().firstOrNull()?.let { openSource(it.file) }
    }

    /** Deletes an installed map and its SQLite sidecar files; if it was the active one the first remaining map becomes active. */
    suspend fun deleteMap(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!insideMapsDir(file)) return@withContext false
        val wasActive = prefs.settings.first().activeOfflineMapPath == file.absolutePath
        val deleted = file.delete()
        if (deleted) deleteSqliteSidecars(file)
        if (deleted && wasActive) {
            val next = listMaps().firstOrNull()?.file?.absolutePath
            prefs.update { it.copy(activeOfflineMapPath = next) }
        }
        deleted
    }

    companion object {
        const val DEFAULT_MAX_IMPORT_BYTES = 8L * 1024 * 1024 * 1024
        const val WAL_NOTE = "This file was saved in WAL mode; changes still held in its separate -wal file are not in the imported copy."
        private const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
        private const val MIB = 1024L * 1024
        private const val TEMP_PREFIX = ".import-"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
