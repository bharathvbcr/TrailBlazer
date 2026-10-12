package com.example.trailblazer.sensors

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
                onConnected = { connected = it },
                onDisconnected = { disconnects++ },
                onLocationData = {},
                onPositionQualityData = {},
                onNmeaData = {},
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
        val ok = transport.connect("not-an-address", {}, {}, {}, {}, {}, {}, {}, {}, { errors += it })
        assertFalse(ok)
    }
}
