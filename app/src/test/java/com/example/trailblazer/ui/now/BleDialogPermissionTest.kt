package com.example.trailblazer.ui.now

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.container
import com.example.trailblazer.sensors.BleConnectionState
import com.example.trailblazer.sensors.BleDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The external-sensor dialog must ask for the Bluetooth ("Nearby devices") permission before it offers to scan, say why, and
 * stop a scan that is still running whenever the dialog goes away. Robolectric runs these on SDK 36, the API 31+ branch of
 * AppPermission.Bluetooth (BLUETOOTH_SCAN and BLUETOOTH_CONNECT).
 */
@RunWith(AndroidJUnit4::class)
class BleDialogPermissionTest {
    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()
    private var scans = 0
    private var stops = 0

    private fun setDialog(devices: List<BleDeviceInfo> = emptyList(), status: BleConnectionState = BleConnectionState.Disconnected) {
        rule.setContent {
            BleSensorDialog(
                status = status,
                devices = devices,
                onScan = { scans++ },
                onStopScan = { stops++ },
                onConnect = { _, _ -> },
                onDisconnect = {},
                onDismiss = {},
            )
        }
    }

    private fun scanButton() = rule.onAllNodesWithText("Scan for sensors")

    @Test
    fun withoutNearbyDevicesPermissionTheDialogExplainsAndOffersNoScan() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        app.container.permissions.refresh()
        setDialog()

        assertTrue("no scan button without the permission", scanButton().fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Nearby devices", substring = true).assertExists()
        rule.onNodeWithText("Allow").assertExists()
        assertEquals(0, scans)
    }

    @Test
    fun grantingThePermissionRevealsTheScanButtonWithoutReopeningTheDialog() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        app.container.permissions.refresh()
        setDialog()
        assertTrue(scanButton().fetchSemanticsNodes().isEmpty())

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        app.container.permissions.refresh()
        rule.waitForIdle()

        rule.onNodeWithText("Scan for sensors").performClick()
        assertEquals(1, scans)
    }

    @Test
    fun scanPermissionAloneIsNotEnoughToConnect() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        app.container.permissions.refresh()
        setDialog()
        assertTrue(scanButton().fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aConnectedSensorCanAlwaysBeDisconnectedEvenIfThePermissionWasRevoked() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        app.container.permissions.refresh()
        setDialog(status = BleConnectionState.Connected("Garmin GLO 2", "00:11:22:33:44:55"))
        rule.onNodeWithText("Disconnect external sensor").assertExists()
    }

    @Test
    fun closingTheDialogStopsAScanInProgress() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        app.container.permissions.refresh()
        var open by mutableStateOf(true)
        rule.setContent {
            if (open) {
                BleSensorDialog(
                    status = BleConnectionState.Scanning,
                    devices = emptyList(),
                    onScan = { scans++ },
                    onStopScan = { stops++ },
                    onConnect = { _, _ -> },
                    onDisconnect = {},
                    onDismiss = { open = false },
                )
            }
        }
        assertEquals("not stopped while open", 0, stops)

        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()

        assertEquals("the scan is stopped when the dialog goes away", 1, stops)
    }

}
