package com.example.trailblazer.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/** Builders for tiny map archives and tiles, written from the MBTiles, PMTiles v3 and MVT specifications. */
object MapArchiveFixtures {
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun png(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** A PNG whose header declares [width] x [height] pixels but carries no pixel data. */
    fun pngDeclaring(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val len = data.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            val body = type.toByteArray(Charsets.US_ASCII) + data
            out.write(body)
            val crc = CRC32().apply { update(body) }.value
            out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        chunk("IHDR", be32(width) + be32(height) + byteArrayOf(8, 6, 0, 0, 0))
        chunk("IDAT", ByteArray(0))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            val low = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) { out.write(low); break }
            out.write(low or 0x80)
        }
        return out.toByteArray()
    }

    private fun field(num: Int, wire: Int, payload: ByteArray, lengthDelimited: Boolean): ByteArray =
        varint(((num shl 3) or wire).toLong()) + (if (lengthDelimited) varint(payload.size.toLong()) else byteArrayOf()) + payload

    /** One layer "contours" with a single line (0,0)-(1000,500) and `ele=1200`; plain protobuf (gzip it yourself). */
    fun contourTile(): ByteArray {
        val geom = byteArrayOf(0x09, 0x00, 0x00, 0x0A, 0xD0.toByte(), 0x0F, 0xE8.toByte(), 0x07)
        val feature = field(2, 2, byteArrayOf(0, 0), true) + field(3, 0, byteArrayOf(2), false) + field(4, 2, geom, true)
        val layer = field(1, 2, "contours".toByteArray(), true) +
            field(2, 2, feature, true) +
            field(3, 2, "ele".toByteArray(), true) +
            field(4, 2, field(1, 2, "1200".toByteArray(), true), true) +
            field(5, 0, varint(4096), false)
        return field(3, 2, layer, true)
    }

    class MbtilesSpec(
        val metadata: List<Pair<String, String>>? = listOf("name" to "Test Map", "format" to "png", "minzoom" to "0", "maxzoom" to "14"),
        /** (zoom, column, XYZ row) -> payload. Rows are stored flipped to TMS here, from the spec's formula. */
        val tiles: List<Pair<Triple<Int, Int, Int>, ByteArray>> = emptyList(),
        val tilesTable: Boolean = true,
    )

    fun mbtiles(file: File, spec: MbtilesSpec = MbtilesSpec()): File {
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            spec.metadata?.let { md ->
                db.execSQL("CREATE TABLE metadata (name text, value text);")
                md.forEach { (k, v) -> db.execSQL("INSERT INTO metadata VALUES (?, ?);", arrayOf(k, v)) }
            }
            if (spec.tilesTable) {
                db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);")
                for ((zxy, payload) in spec.tiles) {
                    val tmsRow = (1 shl zxy.first) - 1 - zxy.third
                    val st = db.compileStatement("INSERT INTO tiles VALUES (?, ?, ?, ?);")
                    st.bindLong(1, zxy.first.toLong())
                    st.bindLong(2, zxy.second.toLong())
                    st.bindLong(3, tmsRow.toLong())
                    st.bindBlob(4, payload)
                    st.executeInsert()
                }
            }
        } finally {
            db.close()
        }
        return file
    }

    // ---- PMTiles v3 ----

    private fun u64(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }
    private fun i32(v: Int) = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

    /** Hilbert tile ids for the handful of low-zoom tiles the tests use, from the spec's table. */
    fun tileId(z: Int, x: Int, y: Int): Long = when (Triple(z, x, y)) {
        Triple(0, 0, 0) -> 0L
        Triple(1, 0, 0) -> 1L
        Triple(1, 0, 1) -> 2L
        Triple(1, 1, 1) -> 3L
        Triple(1, 1, 0) -> 4L
        else -> error("fixture only knows z0-z1 tile ids")
    }

    private fun directory(entries: List<LongArray>): ByteArray {
        // entries: [tileId, offset, length, runLength]
        val out = ByteArrayOutputStream()
        out.write(varint(entries.size.toLong()))
        var last = 0L
        entries.forEach { out.write(varint(it[0] - last)); last = it[0] }
        entries.forEach { out.write(varint(it[3])) }
        entries.forEach { out.write(varint(it[2])) }
        var prevEnd = -1L
        entries.forEachIndexed { i, e ->
            out.write(varint(if (i > 0 && e[1] == prevEnd) 0L else e[1] + 1))
            prevEnd = e[1] + e[2]
        }
        return gzip(out.toByteArray())
    }

    /**
     * A PMTiles v3 archive holding the given z0/z1 tiles. With [viaLeaf] the root only points at a leaf directory.
     * [tileType] 1 = MVT, 2 = PNG.
     */
    fun pmtiles(
        tiles: List<Pair<Triple<Int, Int, Int>, ByteArray>>,
        tileType: Int = 1,
        tileCompression: Int = 2,
        viaLeaf: Boolean = false,
        minZoom: Int = 0,
        maxZoom: Int = 1,
    ): ByteArray {
        val data = ByteArrayOutputStream()
        val entries = ArrayList<LongArray>()
        for ((zxy, payload) in tiles.sortedBy { tileId(it.first.first, it.first.second, it.first.third) }) {
            val bytes = if (tileCompression == 2) gzip(payload) else payload
            entries.add(longArrayOf(tileId(zxy.first, zxy.second, zxy.third), data.size().toLong(), bytes.size.toLong(), 1))
            data.write(bytes)
        }
        val leaf = if (viaLeaf) directory(entries) else ByteArray(0)
        val root = if (viaLeaf) directory(listOf(longArrayOf(entries.first()[0], 0, leaf.size.toLong(), 0))) else directory(entries)
        val header = ByteArray(127)
        "PMTiles".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[7] = 3
        fun put(off: Int, b: ByteArray) = b.copyInto(header, off)
        put(8, u64(127)); put(16, u64(root.size.toLong()))
        put(40, u64(127L + root.size)); put(48, u64(leaf.size.toLong()))
        put(56, u64(127L + root.size + leaf.size)); put(64, u64(data.size().toLong()))
        header[96] = 1; header[97] = 2; header[98] = tileCompression.toByte(); header[99] = tileType.toByte()
        header[100] = minZoom.toByte(); header[101] = maxZoom.toByte()
        put(102, i32(-1_225_000_000)); put(106, i32(370_000_000)); put(110, i32(-1_215_000_000)); put(114, i32(380_000_000))
        header[118] = 1
        put(119, i32(-1_220_000_000)); put(123, i32(375_000_000))
        return header + root + leaf + data.toByteArray()
    }
}
