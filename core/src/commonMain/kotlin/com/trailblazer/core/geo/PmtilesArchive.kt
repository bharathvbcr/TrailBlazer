package com.trailblazer.core.geo

import com.trailblazer.core.geo.TileUtils.PmtilesEntry
import com.trailblazer.core.geo.TileUtils.PmtilesHeader

/**
 * Random-access reader for a PMTiles v3 archive over any byte source ([readAt] reads `length` bytes at `offset`).
 *
 * Archives are untrusted user files. Every offset and length is checked against the file size and against the section
 * (root, leaf, tile data) it must lie in; leaf directories are followed at most [MAX_LEAF_DEPTH] deep and never into a
 * directory already visited by the same lookup (cycles); and sizes are bounded before any allocation. Violations raise
 * [MalformedTileException]. Not thread safe: callers serialise access (the directory cache is unsynchronised).
 */
class PmtilesArchive(
    private val fileLength: Long,
    private val readAt: (offset: Long, length: Int) -> ByteArray,
) {
    val header: PmtilesHeader
    private val root: List<PmtilesEntry>
    private val leafCache = LinkedHashMap<Long, List<PmtilesEntry>>()
    private var cachedEntries = 0

    init {
        if (fileLength < TileUtils.PMTILES_HEADER_BYTES) throw MalformedTileException("file is too small to be a PMTiles archive")
        header = TileUtils.parsePmtilesHeader(readAt(0L, TileUtils.PMTILES_HEADER_BYTES))
        requireInside(header.rootDirectoryOffset, header.rootDirectoryLength, fileLength, "root directory")
        requireInside(header.leafDirectoriesOffset, header.leafDirectoriesLength, fileLength, "leaf directories")
        requireInside(header.tileDataOffset, header.tileDataLength, fileLength, "tile data")
        if (header.rootDirectoryLength > MAX_ROOT_BYTES) throw MalformedTileException("root directory is ${header.rootDirectoryLength} bytes")
        root = TileUtils.parsePmtilesDirectory(
            readAt(header.rootDirectoryOffset, header.rootDirectoryLength.toInt()),
            header.internalCompression,
        )
    }

    /**
     * The decompressed bytes of tile [z]/[x]/[y] (XYZ, y from the north), or null when the archive has no such tile or
     * the request is outside the archive's zoom range or the world.
     */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom || !TileUtils.isValidTile(z, x, y)) return null
        val tileId = TileUtils.zxyToTileId(z, x, y)
        var entries = root
        var depth = 0
        val visited = HashSet<Long>()
        while (true) {
            val entry = TileUtils.findPmtilesEntry(entries, tileId) ?: return null
            if (!entry.isLeaf) return readTile(entry)
            if (depth++ >= MAX_LEAF_DEPTH) throw MalformedTileException("leaf directories are nested deeper than $MAX_LEAF_DEPTH")
            if (!visited.add(entry.offset)) throw MalformedTileException("leaf directory at ${entry.offset} points back into itself")
            entries = leafDirectory(entry)
        }
    }

    private fun leafDirectory(entry: PmtilesEntry): List<PmtilesEntry> {
        leafCache.remove(entry.offset)?.let {
            leafCache[entry.offset] = it
            return it
        }
        requireInside(entry.offset, entry.length, header.leafDirectoriesLength, "leaf directory")
        if (entry.length > MAX_LEAF_BYTES) throw MalformedTileException("leaf directory is ${entry.length} bytes")
        val dir = TileUtils.parsePmtilesDirectory(
            readAt(header.leafDirectoriesOffset + entry.offset, entry.length.toInt()),
            header.internalCompression,
        )
        leafCache[entry.offset] = dir
        cachedEntries += dir.size
        while (cachedEntries > MAX_CACHED_ENTRIES && leafCache.size > 1) {
            val oldest = leafCache.keys.first()
            cachedEntries -= leafCache.remove(oldest)?.size ?: 0
        }
        return dir
    }

    private fun readTile(entry: PmtilesEntry): ByteArray {
        requireInside(entry.offset, entry.length, header.tileDataLength, "tile")
        if (entry.length > MAX_TILE_BYTES) throw MalformedTileException("tile is ${entry.length} bytes")
        val raw = readAt(header.tileDataOffset + entry.offset, entry.length.toInt())
        return TileUtils.inflate(raw, header.tileCompression, "tile")
    }

    private fun requireInside(offset: Long, length: Long, limit: Long, what: String) {
        if (offset < 0L || length < 0L || offset > limit || length > limit - offset) {
            throw MalformedTileException("PMTiles $what ($offset+$length) lies outside its section of $limit bytes")
        }
    }

    companion object {
        /** The spec keeps the root directory under 16 KiB; a megabyte is already generous. */
        const val MAX_ROOT_BYTES = 1L shl 20
        const val MAX_LEAF_BYTES = 8L shl 20
        const val MAX_TILE_BYTES = 8L shl 20
        const val MAX_LEAF_DEPTH = 3
        const val MAX_CACHED_ENTRIES = 200_000
    }
}
