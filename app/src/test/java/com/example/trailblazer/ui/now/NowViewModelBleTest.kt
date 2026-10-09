package com.example.trailblazer.ui.now

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.sensors.BleConnectionState
import com.example.trailblazer.sensors.BleDeviceInfo
import com.example.trailblazer.sensors.BleServiceType
import com.example.trailblazer.sensors.FakeBleTransport
import com.example.trailblazer.sensors.FakeSensorSource
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowViewModelBleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: TrailDb
    private lateinit var transport: FakeBleTransport
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, TrailDb::class.java).allowMainThreadQueries().build()
        transport = FakeBleTransport()
        container = AppContainer(
            app,
            sensorSource = FakeSensorSource(emptySet()),
            db = db,
            bleTransport = transport,
        )
    }

    @After
    fun tearDown() {
        container.scope.cancel()
        db.close()
    }

    @Test
    fun nowViewModelExposesBleStateAndOperations() {
        val vm = NowViewModel(container)
        assertEquals(BleConnectionState.Disconnected, vm.bleStatus.value)

        vm.startBleScan()
        assertEquals(BleConnectionState.Scanning, vm.bleStatus.value)

        transport.emitDevice(BleDeviceInfo("Garmin GLO 2", "00:11:22:33:44:55"))
        assertEquals(1, vm.bleDevices.value.size)
        assertEquals("Garmin GLO 2", vm.bleDevices.value[0].name)

        vm.stopBleScan()
        assertEquals(BleConnectionState.Disconnected, vm.bleStatus.value)

        vm.connectBle("00:11:22:33:44:55", "Garmin GLO 2")
        assertTrue(vm.bleStatus.value is BleConnectionState.Connecting)

        transport.emitConnected(setOf(BleServiceType.Location, BleServiceType.Nmea))
        val connected = vm.bleStatus.value as BleConnectionState.Connected
        assertEquals("Garmin GLO 2", connected.deviceName)
        assertTrue(connected.hasLocation)

        vm.disconnectBle()
        assertEquals(BleConnectionState.Disconnected, vm.bleStatus.value)
    }
}
