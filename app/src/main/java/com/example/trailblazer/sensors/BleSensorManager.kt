package com.example.trailblazer.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.mod360
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Bluetooth SIG and standard vendor UUIDs for external sensors. */
object BleUuids {
    /** Location and Navigation Service (LNS) */
    val LNS_SERVICE: UUID = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb")
    val LOCATION_AND_SPEED: UUID = UUID.fromString("00002a67-0000-1000-8000-00805f9b34fb")
    val POSITION_QUALITY: UUID = UUID.fromString("00002a69-0000-1000-8000-00805f9b34fb")
    val LN_FEATURE: UUID = UUID.fromString("00002a6a-0000-1000-8000-00805f9b34fb")

    /** Environmental Sensing Service (ESS) */
    val ESS_SERVICE: UUID = UUID.fromString("0000181a-0000-1000-8000-00805f9b34fb")
    val PRESSURE: UUID = UUID.fromString("00002a6d-0000-1000-8000-00805f9b34fb")
    val TEMPERATURE: UUID = UUID.fromString("00002a6e-0000-1000-8000-00805f9b34fb")
    val HUMIDITY: UUID = UUID.fromString("00002a6f-0000-1000-8000-00805f9b34fb")

    /** Nordic UART Service (NUS) used by serial GNSS / Garmin receivers for NMEA streaming */
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration Descriptor (CCCD) */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

enum class BleServiceType {
    Location,
    Environmental,
    Nmea,
}

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val serviceUuids: List<UUID> = emptyList(),
)

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Scanning : BleConnectionState
    data class Connecting(val deviceName: String, val address: String) : BleConnectionState
    data class Connected(
        val deviceName: String,
        val address: String,
        val services: Set<BleServiceType> = emptySet(),
    ) : BleConnectionState {
        val hasLocation: Boolean get() = BleServiceType.Location in services || BleServiceType.Nmea in services
        val hasEnvironmental: Boolean get() = BleServiceType.Environmental in services
        val hasNmea: Boolean get() = BleServiceType.Nmea in services
    }
    data class Error(val message: String) : BleConnectionState
}

/** Hardware seam between [BleSensorManager] and Android Bluetooth APIs, enabling unit testing. */
interface BleTransport {
    fun isBluetoothAvailable(): Boolean
    fun isBluetoothEnabled(): Boolean
    fun startScan(onDeviceFound: (BleDeviceInfo) -> Unit): Boolean
    fun stopScan()
    fun connect(
        address: String,
        onConnected: (services: Set<BleServiceType>) -> Unit,
        onDisconnected: () -> Unit,
        onLocationData: (ByteArray) -> Unit,
        onNmeaData: (String) -> Unit,
        onPressureData: (Double) -> Unit,
        onTemperatureData: (Double) -> Unit,
        onHumidityData: (Double) -> Unit,
        onError: (String) -> Unit,
    ): Boolean
    fun disconnect()
}

/** Parses standard NMEA 0183 sentences (GGA, RMC, GLL) into [Fix]. */
object NmeaParser {
    fun parse(sentence: String, clock: Clock = Clock.System): Fix? {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("$")) return null
        val starIdx = trimmed.indexOf('*')
        val content = if (starIdx != -1) {
            val expectedHex = trimmed.substring(starIdx + 1).trim()
            val payload = trimmed.substring(1, starIdx)
            val expectedCs = expectedHex.toIntOrNull(16) ?: return null
            var cs = 0
            for (ch in payload) {
                cs = cs xor ch.code
            }
            if (cs != expectedCs) return null
            payload
        } else {
            trimmed.substring(1)
        }

        val parts = content.split(',')
        if (parts.isEmpty()) return null
        val type = parts[0].uppercase()

        return when {
            type.endsWith("GGA") -> parseGga(parts, clock)
            type.endsWith("RMC") -> parseRmc(parts, clock)
            type.endsWith("GLL") -> parseGll(parts, clock)
            else -> null
        }
    }

    private fun parseGga(parts: List<String>, clock: Clock): Fix? {
        if (parts.size < 10) return null
        val quality = parts[6].toIntOrNull() ?: 0
        if (quality == 0) return null // 0 = invalid fix

        val lat = parseCoord(parts[2], parts[3]) ?: return null
        val lon = parseCoord(parts[4], parts[5]) ?: return null
        val pos = LatLon.of(lat, lon) ?: return null

        val hdop = parts[8].toDoubleOrNull()
        val accuracyM = hdop?.let { it * 5.0 }?.takeIf { it.isFinite() && it in 0.0..100_000.0 }
        val alt = parts[9].toDoubleOrNull()?.takeIf { it.isFinite() && it in -1000.0..20_000.0 }

        return Fix(
            position = pos,
            accuracyM = accuracyM,
            altitudeM = alt,
            altitudeDatum = AltitudeDatum.SeaLevel,
            verticalAccuracyM = null,
            speedMps = null,
            bearingDeg = null,
            timeMs = clock.nowMs(),
            provider = "ble_nmea",
        )
    }

    private fun parseRmc(parts: List<String>, clock: Clock): Fix? {
        if (parts.size < 9) return null
        val status = parts[2]
        if (status != "A") return null // A = active/valid, V = void

        val lat = parseCoord(parts[3], parts[4]) ?: return null
        val lon = parseCoord(parts[5], parts[6]) ?: return null
        val pos = LatLon.of(lat, lon) ?: return null

        val speedKnots = parts[7].toDoubleOrNull()
        val speedMps = speedKnots?.let { it * 0.5144444444444445 }?.takeIf { it.isFinite() && it in 0.0..400.0 }
        val bearingDeg = parts[8].toDoubleOrNull()?.takeIf { it.isFinite() }?.let { mod360(it) }

        return Fix(
            position = pos,
            accuracyM = null,
            altitudeM = null,
            altitudeDatum = AltitudeDatum.SeaLevel,
            verticalAccuracyM = null,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            timeMs = clock.nowMs(),
            provider = "ble_nmea",
        )
    }

    private fun parseGll(parts: List<String>, clock: Clock): Fix? {
        if (parts.size < 7) return null
        val status = parts[6]
        if (status != "A") return null

        val lat = parseCoord(parts[1], parts[2]) ?: return null
        val lon = parseCoord(parts[3], parts[4]) ?: return null
        val pos = LatLon.of(lat, lon) ?: return null

        return Fix(
            position = pos,
            accuracyM = null,
            altitudeM = null,
            altitudeDatum = AltitudeDatum.SeaLevel,
            verticalAccuracyM = null,
            speedMps = null,
            bearingDeg = null,
            timeMs = clock.nowMs(),
            provider = "ble_nmea",
        )
    }

    private fun parseCoord(coordStr: String, dir: String): Double? {
        if (coordStr.isBlank() || dir.isBlank()) return null
        val dot = coordStr.indexOf('.')
        if (dot < 2) return null
        val degLen = dot - 2
        val degStr = coordStr.substring(0, degLen)
        val minStr = coordStr.substring(degLen)
        val deg = degStr.toDoubleOrNull() ?: return null
        val min = minStr.toDoubleOrNull() ?: return null
        var dec = deg + min / 60.0
        val upperDir = dir.trim().uppercase()
        if (upperDir == "S" || upperDir == "W") {
            dec = -dec
        } else if (upperDir != "N" && upperDir != "E") {
            return null
        }
        return dec
    }
}

/** Handles fragmented NMEA chunks received across multiple BLE packets. */
class NmeaStreamBuffer(private val clock: Clock) {
    private val buffer = StringBuilder()

    fun appendAndExtractFixes(chunk: String): List<Fix> {
        buffer.append(chunk)
        val fixes = mutableListOf<Fix>()
        while (true) {
            val newlineIdx = buffer.indexOfAny(charArrayOf('\r', '\n'))
            if (newlineIdx == -1) break
            val line = buffer.substring(0, newlineIdx).trim()
            var endIdx = newlineIdx
            while (endIdx < buffer.length && (buffer[endIdx] == '\r' || buffer[endIdx] == '\n')) {
                endIdx++
            }
            buffer.delete(0, endIdx)
            if (line.isNotEmpty()) {
                val fix = NmeaParser.parse(line, clock)
                if (fix != null) fixes.add(fix)
            }
        }
        return fixes
    }

    fun clear() {
        buffer.clear()
    }
}

/** Parses Bluetooth SIG standard Location and Speed characteristic (0x2A67). */
object BleLnsParser {
    fun parse(data: ByteArray, clock: Clock = Clock.System): Fix? {
        if (data.size < 2) return null
        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        var offset = 2
        var speedMps: Double? = null
        if ((flags and 0x01) != 0) { // Instantaneous Speed
            if (offset + 2 > data.size) return null
            val rawSpeed = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            speedMps = (rawSpeed / 100.0).takeIf { it.isFinite() && it in 0.0..400.0 }
            offset += 2
        }
        if ((flags and 0x02) != 0) { // Total Distance
            offset += 3
        }
        var lat: Double? = null
        var lon: Double? = null
        if ((flags and 0x04) != 0) { // Location Present
            if (offset + 8 > data.size) return null
            val rawLat = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    (data[offset + 3].toInt() shl 24)
            val rawLon = (data[offset + 4].toInt() and 0xFF) or
                    ((data[offset + 5].toInt() and 0xFF) shl 8) or
                    ((data[offset + 6].toInt() and 0xFF) shl 16) or
                    (data[offset + 7].toInt() shl 24)
            lat = rawLat / 10_000_000.0
            lon = rawLon / 10_000_000.0
            offset += 8
        }
        var elevationM: Double? = null
        if ((flags and 0x08) != 0) { // Elevation
            if (offset + 3 > data.size) return null
            var rawElev = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16)
            if ((rawElev and 0x800000) != 0) {
                rawElev = rawElev or (0xFF shl 24)
            }
            elevationM = (rawElev / 100.0).takeIf { it.isFinite() && it in -1000.0..20_000.0 }
            offset += 3
        }
        var headingDeg: Double? = null
        if ((flags and 0x10) != 0) { // Heading
            if (offset + 2 > data.size) return null
            val rawHeading = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            headingDeg = (rawHeading / 100.0).takeIf { it.isFinite() }?.let { mod360(it) }
            offset += 2
        }

        if (lat == null || lon == null) return null
        val pos = LatLon.of(lat, lon) ?: return null
        return Fix(
            position = pos,
            accuracyM = null,
            altitudeM = elevationM,
            altitudeDatum = AltitudeDatum.Ellipsoid,
            verticalAccuracyM = null,
            speedMps = speedMps,
            bearingDeg = headingDeg,
            timeMs = clock.nowMs(),
            provider = "ble_lns",
        )
    }
}

/** Parses Bluetooth SIG standard Environmental Sensing Service characteristics. */
object BleEssParser {
    /** Pressure (0x2A6D): 32-bit unsigned int in 0.1 Pa -> hPa */
    fun parsePressure(data: ByteArray): Double? {
        if (data.size < 4) return null
        val raw = (data[0].toLong() and 0xFF) or
                ((data[1].toLong() and 0xFF) shl 8) or
                ((data[2].toLong() and 0xFF) shl 16) or
                ((data[3].toLong() and 0xFF) shl 24)
        val hpa = raw / 1000.0
        return hpa.takeIf { it.isFinite() && it in 300.0..1100.0 }
    }

    /** Temperature (0x2A6E): 16-bit signed int in 0.01 °C -> °C */
    fun parseTemperature(data: ByteArray): Double? {
        if (data.size < 2) return null
        val raw = (data[0].toInt() and 0xFF) or (data[1].toInt() shl 8)
        val temp = raw / 100.0
        return temp.takeIf { it.isFinite() && it in -60.0..70.0 }
    }

    /** Humidity (0x2A6F): 16-bit unsigned int in 0.01 % -> % */
    fun parseHumidity(data: ByteArray): Double? {
        if (data.size < 2) return null
        val raw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val hum = raw / 100.0
        return hum.takeIf { it.isFinite() && it in 0.0..100.0 }
    }
}

/**
 * Manages scanning, connection, and data streams from Bluetooth Low Energy peripheral sensors
 * (external high-sensitivity GNSS receivers and barometric/environmental sensors).
 */
class BleSensorManager(
    private val context: Context,
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _locationStream = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    val locationStream: SharedFlow<Fix> = _locationStream.asSharedFlow()

    private val _pressureStream = MutableSharedFlow<Double>(extraBufferCapacity = 16)
    val pressureStream: SharedFlow<Double> = _pressureStream.asSharedFlow()

    private val _temperatureStream = MutableSharedFlow<Double>(extraBufferCapacity = 16)
    val temperatureStream: SharedFlow<Double> = _temperatureStream.asSharedFlow()

    private val _humidityStream = MutableSharedFlow<Double>(extraBufferCapacity = 16)
    val humidityStream: SharedFlow<Double> = _humidityStream.asSharedFlow()

    private val nmeaBuffer = NmeaStreamBuffer(clock)

    fun startScan() {
        if (!transport.isBluetoothAvailable() || !transport.isBluetoothEnabled()) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is disabled or unavailable")
            return
        }
        _discoveredDevices.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning
        val started = transport.startScan { device ->
            val cur = _discoveredDevices.value
            if (cur.none { it.address == device.address }) {
                _discoveredDevices.value = cur + device
            }
        }
        if (!started) {
            _connectionState.value = BleConnectionState.Error("Unable to start BLE scan")
        }
    }

    fun stopScan() {
        transport.stopScan()
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    fun connect(address: String, name: String? = null) {
        val deviceName = name ?: _discoveredDevices.value.firstOrNull { it.address == address }?.name ?: address
        _connectionState.value = BleConnectionState.Connecting(deviceName, address)
        val started = transport.connect(
            address = address,
            onConnected = { services ->
                _connectionState.value = BleConnectionState.Connected(
                    deviceName = deviceName,
                    address = address,
                    services = services,
                )
            },
            onDisconnected = {
                _connectionState.value = BleConnectionState.Disconnected
                nmeaBuffer.clear()
            },
            onLocationData = { data ->
                val fix = BleLnsParser.parse(data, clock)
                if (fix != null) _locationStream.tryEmit(fix)
            },
            onNmeaData = { chunk ->
                val fixes = nmeaBuffer.appendAndExtractFixes(chunk)
                for (fix in fixes) _locationStream.tryEmit(fix)
            },
            onPressureData = { p -> _pressureStream.tryEmit(p) },
            onTemperatureData = { t -> _temperatureStream.tryEmit(t) },
            onHumidityData = { h -> _humidityStream.tryEmit(h) },
            onError = { msg ->
                _connectionState.value = BleConnectionState.Error(msg)
                nmeaBuffer.clear()
            },
        )
        if (!started) {
            _connectionState.value = BleConnectionState.Error("Failed to initiate connection")
        }
    }

    fun disconnect() {
        transport.disconnect()
        _connectionState.value = BleConnectionState.Disconnected
        nmeaBuffer.clear()
    }

    fun feedNmea(sentence: String): Fix? {
        val fix = NmeaParser.parse(sentence, clock)
        if (fix != null) _locationStream.tryEmit(fix)
        return fix
    }

    fun feedLnsData(data: ByteArray): Fix? {
        val fix = BleLnsParser.parse(data, clock)
        if (fix != null) _locationStream.tryEmit(fix)
        return fix
    }

    fun feedPressure(hpa: Double) {
        _pressureStream.tryEmit(hpa)
    }
}

/** Android runtime implementation of [BleTransport]. */
class AndroidBleTransport(private val context: Context) : BleTransport {
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var activeScanCallback: ScanCallback? = null
    private var currentGatt: BluetoothGatt? = null

    override fun isBluetoothAvailable(): Boolean = adapter != null

    override fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun startScan(onDeviceFound: (BleDeviceInfo) -> Unit): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        val scanner = a.bluetoothLeScanner ?: return false
        stopScan()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = dev.name ?: result.scanRecord?.deviceName ?: dev.address
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                onDeviceFound(BleDeviceInfo(name, dev.address, uuids))
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                for (r in results) {
                    val dev = r.device ?: continue
                    val name = dev.name ?: r.scanRecord?.deviceName ?: dev.address
                    val uuids = r.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                    onDeviceFound(BleDeviceInfo(name, dev.address, uuids))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // Scan failure callback
            }
        }
        activeScanCallback = callback
        try {
            scanner.startScan(callback)
            return true
        } catch (_: SecurityException) {
            return false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    override fun connect(
        address: String,
        onConnected: (services: Set<BleServiceType>) -> Unit,
        onDisconnected: () -> Unit,
        onLocationData: (ByteArray) -> Unit,
        onNmeaData: (String) -> Unit,
        onPressureData: (Double) -> Unit,
        onTemperatureData: (Double) -> Unit,
        onHumidityData: (Double) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        val device = try {
            a.getRemoteDevice(address)
        } catch (_: Exception) {
            return false
        }

        disconnect()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        onError("Permission denied during service discovery")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onDisconnected()
                    try {
                        gatt.close()
                    } catch (_: Exception) {}
                    if (currentGatt == gatt) currentGatt = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onError("Failed to discover GATT services: $status")
                    return
                }
                val detectedServices = mutableSetOf<BleServiceType>()

                val lns = gatt.getService(BleUuids.LNS_SERVICE)
                if (lns != null) {
                    detectedServices.add(BleServiceType.Location)
                    lns.getCharacteristic(BleUuids.LOCATION_AND_SPEED)?.let { enableNotification(gatt, it) }
                }

                val ess = gatt.getService(BleUuids.ESS_SERVICE)
                if (ess != null) {
                    detectedServices.add(BleServiceType.Environmental)
                    ess.getCharacteristic(BleUuids.PRESSURE)?.let { enableNotification(gatt, it) }
                    ess.getCharacteristic(BleUuids.TEMPERATURE)?.let { enableNotification(gatt, it) }
                    ess.getCharacteristic(BleUuids.HUMIDITY)?.let { enableNotification(gatt, it) }
                }

                val nus = gatt.getService(BleUuids.NUS_SERVICE)
                if (nus != null) {
                    detectedServices.add(BleServiceType.Nmea)
                    nus.getCharacteristic(BleUuids.NUS_TX)?.let { enableNotification(gatt, it) }
                }

                for (service in gatt.services) {
                    if (service.uuid == BleUuids.LNS_SERVICE || service.uuid == BleUuids.ESS_SERVICE || service.uuid == BleUuids.NUS_SERVICE) continue
                    for (ch in service.characteristics) {
                        if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            enableNotification(gatt, ch)
                            detectedServices.add(BleServiceType.Nmea)
                        }
                    }
                }

                onConnected(detectedServices)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                handleCharacteristicChange(characteristic, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleCharacteristicChange(characteristic, value)
            }

            private fun handleCharacteristicChange(ch: BluetoothGattCharacteristic, value: ByteArray) {
                when (ch.uuid) {
                    BleUuids.LOCATION_AND_SPEED -> onLocationData(value)
                    BleUuids.PRESSURE -> BleEssParser.parsePressure(value)?.let(onPressureData)
                    BleUuids.TEMPERATURE -> BleEssParser.parseTemperature(value)?.let(onTemperatureData)
                    BleUuids.HUMIDITY -> BleEssParser.parseHumidity(value)?.let(onHumidityData)
                    else -> {
                        val text = String(value, Charsets.US_ASCII)
                        if (text.contains('$') || text.contains('\n') || text.contains('\r')) {
                            onNmeaData(text)
                        }
                    }
                }
            }

            private fun enableNotification(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                try {
                    gatt.setCharacteristicNotification(ch, true)
                    val cccd = ch.getDescriptor(BleUuids.CCCD)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                } catch (_: SecurityException) {}
            }
        }

        try {
            currentGatt = if (Build.VERSION.SDK_INT >= 26) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, callback)
            }
            return true
        } catch (_: SecurityException) {
            onError("Permission denied to connect to BLE device")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val g = currentGatt ?: return
        currentGatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: Exception) {}
    }
}
