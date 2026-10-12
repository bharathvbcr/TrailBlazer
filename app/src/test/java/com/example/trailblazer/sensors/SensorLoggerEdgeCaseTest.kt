package com.example.trailblazer.sensors

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Edge cases found by the independent review of 10f6587: the idle-wait race, the wipe outcomes, saves and file names. */
@RunWith(AndroidJUnit4::class)
class SensorLoggerEdgeCaseTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val fixtures = ArrayList<LoggerFixture>()

    @After
    fun stopEverything() = finishFixtures(fixtures)

    private fun endNote(file: java.io.File): Map<String, String> {
        val last = readCsv(file).last()
        assertEquals("SESSION_END", last[TYPE_COL])
        return notes(last[NOTE_COL])
    }

    /**
     * An event handed to the writer just as its idle wait times out must still be written. A cancellable receive inside
     * `withTimeoutOrNull` drops such an element (prompt cancellation); the writer's thread is kept busy at that moment so
     * the race window is wide.
     */
    @Test
    fun anEventArrivingAsTheIdleWaitTimesOutIsNeitherLostNorUncounted() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val f = LoggerFixture(
                tmp.newFolder(),
                limits = LogLimits(statsIntervalMs = 20, flushIntervalMs = 20),
                io = executor.asCoroutineDispatcher(),
            ).also { fixtures += it }
            assertTrue(f.logger.start() == null)
            repeat(300) { i ->
                executor.submit { Thread.sleep(30) }
                f.emitAccel(1, firstNs = 5_001_000_000L + i * 5_000_000L)
                Thread.sleep(15)
            }
            f.logger.stop()
            val done = f.logger.awaitFinished()
            val end = endNote(done.log.file)
            val delivered = f.sensors.delivered.getValue(ACCEL).get()
            val written = end.getValue("written").toLong()
            val dropped = end.getValue("dropped").toLong()
            val late = end.getValue("late_after_stop").toLong()
            val accepted = end.getValue("accepted").toLong()
            val atStop = end.getValue("dropped_at_stop").toLong()
            val rows = readCsv(done.log.file).count { it[TYPE_COL] == "ACCELEROMETER" }.toLong()
            assertEquals("rows in the file = written", written, rows)
            assertEquals("accepted = written + dropped_at_stop (end note: $end)", accepted, written + atStop)
            assertEquals("delivered = written + dropped + late (end note: $end)", delivered, written + dropped + late)
        } finally {
            executor.shutdownNow()
        }
    }

    /** The wipe's timeout path: a writer that cannot drain makes stopAndAwait false, so deleteAllData deletes nothing. */
    @Test
    fun stopAndAwaitIsFalseWhenTheWriterCannotFinish() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.submit { gate.await() }
        try {
            val f = LoggerFixture(tmp.newFolder(), io = executor.asCoroutineDispatcher()).also { fixtures += it }
            assertTrue(f.logger.start() == null)
            assertFalse(runBlocking { f.logger.stopAndAwait(timeoutMs = 200) })
            assertTrue(f.logger.state.value is LoggerState.Recording)
            gate.countDown()
            f.logger.awaitFinished()
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    /** SensorLogsRemain: a file that cannot be deleted makes deleteAllFiles false and keeps the waiting recording. */
    @Test
    fun deleteAllFilesIsFalseAndKeepsTheStateWhenAFileCannotBeDeleted() {
        val dir = tmp.newFolder()
        val f = LoggerFixture(dir).also { fixtures += it }
        assertTrue(f.logger.start() == null)
        f.logger.stop()
        val done = f.logger.awaitFinished()
        assertTrue(dir.setWritable(false))
        try {
            assertFalse(f.logger.deleteAllFiles())
            assertTrue(done.log.file.exists())
            assertTrue(f.logger.state.value is LoggerState.Finished)
        } finally {
            dir.setWritable(true)
        }
    }

    /** A save in progress keeps its source: the wipe refuses rather than delete the file under the copy. */
    @Test
    fun deleteAllFilesRefusesWhileASaveIsInProgress() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exporter = SensorLogExporter({ opened.countDown(); release.await(10, TimeUnit.SECONDS); ByteArrayOutputStream() }, {}, Long.MAX_VALUE)
        val f = LoggerFixture(tmp.newFolder(), exporter = exporter).also { fixtures += it }
        assertTrue(f.logger.start() == null)
        f.emitAccel(10)
        f.logger.stop()
        f.logger.awaitFinished()
        f.logger.export(Uri.parse("content://test.documents/document/log.csv"))
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        try {
            assertTrue((f.logger.state.value as LoggerState.Finished).exporting)
            assertFalse("a save is in progress: the wipe must not delete the file under it", f.logger.deleteAllFiles())
        } finally {
            release.countDown()
        }
    }

    /** A leftover `.csv` with the same second's stamp must not leave the next recording un-sealable as `.part`. */
    @Test
    fun aSecondRecordingInTheSameSecondStillSealsToCsv() {
        val files = SensorLogFiles(tmp.newFolder())
        val first = files.seal(files.create("20261012T120000Z"))
        assertTrue(first.name.endsWith(".csv"))
        val second = files.seal(files.create("20261012T120000Z"))
        assertTrue("sealed as ${second.name}", second.name.endsWith(".csv"))
    }

}
