package com.example.trailblazer.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.sensors.log.ClockAnchor
import com.trailblazer.core.sensors.log.LogSensor
import com.trailblazer.core.sensors.log.SensorCsv
import com.trailblazer.core.sensors.log.SensorEventRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** A recording the process died in is a `.part` file; the next start finds it, repairs it and offers it. */
@RunWith(AndroidJUnit4::class)
class SensorLogOrphanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtures = ArrayList<LoggerFixture>()

    @After
    fun stopEverything() {
        fixtures.forEach { it.job.cancel() }
    }

    private val anchor = ClockAnchor(1_700_000_000_123L, 5_000_000_000L)

    private fun row(i: Int) = SensorCsv.rowLine(SensorEventRow(5_000_000_000L + i, LogSensor.Accelerometer, floatArrayOf(1f, 2f, 3f), 3), anchor)

    private fun orphan(dir: File, name: String = "trailblazer-sensors-20231114T221320Z.csv.part", tail: String = ""): File =
        File(dir, name).apply { writeText(SensorCsv.headerLine() + row(1) + row(2) + row(3) + tail) }

    private fun fixture(dir: File) = LoggerFixture(dir).also { fixtures += it }

    @Test
    fun anOrphanWithACutOffLastRowIsRepairedAndOffered() {
        val dir = tmp.newFolder()
        val cut = row(4).substring(0, 40) // the process died in the middle of this row
        orphan(dir, tail = cut)
        val f = fixture(dir)

        waitFor("the orphan to be offered") { f.logger.state.value is LoggerState.Finished }
        val offered = f.logger.state.value as LoggerState.Finished
        assertEquals(StopReason.Kind.Interrupted, offered.reason.kind)
        assertTrue(offered.reason.message.contains("closed or stopped"))
        assertNull(offered.stats)
        assertTrue(offered.log.file.name.endsWith(".csv"))
        assertFalse(File(dir, "trailblazer-sensors-20231114T221320Z.csv.part").exists())

        val rows = readCsv(offered.log.file) // every record whole
        assertEquals(listOf("ACCELEROMETER", "ACCELEROMETER", "ACCELEROMETER", "SESSION_END"), rows.drop(1).map { it[TYPE_COL] })
        val end = notes(rows.last()[NOTE_COL])
        assertEquals("interrupted", end["stop_reason"])
        assertEquals("true", end["recovered"])
        assertEquals(cut.length.toString(), end["truncated_bytes"])
    }

    @Test
    fun anOrphanWithNoCompleteRowHoldsNothingAndIsDeleted() {
        val dir = tmp.newFolder()
        File(dir, "trailblazer-sensors-x.csv.part").writeText("utc_iso,elapsed_ns,sens")
        val f = fixture(dir)
        waitFor("the empty orphan to be removed") { dir.listFiles()!!.isEmpty() }
        assertEquals(LoggerState.Idle, f.logger.state.value)
    }

    @Test
    fun aClosedRecordingThatWasNeverSavedIsOfferedAgainAndKeptUntilTheUserDecides() {
        val dir = tmp.newFolder()
        val finished = File(dir, "trailblazer-sensors-20231114T221320Z.csv").apply { writeText(SensorCsv.headerLine() + row(1)) }
        val f = fixture(dir)
        waitFor("the earlier recording to be offered") { f.logger.state.value is LoggerState.Finished }
        val offered = f.logger.state.value as LoggerState.Finished
        assertEquals(StopReason.Kind.Earlier, offered.reason.kind)
        assertEquals(finished, offered.log.file)
        assertTrue(finished.exists())
        assertTrue(f.logger.start()!!.contains("Save or discard"))
        f.logger.discard()
        waitFor("discard") { f.logger.state.value == LoggerState.Idle }
        assertFalse(finished.exists())
    }

    @Test
    fun aRecordingInProgressIsNeverTreatedAsAnOrphan() {
        val dir = tmp.newFolder()
        val f = fixture(dir)
        assertNull(f.logger.start())
        val active = dir.listFiles()!!.single()
        assertTrue(active.name.endsWith(".part"))
        assertTrue("the live file is skipped when it is named", f.files.pending(active).isEmpty())
        // A second logger over the same folder (a new process) would see it as an orphan; this one must not touch it.
        f.emitAccel(10)
        f.logger.stop()
        val done = f.logger.awaitFinished()
        assertEquals(10, readCsv(done.log.file).count { it[TYPE_COL] == "ACCELEROMETER" })
    }

    @Test
    fun trimKeepsEverythingUpToTheLastLineEnd() {
        val dir = tmp.newFolder()
        val files = SensorLogFiles(dir)
        val whole = File(dir, "whole.csv.part").apply { writeText(SensorCsv.headerLine() + row(1)) }
        assertEquals(0L, files.trimPartialLine(whole))
        val big = File(dir, "big.csv.part").apply {
            writeText(SensorCsv.headerLine() + (1..400).joinToString("") { row(it) } + "partial-without-newline")
        }
        assertEquals("partial-without-newline".length.toLong(), files.trimPartialLine(big))
        assertTrue(big.readText().endsWith("\r\n"))
        val noLine = File(dir, "none.csv.part").apply { writeText("abc") }
        assertEquals(3L, files.trimPartialLine(noLine))
        assertEquals(0L, noLine.length())
    }
}
