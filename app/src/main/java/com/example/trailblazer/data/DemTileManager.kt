package com.example.trailblazer.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.trailblazer.core.geo.DemInspection
import com.trailblazer.core.geo.DemRejection
import com.trailblazer.core.geo.DemResolution
import com.trailblazer.core.geo.DemTile
import com.trailblazer.core.geo.DemTileId
import com.trailblazer.core.geo.DemTileSet
import com.trailblazer.core.geo.Hgt
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.ElevationProfile
import com.trailblazer.core.trip.ElevationProfiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.cancellation.CancellationException

/** An installed SRTM tile. */
data class DemTileInfo(val id: DemTileId, val resolution: DemResolution, val file: File) {
    val sizeBytes: Long get() = resolution.fileBytes
    val label: String get() = if (resolution == DemResolution.OneArcSecond) "1 arc-second" else "3 arc-second"
}

/** One line for the result of importing several tiles: what went in, and the first reason for what did not. */
internal fun importSummary(imported: List<DemTileInfo>, failures: List<String>): String = when {
    failures.isEmpty() && imported.isEmpty() -> "No elevation tiles were picked."
    failures.isEmpty() -> "Imported ${tileCount(imported.size)}: ${imported.joinToString(", ") { it.id.fileName.removeSuffix(".hgt") }}."
    imported.isEmpty() -> "Elevation import failed: ${failures.first()}"
    else -> "Imported ${tileCount(imported.size)}; ${failures.size} failed: ${failures.first()}"
}

private fun tileCount(n: Int) = if (n == 1) "1 elevation tile" else "$n elevation tiles"

/**
 * Owns the app's offline elevation tiles: SRTM `.hgt` files in `files/dem/`, named by their south-west corner.
 *
 * Imports follow [OfflineMapManager]: the picked file is copied into app storage (so no persistable SAF grant is needed),
 * bounded in size, through a hidden staged temp file that is validated (a parseable tile name and exactly 1,201² or
 * 3,601² big-endian 16-bit posts) before it is renamed into place, and removed on any failure or cancellation. Only
 * `.hgt` is accepted: archives are refused with a message, not unpacked, because that would need a decompression library.
 *
 * Tiles are read where they lie, a row at a time ([DemTile]); nothing loads a whole file.
 */
class DemTileManager(context: Context) {
    val demDir: File = File(context.filesDir, "dem").apply { mkdirs() }
    private val resolver = context.contentResolver
    private val importLock = Mutex()
    private val installed = MutableStateFlow(0)

    /** Bumped whenever tiles are added or removed, so a profile built from the old set can be recomputed. */
    val revision: StateFlow<Int> get() = installed

    fun listTiles(): List<DemTileInfo> {
        val files = demDir.listFiles { _, name -> name.endsWith(".hgt", ignoreCase = true) && !name.startsWith(TEMP_PREFIX) } ?: emptyArray()
        return files.mapNotNull { f ->
            (Hgt.inspect(f.name, f.length()) as? DemInspection.Valid)?.let { DemTileInfo(it.id, it.resolution, f) }
        }.sortedBy { it.id.fileName }
    }

    suspend fun deleteTile(id: DemTileId): Boolean = withContext(Dispatchers.IO) {
        val deleted = File(demDir, id.fileName).delete()
        if (deleted) installed.value++
        deleted
    }

    /** The user-facing reason a name and size are not a tile, or null when they are one. */
    internal fun rejectionMessage(name: String, size: Long): String? = when ((Hgt.inspect(name, size) as? DemInspection.Rejected)?.reason) {
        null -> null
        DemRejection.CompressedArchive ->
            "“$name” is a compressed archive. Unzip it first and import the .hgt file inside (a 1 or 3 arc-second SRTM tile)."
        DemRejection.BadName ->
            "“$name” is not named like an SRTM tile. Elevation tiles are named by their south-west corner, for example N36W119.hgt."
        DemRejection.BadSize ->
            "“$name” is not a 1 or 3 arc-second SRTM tile: expected ${DemResolution.OneArcSecond.fileBytes} or ${DemResolution.ThreeArcSecond.fileBytes} bytes, got $size."
    }

    // File.getUsableSpace is the right question for "will this plausibly fit"; a copy that runs out anyway fails with an IOException.
    @SuppressLint("UsableSpace")
    private fun freeBytes(): Long = demDir.usableSpace

    suspend fun importTile(uri: Uri): Result<DemTileInfo> = withContext(Dispatchers.IO) {
        importLock.withLock<Result<DemTileInfo>> {
            var temp: File? = null
            try {
                var displayName: String? = null
                var declaredSize: Long? = null
                try {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            displayName = cursor.getString(0)
                            if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
                        }
                    }
                } catch (_: Exception) {
                }
                // The name is the tile's georeference: without a parseable one the file cannot be placed.
                val name = displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "elevation.hgt"

                // Refuse before copying a byte: a bad name always, and a bad size when the provider tells us one. (With no
                // declared size a valid size stands in, so only the name can be refused here; the copy is checked below.)
                rejectionMessage(name, declaredSize ?: DemResolution.ThreeArcSecond.fileBytes)
                    ?.let { return@withLock Result.failure(IllegalArgumentException(it)) }
                declaredSize?.let { size ->
                    if (size > freeBytes() - STORAGE_RESERVE_BYTES) return@withLock Result.failure(IOException("Not enough free storage for ${size / MIB} MB"))
                }

                // Leftovers of an import that was killed mid-copy (this process holds the only import lock).
                demDir.listFiles { _, n -> n.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }

                val input = resolver.openInputStream(uri) ?: return@withLock Result.failure(IOException("Could not open file"))
                val staged = File(demDir, "$TEMP_PREFIX${System.nanoTime()}$TEMP_SUFFIX")
                temp = staged
                input.use { inStream ->
                    FileOutputStream(staged).use { outStream ->
                        copyBounded(inStream, outStream, DemResolution.OneArcSecond.fileBytes)
                        outStream.flush()
                        outStream.fd.sync()
                    }
                }

                val valid = when (val r = Hgt.inspect(name, staged.length())) {
                    is DemInspection.Valid -> r
                    is DemInspection.Rejected ->
                        return@withLock Result.failure(IllegalArgumentException(rejectionMessage(name, staged.length())))
                }
                val target = File(demDir, valid.id.fileName)
                // Rename replaces an existing copy of the same tile atomically.
                if (!staged.renameTo(target)) throw IOException("Could not move the tile into place")
                temp = null
                installed.value++
                Result.success(DemTileInfo(valid.id, valid.resolution, target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                temp?.delete()
            }
        }
    }

    /** Imports each picked file in turn (a route usually needs several tiles) and returns one line saying what happened. */
    suspend fun importAll(uris: List<Uri>): String {
        val imported = ArrayList<DemTileInfo>()
        val failures = ArrayList<String>()
        for (uri in uris) {
            importTile(uri).fold(
                onSuccess = { imported += it },
                onFailure = { failures += it.message ?: "Unknown error" },
            )
        }
        return importSummary(imported, failures)
    }

    /** Opens the installed tiles lazily for one computation; the caller closes the set. */
    fun openTileSet(): DemTileSet = DemTileSet({ id -> openTile(id) })

    private fun openTile(id: DemTileId): DemTile? {
        val file = File(demDir, id.fileName)
        if (!file.isFile) return null
        val raf = try {
            RandomAccessFile(file, "r")
        } catch (_: IOException) {
            return null
        }
        val tile = try {
            DemTile.open(
                file.name,
                raf.length(),
                { offset, length ->
                    val buf = ByteArray(length)
                    synchronized(raf) {
                        raf.seek(offset)
                        raf.readFully(buf)
                    }
                    buf
                },
                onClose = { try { raf.close() } catch (_: IOException) {} },
            )
        } catch (_: IOException) {
            null
        }
        if (tile == null) try { raf.close() } catch (_: IOException) {}
        return tile
    }

    /**
     * The elevation profile of a route over the installed tiles, on an IO thread. Cancelling the caller stops the
     * sampling within about a hundred samples and closes every tile it opened. Null for fewer than two stops.
     */
    suspend fun profile(stops: List<LatLon>): ElevationProfile? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val set = openTileSet()
        try {
            ElevationProfiler.sample(stops, set, set.finestSpacingM(stops), checkCancelled = { job.ensureActive() })
        } finally {
            set.close()
        }
    }

    private companion object {
        const val TEMP_PREFIX = ".import-"
        const val TEMP_SUFFIX = ".tmp"
        const val STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
        const val MIB = 1024L * 1024
    }
}
