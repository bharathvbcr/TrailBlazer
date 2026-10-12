package com.example.trailblazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest's Bluetooth declarations, as Android 12+ reads them (https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
 * BLUETOOTH_SCAN carries neverForLocation: the app lists nearby sensors and never derives the phone's position from scan
 * results, so Android needs no location permission for the scan. ACCESS_FINE_LOCATION stays unrestricted for the phone's GPS.
 *
 * Robolectric's PackageManager does not carry `usesPermissionFlags` through (it reports only REQUESTED_PERMISSION_REQUIRED),
 * so this reads the manifest source, which unit tests run next to (working directory = the app module).
 */
class ManifestBluetoothPermissionsTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun usesPermission(name: String): Element? {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it) as Element }.firstOrNull { it.getAttributeNS(android, "name") == name }
    }

    @Test
    fun bluetoothScanIsDeclaredNeverForLocation() {
        val scan = usesPermission("android.permission.BLUETOOTH_SCAN")
        assertNotNull("BLUETOOTH_SCAN is declared", scan)
        assertEquals("neverForLocation", scan!!.getAttributeNS(android, "usesPermissionFlags"))
        assertNotNull("BLUETOOTH_CONNECT is declared", usesPermission("android.permission.BLUETOOTH_CONNECT"))
    }

    @Test
    fun legacyBluetoothPermissionsAreLimitedToAndroid11AndOlder() {
        assertEquals("30", usesPermission("android.permission.BLUETOOTH")!!.getAttributeNS(android, "maxSdkVersion"))
        assertEquals("30", usesPermission("android.permission.BLUETOOTH_ADMIN")!!.getAttributeNS(android, "maxSdkVersion"))
    }

    @Test
    fun fineLocationIsNotRestrictedBecausePhoneGpsNeedsIt() {
        val fine = usesPermission("android.permission.ACCESS_FINE_LOCATION")
        assertNotNull("ACCESS_FINE_LOCATION is declared", fine)
        assertEquals("no maxSdkVersion", "", fine!!.getAttributeNS(android, "maxSdkVersion"))
        assertNull(fine.getAttributeNode("android:maxSdkVersion"))
    }
}
