package com.example.trailblazer.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Which characteristics a peripheral's GATT table gets subscribed to, and what it is then said to provide. */
class BleGattPlannerTest {
    private fun notify(uuid: UUID) = GattCharacteristicInfo(uuid, canNotify = true, canRead = false)
    private fun read(uuid: UUID) = GattCharacteristicInfo(uuid, canNotify = false, canRead = true)

    private val vendorService = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val vendorChar = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    // ---- F3 ------------------------------------------------------------------------------------------------------

    @Test
    fun anUnknownServiceIsNeverTreatedAsAnNmeaSource() {
        val plan = BleGattPlanner.plan(listOf(GattServiceInfo(vendorService, listOf(notify(vendorChar), notify(BleUuids.NUS_TX)))))
        assertTrue("nothing to subscribe to", plan.ops.isEmpty())
        assertEquals(emptySet<BleServiceType>(), plan.servicesAfter(emptySet()))
        assertNull(BleGattPlanner.channelOf(vendorService, vendorChar))
        assertNull("NUS TX UUID under another service", BleGattPlanner.channelOf(vendorService, BleUuids.NUS_TX))
    }

    @Test
    fun nmeaIsTaggedOnlyForTheNordicUartTxCharacteristic() {
        val plan = BleGattPlanner.plan(
            listOf(GattServiceInfo(BleUuids.NUS_SERVICE, listOf(notify(BleUuids.NUS_TX), notify(BleUuids.NUS_RX), notify(vendorChar)))),
        )
        assertEquals(
            listOf<GattOp>(GattOp.RequestMtu(BleGattPlanner.PREFERRED_MTU), GattOp.EnableNotify(BleUuids.NUS_SERVICE, BleUuids.NUS_TX)),
            plan.ops,
        )
        assertEquals(setOf(BleServiceType.Nmea), plan.servicesAfter(emptySet()))
        assertEquals(BleChannel.NmeaSerial, BleGattPlanner.channelOf(BleUuids.NUS_SERVICE, BleUuids.NUS_TX))
        assertNull("RX is the phone-to-device direction", BleGattPlanner.channelOf(BleUuids.NUS_SERVICE, BleUuids.NUS_RX))
    }

    @Test
    fun theNordicUartServiceWithoutANotifyingTxProvidesNothing() {
        val plan = BleGattPlanner.plan(listOf(GattServiceInfo(BleUuids.NUS_SERVICE, listOf(read(BleUuids.NUS_TX)))))
        assertTrue(plan.ops.isEmpty())
    }

    // ---- F7 ------------------------------------------------------------------------------------------------------

    @Test
    fun anEssServiceWithoutPressureDoesNotClaimPressure() {
        val plan = BleGattPlanner.plan(listOf(GattServiceInfo(BleUuids.ESS_SERVICE, listOf(notify(BleUuids.TEMPERATURE)))))
        val services = plan.servicesAfter(emptySet())
        assertEquals(setOf(BleServiceType.Temperature, BleServiceType.Environmental), services)
        assertFalse(BleServiceType.Pressure in services)
        assertFalse(BleConnectionState.Connected("d", "a", services).hasPressure)
        assertTrue(BleConnectionState.Connected("d", "a", services).hasTemperature)
    }

    @Test
    fun anEssServiceWithNoSubscribableChannelProvidesNothing() {
        val plan = BleGattPlanner.plan(listOf(GattServiceInfo(BleUuids.ESS_SERVICE, listOf(read(BleUuids.PRESSURE)))))
        assertTrue(plan.ops.isEmpty())
        assertFalse(BleServiceType.Environmental in plan.servicesAfter(emptySet()))
    }

    @Test
    fun aChannelWhoseNotificationsFailedIsDroppedFromWhatTheDeviceProvides() {
        val plan = BleGattPlanner.plan(
            listOf(
                GattServiceInfo(BleUuids.ESS_SERVICE, listOf(notify(BleUuids.PRESSURE), notify(BleUuids.HUMIDITY))),
                GattServiceInfo(BleUuids.LNS_SERVICE, listOf(notify(BleUuids.LOCATION_AND_SPEED))),
            ),
        )
        val pressure = GattOp.EnableNotify(BleUuids.ESS_SERVICE, BleUuids.PRESSURE)
        val humidity = GattOp.EnableNotify(BleUuids.ESS_SERVICE, BleUuids.HUMIDITY)
        val location = GattOp.EnableNotify(BleUuids.LNS_SERVICE, BleUuids.LOCATION_AND_SPEED)

        assertEquals(
            setOf(BleServiceType.Location, BleServiceType.Pressure, BleServiceType.Humidity, BleServiceType.Environmental),
            plan.servicesAfter(emptySet()),
        )
        assertEquals(
            setOf(BleServiceType.Location, BleServiceType.Humidity, BleServiceType.Environmental),
            plan.servicesAfter(setOf(pressure)),
        )
        assertEquals(setOf(BleServiceType.Location), plan.servicesAfter(setOf(pressure, humidity)))
        assertEquals(emptySet<BleServiceType>(), plan.servicesAfter(setOf(pressure, humidity, location)))
    }

    // ---- LNS, Position Quality, MTU ------------------------------------------------------------------------------

    @Test
    fun positionQualityIsSubscribedWhenItNotifiesAndReadOnceWhenItOnlyReads() {
        fun planFor(quality: GattCharacteristicInfo) = BleGattPlanner.plan(
            listOf(GattServiceInfo(BleUuids.LNS_SERVICE, listOf(notify(BleUuids.LOCATION_AND_SPEED), quality))),
        )
        assertTrue(GattOp.EnableNotify(BleUuids.LNS_SERVICE, BleUuids.POSITION_QUALITY) in planFor(notify(BleUuids.POSITION_QUALITY)).ops)
        assertTrue(GattOp.Read(BleUuids.LNS_SERVICE, BleUuids.POSITION_QUALITY) in planFor(read(BleUuids.POSITION_QUALITY)).ops)
        // Position Quality refines a fix; failing to enable it must not remove the location capability.
        val plan = planFor(notify(BleUuids.POSITION_QUALITY))
        val failedQuality = GattOp.EnableNotify(BleUuids.LNS_SERVICE, BleUuids.POSITION_QUALITY)
        assertEquals(setOf(BleServiceType.Location), plan.servicesAfter(setOf(failedQuality)))
    }

    @Test
    fun positionQualityAloneIsNotASensor() {
        val plan = BleGattPlanner.plan(listOf(GattServiceInfo(BleUuids.LNS_SERVICE, listOf(notify(BleUuids.POSITION_QUALITY)))))
        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun theMtuRequestComesFirstAndOnlyWhenThereIsSomethingToSubscribeTo() {
        val plan = BleGattPlanner.plan(
            listOf(
                GattServiceInfo(BleUuids.NUS_SERVICE, listOf(notify(BleUuids.NUS_TX))),
                GattServiceInfo(BleUuids.LNS_SERVICE, listOf(notify(BleUuids.LOCATION_AND_SPEED))),
            ),
        )
        assertEquals(GattOp.RequestMtu(BleGattPlanner.PREFERRED_MTU), plan.ops.first())
        assertEquals(3, plan.ops.size)
        assertTrue("room for a whole NMEA sentence plus CR LF in one notification", BleGattPlanner.PREFERRED_MTU - 3 >= 84)
        assertTrue(BleGattPlanner.plan(emptyList()).ops.isEmpty())
    }

    @Test
    fun duplicateCharacteristicsProduceOneOperation() {
        val plan = BleGattPlanner.plan(
            listOf(GattServiceInfo(BleUuids.ESS_SERVICE, listOf(notify(BleUuids.PRESSURE), notify(BleUuids.PRESSURE)))),
        )
        assertEquals(2, plan.ops.size) // MTU + one subscription
    }
}
