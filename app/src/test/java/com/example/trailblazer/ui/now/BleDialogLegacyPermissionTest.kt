package com.example.trailblazer.ui.now

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.container
import com.example.trailblazer.sensors.BleConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Before Android 12 the Bluetooth permissions are install-time, and a BLE scan returns nothing unless location access is
 * granted at runtime, so the dialog gates on location there. Robolectric SDK 28 exercises the pre-API-31 branch of
 * AppPermission.Bluetooth (BLUETOOTH, BLUETOOTH_ADMIN).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class BleDialogLegacyPermissionTest {
    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()

    @Test
    fun scanningNeedsLocationAccessOnAndroid9() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        app.container.permissions.refresh()
        rule.setContent {
            BleSensorDialog(
                status = BleConnectionState.Disconnected,
                devices = emptyList(),
                onScan = {},
                onStopScan = {},
                onConnect = { _, _ -> },
                onDisconnect = {},
                onDismiss = {},
            )
        }
        assertTrue(rule.onAllNodesWithText("Scan for sensors").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("location access", substring = true).assertExists()

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        app.container.permissions.refresh()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithText("Scan for sensors").fetchSemanticsNodes().size)
    }
}
