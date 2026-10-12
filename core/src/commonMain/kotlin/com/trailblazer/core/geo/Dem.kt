package com.trailblazer.core.geo

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

/** Anything that can say how high the ground is at a position. Null means unknown, never 0 m. */
fun interface ElevationSource {
    fun elevationM(position: LatLon): Double?
}

/**
 * A 1° x 1° SRTM tile, named by the integer latitude and longitude of its **south-west** corner (`N36W119` covers
 * 36–37 °N, 118–119 °W).
 */
data class DemTileId(val southLat: Int, val westLon: Int) {
    init {
        require(southLat in -90..89) { "tile latitude out of range: $southLat" }
        require(westLon in -180..179) { "tile longitude out of range: $westLon" }
    }

    /** The canonical file name, for example `N36W119.hgt`. */
    val fileName: String
        get() {
            val ns = if (southLat >= 0) 'N' else 'S'
            val ew = if (westLon >= 0) 'E' else 'W'
            return "$ns${pad(kotlin.math.abs(southLat), 2)}$ew${pad(kotlin.math.abs(westLon), 3)}.hgt"
        }

    companion object {
        private val NAME = Regex("^([NS])([0-9]{2})([EW])([0-9]{3})(?:\\.[A-Za-z0-9_-]+)?\\.hgt$", RegexOption.IGNORE_CASE)

        private fun pad(n: Int, width: Int): String = n.toString().padStart(width, '0')

        /**
         * The tile a file name places, or null when it does not parse. Real downloads are `N37W105.hgt` or
         * `N37W105.SRTMGL1.hgt`; case is ignored. `S00` and `W000` do not exist (the south-west corner of the tile just
         * below the equator is S01), and neither does a corner outside the world (N90, E180).
         */
        fun parse(fileName: String): DemTileId? {
            val m = NAME.matchEntire(fileName) ?: return null
            val lat = m.groupValues[2].toInt()
            val lon = m.groupValues[4].toInt()
            val south = m.groupValues[1].equals("S", ignoreCase = true)
            val west = m.groupValues[3].equals("W", ignoreCase = true)
            val signedLat = if (south) -lat else lat
            val signedLon = if (west) -lon else lon
            if (south && lat == 0) return null
            if (west && lon == 0) return null
            if (signedLat !in -90..89 || signedLon !in -180..179) return null
            return DemTileId(signedLat, signedLon)
        }

        /**
         * Every tile whose area contains [p], the natural one first. A point on a whole degree lies on the shared edge
         * of two tiles (their edge posts are identical), and the north pole and the antimeridian are the edges of the
         * last row and column of tiles; whichever of those tiles is installed can answer.
         */
        fun candidates(p: LatLon): List<DemTileId> {
            val souths = axis(p.lat, -90, 89)
            val wests = when (p.lon) {
                180.0 -> listOf(179, -180)
                -180.0 -> listOf(-180, 179)
                else -> axis(p.lon, -180, 179)
            }
            return souths.flatMap { s -> wests.map { w -> DemTileId(s, w) } }
        }

        private fun axis(v: Double, lo: Int, hi: Int): List<Int> {
            val f = floor(v).toInt()
            val out = ArrayList<Int>(2)
            out += f.coerceIn(lo, hi)
            if (v == f.toDouble() && f - 1 in lo..hi && (f - 1) !in out) out += f - 1
            return out
        }
    }
}

/** The two SRTM grids. [side] posts per row and per column, [spacingM] the nominal post spacing at the equator. */
enum class DemResolution(val side: Int, val spacingM: Double) {
    OneArcSecond(3601, 30.0),
    ThreeArcSecond(1201, 90.0),
    ;

    /** The exact size of a `.hgt` file of this grid: [side] squared big-endian 16-bit posts, no header. */
    val fileBytes: Long get() = side.toLong() * side * 2L

    companion object {
        fun forFileLength(length: Long): DemResolution? = entries.firstOrNull { it.fileBytes == length }
    }
}

enum class DemRejection { CompressedArchive, BadName, BadSize }

sealed interface DemInspection {
    data class Valid(val id: DemTileId, val resolution: DemResolution) : DemInspection
    data class Rejected(val reason: DemRejection) : DemInspection
}

/**
 * SRTM `.hgt` (height) files, as described in the NASA SRTM Collection User Guide (2015) section 2.1.4 and read by
 * GDAL's SRTMHGT driver:
 *
 * - the name gives the south-west corner (`N36W119`), and the elevations are metres above the EGM96 geoid;
 * - a square grid of big-endian signed 16-bit posts, 3,601 per side (1 arc-second) or 1,201 (3 arc-second), with no
 *   header, so the file size is exactly `side² x 2` bytes;
 * - row-major, the northernmost row first, each row running west to east;
 * - the edge rows and columns lie on whole degrees and repeat in the neighbouring tile;
 * - a void is -32,768.
 */
object Hgt {
    const val VOID = -32768

    /** Checks a candidate file by name and size alone, so a picked file is vetted before a byte is copied. */
    fun inspect(fileName: String, fileLength: Long): DemInspection {
        val lower = fileName.lowercase()
        if (lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".gzip")) {
            return DemInspection.Rejected(DemRejection.CompressedArchive)
        }
        val id = DemTileId.parse(fileName) ?: return DemInspection.Rejected(DemRejection.BadName)
        val resolution = DemResolution.forFileLength(fileLength) ?: return DemInspection.Rejected(DemRejection.BadSize)
        return DemInspection.Valid(id, resolution)
    }
}

/**
 * One `.hgt` tile read lazily through [readAt] (`length` bytes at `offset`, exactly): only the rows a sample needs are
 * fetched, and the last few are kept, so a profile that walks along a route reads each row about once. Never loads the
 * file. Not thread safe: callers serialise access.
 *
 * Sampling is bilinear between the four surrounding posts. A void post that has weight in the answer makes the answer
 * null: the elevation is not interpolated through a void and is never reported as 0 m. A read that fails is also
 * "unknown".
 */
class DemTile private constructor(
    val id: DemTileId,
    val resolution: DemResolution,
    private val readAt: (offset: Long, length: Int) -> ByteArray,
    private val onClose: () -> Unit,
) : ElevationSource {
    private val side = resolution.side
    private val cachedRow = IntArray(ROW_CACHE) { -1 }
    private val cachedBytes = arrayOfNulls<ByteArray>(ROW_CACHE)
    private val cachedAt = LongArray(ROW_CACHE)
    private var tick = 0L

    /** True when [p] is inside this tile, edges included. */
    fun covers(p: LatLon): Boolean = localLon(p.lon).let { lon ->
        p.lat >= id.southLat && p.lat <= id.southLat + 1 && lon >= id.westLon && lon <= id.westLon + 1
    }

    override fun elevationM(position: LatLon): Double? {
        if (!covers(position)) return null
        val last = side - 1
        val x = (localLon(position.lon) - id.westLon) * last
        val y = (id.southLat + 1 - position.lat) * last
        val col = floor(x).toInt().coerceIn(0, side - 2)
        val row = floor(y).toInt().coerceIn(0, side - 2)
        val fx = snap(x - col)
        val fy = snap(y - row)
        var sum = 0.0
        for (dr in 0..1) {
            for (dc in 0..1) {
                val w = (if (dc == 0) 1.0 - fx else fx) * (if (dr == 0) 1.0 - fy else fy)
                if (w <= 0.0) continue
                val post = post(row + dr, col + dc) ?: return null
                if (post == Hgt.VOID) return null
                sum += w * post
            }
        }
        return sum
    }

    /**
     * A position within a nanometre-scale fraction of a post is that post: converting degrees to a grid index leaves
     * rounding dust, which must not give a void neighbour a weight of 1e-16 and turn a valid post into "unknown".
     */
    private fun snap(f: Double): Double = when {
        f <= SNAP -> 0.0
        f >= 1.0 - SNAP -> 1.0
        else -> f
    }

    /** The raw post (row 0 is the north edge, column 0 the west edge), or null when it cannot be read. */
    internal fun post(row: Int, col: Int): Int? {
        val bytes = rowBytes(row) ?: return null
        val i = col * 2
        return ((bytes[i].toInt() shl 8) or (bytes[i + 1].toInt() and 0xFF)).toShort().toInt()
    }

    fun close() = onClose()

    /** The tile just east of 179 °E and the one just west of -179 °W share the antimeridian, 180 and -180 °. */
    private fun localLon(lon: Double): Double = when {
        id.westLon == -180 && lon == 180.0 -> -180.0
        id.westLon == 179 && lon == -180.0 -> 180.0
        else -> lon
    }

    private fun rowBytes(row: Int): ByteArray? {
        if (row !in 0 until side) return null
        tick++
        for (i in 0 until ROW_CACHE) {
            if (cachedRow[i] == row) {
                cachedAt[i] = tick
                return cachedBytes[i]
            }
        }
        val rowLength = side * 2
        val bytes = try {
            readAt(row.toLong() * rowLength, rowLength)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (bytes.size != rowLength) return null
        var slot = 0
        for (i in 1 until ROW_CACHE) if (cachedAt[i] < cachedAt[slot]) slot = i
        cachedRow[slot] = row
        cachedBytes[slot] = bytes
        cachedAt[slot] = tick
        return bytes
    }

    companion object {
        /** Rows kept: walking a route touches two adjacent rows at a time, so a handful is plenty. */
        private const val ROW_CACHE = 6
        private const val SNAP = 1e-9

        /**
         * Opens the tile that [fileName] and [fileLength] describe, or null when they are not a valid `.hgt`
         * ([Hgt.inspect] says why). [onClose] releases whatever backs [readAt].
         */
        fun open(
            fileName: String,
            fileLength: Long,
            readAt: (offset: Long, length: Int) -> ByteArray,
            onClose: () -> Unit = {},
        ): DemTile? {
            val ok = Hgt.inspect(fileName, fileLength) as? DemInspection.Valid ?: return null
            return DemTile(ok.id, ok.resolution, readAt, onClose)
        }
    }
}

/**
 * The tiles a device holds. [opener] opens one tile, or returns null when it is not installed (or unreadable); each
 * answer is remembered, and at most [maxOpen] tiles stay open. Not thread safe: one set per computation, [close]d
 * afterwards.
 */
class DemTileSet(
    private val opener: (DemTileId) -> DemTile?,
    private val maxOpen: Int = 6,
) : ElevationSource {
    private val open = LinkedHashMap<DemTileId, DemTile>()
    private val absent = HashSet<DemTileId>()

    override fun elevationM(position: LatLon): Double? {
        for (id in DemTileId.candidates(position)) {
            val tile = tile(id) ?: continue
            tile.elevationM(position)?.let { return it }
        }
        return null
    }

    /**
     * The finest post spacing among the installed tiles that hold [positions], or [fallbackM] when none does. A route
     * is sampled this densely: 30 m where 1 arc-second tiles exist, else 90 m.
     */
    fun finestSpacingM(positions: List<LatLon>, fallbackM: Double = DemResolution.ThreeArcSecond.spacingM): Double {
        var best = Double.POSITIVE_INFINITY
        for (p in positions) {
            for (id in DemTileId.candidates(p)) {
                val tile = tile(id) ?: continue
                if (tile.resolution.spacingM < best) best = tile.resolution.spacingM
            }
        }
        return if (best.isFinite()) best else fallbackM
    }

    fun close() {
        open.values.forEach { it.close() }
        open.clear()
    }

    private fun tile(id: DemTileId): DemTile? {
        open[id]?.let { return it }
        if (id in absent) return null
        val tile = opener(id)
        if (tile == null) {
            absent += id
            return null
        }
        if (open.size >= maxOpen) {
            val oldest = open.keys.first()
            open.remove(oldest)?.close()
        }
        open[id] = tile
        return tile
    }
}
