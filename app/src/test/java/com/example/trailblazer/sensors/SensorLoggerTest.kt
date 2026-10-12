package com.example.trailblazer.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.sensors.log.GnssMeasurementRow
import com.trailblazer.core.sensors.log.SensorCsv
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SensorLoggerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtures = ArrayList<LoggerFixture>()

    private fun fixture(
        present: Set<Int> = setOf(ACCEL, GYRO, BARO),
        limits: LogLimits = LogLimits(statsIntervalMs = 20, flushIntervalMs = 10),
        io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        freeBytes: () -> Long = { Long.MAX_VALUE },
        gnss: FakeGnss = FakeGnss(),
        exporter: SensorLogExporter = SensorLogExporter({ null }, {}, Long.MAX_VALUE),
    ) = LoggerFixture(tmp.newFolder(), present, limits, io, freeBytes, gnss, exporter).also { fixtures += it }

    @After
    fun stopEverything() {
        finishFixtures(fixtures)
    }

    private fun endNote(file: java.io.File): Map<String, String> {
        val last = readCsv(file).last()
        assertEquals("SESSION_END", last[TYPE_COL])
        return notes(last[NOTE_COL])
    }

    @Test
    fun recordsEverySensorToAValidCsvWithUtcAndRawNanos() {
        val f = fixture()
        assertNull(f.logger.start())
        f.sensors.emit(ACCEL, 0.1f, 9.8f, -0.2f, accuracy = 3, timestampNs = 5_001_500_000L)
        f.sensors.emit(GYRO, 0.01f, -0.02f, 0.03f, accuracy = 2, timestampNs = 5_002_000_000L)
        f.sensors.emit(BARO, 1013.25f, accuracy = 1, timestampNs = 5_003_000_000L)
        f.logger.stop()
        val done = f.logger.awaitFinished()

        assertTrue(done.log.file.name.endsWith(".csv"))
        assertEquals(StopReason.Kind.UserStopped, done.reason.kind)
        val rows = readCsv(done.log.file)
        assertEquals(SensorCsv.COLUMNS, rows[0])
        assertTrue(rows.all { it.size == SensorCsv.COLUMNS.size })
        assertEquals(listOf("SESSION_START", "ACCELEROMETER", "GYROSCOPE", "PRESSURE", "SESSION_END"), rows.drop(1).map { it[TYPE_COL] })
        val accel = rows[2]
        assertEquals("2023-11-14T22:13:20.124500Z", accel[0])
        assertEquals("5001500000", accel[1])
        assertEquals(listOf("0.1", "9.8", "-0.2", "3"), accel.subList(3, 7))
        assertEquals(listOf("1", "1013.25"), listOf(rows[4][6], rows[4][3]))
        assertEquals("2", rows[3][6])
        val start = notes(rows[1][NOTE_COL])
        assertEquals("1700000000123", start["anchor_wall_ms"])
        assertEquals("5000000000", start["anchor_elapsed_ns"])
        assertEquals("drop_newest", start["overflow"])
        val end = notes(rows.last()[NOTE_COL])
        assertEquals("user", end["stop_reason"])
        assertEquals("3", end["written"])
        assertEquals("0", end["dropped"])
    }

    @Test
    fun requestedRatesNeverExceedTheLimitThatNeedsNoPermission() {
        val f = fixture()
        assertNull(f.logger.start())
        for (type in listOf(ACCEL, GYRO)) assertTrue("$type period", f.sensors.requestedPeriodUs.getValue(type) >= 5_000)
        f.logger.stop()
        f.logger.awaitFinished()
        assertEquals(200, SamplingRates.MAX_MOTION_HZ)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SamplingRates(motionHz = 201) }
    }

    @Test
    fun listenersAreHeldOnlyWhileRecordingAndAlwaysReleased() {
        val f = fixture()
        assertNull(f.logger.start())
        for (type in listOf(ACCEL, GYRO, BARO)) assertEquals(1, f.sensors.listenerCount(type))
        assertEquals(1, f.gnss.registrations.get())
        f.logger.stop()
        f.logger.awaitFinished()
        for (type in listOf(ACCEL, GYRO, BARO)) assertEquals(0, f.sensors.listenerCount(type))
        assertEquals(1, f.gnss.closed.get())
    }

    @Test
    fun aBurstOf100kEventsWithABlockedWriterNeverBlocksTheCallbackAndEveryEventIsCounted() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.submit { gate.await() } // the writer cannot run until the test opens the gate
        try {
            val f = fixture(limits = LogLimits(queueCapacity = 64, statsIntervalMs = 20, flushIntervalMs = 10), io = executor.asCoroutineDispatcher())
            assertNull(f.logger.start())
            f.emitAccel(100_000) // would hang here if the sensor callback ever waited for the writer
            gate.countDown()
            f.logger.stop()
            val done = f.logger.awaitFinished()

            val rows = readCsv(done.log.file)
            val end = notes(rows.last()[NOTE_COL])
            val written = rows.count { it[TYPE_COL] == "ACCELEROMETER" }
            assertEquals(64, written)
            assertEquals("64", end["written"])
            assertEquals("99936", end["dropped"])
            assertEquals(100_000L, end.getValue("written").toLong() + end.getValue("dropped").toLong())
            assertEquals(99_936L, done.stats!!.dropped)
            val gap = rows.single { it[TYPE_COL] == "GAP" }
            assertTrue(gap[NOTE_COL], gap[NOTE_COL].startsWith("dropped=99936"))
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aBurstThroughARealWriterLosesNothingSilently() {
        val f = fixture()
        assertNull(f.logger.start())
        f.emitAccel(100_000)
        f.logger.stop()
        val done = f.logger.awaitFinished()
        val rows = readCsv(done.log.file)
        val end = notes(rows.last()[NOTE_COL])
        val written = rows.count { it[TYPE_COL] == "ACCELEROMETER" }
        val dropped = end.getValue("dropped").toLong()
        assertEquals(100_000L, written + dropped)
        assertEquals(written.toLong(), end.getValue("written").toLong())
        assertEquals(dropped, done.stats!!.dropped)
        assertEquals(dropped > 0, rows.any { it[TYPE_COL] == "GAP" })
        // Rows that were kept are in order and unduplicated.
        val ts = rows.filter { it[TYPE_COL] == "ACCELEROMETER" }.map { it[1].toLong() }
        assertEquals(ts.sorted().distinct(), ts)
    }

    @Test
    fun theSizeCapStopsTheRecordingWithAVisibleReasonAndAWholeFile() {
        val cap = 30_000L
        val f = fixture(limits = LogLimits(maxBytes = cap, statsIntervalMs = 20, flushIntervalMs = 10))
        assertNull(f.logger.start())
        f.emitAccel(10_000)
        val done = f.logger.awaitFinished()

        assertEquals(StopReason.Kind.SizeCap, done.reason.kind)
        assertTrue(done.reason.message, done.reason.message.contains("size limit"))
        assertTrue("size ${done.log.bytes}", done.log.bytes in cap..(cap + 1_500))
        val rows = readCsv(done.log.file)
        val end = endNote(done.log.file)
        assertEquals("size_cap", end["stop_reason"])
        assertEquals(end.getValue("accepted").toLong(), end.getValue("written").toLong() + end.getValue("dropped_at_stop").toLong())
        assertTrue(rows.dropLast(1).all { it[TYPE_COL] != "SESSION_END" })
        assertEquals(0, f.sensors.listenerCount(ACCEL))
    }

    @Test
    fun theDurationCapStopsTheRecording() {
        val f = fixture(limits = LogLimits(maxDurationMs = 1_000, statsIntervalMs = 20, flushIntervalMs = 10))
        assertNull(f.logger.start())
        f.clocks.elapsed.addAndGet(2_000_000_000L)
        val done = f.logger.awaitFinished()
        assertEquals(StopReason.Kind.DurationCap, done.reason.kind)
        assertEquals("duration_cap", endNote(done.log.file)["stop_reason"])
    }

    @Test
    fun lowDiskStopsARunningRecording() {
        val calls = AtomicInteger()
        val f = fixture(freeBytes = { if (calls.incrementAndGet() <= 1) Long.MAX_VALUE else 1_000L })
        assertNull(f.logger.start()) // the start check is the first call and passes
        // The free-space check runs at each flush (every 10 ms here), so keep the samples coming until it trips.
        waitFor("the low-disk stop") { f.emitAccel(5); f.logger.state.value is LoggerState.Finished }
        val done = f.logger.awaitFinished()
        assertEquals(StopReason.Kind.DiskLow, done.reason.kind)
        assertTrue(done.reason.message.contains("storage"))
        assertEquals("disk_low", endNote(done.log.file)["stop_reason"])
    }

    @Test
    fun lowDiskRefusesToStartAndLeavesNoFileOrListener() {
        val f = fixture(freeBytes = { 1_000L })
        val message = f.logger.start()
        assertNotNull(message)
        assertTrue(message!!, message.contains("storage"))
        assertEquals(message, f.logger.notice.value)
        assertEquals(LoggerState.Idle, f.logger.state.value)
        assertEquals(0, f.dir.listFiles()!!.size)
        assertEquals(0, f.sensors.listenerCount(ACCEL))
    }

    @Test
    fun cancellingTheScopeMidWriteLeavesAValidPartialFileAndReleasesEverything() {
        val f = fixture()
        assertNull(f.logger.start())
        f.emitAccel(5_000)
        f.job.cancel()
        waitFor("the cancelled recording to be closed") { f.logger.state.value is LoggerState.Finished }
        val done = f.logger.state.value as LoggerState.Finished
        assertEquals(StopReason.Kind.Cancelled, done.reason.kind)
        assertTrue(done.log.file.name.endsWith(".csv"))
        val rows = readCsv(done.log.file) // throws if any record is cut off
        assertEquals(SensorCsv.COLUMNS, rows[0])
        assertTrue(rows.all { it.size == SensorCsv.COLUMNS.size })
        val end = notes(rows.last()[NOTE_COL])
        assertEquals("cancelled", end["stop_reason"])
        assertEquals(rows.count { it[TYPE_COL] == "ACCELEROMETER" }.toString(), end["written"])
        for (type in listOf(ACCEL, GYRO, BARO)) assertEquals(0, f.sensors.listenerCount(type))
        assertEquals(1, f.gnss.closed.get())
    }

    @Test
    fun missingSensorsAreSkippedAndSaidSo() {
        val f = fixture(present = setOf(BARO))
        assertNull(f.logger.start())
        val recording = f.logger.state.value as LoggerState.Recording
        assertEquals(listOf("Barometer", "GNSS raw measurements"), recording.sources)
        assertTrue(recording.skipped.any { it.startsWith("Accelerometer") })
        f.logger.stop()
        val rows = readCsv(f.logger.awaitFinished().log.file)
        val start = notes(rows[1][NOTE_COL])
        assertEquals("absent", start["accelerometer"])
        assertEquals("absent", start["gyroscope"])
    }

    @Test
    fun nothingToRecordIsRefusedWithAReason() {
        val f = fixture(present = emptySet(), gnss = FakeGnss(available = "no GNSS receiver"))
        val message = f.logger.start()
        assertNotNull(message)
        assertTrue(message!!, message.contains("Nothing to record"))
        assertEquals(LoggerState.Idle, f.logger.state.value)
        assertEquals(0, f.dir.listFiles()!!.size)
    }

    @Test
    fun gnssRawRowsAreWrittenAndAnUnavailableReceiverIsRecordedInTheStartRow() {
        val withGnss = fixture()
        assertNull(withGnss.logger.start())
        withGnss.gnss.emit(listOf(row(17), row(18)))
        withGnss.gnss.status("raw measurements not supported by this device")
        withGnss.logger.stop()
        val rows = readCsv(withGnss.logger.awaitFinished().log.file)
        assertEquals(2, rows.count { it[TYPE_COL] == "GNSS_MEASUREMENT" })
        assertEquals(listOf("17", "18"), rows.filter { it[TYPE_COL] == "GNSS_MEASUREMENT" }.map { it[7] })
        assertEquals(1, rows.count { it[TYPE_COL] == "GNSS_RAW_STATUS" })

        val without = fixture(gnss = FakeGnss(available = "precise location permission not granted"))
        assertNull(without.logger.start())
        assertTrue((without.logger.state.value as LoggerState.Recording).skipped.any { it.contains("permission") })
        without.logger.stop()
        val start = readCsv(without.logger.awaitFinished().log.file)[1]
        assertTrue(start[NOTE_COL], start[NOTE_COL].contains("gnss_raw=off(precise location permission not granted)"))
    }

    @Test
    fun leavingAndReturningToTheForegroundIsMarkedInTheFile() {
        val f = fixture()
        assertNull(f.logger.start())
        f.foreground.value = false
        waitFor("the background marker to be written") { ((f.logger.state.value as LoggerState.Recording).stats.written) >= 1 }
        f.foreground.value = true
        waitFor("the foreground marker to be written") { ((f.logger.state.value as LoggerState.Recording).stats.written) >= 2 }
        f.logger.stop()
        val types = readCsv(f.logger.awaitFinished().log.file).map { it[TYPE_COL] }
        assertEquals(listOf("APP_BACKGROUND", "APP_FOREGROUND"), types.filter { it.startsWith("APP_") })
    }

    @Test
    fun aSecondStartWhileRecordingOrWhileAFileIsWaitingIsRefused() {
        val f = fixture()
        assertNull(f.logger.start())
        assertNotNull(f.logger.start())
        assertEquals(1, f.sensors.listenerCount(ACCEL))
        f.logger.stop()
        f.logger.stop() // idempotent
        f.logger.awaitFinished()
        val message = f.logger.start()
        assertTrue(message!!, message.contains("Save or discard"))
    }

    @Test
    fun discardDeletesTheFileOnlyWhenAsked() {
        val f = fixture()
        assertNull(f.logger.start())
        f.emitAccel(10)
        f.logger.stop()
        val done = f.logger.awaitFinished()
        assertTrue(done.log.file.exists())
        f.logger.discard()
        waitFor("discard") { f.logger.state.value == LoggerState.Idle }
        assertFalse(done.log.file.exists())
        assertEquals(0, f.dir.listFiles()!!.size)
    }

    // --- where the loss is marked ---

    private val firstNs = 5_001_000_000L
    private val stepNs = 5_000_000L

    @Test
    fun theGapRowSitsAtTheLossAndItsTimesBracketTheDroppedEvents() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.submit { gate.await() }
        try {
            val f = fixture(limits = LogLimits(queueCapacity = 64, statsIntervalMs = 20, flushIntervalMs = 10), io = executor.asCoroutineDispatcher())
            assertNull(f.logger.start())
            f.emitAccel(100, firstNs = firstNs, stepNs = stepNs) // the first 64 fit; events 64..99 are lost
            gate.countDown()
            f.logger.stop()
            val rows = readCsv(f.logger.awaitFinished().log.file)

            assertEquals(
                "the 64 queued events, then the gap, then the close: the gap is not placed before them",
                List(64) { "ACCELEROMETER" } + listOf("GAP", "SESSION_END"),
                rows.drop(2).map { it[TYPE_COL] },
            )
            val gap = rows.single { it[TYPE_COL] == "GAP" }
            val n = notes(gap[NOTE_COL])
            assertEquals("36", n["dropped"])
            assertEquals((firstNs + 64 * stepNs).toString(), n["first_dropped_ns"])
            assertEquals((firstNs + 99 * stepNs).toString(), n["last_dropped_ns"])
            assertEquals("the row is stamped with the first lost time", n["first_dropped_ns"], gap[1])
            assertEquals("36", n["total_dropped"])
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aLossBetweenTwoBurstsIsMarkedBetweenThem() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.submit { gate.await() }
        try {
            val f = fixture(limits = LogLimits(queueCapacity = 64, statsIntervalMs = 20, flushIntervalMs = 10), io = executor.asCoroutineDispatcher())
            assertNull(f.logger.start())
            f.emitAccel(100, firstNs = firstNs, stepNs = stepNs)
            gate.countDown()
            waitFor("the first burst to be written") { ((f.logger.state.value as LoggerState.Recording).stats.written) >= 64 }
            f.emitAccel(5, firstNs = firstNs + 1_000 * stepNs, stepNs = stepNs)
            f.logger.stop()
            val rows = readCsv(f.logger.awaitFinished().log.file)

            assertEquals(
                List(64) { "ACCELEROMETER" } + listOf("GAP") + List(5) { "ACCELEROMETER" } + listOf("SESSION_END"),
                rows.drop(2).map { it[TYPE_COL] },
            )
            val n = notes(rows.single { it[TYPE_COL] == "GAP" }[NOTE_COL])
            assertEquals("36", n["dropped"])
            assertEquals((firstNs + 64 * stepNs).toString(), n["first_dropped_ns"])
            assertEquals((firstNs + 99 * stepNs).toString(), n["last_dropped_ns"])
            assertFalse("the loss was reported once, not again at the end", n.containsKey("at"))
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    // --- durability while the sensors are quiet ---

    @Test
    fun rowsReachTheDiskWhenTheEventsStop() {
        val f = fixture(limits = LogLimits(statsIntervalMs = 20, flushIntervalMs = 50))
        assertNull(f.logger.start())
        f.emitAccel(10) // ten rows in an instant, then silence
        val live = f.dir.listFiles()!!.single()
        waitFor("the ten rows on disk while no event arrives", timeoutMs = 5_000) {
            live.readText().split("\r\n").count { it.contains(",ACCELEROMETER,") } == 10
        }
    }

    @Test
    fun aForegroundMarkerIsOnDiskAtOnceEvenWithALongFlushInterval() {
        val f = fixture(limits = LogLimits(statsIntervalMs = 20, flushIntervalMs = 600_000))
        assertNull(f.logger.start())
        val live = f.dir.listFiles()!!.single()
        f.foreground.value = false
        waitFor("the background marker on disk", timeoutMs = 5_000) { live.readText().contains(",APP_BACKGROUND,") }
    }

    @Test
    fun quietSensorsStillTriggerTheLowStorageCheck() {
        val calls = AtomicInteger()
        val f = fixture(
            limits = LogLimits(statsIntervalMs = 20, flushIntervalMs = 30),
            freeBytes = { if (calls.incrementAndGet() <= 1) Long.MAX_VALUE else 1_000L },
        )
        assertNull(f.logger.start())
        // No events at all: the idle check alone has to notice that storage ran out.
        val done = f.logger.awaitFinished()
        assertEquals(StopReason.Kind.DiskLow, done.reason.kind)
    }

    // --- one job owns the session ---

    @Test
    fun aWriterThatEndsAtOnceLeavesNoTickerOrWatcherRunning() {
        // Unconfined: the writer runs inside start(), before the ticker is even launched (the order a fast failure has).
        val f = fixture(limits = LogLimits(maxBytes = 1, statsIntervalMs = 20, flushIntervalMs = 10), io = kotlinx.coroutines.Dispatchers.Unconfined)
        f.sensors.onRegister = { type -> if (type == ACCEL) f.sensors.emit(ACCEL, 1f, 2f, 3f, timestampNs = firstNs) }
        assertNull(f.logger.start())
        val done = f.logger.state.value as LoggerState.Finished
        assertEquals(StopReason.Kind.SizeCap, done.reason.kind)
        // A session that ended inside start() leaves no listener behind either.
        for (type in listOf(ACCEL, GYRO, BARO)) assertEquals("listener $type", 0, f.sensors.listenerCount(type))
        assertEquals(f.gnss.registrations.get(), f.gnss.closed.get())
        waitFor("no coroutine of the session left running", timeoutMs = 5_000) { f.job.children.none { it.isActive } }
    }

    // --- every delivered event is accounted for ---

    @Test
    fun everyDeliveredEventIsWrittenDroppedOrLateEvenWhenTheSizeCapEndsTheRecording() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.submit { gate.await() }
        try {
            val f = fixture(
                limits = LogLimits(queueCapacity = 1_000, maxBytes = 30_000, statsIntervalMs = 20, flushIntervalMs = 10),
                io = executor.asCoroutineDispatcher(),
            )
            assertNull(f.logger.start())
            f.emitAccel(5_000) // 1,000 fit, 4,000 overflow; the cap then stops the writer with the rest still queued
            gate.countDown()
            val done = f.logger.awaitFinished()
            assertEquals(StopReason.Kind.SizeCap, done.reason.kind)

            val end = endNote(done.log.file)
            val delivered = f.sensors.delivered.getValue(ACCEL).get()
            val written = end.getValue("written").toLong()
            val dropped = end.getValue("dropped").toLong()
            val atStop = end.getValue("dropped_at_stop").toLong()
            val late = end.getValue("late_after_stop").toLong()
            assertTrue("events were left in the queue when the cap hit", atStop > 0)
            assertEquals(5_000L, delivered)
            assertEquals("written + dropped + late is every delivered event", delivered, written + dropped + late)
            assertEquals("accepted = written + dropped_at_stop", end.getValue("accepted").toLong(), written + atStop)
            assertEquals(4_000L + atStop, dropped)
            assertEquals(dropped, done.stats!!.dropped)
            // The gap rows add up to the overflow drops (those at stop are counted separately).
            val gaps = readCsv(done.log.file).filter { it[TYPE_COL] == "GAP" }.sumOf { notes(it[NOTE_COL]).getValue("dropped").toLong() }
            assertEquals(4_000L, gaps)
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    private fun row(svid: Int) = GnssMeasurementRow(
        elapsedNs = 5_100_000_000L, constellation = 1, svid = svid, cn0DbHz = 40.0, receivedSvTimeNs = 1L, receivedSvTimeUncertaintyNs = 2L,
        state = 3, pseudorangeRateMps = 1.0, pseudorangeRateUncertaintyMps = 0.1, timeOffsetNs = 0.0, accumulatedDeltaRangeM = 0.0,
        accumulatedDeltaRangeState = 0, carrierFrequencyHz = null, clockTimeNs = 10L, fullBiasNs = null, biasNs = null,
        hardwareClockDiscontinuityCount = 0,
    )
}
