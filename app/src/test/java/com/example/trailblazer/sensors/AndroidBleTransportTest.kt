package com.example.trailblazer.sensors

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/**
 * The Android transport against Robolectric's Bluetooth shadows: connection-failure paths that need no radio. Behaviour on a
 * real peripheral (CCCD sequencing, MTU, notification delivery) is covered by the pure [GattOpQueue] and [BleGattPlanner]
 * tests and has not been exercised on hardware.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBleTransportTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val address = "00:11:22:33:44:55"
    private lateinit var transport: AndroidBleTransport
    private val errors = ArrayList<String>()
    private var disconnects = 0
    private var connected: Set<BleServiceType>? = null
    private var connectedCalls = 0
    private val nmeaChunks = ArrayList<String>()
    private val lnFeatures = ArrayList<ByteArray>()
    private val requestedMtus = ArrayList<Int>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        @Suppress("DEPRECATION")
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        transport = AndroidBleTransport(app)
    }

    private fun connect(): BluetoothGatt {
        assertTrue(
            transport.connect(
                address,
                onConnected = { connected = it; connectedCalls++ },
                onDisconnected = { disconnects++ },
                onLocationData = {},
                onPositionQualityData = {},
                onLnFeatureData = { lnFeatures += it },
                onNmeaData = { nmeaChunks += it },
                onPressureData = {},
                onTemperatureData = {},
                onHumidityData = {},
                onError = { errors += it },
            ),
        )
        @Suppress("DEPRECATION")
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return shadowOf(device).bluetoothGatts.single()
    }

    private fun callback(gatt: BluetoothGatt): BluetoothGattCallback = shadowOf(gatt).gattCallback

    @Test
    fun aFailedServiceDiscoveryReportsTheErrorAndClosesTheConnection() {
        val gatt = connect()
        val cb = callback(gatt)

        cb.onServicesDiscovered(gatt, 129) // GATT_INTERNAL_ERROR

        assertEquals(listOf("Failed to discover GATT services: 129"), errors)
        assertTrue("the GATT client is closed, not left open", shadowOf(gatt).isClosed)
        // The platform's late disconnect callback must not overwrite the error with a plain "disconnected".
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(0, disconnects)
        assertNotNull(errors.singleOrNull())
    }

    @Test
    fun aDeviceWithoutAnySupportedServiceIsAnErrorNotAConnection() {
        val gatt = connect()
        val cb = callback(gatt)

        cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) // the shadow exposes no services

        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().startsWith("Device offers no supported sensor service"))
        assertEquals(null, connected)
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aConnectionErrorStatusIsReportedAndCloses() {
        val gatt = connect()
        val cb = callback(gatt)
        cb.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED) // the classic Android GATT_ERROR

        assertEquals(listOf("Connection lost (GATT status 133)"), errors)
        assertEquals(0, disconnects)
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test
    fun aCleanRemoteDisconnectIsReportedAsDisconnected() {
        val gatt = connect()
        val cb = callback(gatt)
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        assertEquals(1, disconnects)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun callbacksFromAnAbandonedConnectionAreIgnored() {
        val gatt = connect()
        val cb = callback(gatt)
        transport.disconnect()

        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        cb.onServicesDiscovered(gatt, 129)

        assertEquals("a user-initiated disconnect is reported by the manager, not the late callback", 0, disconnects)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun connectingToAMalformedAddressFailsWithoutThrowing() {
        val ok = transport.connect("not-an-address", {}, {}, {}, {}, {}, {}, {}, {}, {}, { errors += it })
        assertFalse(ok)
    }

    // ---- through a discovered GATT table -------------------------------------------------------------------------
    // Robolectric's shadow answers MTU requests, descriptor writes and reads synchronously, which the queue must survive.

    private fun characteristic(uuid: UUID, properties: Int): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(uuid, properties, BluetoothGattCharacteristic.PERMISSION_READ)

    private fun service(uuid: UUID, vararg chars: BluetoothGattCharacteristic): BluetoothGattService =
        BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).also { s ->
            chars.forEach { c ->
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    c.addDescriptor(BluetoothGattDescriptor(BleUuids.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
                }
                s.addCharacteristic(c)
            }
        }

    /** Wraps the session callback to record the MTU the transport asks for, and returns the real callback. */
    private fun observeMtu(gatt: BluetoothGatt): BluetoothGattCallback {
        val real = shadowOf(gatt).gattCallback
        shadowOf(gatt).gattCallback = object : BluetoothGattCallback() {
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                requestedMtus += mtu
                real.onMtuChanged(g, mtu, status)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = real.onDescriptorWrite(g, d, status)
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = real.onServicesDiscovered(g, status)
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                real.onCharacteristicRead(g, c, value, status)
        }
        return real
    }

    private fun discover(gatt: BluetoothGatt, vararg services: BluetoothGattService) {
        val shadow = shadowOf(gatt)
        services.forEach { s ->
            shadow.addDiscoverableService(s)
            s.characteristics.forEach { shadow.allowCharacteristicNotification(it) }
        }
        assertTrue(gatt.discoverServices())
    }

    private fun hasSession(): Boolean =
        AndroidBleTransport::class.java.getDeclaredField("session").also { it.isAccessible = true }.get(transport) != null

    @Test
    fun aNusChunkWithoutDollarOrLineEndingReachesTheNmeaConsumerAfterTheMtuRequest() {
        val gatt = connect()
        val real = observeMtu(gatt)
        val tx = characteristic(BleUuids.NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        discover(gatt, service(BleUuids.NUS_SERVICE, tx))

        assertEquals(setOf(BleServiceType.Nmea), connected)
        assertEquals("the transport asked for the preferred MTU", listOf(BleGattPlanner.PREFERRED_MTU), requestedMtus)

        // The middle of a sentence split at 20 bytes: no '$', no CR, no LF.
        val middle = "8,0.9,545.4,M,46.9,M"
        real.onCharacteristicChanged(gatt, tx, middle.toByteArray(Charsets.US_ASCII))
        assertEquals(listOf(middle), nmeaChunks)
    }

    @Test
    fun notificationsFromUnknownServicesAreNotNmea() {
        val gatt = connect()
        val real = observeMtu(gatt)
        val tx = characteristic(BleUuids.NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val vendorChar = characteristic(UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"), BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val sameUuidElsewhere = characteristic(BleUuids.NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        discover(
            gatt,
            service(BleUuids.NUS_SERVICE, tx),
            service(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"), vendorChar, sameUuidElsewhere),
        )

        real.onCharacteristicChanged(gatt, vendorChar, "\$GPGGA,junk\r\n".toByteArray())
        real.onCharacteristicChanged(gatt, sameUuidElsewhere, "\$GPGGA,junk\r\n".toByteArray())
        assertTrue(nmeaChunks.isEmpty())
        assertEquals(setOf(BleServiceType.Nmea), connected)
    }

    @Test
    fun lnFeatureIsReadThroughTheQueueAndDelivered() {
        val gatt = connect()
        observeMtu(gatt)
        val location = characteristic(BleUuids.LOCATION_AND_SPEED, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val feature = characteristic(BleUuids.LN_FEATURE, BluetoothGattCharacteristic.PROPERTY_READ)
        @Suppress("DEPRECATION")
        feature.value = byteArrayOf(0x1F, 0x00, 0x10, 0x00) // bit 20: Position Status Supported
        discover(gatt, service(BleUuids.LNS_SERVICE, location, feature))

        assertEquals(setOf(BleServiceType.Location), connected)
        assertEquals(1, lnFeatures.size)
        assertEquals(true, BleLnFeatureParser.positionStatusSupported(lnFeatures.single()))
    }

    @Test
    fun aRedeliveredServiceDiscoveryDoesNotSubscribeOrConnectTwice() {
        val gatt = connect()
        val real = observeMtu(gatt)
        val tx = characteristic(BleUuids.NUS_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        discover(gatt, service(BleUuids.NUS_SERVICE, tx))
        assertEquals(1, connectedCalls)
        assertEquals(1, requestedMtus.size)

        real.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS) // Service Changed: Android discovers again
        real.onServicesDiscovered(gatt, 129) // and even a failed re-discovery must not tear down a working connection

        assertEquals("connected is reported once", 1, connectedCalls)
        assertEquals("no second MTU request and no second set of subscriptions", 1, requestedMtus.size)
        assertTrue(errors.isEmpty())
        assertFalse(shadowOf(gatt).isClosed)
    }

    @Test
    fun aRemoteDisconnectForgetsTheSessionSoDisconnectDoesNotCloseTwice() {
        val gatt = connect()
        assertTrue(hasSession())
        callback(gatt).onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, disconnects)
        assertFalse("the closed session is dropped", hasSession())
        transport.disconnect() // must be a no-op now
        assertEquals(1, disconnects)
    }

    @Test
    fun aFailedSessionIsAlsoForgotten() {
        val gatt = connect()
        callback(gatt).onServicesDiscovered(gatt, 129)
        assertFalse(hasSession())
    }
}
