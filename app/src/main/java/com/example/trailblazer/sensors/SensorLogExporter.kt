package com.example.trailblazer.sensors

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

sealed interface ExportOutcome {
    data class Saved(val bytes: Long) : ExportOutcome

    /** [message] is fit to show the user; [noSpace] is true when the destination ran out of room. */
    data class Failed(val message: String, val noSpace: Boolean = false) : ExportOutcome
}

/**
 * Copies a finished recording to a document the user chose with the system file picker (`ACTION_CREATE_DOCUMENT`).
 * The copy is bounded by the file's size at the start and by [maxBytes], runs on [io], checks for cancellation between
 * 64 KiB chunks, and surfaces a write or close error (a destination that is full fails on write or on close) instead
 * of reporting a short file as saved. On any failure the half-written destination is removed with [discardPartial],
 * and the source file is never touched here: it is deleted only by the caller, after a [ExportOutcome.Saved].
 */
class SensorLogExporter(
    private val openOutput: (Uri) -> OutputStream?,
    private val discardPartial: (Uri) -> Unit,
    private val maxBytes: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun copy(source: File, destination: Uri): ExportOutcome = withContext(io) {
        val expected = source.length()
        if (expected > maxBytes) return@withContext ExportOutcome.Failed("The recording is larger than the $maxBytes byte limit and was not copied.")
        val output = try {
            openOutput(destination)
        } catch (e: IOException) {
            return@withContext fail(e)
        } catch (e: SecurityException) {
            return@withContext ExportOutcome.Failed("The chosen location is not writable (${e.message ?: "permission denied"}).")
        } catch (e: RuntimeException) {
            // A document provider may throw anything from open (UnsupportedOperationException for a mode it does not know).
            return@withContext fail(e)
        } ?: return@withContext ExportOutcome.Failed("The chosen location could not be opened for writing.")
        var copied = 0L
        try {
            source.inputStream().use { input ->
                output.use { out ->
                    val buffer = ByteArray(CHUNK)
                    while (copied < expected) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                    }
                    // A save cancelled during the last chunk must not look finished: the destination is removed below.
                    currentCoroutineContext().ensureActive()
                    out.flush()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            discardPartial(destination)
            throw e
        } catch (e: Exception) {
            // Providers also throw unchecked exceptions from write and close; none may escape into the app scope.
            discardPartial(destination)
            return@withContext fail(e)
        }
        if (copied != expected) {
            discardPartial(destination)
            return@withContext ExportOutcome.Failed("The recording changed while it was being copied ($copied of $expected bytes); nothing was saved.")
        }
        ExportOutcome.Saved(copied)
    }

    private fun fail(e: Throwable): ExportOutcome.Failed {
        val noSpace = e.isNoSpace()
        return ExportOutcome.Failed(
            if (noSpace) "There is not enough space at the chosen location. Free some space or pick another location."
            else "Could not write to the chosen location: ${e.message ?: e::class.java.simpleName}.",
            noSpace,
        )
    }

    private fun Throwable.isNoSpace(): Boolean = generateSequence(this) { it.cause }.take(8).any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) ||
            it.message?.let { m -> m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) } == true
    }

    companion object {
        private const val CHUNK = 64 * 1024

        /** The exporter for the real file picker: output through the content resolver, a failed copy deleted afterwards. */
        fun forResolver(resolver: ContentResolver, maxBytes: Long): SensorLogExporter = SensorLogExporter(
            openOutput = { uri ->
                try {
                    resolver.openOutputStream(uri, "wt")
                } catch (_: IllegalArgumentException) {
                    resolver.openOutputStream(uri, "w") // a provider that does not know the truncating mode
                }
            },
            discardPartial = { uri ->
                try {
                    DocumentsContract.deleteDocument(resolver, uri)
                } catch (_: Exception) {
                    // Best effort: the user can delete a leftover file; the failure itself is already reported.
                }
            },
            maxBytes = maxBytes,
        )
    }
}
