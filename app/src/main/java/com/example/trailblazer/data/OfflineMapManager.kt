package com.example.trailblazer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.geo.TileUtils.TileBounds
import com.trailblazer.core.geo.TileUtils.TileFormat
import com.trailblazer.core.geo.TileUtils.VectorTile
import com.trailblazer.core.io.decompressGzip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
    fun getTile(zoom: Int, x: Int, y: Int): DecodedTile?
}

internal fun decodeTileBytes(raw: ByteArray, hintFormat: TileFormat): DecodedTile? {
    val detected = if (hintFormat != TileFormat.Unknown) hintFormat else TileFormat.detect(raw)
    return if (detected == TileFormat.VectorMvt) {
        try {
            DecodedTile.Vector(TileUtils.parseMvt(raw))
        } catch (_: Exception) {
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bmp != null) DecodedTile.Raster(bmp) else null
        }
    } else {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        if (bmp != null) {
            DecodedTile.Raster(bmp)
        } else {
            try {
                DecodedTile.Vector(TileUtils.parseMvt(raw))
            } catch (_: Exception) {
                null
            }
        }
    }
}

class MbtilesTileSource(override val file: File) : OfflineTileSource {
    private val db: SQLiteDatabase

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

        var hasMetadata = false
        try {
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                val nameCol = c.getColumnIndex("name")
                val valCol = c.getColumnIndex("value")
                while (c.moveToNext()) {
                    hasMetadata = true
                    val k = c.getString(nameCol)?.lowercase()
                    val v = c.getString(valCol) ?: continue
                    when (k) {
                        "name" -> parsedName = v
                        "format" -> parsedFormat = TileFormat.fromMimeOrExtension(v)
                        "minzoom" -> parsedMinZ = v.toIntOrNull() ?: parsedMinZ
                        "maxzoom" -> parsedMaxZ = v.toIntOrNull() ?: parsedMaxZ
                        "bounds" -> {
                            val parts = v.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                            if (parts.size == 4) {
                                parsedBounds = TileBounds(parts[1], parts[0], parts[3], parts[2])
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val hasTiles = try {
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='tiles'", null).use { c ->
                if (c.moveToNext()) c.getInt(0) > 0 else false
            }
        } catch (_: Exception) {
            false
        }

        if (!hasMetadata && !hasTiles) {
            try { db.close() } catch (_: Exception) {}
            throw IllegalArgumentException("File lacks required MBTiles metadata or tiles tables")
        }

        name = parsedName
        format = parsedFormat
        minZoom = parsedMinZ
        maxZoom = parsedMaxZ
        bounds = parsedBounds
    }

    override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? {
        if (!db.isOpen) return null
        val tmsY = TileUtils.xyzYToTmsY(y, zoom)
        return try {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(zoom.toString(), x.toString(), tmsY.toString()),
            ).use { cursor ->
                if (cursor.moveToNext()) cursor.getBlob(0) else null
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
        try {
            if (db.isOpen) db.close()
        } catch (_: Exception) {}
    }
}

class PmtilesTileSource(override val file: File) : OfflineTileSource {
    private val raf: RandomAccessFile
    private val header: TileUtils.PmtilesHeader
    private val entries: List<TileUtils.PmtilesEntry>

    override val name: String get() = file.nameWithoutExtension
    override val format: TileFormat get() = header.tileType
    override val minZoom: Int get() = header.minZoom
    override val maxZoom: Int get() = header.maxZoom
    override val bounds: TileBounds? get() = TileBounds(header.minLat, header.minLon, header.maxLat, header.maxLon)

    init {
        val r = RandomAccessFile(file, "r")
        try {
            val headerBytes = ByteArray(127)
            r.readFully(headerBytes)
            header = TileUtils.parsePmtilesHeader(headerBytes)
            val dirBytes = ByteArray(header.rootDirectoryLength.toInt())
            r.seek(header.rootDirectoryOffset)
            r.readFully(dirBytes)
            entries = TileUtils.parsePmtilesDirectory(dirBytes)
            raf = r
        } catch (e: Exception) {
            try { r.close() } catch (_: Exception) {}
            throw e
        }
    }

    override fun getRawTile(zoom: Int, x: Int, y: Int): ByteArray? {
        val tileId = TileUtils.zxyToTileId(zoom, x, y)
        val entry = TileUtils.findPmtilesEntry(entries, tileId) ?: return null
        val buf = ByteArray(entry.length.toInt())
        synchronized(raf) {
            raf.seek(header.tileDataOffset + entry.offset)
            raf.readFully(buf)
        }
        return decompressGzip(buf)
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

class OfflineMapManager(
    private val context: Context,
    private val prefs: PrefsRepository,
) {
    val mapsDir: File = File(context.filesDir, "maps").apply { mkdirs() }

    fun listMaps(): List<OfflineMapInfo> {
        val files = mapsDir.listFiles { _, name ->
            name.endsWith(".mbtiles", ignoreCase = true) || name.endsWith(".pmtiles", ignoreCase = true)
        } ?: emptyArray()

        return files.mapNotNull { inspectFile(it) }.sortedBy { it.name }
    }

    fun inspectFile(file: File): OfflineMapInfo? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            if (file.name.endsWith(".pmtiles", ignoreCase = true)) {
                PmtilesTileSource(file).use { s ->
                    OfflineMapInfo(file, s.name, s.format, s.minZoom, s.maxZoom, s.bounds, file.length())
                }
            } else {
                MbtilesTileSource(file).use { s ->
                    OfflineMapInfo(file, s.name, s.format, s.minZoom, s.maxZoom, s.bounds, file.length())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importMap(uri: Uri): Result<OfflineMapInfo> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var originalName: String? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToNext()) {
                    originalName = cursor.getString(0)
                }
            }
        } catch (_: Exception) {}

        if (originalName.isNullOrBlank()) {
            originalName = uri.lastPathSegment ?: "offline_map.mbtiles"
        }

        val safeName = originalName!!.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val isPmtiles = safeName.endsWith(".pmtiles", ignoreCase = true)
        val ext = if (isPmtiles) ".pmtiles" else ".mbtiles"
        val baseName = safeName.removeSuffix(ext).removeSuffix(".mbtiles").removeSuffix(".pmtiles").ifEmpty { "offline_map" }
        var targetFile = File(mapsDir, "$baseName$ext")
        var counter = 1
        while (targetFile.exists()) {
            targetFile = File(mapsDir, "${baseName}_$counter$ext")
            counter++
        }

        try {
            val input = resolver.openInputStream(uri) ?: return@withContext Result.failure(IOException("Could not open file"))
            val tempFile = File(mapsDir, "${targetFile.name}.tmp")
            input.use { inStream ->
                tempFile.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val info = inspectFile(targetFile)
            if (info == null) {
                targetFile.delete()
                return@withContext Result.failure(IllegalArgumentException("File is not a valid MBTiles or PMTiles archive"))
            }

            prefs.update { it.copy(activeOfflineMapPath = targetFile.absolutePath) }
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openSource(file: File): OfflineTileSource? {
        if (!file.exists()) return null
        return try {
            if (file.name.endsWith(".pmtiles", ignoreCase = true)) {
                PmtilesTileSource(file)
            } else {
                MbtilesTileSource(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun openActiveSource(): OfflineTileSource? {
        val currentPath = runBlocking { prefs.settings.first().activeOfflineMapPath }
        if (currentPath != null) {
            val file = File(currentPath)
            if (file.exists()) {
                val source = openSource(file)
                if (source != null) return source
            }
        }
        val first = listMaps().firstOrNull() ?: return null
        return openSource(first.file)
    }

    fun deleteMap(file: File): Boolean {
        val deleted = file.delete()
        if (deleted) {
            val remaining = listMaps()
            runBlocking {
                prefs.update { it.copy(activeOfflineMapPath = remaining.firstOrNull()?.file?.absolutePath) }
            }
        }
        return deleted
    }
}
