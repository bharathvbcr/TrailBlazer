package com.trailblazer.core.geo

/**
 * A synthetic `.hgt` file that is never materialised: [readAt] writes the bytes for exactly the posts asked for, so a
 * 3,601 x 3,601 tile costs nothing. [post] gives the elevation at (row, col), row 0 being the north edge. The posts
 * are encoded big-endian signed 16-bit, as in a real file.
 */
internal class FakeHgt(val resolution: DemResolution, private val post: (row: Int, col: Int) -> Int) {
    val side: Int get() = resolution.side
    val reads = ArrayList<Pair<Long, Int>>()
    var closed = false

    fun readAt(offset: Long, length: Int): ByteArray {
        reads += offset to length
        require(offset >= 0 && offset + length <= resolution.fileBytes) { "read outside the file: $offset+$length" }
        val out = ByteArray(length)
        for (i in 0 until length) {
            val byteIndex = offset + i
            val postIndex = byteIndex / 2
            val v = post((postIndex / side).toInt(), (postIndex % side).toInt())
            out[i] = if (byteIndex % 2 == 0L) (v shr 8).toByte() else v.toByte()
        }
        return out
    }

    fun tile(id: DemTileId): DemTile =
        DemTile.open(id.fileName, resolution.fileBytes, ::readAt, onClose = { closed = true })!!
}

/** A terrain that gives every post a different, unremarkable height, so a mis-indexed read cannot match by luck. */
internal fun scrambled(row: Int, col: Int): Int = 800 + (row * 31 + col * 17) % 1500

/**
 * Terrain with a summit: the four posts at [row], [col] (and the ones south and east of them) are [summitM], and the
 * ground falls away at [slopeMPerPost] metres per post from there.
 */
internal fun summit(row: Int, col: Int, summitM: Int, slopeMPerPost: Double = 8.0): (Int, Int) -> Int = { r, c ->
    val inPlateau = r in row..row + 1 && c in col..col + 1
    if (inPlateau) {
        summitM
    } else {
        val dr = r - (row + 0.5)
        val dc = c - (col + 0.5)
        val d = kotlin.math.sqrt(dr * dr + dc * dc)
        maxOf(1200.0, summitM - slopeMPerPost * d).toInt()
    }
}
