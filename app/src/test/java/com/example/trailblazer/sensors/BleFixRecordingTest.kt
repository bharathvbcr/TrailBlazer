package com.example.trailblazer.sensors

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.location.Fix
import com.example.trailblazer.tracking.TrackRecorder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BLE fixes against the unmodified [TrackRecorder.offer]: a recording from an external receiver must keep its points.
 * TrackRecorder drops fixes without a reported accuracy, so the BLE side has to supply one.
 */
@RunWith(AndroidJUnit4::class)
class BleFixRecordingTest {
    private lateinit var db: TrailDb
    private var now = epochMs(2026, 10, 12, 9, 0, 0)
    private val clock = Clock { now }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrailDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun rmcAt(second: Int, latMinutes: String) =
        nmea("GPRMC,09%02d%02d,A,4807.$latMinutes,N,01131.000,E,003.0,084.4,121026,003.1,W".format(0, second))

    private fun rmcFixes(vararg latMinutes: String): List<Fix> {
        val pipeline = BleLocationPipeline(clock)
        val fixes = ArrayList<Fix>()
        latMinutes.forEachIndexed { i, m ->
            fixes += pipeline.onNmeaChunk(rmcAt(i, m) + "\r\n")
            pipeline.flushNmea()?.let { fixes += it } // an RMC-only receiver: the owner's flush delivers each epoch
        }
        return fixes
    }

    @Test
    fun rmcFixesAreRecordedByTheRealRecorder() = runBlocking {
        val fixes = rmcFixes("038", "068", "098") // 0.03 arc-minutes (about 55 m) apart
        assertEquals(3, fixes.size)
        val recorder = TrackRecorder(db.tracks(), clock)
        recorder.openOrStart("BLE")
        fixes.forEach { assertTrue("accuracy ${it.accuracyM} must pass the recorder's gate", recorder.offer(it)) }
        assertEquals(3, recorder.pointCount)
    }

    @Test
    fun lnsFixesWithAndWithoutPositionQualityAreRecorded() = runBlocking {
        val pipeline = BleLocationPipeline(clock)
        val recorder = TrackRecorder(db.tracks(), clock)
        recorder.openOrStart("BLE")

        val noQuality = pipeline.onLocationAndSpeed(lnsLocation(46.5000, 7.8000))!!
        assertTrue(recorder.offer(noQuality))

        now += 1_000
        pipeline.onPositionQuality(positionQuality(ehpeM = 4.0))
        val withQuality = pipeline.onLocationAndSpeed(lnsLocation(46.5006, 7.8000))!!
        assertEquals(4.0, withQuality.accuracyM!!, 1e-9)
        assertTrue(recorder.offer(withQuality))
        assertEquals(2, recorder.pointCount)
    }

    @Test
    fun aReceiverWithoutPositionStatusSupportIsRecordedDespiteItsZeroStatusBits() = runBlocking {
        val pipeline = BleLocationPipeline(clock) // LN Feature never read, or read and bit 20 clear
        val recorder = TrackRecorder(db.tracks(), clock)
        recorder.openOrStart("BLE")
        assertTrue(recorder.offer(pipeline.onLocationAndSpeed(lnsLocation(46.5000, 7.8000, BlePositionStatus.NO_POSITION))!!))
        now += 1_000
        pipeline.onLnFeature(Frame().u32(0x1F).toBytes())
        assertTrue(recorder.offer(pipeline.onLocationAndSpeed(lnsLocation(46.5006, 7.8000, BlePositionStatus.NO_POSITION))!!))
        assertEquals(2, recorder.pointCount)
    }

    @Test
    fun aReceiverReportingPoorAccuracyIsStillRefusedAndAnEstimatedPositionIsNeverRecorded() = runBlocking {
        val recorder = TrackRecorder(db.tracks(), clock)
        recorder.openOrStart("BLE")
        val pipeline = BleLocationPipeline(clock)

        pipeline.onPositionQuality(positionQuality(ehpeM = 80.0))
        assertFalse("EHPE of 80 m is over the 30 m gate", recorder.offer(pipeline.onLocationAndSpeed(lnsLocation(46.5, 7.8))!!))

        now += 1_000
        // "Estimated" is only meaningful from a device that declared Position Status support in LN Feature (bit 20).
        val statusAware = BleLocationPipeline(clock).also { it.onLnFeature(Frame().u32(1L shl 20).toBytes()) }
        val estimated = statusAware.onLocationAndSpeed(lnsLocation(46.6, 7.8, BlePositionStatus.ESTIMATED))!!
        assertFalse("an estimated position is shown, not recorded", recorder.offer(estimated))
        assertEquals(0, recorder.pointCount)
    }

    @Test
    fun aGgaRmcPairRecordsOnePointNotTwo() = runBlocking {
        val pipeline = BleLocationPipeline(clock)
        val gga = nmea("GPGGA,090000,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        val rmc = nmea("GPRMC,090000,A,4807.038,N,01131.000,E,003.0,084.4,121026,003.1,W")
        val fixes = pipeline.onNmeaChunk("$gga\r\n$rmc\r\n")
        val recorder = TrackRecorder(db.tracks(), clock)
        recorder.openOrStart("BLE")
        fixes.forEach { recorder.offer(it) }
        assertEquals(1, fixes.size)
        assertEquals(1, recorder.pointCount)
        assertEquals("the recorded point carries the RMC speed", 3.0 * 0.5144444444444445, fixes.single().speedMps!!, 1e-9)
    }
}
