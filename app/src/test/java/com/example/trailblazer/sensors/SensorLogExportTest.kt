package com.example.trailblazer.sensors

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Saving through the system file picker: success, the user cancelling, and every way the destination can fail. */
@RunWith(AndroidJUnit4::class)
class SensorLogExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtures = ArrayList<LoggerFixture>()
    private val uri: Uri = Uri.parse("content://test.documents/document/log.csv")

    @After
    fun stopEverything() {
        fixtures.forEach { it.job.cancel() }
    }

    private fun source(size: Int = 200_000): File = tmp.newFile().apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    // --- the copy ---

    @Test
    fun copiesEveryByteInChunks() = runBlocking {
        val out = ByteArrayOutputStream()
        val src = source(300_000)
        val outcome = SensorLogExporter({ out }, { error("nothing to discard") }, Long.MAX_VALUE).copy(src, uri)
        assertEquals(ExportOutcome.Saved(300_000), outcome)
        assertTrue(src.readBytes().contentEquals(out.toByteArray()))
    }

    @Test
    fun aFileLargerThanTheLimitIsRefusedBeforeAnythingIsOpened() = runBlocking {
        val opened = AtomicInteger()
        val outcome = SensorLogExporter({ opened.incrementAndGet(); ByteArrayOutputStream() }, {}, maxBytes = 1_000).copy(source(5_000), uri)
        assertTrue(outcome is ExportOutcome.Failed)
        assertEquals(0, opened.get())
    }

    @Test
    fun aDestinationThatCannotBeOpenedFails() = runBlocking {
        assertTrue(SensorLogExporter({ null }, {}, Long.MAX_VALUE).copy(source(), uri) is ExportOutcome.Failed)
        val thrown = SensorLogExporter({ throw java.io.FileNotFoundException("gone") }, {}, Long.MAX_VALUE).copy(source(), uri) as ExportOutcome.Failed
        assertTrue(thrown.message, thrown.message.contains("gone"))
        val denied = SensorLogExporter({ throw SecurityException("denied") }, {}, Long.MAX_VALUE).copy(source(), uri) as ExportOutcome.Failed
        assertTrue(denied.message, denied.message.contains("not writable"))
    }

    @Test
    fun aFullDestinationIsReportedAsSuchAndTheHalfWrittenFileRemoved() = runBlocking {
        val discarded = ArrayList<Uri>()
        val full = object : OutputStream() {
            var total = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
                if (total > 100_000) throw IOException("write failed: ENOSPC (No space left on device)")
            }
        }
        val outcome = SensorLogExporter({ full }, { discarded += it }, Long.MAX_VALUE).copy(source(300_000), uri) as ExportOutcome.Failed
        assertTrue(outcome.noSpace)
        assertTrue(outcome.message, outcome.message.contains("not enough space"))
        assertEquals(listOf(uri), discarded)
    }

    @Test
    fun aFailureOnCloseIsAFailureNotASuccess() = runBlocking {
        val discarded = AtomicInteger()
        val closeFails = object : ByteArrayOutputStream() {
            override fun close() = throw IOException("flush to storage failed")
        }
        val outcome = SensorLogExporter({ closeFails }, { discarded.incrementAndGet() }, Long.MAX_VALUE).copy(source(), uri)
        assertTrue(outcome.toString(), outcome is ExportOutcome.Failed)
        assertEquals(1, discarded.get())
    }

    @Test
    fun cancellingMidCopyStopsItAndRemovesTheHalfWrittenFile() = runBlocking {
        val discarded = AtomicInteger()
        val reachedHalf = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : OutputStream() {
            var total = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
                if (total >= 128 * 1024 && !reachedHalf.isCompleted) {
                    reachedHalf.complete(Unit)
                    runBlocking { release.await() }
                }
            }
        }
        val exporter = SensorLogExporter({ slow }, { discarded.incrementAndGet() }, Long.MAX_VALUE, io = Dispatchers.IO)
        val job = launch { exporter.copy(source(1_000_000), uri) }
        withTimeout(20_000) { reachedHalf.await() }
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, discarded.get())
        assertTrue("stopped early", slow.total < 1_000_000)
    }

    // --- the logger around it ---

    private fun finishedFixture(exporter: SensorLogExporter): Pair<LoggerFixture, LoggerState.Finished> {
        val f = LoggerFixture(tmp.newFolder(), exporter = exporter).also { fixtures += it }
        assertNull(f.logger.start())
        f.emitAccel(20)
        f.logger.stop()
        return f to f.logger.awaitFinished()
    }

    @Test
    fun aSuccessfulSaveDeletesThePrivateFileAndReportsIt() {
        val out = ByteArrayOutputStream()
        val (f, done) = finishedFixture(SensorLogExporter({ out }, {}, Long.MAX_VALUE))
        val original = done.log.file.readBytes()
        runBlocking { f.logger.export(uri)!!.join() }
        assertTrue(original.contentEquals(out.toByteArray()))
        assertFalse(done.log.file.exists())
        assertEquals(LoggerState.Idle, f.logger.state.value)
        assertTrue(f.logger.notice.value!!, f.logger.notice.value!!.startsWith("Saved"))
    }

    @Test
    fun cancellingThePickerChangesNothing() {
        val (f, done) = finishedFixture(SensorLogExporter({ error("must not open") }, {}, Long.MAX_VALUE))
        assertNull(f.logger.export(null))
        assertTrue(done.log.file.exists())
        assertEquals(done, f.logger.state.value)
        assertTrue(f.logger.notice.value!!, f.logger.notice.value!!.contains("cancelled"))
    }

    @Test
    fun aFailedSaveKeepsTheRecordingAndSaysWhy() {
        val (f, done) = finishedFixture(SensorLogExporter({ throw IOException("disk on fire") }, {}, Long.MAX_VALUE))
        runBlocking { f.logger.export(uri)!!.join() }
        assertTrue("file kept", done.log.file.exists())
        val state = f.logger.state.value as LoggerState.Finished
        assertFalse(state.exporting)
        assertTrue(f.logger.notice.value!!, f.logger.notice.value!!.contains("disk on fire"))
        assertTrue(f.logger.notice.value!!.contains("still on this phone"))
    }

    @Test
    fun cancellingASaveInProgressKeepsTheRecording() {
        val started = CompletableDeferred<Unit>()
        val block = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                started.complete(Unit)
                Thread.sleep(50)
            }
        }
        val (f, done) = finishedFixture(SensorLogExporter({ block }, {}, Long.MAX_VALUE))
        val job = f.logger.export(uri)!!
        runBlocking { withTimeout(20_000) { started.await() } }
        assertTrue((f.logger.state.value as LoggerState.Finished).exporting)
        f.logger.cancelExport()
        runBlocking { job.join() }
        waitFor("exporting flag to clear") { !(f.logger.state.value as LoggerState.Finished).exporting }
        assertTrue(done.log.file.exists())
        assertTrue(f.logger.notice.value!!.contains("cancelled"))
    }
}
