package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.log.MarkerKind
import com.trailblazer.core.sensors.log.SensorCsv
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A recording waiting to be saved or discarded. [interrupted] is true for a file that never got its closing row: the
 * process died (or the app was killed) while it was recording.
 */
data class PendingLog(val file: File, val bytes: Long, val interrupted: Boolean)

/**
 * The recorder's private files. They live in [dir] (the app's `noBackupFilesDir`, so they are never uploaded to cloud
 * backup and are not cleared by the system like the cache): a file is `*.csv.part` while it is being written and
 * `*.csv` once it was closed properly. A `.part` file that no recording is writing is an orphan.
 */
class SensorLogFiles(private val dir: File, private val freeSpace: () -> Long = { dir.apply { mkdirs() }.usableSpace }) {
    fun freeBytes(): Long = freeSpace()

    /** A new, empty `.part` file for a session that started at [stamp] (a file-name safe UTC time). */
    fun create(stamp: String): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        var n = 0
        while (true) {
            val file = File(dir, "trailblazer-sensors-$stamp${if (n == 0) "" else "-$n"}.csv$PART")
            if (file.createNewFile()) return file
            if (++n > 99) throw IOException("Cannot create a recording file in $dir")
        }
    }

    /** Orphans first (oldest first), then finished recordings. A `.part` that is [active] is being written and is skipped. */
    fun pending(active: File? = null): List<PendingLog> = dir.listFiles().orEmpty()
        .filter { it.isFile && (it.name.endsWith(".csv") || it.name.endsWith(".csv$PART")) && it != active }
        .sortedWith(compareBy({ !it.name.endsWith(PART) }, { it.lastModified() }, { it.name }))
        .map { PendingLog(it, it.length(), it.name.endsWith(PART)) }

    /** Renames a closed `.part` file to `.csv`. Returns the file to use: the renamed one, or [file] if the rename failed. */
    fun seal(file: File): File {
        if (!file.name.endsWith(PART)) return file
        val target = File(file.parentFile, file.name.removeSuffix(PART))
        return if (!target.exists() && file.renameTo(target)) target else file
    }

    /**
     * Removes anything after the last complete line (a row cut off by a crash or a full disk) and returns the number of
     * bytes removed. Rows and notes never contain a line break inside a field, so the last line end is always a record
     * end. A file with no complete line is left empty.
     */
    fun trimPartialLine(file: File): Long {
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            var end = length
            val chunk = ByteArray(CHUNK)
            var keep = 0L
            search@ while (end > 0) {
                val start = maxOf(0L, end - CHUNK)
                val size = (end - start).toInt()
                raf.seek(start)
                raf.readFully(chunk, 0, size)
                for (i in size - 1 downTo 0) {
                    if (chunk[i] == '\n'.code.toByte()) {
                        keep = start + i + 1
                        break@search
                    }
                }
                end = start
            }
            if (keep < length) raf.setLength(keep)
            return length - keep
        }
    }

    /**
     * Repairs an orphan: drops a half-written last row, appends a closing row that says it was interrupted, and renames
     * the file to `.csv`. A file with no complete row at all holds no data and is deleted (returns null).
     */
    fun recover(part: File): PendingLog? {
        val removed = trimPartialLine(part)
        if (part.length() == 0L) {
            part.delete()
            return null
        }
        val note = "stop_reason=interrupted;recovered=true;truncated_bytes=$removed;dropped=unknown"
        part.appendText(SensorCsv.undatedMarkerLine(MarkerKind.SessionEnd, note), Charsets.UTF_8)
        val sealed = seal(part)
        return PendingLog(sealed, sealed.length(), interrupted = true)
    }

    fun delete(file: File): Boolean = !file.exists() || file.delete()

    companion object {
        const val PART = ".part"
        private const val CHUNK = 8192
    }
}
