package com.example.trailblazer.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Plain JVM tests: NMEA parsing, epoch merging and line reassembly have no Android dependency. */
class NmeaParserTest {
    private val now = epochMs(2023, 11, 14, 22, 13, 20)
    private val clock = Clock { now }

    private fun gga(time: String = "123519", lat: String = "4807.038", lon: String = "01131.000", quality: Int = 1, hdop: String = "0.9") =
        nmea("GPGGA,$time,$lat,N,$lon,E,$quality,08,$hdop,545.4,M,46.9,M,,")

    private fun rmc(time: String = "123519", date: String = "141123", status: String = "A", mode: String? = null) =
        nmea("GPRMC,$time,$status,4807.038,N,01131.000,E,022.4,084.4,$date,003.1,W" + (mode?.let { ",$it" } ?: ""))

    private fun gll(time: String = "123519") = nmea("GPGLL,4807.038,N,01131.000,E,$time,A,A")

    private fun sentence(s: String): NmeaSentence = NmeaParser.parseSentence(s)!!

    // ---- validation ----------------------------------------------------------------------------------------------

    @Test
    fun aSentenceWithoutAChecksumIsRejected() {
        val bare = gga().substringBefore('*')
        assertNull(NmeaParser.parse(bare, clock))
        assertNotNull(NmeaParser.parse(gga(), clock))
    }

    @Test
    fun malformedChecksumFieldsAreRejected() {
        val body = gga().substringBefore('*')
        for (bad in listOf("*", "*4", "*047", "*GG", "*-1", "* 7")) {
            assertNull("checksum field '$bad'", NmeaParser.parse(body + bad, clock))
        }
    }

    @Test
    fun minutesOfSixtyOrMoreAreRejected() {
        assertNull(NmeaParser.parse(gga(lat = "4860.000"), clock))
        assertNull(NmeaParser.parse(gga(lon = "01160.500"), clock))
        assertNotNull(NmeaParser.parse(gga(lat = "4859.999"), clock))
    }

    @Test
    fun coordinatesOutsideTheEarthAreRejected() {
        assertNull(NmeaParser.parse(gga(lat = "9100.000"), clock))
        assertNull(NmeaParser.parse(gga(lat = "9000.001"), clock))
        assertNotNull(NmeaParser.parse(gga(lat = "9000.000"), clock))
        assertNull(NmeaParser.parse(gga(lon = "18100.000"), clock))
        assertNull("not ddmm.mmmm", NmeaParser.parse(gga(lat = "48.07038"), clock))
        assertNull("exponent notation is not NMEA", NmeaParser.parse(gga(lat = "4807.0e1"), clock))
    }

    @Test
    fun impossibleUtcTimesAreRejected() {
        for (t in listOf("256000", "126100", "123560", "1235", "abcdef", "")) {
            assertNull("time '$t'", NmeaParser.parse(gga(time = t), clock))
        }
        assertNotNull(NmeaParser.parse(gga(time = "235959.99"), clock))
    }

    @Test
    fun unusableFixesAreRejected() {
        for (q in listOf(0, 6, 7, 8, 9)) assertNull("GGA quality $q", NmeaParser.parse(gga(quality = q), clock))
        for (q in listOf(1, 2, 4, 5)) assertNotNull("GGA quality $q", NmeaParser.parse(gga(quality = q), clock))
        assertNull("void RMC", NmeaParser.parse(rmc(status = "V"), clock))
        for (m in listOf("N", "E", "M", "S")) assertNull("RMC mode $m", NmeaParser.parse(rmc(mode = m), clock))
        assertNotNull("RMC mode D", NmeaParser.parse(rmc(mode = "D"), clock))
    }

    @Test
    fun noiseThatIsNotASentenceIsRejected() {
        assertNull(NmeaParser.parse("", clock))
        assertNull(NmeaParser.parse("GPGGA,1,2,3", clock))
        assertNull(NmeaParser.parse(nmea("GPXYZ,1,2,3"), clock))
        assertNull(NmeaParser.parse(nmea("G,1,2,3"), clock))
        assertNull("embedded control character, checksum correct", NmeaParser.parse(nmea("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,5\u000145.4,M,46.9,M,,"), clock))
    }

    // ---- accuracy policy (F5) ------------------------------------------------------------------------------------

    @Test
    fun everyAcceptedSentenceCarriesAnAccuracyBelowTheRecordingGate() {
        val fromGga = NmeaParser.parse(gga(), clock)!!
        assertEquals(0.9 * BleFixPolicy.HDOP_TO_METERS, fromGga.accuracyM!!, 1e-9)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, NmeaParser.parse(rmc(), clock)!!.accuracyM!!, 0.0)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, NmeaParser.parse(gll(), clock)!!.accuracyM!!, 0.0)
        assertEquals("blank HDOP", BleFixPolicy.DEFAULT_ACCURACY_M, NmeaParser.parse(gga(hdop = ""), clock)!!.accuracyM!!, 0.0)
        assertTrue("TrackRecorder.MAX_ACCURACY_M is 30", BleFixPolicy.DEFAULT_ACCURACY_M < 30.0)
        assertTrue(BleFixPolicy.ESTIMATED_ACCURACY_M > 30.0)
    }

    // ---- epoch merging (F12) -------------------------------------------------------------------------------------

    @Test
    fun ggaAndRmcOfOneSecondBecomeOneFix() {
        val merger = NmeaEpochMerger(clock)
        assertTrue(merger.accept(sentence(gga())).isEmpty())
        val fixes = merger.accept(sentence(rmc()))
        assertEquals(1, fixes.size)
        val fix = fixes[0]
        assertEquals(545.4, fix.altitudeM!!, 1e-9) // from GGA
        assertEquals(84.4, fix.bearingDeg!!, 1e-9) // from RMC
        assertEquals(22.4 * 0.5144444444444445, fix.speedMps!!, 1e-9)
        assertEquals(4.5, fix.accuracyM!!, 1e-9) // HDOP of the GGA, not the RMC's default
        assertFalse(merger.hasPending)
    }

    @Test
    fun theRmcFirstOrderMergesTheSame() {
        val merger = NmeaEpochMerger(clock)
        assertTrue(merger.accept(sentence(rmc())).isEmpty())
        val fix = merger.accept(sentence(gga())).single()
        assertEquals(545.4, fix.altitudeM!!, 1e-9)
        assertEquals(84.4, fix.bearingDeg!!, 1e-9)
    }

    @Test
    fun aLaterEpochFlushesTheIncompleteEarlierOne() {
        val merger = NmeaEpochMerger(clock)
        assertTrue(merger.accept(sentence(gll("123519"))).isEmpty())
        val out = merger.accept(sentence(gll("123520")))
        assertEquals(1, out.size)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, out[0].accuracyM!!, 0.0)
        assertTrue(merger.hasPending)
        assertNotNull(merger.flush())
        assertNull(merger.flush())
    }

    @Test
    fun aStragglerOfAnEmittedEpochNeverMakesASecondFix() {
        val merger = NmeaEpochMerger(clock)
        merger.accept(sentence(gga()))
        assertEquals(1, merger.accept(sentence(rmc())).size)
        assertTrue("GLL of the same second", merger.accept(sentence(gll())).isEmpty())
        assertNull(merger.flush())
        assertTrue("an older second", merger.accept(sentence(gga(time = "123510"))).isEmpty())
        assertFalse(merger.hasPending)
    }

    @Test
    fun aSentenceOlderThanTheOpenEpochIsDropped() {
        val merger = NmeaEpochMerger(clock)
        merger.accept(sentence(gga(time = "123520")))
        assertTrue(merger.accept(sentence(gga(time = "123519"))).isEmpty())
        val fix = merger.flush()!!
        assertEquals(epochMs(2023, 11, 14, 12, 35, 20), fix.timeMs)
    }

    @Test
    fun theFirstSentenceOfAKindWinsInAnEpoch() {
        val merger = NmeaEpochMerger(clock)
        merger.accept(sentence(gga(hdop = "0.9")))
        merger.accept(sentence(gga(hdop = "5.0"))) // second constellation's GGA
        assertEquals(4.5, merger.flush()!!.accuracyM!!, 1e-9)
    }

    @Test
    fun resetDropsTheOpenEpoch() {
        val merger = NmeaEpochMerger(clock)
        merger.accept(sentence(gga()))
        merger.reset()
        assertFalse(merger.hasPending)
        assertNull(merger.flush())
    }

    // ---- receiver UTC (F12) --------------------------------------------------------------------------------------

    @Test
    fun theReceiversUtcDateAndTimeBecomeTheFixTime() {
        val fix = NmeaParser.parse(rmc(time = "221000", date = "141123"), clock)!!
        assertEquals(epochMs(2023, 11, 14, 22, 10, 0), fix.timeMs)
        assertNotEquals(now, fix.timeMs)
    }

    @Test
    fun aBrokenReceiverDateFallsBackToTheNearestDayOfThePhoneClock() {
        // 230394 is what a receiver with a GPS week rollover problem reports.
        val fix = NmeaParser.parse(rmc(time = "123519", date = "230394"), clock)!!
        assertEquals(epochMs(2023, 11, 14, 12, 35, 19), fix.timeMs)
        assertNull("31 February", NmeaParser.parseDate("310223"))
    }

    @Test
    fun aTimeWithoutADateTakesTheDayNearestThePhoneClockAcrossMidnight() {
        val justAfterMidnight = Clock { epochMs(2023, 11, 15, 0, 0, 30) }
        val fix = NmeaParser.parse(gga(time = "235958"), justAfterMidnight)!!
        assertEquals(epochMs(2023, 11, 14, 23, 59, 58), fix.timeMs)
        val justBeforeMidnight = Clock { epochMs(2023, 11, 14, 23, 59, 50) }
        assertEquals(epochMs(2023, 11, 15, 0, 0, 2), NmeaParser.parse(gga(time = "000002"), justBeforeMidnight)!!.timeMs)
    }

    // ---- line reassembly (F4, F11) -------------------------------------------------------------------------------

    @Test
    fun aSentenceSplitIntoTwentyByteNotificationsIsReassembled() {
        val text = gga() + "\r\n" + rmc() + "\r\n"
        val pipeline = BleLocationPipeline(clock)
        val fixes = ArrayList<com.example.trailblazer.location.Fix>()
        for (chunk in text.chunked(20)) {
            assertTrue("no chunk may be dropped for lacking a '$' or a line end", chunk.length <= 20)
            fixes += pipeline.onNmeaChunk(chunk)
        }
        assertEquals(1, fixes.size)
        assertEquals(48.1173, fixes[0].position.lat, 1e-4)
        assertEquals(84.4, fixes[0].bearingDeg!!, 1e-9)
    }

    @Test
    fun forwardingEveryChunkIsWhatKeepsTheMiddleOfASentence() {
        // The retired transport forwarded only chunks containing '$', CR or LF. At the default 20-byte payload that drops the
        // middle of every sentence: replay that rule here to show what it cost, then show that forwarding everything works.
        val chunks = (gga() + "\r\n" + rmc() + "\r\n").chunked(20)
        fun fixesFrom(forwarded: List<String>): Int {
            val pipeline = BleLocationPipeline(clock)
            return forwarded.sumOf { pipeline.onNmeaChunk(it).size } + (pipeline.flushNmea()?.let { 1 } ?: 0)
        }
        val retiredRule = chunks.filter { '$' in it || '\r' in it || '\n' in it }
        assertTrue("some chunks would have been dropped", retiredRule.size < chunks.size)
        assertEquals("the old rule loses the whole epoch", 0, fixesFrom(retiredRule))
        assertEquals(1, fixesFrom(chunks))
    }

    @Test
    fun aStreamWithoutLineEndingsStaysBounded() {
        val buffer = NmeaStreamBuffer()
        repeat(500) { buffer.appendAndExtractLines("X".repeat(100)) }
        assertTrue("pending ${buffer.pendingLength()}", buffer.pendingLength() <= NmeaStreamBuffer.MAX_PENDING_CHARS)
        val lines = buffer.appendAndExtractLines(gga() + "\r\n")
        assertEquals(listOf(gga()), lines)
    }

    @Test
    fun aSentenceInProgressSurvivesAnOverflowOfJunkBeforeIt() {
        val buffer = NmeaStreamBuffer()
        val sentence = gga()
        buffer.appendAndExtractLines("J".repeat(NmeaStreamBuffer.MAX_PENDING_CHARS + 10) + sentence.take(30))
        assertEquals("junk before the '$' is dropped, the half sentence kept", 30, buffer.pendingLength())
        assertEquals(listOf(sentence), buffer.appendAndExtractLines(sentence.drop(30) + "\r\n"))
        buffer.appendAndExtractLines("J".repeat(2000))
        assertEquals("junk with no '$' at all is dropped entirely", 0, buffer.pendingLength())
    }

    @Test
    fun aLostChunkDoesNotPoisonTheNextSentence() {
        val buffer = NmeaStreamBuffer()
        // The tail of a sentence whose head was lost, then a whole sentence on the same line before the terminator.
        val lines = buffer.appendAndExtractLines("m,08,0.9,545.4,M,46.9,M,,*47" + gga() + "\r\n")
        assertEquals(listOf(gga()), lines)
    }

    @Test
    fun bareLineEndingsAndBlankLinesProduceNothing() {
        val buffer = NmeaStreamBuffer()
        assertTrue(buffer.appendAndExtractLines("\r\n\r\n\n\r").isEmpty())
        assertTrue(buffer.appendAndExtractLines("not nmea\r\n").isEmpty())
    }

    @Test
    fun concurrentBinderAndMainThreadAccessNeverCorruptsTheBuffer() {
        val buffer = NmeaStreamBuffer()
        val failure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        val text = (gga() + "\r\n").repeat(50)
        repeat(3) {
            pool.execute {
                try {
                    repeat(300) { text.chunked(7).forEach { c -> buffer.appendAndExtractLines(c) } }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        pool.execute { // the main thread resetting on disconnect
            try {
                repeat(3000) { buffer.clear() }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertNull(failure.get()?.toString(), failure.get())
        assertTrue(buffer.pendingLength() <= NmeaStreamBuffer.MAX_PENDING_CHARS)
    }
}
