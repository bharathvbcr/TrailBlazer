package com.example.trailblazer.sensors

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BleSensorManagerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var transport: FakeBleTransport
    private lateinit var manager: BleSensorManager
    private val clock = Clock { 1700000000000L }

    @Before
    fun setUp() {
        transport = FakeBleTransport()
        manager = BleSensorManager(app, transport, scope, clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun scanDiscoversDevicesAndIgnoresDuplicates() {
        assertEquals(BleConnectionState.Disconnected, manager.connectionState.value)
        manager.startScan()
        assertEquals(BleConnectionState.Scanning, manager.connectionState.value)

        transport.emitDevice(BleDeviceInfo("Garmin GLO 2", "00:11:22:33:44:55", listOf(BleUuids.NUS_SERVICE)))
        transport.emitDevice(BleDeviceInfo("Garmin GLO 2", "00:11:22:33:44:55", listOf(BleUuids.NUS_SERVICE))) // Duplicate
        transport.emitDevice(BleDeviceInfo("Nordic Baro", "AA:BB:CC:DD:EE:FF", listOf(BleUuids.ESS_SERVICE)))

        assertEquals(2, manager.discoveredDevices.value.size)
        assertEquals("Garmin GLO 2", manager.discoveredDevices.value[0].name)
        assertEquals("Nordic Baro", manager.discoveredDevices.value[1].name)

        manager.stopScan()
        assertEquals(BleConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun scanFailsWhenBluetoothIsDisabled() {
        transport.isEnabled = false
        manager.startScan()
        assertTrue(manager.connectionState.value is BleConnectionState.Error)
    }

    @Test
    fun connectAndDisconnectTransitionsStateProperly() {
        manager.connect("00:11:22:33:44:55", "Garmin GLO 2")
        val connecting = manager.connectionState.value as BleConnectionState.Connecting
        assertEquals("Garmin GLO 2", connecting.deviceName)
        assertEquals("00:11:22:33:44:55", connecting.address)

        transport.emitConnected(setOf(BleServiceType.Location, BleServiceType.Environmental, BleServiceType.Nmea))
        val connected = manager.connectionState.value as BleConnectionState.Connected
        assertEquals("Garmin GLO 2", connected.deviceName)
        assertTrue(connected.hasLocation)
        assertTrue(connected.hasEnvironmental)
        assertTrue(connected.hasNmea)

        manager.disconnect()
        assertEquals(BleConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun parsesStandardBleLocationAndSpeedCharacteristic() {
        manager.connect("00:11:22:33:44:55", "Garmin GLO 2")
        transport.emitConnected(setOf(BleServiceType.Location))

        val fixes = mutableListOf<Fix>()
        val job = scope.launch { manager.locationStream.collect { fixes.add(it) } }

        // Build a sample LNS Location and Speed (0x2A67) packet
        // Flags: 0x1F = speed(bit0) | dist(bit1) | loc(bit2) | elev(bit3) | heading(bit4)
        // flags: 2 bytes (0x1F, 0x00)
        // speed: 2 bytes uint16 in 0.01 m/s -> 15.5 m/s = 1550 = 0x060E -> (0x0E, 0x06)
        // distance: 3 bytes (ignored) -> (0x00, 0x00, 0x00)
        // lat: 4 bytes sint32 in 10^-7 deg -> 46.5582 deg = 465582000 = 0x1BC037B0 -> (0xB0, 0x37, 0xC0, 0x1B)
        // lon: 4 bytes sint32 in 10^-7 deg -> 7.8352 deg = 78352000 = 0x04AB8E80 -> (0x80, 0x8E, 0xAB, 0x04)
        // elev: 3 bytes sint24 in 0.01 m -> 2100.5 m = 210050 = 0x033482 -> (0x82, 0x34, 0x03)
        // heading: 2 bytes uint16 in 0.01 deg -> 180.25 deg = 18025 = 0x4669 -> (0x69, 0x46)
        val data = byteArrayOf(
            0x1F.toByte(), 0x00.toByte(),
            0x0E.toByte(), 0x06.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xB0.toByte(), 0x37.toByte(), 0xC0.toByte(), 0x1B.toByte(),
            0x80.toByte(), 0x8E.toByte(), 0xAB.toByte(), 0x04.toByte(),
            0x82.toByte(), 0x34.toByte(), 0x03.toByte(),
            0x69.toByte(), 0x46.toByte(),
        )

        transport.emitLocation(data)

        assertEquals(1, fixes.size)
        val fix = fixes[0]
        assertEquals(46.5582, fix.position.lat, 1e-6)
        assertEquals(7.8352, fix.position.lon, 1e-6)
        assertEquals(2100.5, fix.altitudeM!!, 0.01)
        assertEquals(AltitudeDatum.Ellipsoid, fix.altitudeDatum)
        assertEquals(15.5, fix.speedMps!!, 0.01)
        assertEquals(180.25, fix.bearingDeg!!, 0.01)
        assertEquals("ble_lns", fix.provider)
        job.cancel()
    }

    @Test
    fun parsesStandardBleEnvironmentalSensingCharacteristics() {
        val pressure = BleEssParser.parsePressure(
            byteArrayOf(
                0xB0.toByte(), 0xD6.toByte(), 0x0F.toByte(), 0x00.toByte(), // 1038000 * 0.1 Pa = 1038.0 hPa
            ),
        )
        assertNotNull(pressure)
        assertEquals(1038.0, pressure!!, 0.01)

        val temp = BleEssParser.parseTemperature(
            byteArrayOf(
                0xAC.toByte(), 0x08.toByte(), // 2220 * 0.01 °C = 22.20 °C
            ),
        )
        assertNotNull(temp)
        assertEquals(22.2, temp!!, 0.01)

        val humidity = BleEssParser.parseHumidity(
            byteArrayOf(
                0xB8.toByte(), 0x13.toByte(), // 5048 * 0.01 % = 50.48 %
            ),
        )
        assertNotNull(humidity)
        assertEquals(50.48, humidity!!, 0.01)
    }

    @Test
    fun parsesNmeaGgaSentence() {
        // Sample valid GGA sentence: lat 48° 07.038' N, lon 11° 31.000' E, alt 545.4 m, HDOP 0.9
        val sentence = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val fix = NmeaParser.parse(sentence, clock)

        assertNotNull(fix)
        assertEquals(48.1173, fix!!.position.lat, 1e-4)
        assertEquals(11.516667, fix.position.lon, 1e-4)
        assertEquals(545.4, fix.altitudeM!!, 0.1)
        assertEquals(AltitudeDatum.SeaLevel, fix.altitudeDatum)
        assertEquals(4.5, fix.accuracyM!!, 0.1) // 0.9 * 5.0 = 4.5 m
        assertEquals("ble_nmea", fix.provider)
    }

    @Test
    fun parsesNmeaRmcSentence() {
        // Sample valid RMC sentence: lat 48° 07.038' N, lon 11° 31.000' E, speed 22.4 knots, course 84.4°
        val sentence = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        val fix = NmeaParser.parse(sentence, clock)

        assertNotNull(fix)
        assertEquals(48.1173, fix!!.position.lat, 1e-4)
        assertEquals(11.516667, fix.position.lon, 1e-4)
        assertEquals(22.4 * 0.5144444444444445, fix.speedMps!!, 0.01)
        assertEquals(84.4, fix.bearingDeg!!, 0.01)
    }

    @Test
    fun rejectsNmeaWithInvalidChecksumOrVoidFix() {
        // Corrupted checksum (should be *47, but has *00)
        val badChecksum = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"
        assertNull(NmeaParser.parse(badChecksum, clock))

        // Void RMC status ('V')
        val voidRmc = "\$GPRMC,123519,V,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*75"
        assertNull(NmeaParser.parse(voidRmc, clock))

        // Invalid GGA quality ('0')
        val invalidGga = "\$GPGGA,123519,4807.038,N,01131.000,E,0,08,0.9,545.4,M,46.9,M,,*46"
        assertNull(NmeaParser.parse(invalidGga, clock))
    }

    @Test
    fun streamingNmeaBufferHandlesChunkFragmentationAcrossPackets() {
        manager.connect("00:11:22:33:44:55", "Garmin GLO 2")
        transport.emitConnected(setOf(BleServiceType.Nmea))

        val fixes = mutableListOf<Fix>()
        val job = scope.launch { manager.locationStream.collect { fixes.add(it) } }

        // Packet 1: incomplete first half of GGA
        transport.emitNmea("\$GPGGA,123519,4807.038,N,01131.000,E,1,0")
        assertEquals(0, fixes.size)

        // Packet 2: second half of GGA plus full RMC
        transport.emitNmea("8,0.9,545.4,M,46.9,M,,*47\r\n\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A\r\n")

        assertEquals(2, fixes.size)
        assertEquals(48.1173, fixes[0].position.lat, 1e-4)
        assertEquals(545.4, fixes[0].altitudeM!!, 0.1)
        assertEquals(84.4, fixes[1].bearingDeg!!, 0.1)

        job.cancel()
    }
}
