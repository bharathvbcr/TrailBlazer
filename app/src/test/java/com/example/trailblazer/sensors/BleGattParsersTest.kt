package com.example.trailblazer.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Location and Speed (0x2A67), Position Quality (0x2A69) and Environmental Sensing parsing, per the Bluetooth SIG layouts. */
class BleGattParsersTest {
    private val now = epochMs(2026, 10, 12, 9, 0, 0)
    private val clock = Clock { now }

    // ---- LNS Position Status (F12) -------------------------------------------------------------------------------

    @Test
    fun aDeviceThatReportsPositionStatusHasOnlyPositionOkAndEstimatedFramesAccepted() {
        fun parse(frame: ByteArray) = BleLnsParser.parse(frame, clock, positionStatusSupported = true)
        assertNotNull(parse(lnsLocation(46.5, 7.8, BlePositionStatus.POSITION_OK)))
        assertNotNull(parse(lnsLocation(46.5, 7.8, BlePositionStatus.ESTIMATED)))
        assertNull("No Position", parse(lnsLocation(0.0, 0.0, BlePositionStatus.NO_POSITION)))
        assertNull("Last Known", parse(lnsLocation(46.5, 7.8, BlePositionStatus.LAST_KNOWN)))
    }

    @Test
    fun aDeviceWithoutPositionStatusSupportIsNeverRejectedForItsStatusBits() {
        // LN Feature bit 20 clear: bits 7-8 are 0 next to valid coordinates, and are not a verdict on the fix.
        val original = byteArrayOf( // the original sample: flags 0x1F (speed, distance, location, elevation, heading), status bits 0
            0x1F, 0x00, 0x0E, 0x06, 0x00, 0x00, 0x00,
            0xB0.toByte(), 0x37, 0xC0.toByte(), 0x1B, 0x80.toByte(), 0x8E.toByte(), 0xAB.toByte(), 0x04,
            0x82.toByte(), 0x34, 0x03, 0x69, 0x46,
        )
        val fix = BleLnsParser.parse(original, clock)!!
        assertEquals(46.5582, fix.position.lat, 1e-6)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, fix.accuracyM!!, 0.0)
        for (status in 0..3) {
            assertNotNull("status bits $status", BleLnsParser.parse(lnsLocation(46.5, 7.8, status), clock, positionStatusSupported = false))
        }
        // The same status-0 frame is a rejection only once the device has declared that it reports status.
        assertNull(BleLnsParser.parse(lnsLocation(46.5, 7.8, BlePositionStatus.NO_POSITION), clock, positionStatusSupported = true))
    }

    @Test
    fun lnFeatureBit20IsPositionStatusSupported() {
        fun feature(bits: Long) = Frame().u32(bits).toBytes()
        assertEquals(true, BleLnFeatureParser.positionStatusSupported(feature(1L shl 20)))
        assertEquals(true, BleLnFeatureParser.positionStatusSupported(feature(0xFFFF_FFFFL)))
        assertEquals(false, BleLnFeatureParser.positionStatusSupported(feature(0x1F)))
        assertEquals("bit 19 (Elevation Setting) is not it", false, BleLnFeatureParser.positionStatusSupported(feature(1L shl 19)))
        assertEquals("bit 21 is reserved", false, BleLnFeatureParser.positionStatusSupported(feature(1L shl 21)))
        assertNull("a truncated feature field says nothing", BleLnFeatureParser.positionStatusSupported(byteArrayOf(0, 0, 0x10)))
        assertNull(BleLnFeatureParser.positionStatusSupported(byteArrayOf()))
    }

    @Test
    fun anEstimatedPositionIsShownButNeverUnderTheRecordingGate() {
        val fix = BleLnsParser.parse(lnsLocation(46.5, 7.8, BlePositionStatus.ESTIMATED), clock, positionStatusSupported = true)!!
        assertEquals(BleFixPolicy.ESTIMATED_ACCURACY_M, fix.accuracyM!!, 0.0)
        // Even a claimed 2 m EHPE cannot make an estimated position recordable.
        val withQuality = BleLnsParser.parse(
            lnsLocation(46.5, 7.8, BlePositionStatus.ESTIMATED), clock, BlePositionQuality(1, 2.0, null), positionStatusSupported = true,
        )!!
        assertEquals(BleFixPolicy.ESTIMATED_ACCURACY_M, withQuality.accuracyM!!, 0.0)
    }

    @Test
    fun aFrameWithoutALocationIsNotAFix() {
        val speedOnly = Frame().u16(LnsFlags.SPEED or LnsFlags.status(1)).u16(150).toBytes()
        assertNull(BleLnsParser.parse(speedOnly, clock))
    }

    @Test
    fun truncatedFramesAreRejectedAtEveryLength() {
        val full = Frame()
            .u16(LnsFlags.SPEED or LnsFlags.DISTANCE or LnsFlags.LOCATION or LnsFlags.ELEVATION or LnsFlags.HEADING or
                LnsFlags.ROLLING_TIME or LnsFlags.UTC_TIME or LnsFlags.status(1))
            .u16(1550).u24(0).s32(465_582_000).s32(78_352_000).u24(210_050).u16(18_025).u8(7)
            .u16(2026).u8(10).u8(12).u8(9).u8(0).u8(0)
            .toBytes()
        assertNotNull(BleLnsParser.parse(full, clock))
        for (n in 0 until full.size) assertNull("length $n", BleLnsParser.parse(full.copyOf(n), clock))
    }

    @Test
    fun coordinatesOutsideTheEarthAreCorruptFramesNotWrappedLongitudes() {
        assertNull(BleLnsParser.parse(lnsLocation(91.0, 0.0), clock))
        assertNull(BleLnsParser.parse(lnsLocation(0.0, 181.0), clock))
        assertNotNull(BleLnsParser.parse(lnsLocation(-90.0, -180.0), clock))
    }

    // ---- LNS time and accuracy (F5, F12) -------------------------------------------------------------------------

    @Test
    fun theReceiversUtcTimeIsUsedWhenItAgreesWithThePhone() {
        val utc = epochMs(2026, 10, 12, 8, 59, 40)
        val frame = lnsLocation(46.5, 7.8, extraFlags = LnsFlags.UTC_TIME) {
            u16(2026).u8(10).u8(12).u8(8).u8(59).u8(40)
        }
        assertEquals(utc, BleLnsParser.parse(frame, clock)!!.timeMs)
    }

    @Test
    fun anUnknownOrFarOffUtcTimeFallsBackToThePhoneClock() {
        val unknown = lnsLocation(46.5, 7.8, extraFlags = LnsFlags.UTC_TIME) { u16(0).u8(0).u8(0).u8(0).u8(0).u8(0) }
        assertEquals(now, BleLnsParser.parse(unknown, clock)!!.timeMs)
        val rolledOver = lnsLocation(46.5, 7.8, extraFlags = LnsFlags.UTC_TIME) { u16(2006).u8(5).u8(1).u8(0).u8(0).u8(0) }
        assertEquals(now, BleLnsParser.parse(rolledOver, clock)!!.timeMs)
        val noUtc = lnsLocation(46.5, 7.8)
        assertEquals(now, BleLnsParser.parse(noUtc, clock)!!.timeMs)
    }

    @Test
    fun accuracyComesFromPositionQualityElseTheConservativeDefault() {
        val frame = lnsLocation(46.5, 7.8)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, BleLnsParser.parse(frame, clock)!!.accuracyM!!, 0.0)
        assertEquals(3.2, BleLnsParser.parse(frame, clock, BlePositionQuality(1, 3.2, null))!!.accuracyM!!, 1e-9)
        assertEquals("HDOP 1.4 -> 7 m", 7.0, BleLnsParser.parse(frame, clock, BlePositionQuality(1, null, 1.4))!!.accuracyM!!, 1e-9)
        assertEquals(
            "a quality that says No Position says nothing about this fix",
            BleFixPolicy.DEFAULT_ACCURACY_M,
            BleLnsParser.parse(frame, clock, BlePositionQuality(BlePositionStatus.NO_POSITION, 1.0, null), positionStatusSupported = true)!!.accuracyM!!,
            0.0,
        )
        assertEquals(
            "a device without Position Status always says 0 there, which must not discard its EHPE",
            1.0,
            BleLnsParser.parse(frame, clock, BlePositionQuality(BlePositionStatus.NO_POSITION, 1.0, null))!!.accuracyM!!,
            1e-9,
        )
    }

    @Test
    fun anEhpeOrHdopOfZeroIsUnknownNotPerfect() {
        val frame = lnsLocation(46.5, 7.8)
        val ehpe0 = BlePositionQualityParser.parse(positionQuality(ehpeM = 0.0))!!
        assertNull("EHPE 0 is unknown", ehpe0.ehpeM)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, BleLnsParser.parse(frame, clock, ehpe0)!!.accuracyM!!, 0.0)

        val hdop0 = BlePositionQualityParser.parse(positionQuality(hdop = 0.0))!!
        assertNull("HDOP 0 is unknown", hdop0.hdop)
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, BleLnsParser.parse(frame, clock, hdop0)!!.accuracyM!!, 0.0)

        assertEquals("a zero EHPE still lets a real HDOP through", 7.0,
            BleLnsParser.parse(frame, clock, BlePositionQuality(1, 0.0, 1.4))!!.accuracyM!!, 1e-9)
        assertNull(BleFixPolicy.accuracyFromHdop(0.0))
        assertNull(BleFixPolicy.accuracyFromHdop(-1.0))
        assertNull(BleFixPolicy.accuracyFromHdop(Double.NaN))
        assertEquals(5.0, BleFixPolicy.accuracyFromHdop(1.0)!!, 0.0)
    }

    @Test
    fun theFullFrameKeepsTheDocumentedFieldValues() {
        val frame = Frame()
            .u16(LnsFlags.SPEED or LnsFlags.DISTANCE or LnsFlags.LOCATION or LnsFlags.ELEVATION or LnsFlags.HEADING or LnsFlags.status(1))
            .u16(1550).u24(0).s32(465_582_000).s32(78_352_000).u24(210_050).u16(18_025)
            .toBytes()
        val fix = BleLnsParser.parse(frame, clock)!!
        assertEquals(46.5582, fix.position.lat, 1e-9)
        assertEquals(7.8352, fix.position.lon, 1e-9)
        assertEquals(15.5, fix.speedMps!!, 1e-9)
        assertEquals(2100.5, fix.altitudeM!!, 1e-9)
        assertEquals(180.25, fix.bearingDeg!!, 1e-9)
        assertEquals("ble_lns", fix.provider)
    }

    @Test
    fun negativeElevationAndSouthWestCoordinatesKeepTheirSign() {
        val frame = Frame()
            .u16(LnsFlags.LOCATION or LnsFlags.ELEVATION or LnsFlags.status(1))
            .s32(-337_000_000).s32(-1_512_000_000).u24(0xFFFF38) // -200 = -2.00 m
            .toBytes()
        val fix = BleLnsParser.parse(frame, clock)!!
        assertEquals(-33.7, fix.position.lat, 1e-9)
        assertEquals(-151.2, fix.position.lon, 1e-9)
        assertEquals(-2.0, fix.altitudeM!!, 1e-9)
    }

    // ---- Position Quality ----------------------------------------------------------------------------------------

    @Test
    fun positionQualityReadsEhpeAndHdopInTheirDocumentedUnits() {
        val q = BlePositionQualityParser.parse(positionQuality(ehpeM = 4.25, hdop = 1.2))!!
        assertEquals(BlePositionStatus.POSITION_OK, q.positionStatus)
        assertEquals(4.25, q.ehpeM!!, 1e-9)
        assertEquals(1.2, q.hdop!!, 1e-9)
    }

    @Test
    fun positionQualitySkipsFieldsBeforeEhpeInTheSpecifiedOrder() {
        // flags: beacons in solution (u8), in view (u8), time to first fix (u16), EHPE (u32), EVPE (u32), HDOP (u8), VDOP (u8)
        val frame = Frame().u16(0x7F or (BlePositionStatus.POSITION_OK shl 7))
            .u8(9).u8(14).u16(123).u32(1_234).u32(5_678).u8(7).u8(8)
            .toBytes()
        val q = BlePositionQualityParser.parse(frame)!!
        assertEquals(12.34, q.ehpeM!!, 1e-9)
        assertEquals(1.4, q.hdop!!, 1e-9)
        for (n in 0 until frame.size) assertNull("length $n", BlePositionQualityParser.parse(frame.copyOf(n)))
    }

    @Test
    fun anAbsurdEhpeIsNotAnAccuracy() {
        val q = BlePositionQualityParser.parse(Frame().u16(0x08 or (1 shl 7)).u32(0xFFFF_FFFFL).toBytes())!!
        assertNull(q.ehpeM)
    }

    // ---- Environmental Sensing -----------------------------------------------------------------------------------

    @Test
    fun essValuesUseTheirSpecifiedUnitsAndSigns() {
        assertEquals(1038.0, BleEssParser.parsePressure(Frame().u32(1_038_000).toBytes())!!, 1e-9) // 0.1 Pa units
        assertEquals(-5.5, BleEssParser.parseTemperature(Frame().u16(0xFDDA).toBytes())!!, 1e-9) // sint16, 0.01 degC
        assertEquals(50.48, BleEssParser.parseHumidity(Frame().u16(5048).toBytes())!!, 1e-9)
        assertNull(BleEssParser.parsePressure(byteArrayOf(1, 2, 3)))
        assertNull(BleEssParser.parseTemperature(byteArrayOf(1)))
        assertNull("0x8000 means unknown", BleEssParser.parseTemperature(Frame().u16(0x8000).toBytes()))
        assertNull(BleEssParser.parseHumidity(Frame().u16(0xFFFF).toBytes()))
    }
}
