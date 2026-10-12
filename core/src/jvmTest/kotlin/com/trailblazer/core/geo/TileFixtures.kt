package com.trailblazer.core.geo

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Byte-level builders for the tile formats, written from the Mapbox Vector Tile 2.1 and PMTiles v3 specifications
 * (not from the parser under test), so a test that round-trips through them checks the parser against the formats.
 */
object TileFixtures {
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // ---- protobuf ----

    class Pb {
        private val out = ByteArrayOutputStream()

        fun varint(v: Long): Pb {
            var x = v
            while (true) {
                val low = (x and 0x7FL).toInt()
                x = x ushr 7
                if (x == 0L) {
                    out.write(low)
                    return this
                }
                out.write(low or 0x80)
            }
        }

        fun tag(field: Int, wire: Int): Pb = varint((field.toLong() shl 3) or wire.toLong())
        fun uint(field: Int, v: Long): Pb = tag(field, 0).varint(v)
        fun bytes(field: Int, b: ByteArray): Pb = tag(field, 2).varint(b.size.toLong()).also { out.write(b) }
        fun string(field: Int, s: String): Pb = bytes(field, s.encodeToByteArray())
        fun fixed32(field: Int, v: Int): Pb {
            tag(field, 5)
            for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF)
            return this
        }
        fun fixed64(field: Int, v: Long): Pb {
            tag(field, 1)
            for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
            return this
        }
        fun raw(b: ByteArray): Pb = also { out.write(b) }
        fun build(): ByteArray = out.toByteArray()
    }

    fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)

    /** Packed command integers for a geometry: moveTo/lineTo carry points as deltas from the cursor. */
    class Geom {
        private val ints = ArrayList<Long>()
        private var cx = 0L
        private var cy = 0L

        private fun cmd(id: Int, count: Int) = ints.add((count.toLong() shl 3) or id.toLong())

        private fun point(x: Long, y: Long) {
            ints.add(zigzag(x - cx))
            ints.add(zigzag(y - cy))
            cx = x
            cy = y
        }

        fun moveTo(x: Long, y: Long): Geom = apply { cmd(1, 1); point(x, y) }

        /** One MoveTo command carrying several points: how a MultiPoint is encoded. */
        fun moveToMany(vararg xy: Long): Geom = apply {
            cmd(1, xy.size / 2)
            for (i in xy.indices step 2) point(xy[i], xy[i + 1])
        }
        fun lineTo(vararg xy: Long): Geom = apply {
            cmd(2, xy.size / 2)
            for (i in xy.indices step 2) point(xy[i], xy[i + 1])
        }
        fun close(): Geom = apply { cmd(7, 1) }
        fun rawInt(v: Long): Geom = apply { ints.add(v) }

        fun build(): ByteArray {
            val pb = Pb()
            ints.forEach { pb.varint(it) }
            return pb.build()
        }
    }

    class FeatureSpec(val type: Int, val geometry: ByteArray, val tags: List<Int> = emptyList(), val id: Long = 1L)

    fun mvtFeature(f: FeatureSpec): ByteArray {
        val pb = Pb().uint(1, f.id)
        if (f.tags.isNotEmpty()) {
            val t = Pb()
            f.tags.forEach { t.varint(it.toLong()) }
            pb.bytes(2, t.build())
        }
        return pb.uint(3, f.type.toLong()).bytes(4, f.geometry).build()
    }

    fun mvtValueString(s: String): ByteArray = Pb().string(1, s).build()

    fun mvtLayer(
        name: String,
        features: List<FeatureSpec>,
        keys: List<String> = emptyList(),
        values: List<ByteArray> = emptyList(),
        extent: Int = 4096,
    ): ByteArray {
        val pb = Pb().string(1, name)
        features.forEach { pb.bytes(2, mvtFeature(it)) }
        keys.forEach { pb.string(3, it) }
        values.forEach { pb.bytes(4, it) }
        return pb.uint(5, extent.toLong()).uint(15, 2).build()
    }

    fun mvtTile(vararg layers: ByteArray): ByteArray {
        val pb = Pb()
        layers.forEach { pb.bytes(3, it) }
        return pb.build()
    }

    /** One contour layer with two lines whose `ele` values come from the given value messages. */
    fun contourTile(eleValue: ByteArray = mvtValueString("1200")): ByteArray {
        val line1 = Geom().moveTo(0, 0).lineTo(1000, 500, 2000, 700).build()
        val line2 = Geom().moveTo(100, 100).lineTo(300, 100, 300, 300).close().build()
        return mvtTile(
            mvtLayer(
                "contours",
                listOf(FeatureSpec(2, line1, listOf(0, 0), 1), FeatureSpec(3, line2, listOf(0, 0), 2)),
                keys = listOf("ele"),
                values = listOf(eleValue),
            ),
        )
    }

    // ---- PMTiles v3 ----

    class Entry(val tileId: Long, val offset: Long, val length: Long, val runLength: Int)

    /** Serialises a directory exactly as the spec describes (columns; offset 0 = contiguous with the previous entry). */
    fun directory(entries: List<Entry>, compression: Int = 2): ByteArray {
        val pb = Pb().varint(entries.size.toLong())
        var last = 0L
        for (e in entries) {
            pb.varint(e.tileId - last)
            last = e.tileId
        }
        entries.forEach { pb.varint(it.runLength.toLong()) }
        entries.forEach { pb.varint(it.length) }
        var prevEnd = -1L
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.offset == prevEnd) pb.varint(0L) else pb.varint(e.offset + 1)
            prevEnd = e.offset + e.length
        }
        val raw = pb.build()
        return if (compression == 2) gzip(raw) else raw
    }

    class HeaderSpec(
        val rootOffset: Long = 127,
        val rootLength: Long = 0,
        val leafOffset: Long = 0,
        val leafLength: Long = 0,
        val dataOffset: Long = 0,
        val dataLength: Long = 0,
        val internalCompression: Int = 2,
        val tileCompression: Int = 2,
        val tileType: Int = 1,
        val minZoom: Int = 0,
        val maxZoom: Int = 14,
        val minLon: Double = -122.5,
        val minLat: Double = 37.0,
        val maxLon: Double = -121.5,
        val maxLat: Double = 38.0,
        val centerZoom: Int = 8,
        val centerLon: Double = -122.0,
        val centerLat: Double = 37.5,
    )

    private fun putU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putI32(b: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    fun pmtilesHeader(h: HeaderSpec): ByteArray {
        val b = ByteArray(127)
        "PMTiles".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[7] = 3
        putU64(b, 8, h.rootOffset)
        putU64(b, 16, h.rootLength)
        putU64(b, 40, h.leafOffset)
        putU64(b, 48, h.leafLength)
        putU64(b, 56, h.dataOffset)
        putU64(b, 64, h.dataLength)
        b[96] = 1
        b[97] = h.internalCompression.toByte()
        b[98] = h.tileCompression.toByte()
        b[99] = h.tileType.toByte()
        b[100] = h.minZoom.toByte()
        b[101] = h.maxZoom.toByte()
        putI32(b, 102, Math.round(h.minLon * 1e7).toInt())
        putI32(b, 106, Math.round(h.minLat * 1e7).toInt())
        putI32(b, 110, Math.round(h.maxLon * 1e7).toInt())
        putI32(b, 114, Math.round(h.maxLat * 1e7).toInt())
        b[118] = h.centerZoom.toByte()
        putI32(b, 119, Math.round(h.centerLon * 1e7).toInt())
        putI32(b, 123, Math.round(h.centerLat * 1e7).toInt())
        return b
    }

    /**
     * Lays out a complete archive: header, root directory, leaf directories, tile data. [tiles] are (z,x,y) to payload;
     * payloads are gzip-wrapped when [tileCompression] is 2. With [leafSize] > 0 the entries are split into leaf
     * directories of that many entries each (root then holds only leaf pointers).
     */
    fun archive(
        tiles: List<Pair<Triple<Int, Int, Int>, ByteArray>>,
        tileType: Int = 1,
        tileCompression: Int = 2,
        leafSize: Int = 0,
        minZoom: Int = 0,
        maxZoom: Int = 14,
    ): ByteArray {
        val data = ByteArrayOutputStream()
        val entries = ArrayList<Entry>()
        for ((zxy, payload) in tiles.sortedBy { TileUtils.zxyToTileId(it.first.first, it.first.second, it.first.third) }) {
            val bytes = if (tileCompression == 2) gzip(payload) else payload
            val id = TileUtils.zxyToTileId(zxy.first, zxy.second, zxy.third)
            entries.add(Entry(id, data.size().toLong(), bytes.size.toLong(), 1))
            data.write(bytes)
        }
        val leaves = ByteArrayOutputStream()
        val rootEntries = ArrayList<Entry>()
        if (leafSize > 0) {
            for (chunk in entries.chunked(leafSize)) {
                val dir = directory(chunk)
                rootEntries.add(Entry(chunk.first().tileId, leaves.size().toLong(), dir.size.toLong(), 0))
                leaves.write(dir)
            }
        } else {
            rootEntries.addAll(entries)
        }
        val root = directory(rootEntries)
        val rootOffset = 127L
        val leafOffset = rootOffset + root.size
        val dataOffset = leafOffset + leaves.size()
        val header = pmtilesHeader(
            HeaderSpec(
                rootOffset = rootOffset,
                rootLength = root.size.toLong(),
                leafOffset = leafOffset,
                leafLength = leaves.size().toLong(),
                dataOffset = dataOffset,
                dataLength = data.size().toLong(),
                tileCompression = tileCompression,
                tileType = tileType,
                minZoom = minZoom,
                maxZoom = maxZoom,
            ),
        )
        return header + root + leaves.toByteArray() + data.toByteArray()
    }

    fun readerOf(file: ByteArray): (Long, Int) -> ByteArray = { off, len ->
        if (off < 0 || len < 0 || off + len > file.size) throw java.io.IOException("read past end")
        file.copyOfRange(off.toInt(), (off + len).toInt())
    }
}
