package com.example.trailblazer.sensors

import com.example.trailblazer.location.Fix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan and connection lifecycle of [BleSensorManager] against a fake transport and a virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleSensorManagerLifecycleTest {
    private val clock = Clock { epochMs(2023, 11, 14, 22, 13, 20) }
    private val transport = FakeBleTransport()

    private fun TestScope.manager() = BleSensorManager(transport, backgroundScope, clock)

    private fun TestScope.collectFixes(manager: BleSensorManager): MutableList<Fix> {
        val fixes = ArrayList<Fix>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.locationStream.collect { fixes += it } }
        return fixes
    }

    private val gga = nmea("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
    private val rmc = nmea("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,141123,003.1,W")

    // ---- F8 scan -------------------------------------------------------------------------------------------------

    @Test
    fun aScanStopsByItselfAfterTheTimeout() = runTest {
        val m = manager()
        m.startScan()
        transport.emitDevice(BleDeviceInfo("Garmin GLO 2", "00:11:22:33:44:55"))
        assertTrue(transport.scanning)

        advanceTimeBy(BleSensorManager.SCAN_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue("still scanning just before the timeout", transport.scanning)
        advanceTimeBy(2)
        runCurrent()

        assertFalse("the radio scan was stopped", transport.scanning)
        assertEquals(BleConnectionState.Disconnected, m.connectionState.value)
        assertEquals("devices found so far stay listed", 1, m.discoveredDevices.value.size)
    }

    @Test
    fun stoppingTheScanCancelsTheTimeoutAndStopsOnce() = runTest {
        val m = manager()
        m.startScan()
        m.stopScan()
        assertEquals(1, transport.scanStops)
        advanceTimeBy(BleSensorManager.SCAN_TIMEOUT_MS * 2)
        runCurrent()
        assertEquals("a cancelled timeout must not stop the radio again or flip the state", 1, transport.scanStops)
        assertEquals(BleConnectionState.Disconnected, m.connectionState.value)
    }

    @Test
    fun restartingAScanRestartsItsTimeout() = runTest {
        val m = manager()
        m.startScan()
        advanceTimeBy(BleSensorManager.SCAN_TIMEOUT_MS - 1_000)
        m.startScan()
        advanceTimeBy(BleSensorManager.SCAN_TIMEOUT_MS - 1_000)
        runCurrent()
        assertTrue("the first scan's timeout must not end the second", transport.scanning)
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(transport.scanning)
    }

    @Test
    fun aScanFailureIsReportedWithItsReason() = runTest {
        val m = manager()
        m.startScan()
        transport.emitScanFailure("Scanning too often: wait a moment and try again")
        assertEquals(BleConnectionState.Error("Scanning too often: wait a moment and try again"), m.connectionState.value)
        assertFalse("a failed scan is stopped", transport.scanning)
        advanceTimeBy(BleSensorManager.SCAN_TIMEOUT_MS * 2)
        runCurrent()
        assertTrue("the timeout does not overwrite the error", m.connectionState.value is BleConnectionState.Error)
    }

    @Test
    fun aMissingPermissionAtScanStartShowsItsOwnMessageNotAGenericOne() = runTest {
        transport.scanStartFailure = "Bluetooth permission denied: allow Nearby devices to scan for sensors"
        val m = manager()
        m.startScan()
        assertEquals(
            BleConnectionState.Error("Bluetooth permission denied: allow Nearby devices to scan for sensors"),
            m.connectionState.value,
        )
    }

    @Test
    fun aScanThatCannotStartWithoutAReasonStillErrors() = runTest {
        val failing = object : BleTransport by transport {
            override fun startScan(onDeviceFound: (BleDeviceInfo) -> Unit, onScanFailed: (String) -> Unit) = false
        }
        val m = BleSensorManager(failing, backgroundScope, clock)
        m.startScan()
        assertEquals(BleConnectionState.Error("Unable to start BLE scan"), m.connectionState.value)
    }

    @Test
    fun connectingEndsTheScanFirst() = runTest {
        val m = manager()
        m.startScan()
        m.connect("00:11:22:33:44:55", "Garmin GLO 2")
        assertFalse(transport.scanning)
        advanceTimeBy(BleSensorManager.SCAN_TIMEOUT_MS * 2)
        runCurrent()
        assertTrue("the scan timeout must not disturb the connection", m.connectionState.value is BleConnectionState.Connecting)
    }

    // ---- F9 connect failure --------------------------------------------------------------------------------------

    @Test
    fun aConnectionThatCannotBeInitiatedIsAnError() = runTest {
        transport.isEnabled = false
        val m = manager()
        m.connect("00:11:22:33:44:55", "Garmin GLO 2")
        assertTrue(m.connectionState.value is BleConnectionState.Error)
    }

    @Test
    fun anErrorFromTheTransportIsKeptAndPartialDataIsDropped() = runTest {
        val m = manager()
        val fixes = collectFixes(m)
        m.connect("00:11:22:33:44:55", "Garmin GLO 2")
        transport.emitConnected(setOf(BleServiceType.Nmea))
        transport.emitNmea(gga + "\r\n")
        transport.emitError("Failed to discover GATT services: 129")
        assertEquals(BleConnectionState.Error("Failed to discover GATT services: 129"), m.connectionState.value)
        advanceTimeBy(BleSensorManager.NMEA_FLUSH_DELAY_MS * 2)
        runCurrent()
        assertTrue("the half-received epoch of a failed connection is not emitted", fixes.isEmpty())
    }

    // ---- NMEA delivery, epoch flush (F5, F12) --------------------------------------------------------------------

    @Test
    fun aCompleteGgaRmcEpochIsDeliveredAtOnce() = runTest {
        val m = manager()
        val fixes = collectFixes(m)
        m.connect("00:11:22:33:44:55", "Garmin GLO 2")
        transport.emitConnected(setOf(BleServiceType.Nmea))
        transport.emitNmea(gga + "\r\n")
        assertTrue(fixes.isEmpty())
        transport.emitNmea(rmc + "\r\n")
        assertEquals(1, fixes.size)
        assertEquals("one fix per epoch, not one per sentence", 1, fixes.size)
        advanceTimeBy(BleSensorManager.NMEA_FLUSH_DELAY_MS * 2)
        runCurrent()
        assertEquals("nothing left to flush", 1, fixes.size)
    }

    @Test
    fun anEpochOfOneSentenceTypeIsDeliveredAfterTheFlushDelay() = runTest {
        val m = manager()
        val fixes = collectFixes(m)
        m.connect("00:11:22:33:44:55", "Receiver")
        transport.emitConnected(setOf(BleServiceType.Nmea))
        transport.emitNmea(rmc + "\r\n")
        assertTrue(fixes.isEmpty())

        advanceTimeBy(BleSensorManager.NMEA_FLUSH_DELAY_MS - 1)
        runCurrent()
        assertTrue(fixes.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        val fix = fixes.single()
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, fix.accuracyM!!, 0.0)
        assertEquals(84.4, fix.bearingDeg!!, 1e-9)
    }

    @Test
    fun disconnectingDiscardsTheOpenEpoch() = runTest {
        val m = manager()
        val fixes = collectFixes(m)
        m.connect("00:11:22:33:44:55", "Receiver")
        transport.emitConnected(setOf(BleServiceType.Nmea))
        transport.emitNmea(gga + "\r\n")
        m.disconnect()
        advanceTimeBy(BleSensorManager.NMEA_FLUSH_DELAY_MS * 2)
        runCurrent()
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun aReconnectStartsWithoutLeftoverText() = runTest {
        val m = manager()
        val fixes = collectFixes(m)
        m.connect("00:11:22:33:44:55", "Receiver")
        transport.emitNmea(gga.take(25)) // the connection drops mid-sentence
        m.disconnect()
        m.connect("00:11:22:33:44:55", "Receiver")
        // The tail of the old sentence alone: without a reset it would complete the GGA and surface as a fix.
        transport.emitNmea(gga.drop(25) + "\r\n")
        advanceTimeBy(BleSensorManager.NMEA_FLUSH_DELAY_MS * 2)
        runCurrent()
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun lnsFramesUsePositionQualityForTheirAccuracy() = runTest {
        val m = manager()
        val fixes = collectFixes(m)
        m.connect("00:11:22:33:44:55", "Receiver")
        transport.emitConnected(setOf(BleServiceType.Location))
        transport.emitLocation(lnsLocation(46.5, 7.8))
        transport.emitPositionQuality(positionQuality(ehpeM = 2.5))
        transport.emitLocation(lnsLocation(46.5, 7.8))

        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, fixes[0].accuracyM!!, 0.0)
        assertEquals(2.5, fixes[1].accuracyM!!, 1e-9)
    }

    @Test
    fun positionQualityGoesStaleAndIsForgottenOnDisconnect() = runTest {
        var now = clock.nowMs()
        val m = BleSensorManager(transport, backgroundScope, clock = Clock { now })
        val fixes = ArrayList<Fix>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.locationStream.collect { fixes += it } }
        m.connect("00:11:22:33:44:55", "Receiver")
        transport.emitPositionQuality(positionQuality(ehpeM = 2.5))
        now += BleFixPolicy.MAX_QUALITY_AGE_MS + 1
        transport.emitLocation(lnsLocation(46.5, 7.8))
        assertEquals("a stale quality sample does not describe this fix", BleFixPolicy.DEFAULT_ACCURACY_M, fixes.single().accuracyM!!, 0.0)

        transport.emitPositionQuality(positionQuality(ehpeM = 2.5))
        m.disconnect()
        m.connect("00:11:22:33:44:55", "Receiver")
        transport.emitLocation(lnsLocation(46.5, 7.8))
        assertEquals(BleFixPolicy.DEFAULT_ACCURACY_M, fixes.last().accuracyM!!, 0.0)
    }

    // ---- F7/dead-code wiring: ESS temperature and humidity streams -----------------------------------------------

    @Test
    fun temperatureAndHumidityReachTheirStreams() = runTest {
        val m = manager()
        val temps = ArrayList<Double>()
        val hums = ArrayList<Double>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.temperatureStream.collect { temps += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.humidityStream.collect { hums += it } }
        m.connect("11:22:33:44:55:66", "Weather")
        transport.emitConnected(setOf(BleServiceType.Environmental, BleServiceType.Temperature, BleServiceType.Humidity))
        transport.emitTemperature(21.5)
        transport.emitHumidity(44.0)
        assertEquals(listOf(21.5), temps)
        assertEquals(listOf(44.0), hums)
    }
}
