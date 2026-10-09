package com.example.trailblazer.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.permissions.Permissions
import com.example.trailblazer.sensors.BleConnectionState
import com.example.trailblazer.sensors.BleSensorManager
import com.example.trailblazer.sensors.BleServiceType
import com.example.trailblazer.sensors.Clock
import com.example.trailblazer.sensors.FakeBleTransport
import com.trailblazer.core.sensors.Reading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class LocationRepositoryBleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var transport: FakeBleTransport
    private lateinit var bleSensors: BleSensorManager
    private val clock = Clock { 1700000000000L }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLocationEnabled(true)
        transport = FakeBleTransport()
        bleSensors = BleSensorManager(app, transport, scope, clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            idle()
        }
    }

    @Test
    fun parseNmeaDeliversExternalFixIntoRepository() {
        val perms = Permissions(app).also { it.refresh() }
        val repo = LocationRepository(app, manager, perms, scope, bleSensors, clock)
        val seen = mutableListOf<Reading<Fix>>()
        scope.launch { repo.live(1_000L).collect { seen.add(it) } }
        idle()

        val nmea = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val parsed = repo.parseNmea(nmea)
        assertNotNull(parsed)

        awaitUntil { seen.any { it is Reading.Value } }
        val latest = seen.filterIsInstance<Reading.Value<Fix>>().lastOrNull()
        assertNotNull(latest)
        assertEquals(48.1173, latest!!.value.position.lat, 1e-4)
        assertEquals(11.516667, latest.value.position.lon, 1e-4)
        assertEquals(545.4, latest.value.altitudeM!!, 0.1)
        assertEquals("ble_nmea", latest.value.provider)
    }

    @Test
    fun connectedBleGpsStreamsFixesAndGracefullyFallsBackToInternalOnDisconnect() {
        val perms = Permissions(app).also { it.refresh() }
        val repo = LocationRepository(app, manager, perms, scope, bleSensors, clock)
        val seen = mutableListOf<Reading<Fix>>()
        scope.launch { repo.live(1_000L).collect { seen.add(it) } }
        idle()

        // 1. Connect BLE GNSS peripheral (e.g. Garmin GLO 2)
        bleSensors.connect("00:11:22:33:44:55", "Garmin GLO 2")
        transport.emitConnected(setOf(BleServiceType.Nmea))
        idle()

        // 2. Stream NMEA from BLE peripheral
        transport.emitNmea("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n")
        awaitUntil { seen.any { it is Reading.Value && it.value.provider == "ble_nmea" } }

        val bleFix = seen.filterIsInstance<Reading.Value<Fix>>().last()
        assertEquals("ble_nmea", bleFix.value.provider)
        assertEquals(48.1173, bleFix.value.position.lat, 1e-4)

        // 3. BLE connection drops / disconnects
        bleSensors.disconnect()
        idle()
        assertEquals(BleConnectionState.Disconnected, bleSensors.connectionState.value)

        // 4. Simulate internal phone GPS fix - verify graceful fallback to internal sensors!
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.7749; longitude = -122.4194; accuracy = 8f; altitude = 15.0; time = System.currentTimeMillis()
        })
        awaitUntil { seen.any { it is Reading.Value && it.value.provider == LocationManager.GPS_PROVIDER } }

        val fallbackFix = seen.filterIsInstance<Reading.Value<Fix>>().last()
        assertEquals(LocationManager.GPS_PROVIDER, fallbackFix.value.provider)
        assertEquals(37.7749, fallbackFix.value.position.lat, 1e-4)
        assertEquals(-122.4194, fallbackFix.value.position.lon, 1e-4)
    }
}
