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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.trailblazer.location.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/**
 * What a connected peripheral can provide, one entry per capability whose notifications were actually enabled.
 * [Environmental] means at least one of [Pressure], [Temperature] or [Humidity] is available.
 */
enum class BleServiceType {
    Location,
    Environmental,
    Nmea,
    Pressure,
    Temperature,
    Humidity,
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
        val hasPressure: Boolean get() = BleServiceType.Pressure in services
        val hasTemperature: Boolean get() = BleServiceType.Temperature in services
        val hasHumidity: Boolean get() = BleServiceType.Humidity in services
    }
    data class Error(val message: String) : BleConnectionState
}

/** Hardware seam between [BleSensorManager] and Android Bluetooth APIs, enabling unit testing. */
interface BleTransport {
    fun isBluetoothAvailable(): Boolean
    fun isBluetoothEnabled(): Boolean

    /**
     * Starts a scan. Returns false if it could not start; the reason, when known, is also reported through [onScanFailed],
     * as is any failure after a successful start.
     */
    fun startScan(onDeviceFound: (BleDeviceInfo) -> Unit, onScanFailed: (String) -> Unit): Boolean
    fun stopScan()
    fun connect(
        address: String,
        onConnected: (services: Set<BleServiceType>) -> Unit,
        onDisconnected: () -> Unit,
        onLocationData: (ByteArray) -> Unit,
        onPositionQualityData: (ByteArray) -> Unit,
        onNmeaData: (String) -> Unit,
        onPressureData: (Double) -> Unit,
        onTemperatureData: (Double) -> Unit,
        onHumidityData: (Double) -> Unit,
        onError: (String) -> Unit,
    ): Boolean
    fun disconnect()
}

/**
 * Manages scanning, connection, and data streams from Bluetooth Low Energy peripheral sensors
 * (external high-sensitivity GNSS receivers and barometric/environmental sensors).
 */
class BleSensorManager(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val scanTimeoutMs: Long = SCAN_TIMEOUT_MS,
    private val nmeaFlushDelayMs: Long = NMEA_FLUSH_DELAY_MS,
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

    private val pipeline = BleLocationPipeline(clock)
    private val jobLock = Any()
    private var scanTimeoutJob: Job? = null
    private var nmeaFlushJob: Job? = null

    fun startScan() {
        if (!transport.isBluetoothAvailable() || !transport.isBluetoothEnabled()) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is disabled or unavailable")
            return
        }
        cancelScanTimeout()
        _discoveredDevices.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning
        val started = transport.startScan(
            onDeviceFound = { device ->
                _discoveredDevices.update { cur -> if (cur.none { it.address == device.address }) cur + device else cur }
            },
            onScanFailed = { reason ->
                if (_connectionState.value is BleConnectionState.Scanning) {
                    cancelScanTimeout()
                    transport.stopScan()
                    _connectionState.value = BleConnectionState.Error(reason)
                }
            },
        )
        if (!started) {
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Error("Unable to start BLE scan")
            }
            return
        }
        synchronized(jobLock) {
            scanTimeoutJob = scope.launch {
                delay(scanTimeoutMs)
                stopScan()
            }
        }
    }

    fun stopScan() {
        cancelScanTimeout()
        transport.stopScan()
        _connectionState.update { if (it is BleConnectionState.Scanning) BleConnectionState.Disconnected else it }
    }

    fun connect(address: String, name: String? = null) {
        stopScanForConnect()
        val deviceName = name ?: _discoveredDevices.value.firstOrNull { it.address == address }?.name ?: address
        pipeline.reset()
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
                resetLocationState()
                _connectionState.value = BleConnectionState.Disconnected
            },
            onLocationData = { data -> pipeline.onLocationAndSpeed(data)?.let { _locationStream.tryEmit(it) } },
            onPositionQualityData = { data -> pipeline.onPositionQuality(data) },
            onNmeaData = { chunk -> onNmea(chunk) },
            onPressureData = { p -> _pressureStream.tryEmit(p) },
            onTemperatureData = { t -> _temperatureStream.tryEmit(t) },
            onHumidityData = { h -> _humidityStream.tryEmit(h) },
            onError = { msg ->
                resetLocationState()
                _connectionState.value = BleConnectionState.Error(msg)
            },
        )
        if (!started && _connectionState.value is BleConnectionState.Connecting) {
            _connectionState.value = BleConnectionState.Error("Failed to initiate connection")
        }
    }

    fun disconnect() {
        transport.disconnect()
        resetLocationState()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun stopScanForConnect() {
        cancelScanTimeout()
        transport.stopScan()
    }

    private fun onNmea(chunk: String) {
        for (fix in pipeline.onNmeaChunk(chunk)) _locationStream.tryEmit(fix)
        // An epoch that never completes (a receiver sending one sentence type) is emitted after a short wait.
        synchronized(jobLock) {
            nmeaFlushJob?.cancel()
            nmeaFlushJob = if (pipeline.hasPendingNmea) {
                scope.launch {
                    delay(nmeaFlushDelayMs)
                    pipeline.flushNmea()?.let { _locationStream.tryEmit(it) }
                }
            } else {
                null
            }
        }
    }

    private fun resetLocationState() {
        synchronized(jobLock) {
            nmeaFlushJob?.cancel()
            nmeaFlushJob = null
        }
        pipeline.reset()
    }

    private fun cancelScanTimeout() {
        synchronized(jobLock) {
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
        }
    }

    companion object {
        /** A scan is battery-expensive; it stops by itself after this long if the user does not stop it. */
        const val SCAN_TIMEOUT_MS = 30_000L

        /** How long a half-received NMEA epoch waits for the rest of its sentences. A GGA+RMC burst takes well under this. */
        const val NMEA_FLUSH_DELAY_MS = 500L
    }
}

/** Android runtime implementation of [BleTransport]. */
class AndroidBleTransport(private val context: Context) : BleTransport {
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutScheduler = GattTimeoutScheduler { delayMs, task ->
        val r = Runnable { task() }
        mainHandler.postDelayed(r, delayMs)
        val cancel: () -> Unit = { mainHandler.removeCallbacks(r) }
        cancel
    }

    @Volatile private var activeScanCallback: ScanCallback? = null
    @Volatile private var session: GattSession? = null

    override fun isBluetoothAvailable(): Boolean = adapter != null

    override fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun startScan(onDeviceFound: (BleDeviceInfo) -> Unit, onScanFailed: (String) -> Unit): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        val scanner = a.bluetoothLeScanner ?: return false
        stopScan()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                deviceInfo(result)?.let(onDeviceFound)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                for (r in results) deviceInfo(r)?.let(onDeviceFound)
            }

            override fun onScanFailed(errorCode: Int) {
                if (activeScanCallback === this) activeScanCallback = null
                onScanFailed(describeScanFailure(errorCode))
            }
        }
        activeScanCallback = callback
        return try {
            scanner.startScan(callback)
            true
        } catch (_: SecurityException) {
            activeScanCallback = null
            onScanFailed("Bluetooth permission denied: allow Nearby devices to scan for sensors")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceInfo(result: ScanResult): BleDeviceInfo? {
        val dev = result.device ?: return null
        val name = try {
            dev.name
        } catch (_: SecurityException) {
            null
        } ?: result.scanRecord?.deviceName ?: dev.address
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
        return BleDeviceInfo(name, dev.address, uuids)
    }

    private fun describeScanFailure(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "BLE scan is already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE scan could not be registered"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support BLE scanning"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth reported an internal scan error"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Bluetooth is out of scan resources"
        6 /* SCAN_FAILED_SCANNING_TOO_FREQUENTLY, API 30 */ -> "Scanning too often: wait a moment and try again"
        else -> "BLE scan failed (code $code)"
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
        onPositionQualityData: (ByteArray) -> Unit,
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
        } catch (_: IllegalArgumentException) {
            return false
        }

        disconnect()

        val s = GattSession(
            onConnected, onDisconnected, onLocationData, onPositionQualityData, onNmeaData,
            onPressureData, onTemperatureData, onHumidityData, onError,
        )
        session = s
        val gatt = try {
            if (Build.VERSION.SDK_INT >= 26) {
                device.connectGatt(context, false, s, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, s)
            }
        } catch (_: SecurityException) {
            s.shutdown()
            onError("Permission denied to connect to BLE device")
            return false
        }
        if (gatt == null) { // the platform could not create a GATT client (Bluetooth stack unavailable)
            s.shutdown()
            return false
        }
        s.attach(gatt)
        return true
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val s = session ?: return
        session = null
        s.shutdown()
    }

    /**
     * One connection attempt. Holds the GATT client, the operation queue and what discovery planned; everything that decides
     * *what* to do lives in the pure [BleGattPlanner] and [GattOpQueue], this class only talks to Android. Once [shutdown]
     * (or an error) has closed it, late callbacks are ignored.
     */
    @SuppressLint("MissingPermission")
    private inner class GattSession(
        private val onConnected: (Set<BleServiceType>) -> Unit,
        private val onDisconnected: () -> Unit,
        private val onLocationData: (ByteArray) -> Unit,
        private val onPositionQualityData: (ByteArray) -> Unit,
        private val onNmeaData: (String) -> Unit,
        private val onPressureData: (Double) -> Unit,
        private val onTemperatureData: (Double) -> Unit,
        private val onHumidityData: (Double) -> Unit,
        private val onError: (String) -> Unit,
    ) : BluetoothGattCallback() {
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var closed = false
        @Volatile private var plan = BleGattPlan(emptyList(), emptyMap())
        private val failedOps = HashSet<GattOp>()

        private val queue = GattOpQueue(
            timeoutMs = OP_TIMEOUT_MS,
            scheduler = timeoutScheduler,
            execute = ::execute,
            onFinished = { op, result -> if (result != GattOpResult.Success) failedOps += op },
            onDrained = ::onQueueDrained,
        )

        fun attach(g: BluetoothGatt) {
            gatt = g
            if (closed) closeGatt(g)
        }

        /** Closes the connection and ignores everything it still delivers. Reports nothing. */
        fun shutdown() {
            closed = true
            queue.clear()
            gatt?.let(::closeGatt)
        }

        private fun fail(message: String) {
            if (closed) return
            shutdown()
            onError(message)
        }

        private fun closeGatt(g: BluetoothGatt) {
            try {
                g.disconnect()
                g.close()
            } catch (_: Exception) {}
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            this.gatt = gatt
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    val started = try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        fail("Permission denied during service discovery")
                        return
                    }
                    if (!started) fail("Could not start GATT service discovery")
                }
                newState == BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    shutdown()
                    onDisconnected()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> fail("Connection lost (GATT status $status)")
                status != BluetoothGatt.GATT_SUCCESS -> fail("Connection failed (GATT status $status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Failed to discover GATT services: $status")
                return
            }
            val infos = gatt.services.map { svc ->
                GattServiceInfo(
                    svc.uuid,
                    svc.characteristics.map { c ->
                        GattCharacteristicInfo(
                            c.uuid,
                            canNotify = (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                            canRead = (c.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                        )
                    },
                )
            }
            val p = BleGattPlanner.plan(infos)
            if (p.ops.isEmpty()) {
                fail("Device offers no supported sensor service (location, environment or NMEA serial)")
                return
            }
            plan = p
            queue.enqueue(p.ops)
        }

        private fun onQueueDrained() {
            if (closed) return
            val services = plan.servicesAfter(failedOps)
            if (services.isEmpty()) fail("Could not enable notifications from the device") else onConnected(services)
        }

        private fun execute(op: GattOp): Boolean {
            val g = gatt ?: return false
            return try {
                when (op) {
                    is GattOp.RequestMtu -> g.requestMtu(op.mtu)
                    is GattOp.Read -> characteristic(g, op.service, op.characteristic)?.let { g.readCharacteristic(it) } ?: false
                    is GattOp.EnableNotify -> characteristic(g, op.service, op.characteristic)?.let { enableNotify(g, it) } ?: false
                }
            } catch (_: SecurityException) {
                false
            }
        }

        private fun characteristic(g: BluetoothGatt, service: UUID, characteristic: UUID) =
            g.getService(service)?.getCharacteristic(characteristic)

        /** setCharacteristicNotification, then the CCCD write whose completion arrives in [onDescriptorWrite]. */
        private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
            if (!g.setCharacteristicNotification(ch, true)) return false
            val cccd = ch.getDescriptor(BleUuids.CCCD) ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (closed) return
            queue.complete(GattOpKind.RequestMtu, null, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed || descriptor.uuid != BleUuids.CCCD) return
            queue.complete(GattOpKind.EnableNotify, descriptor.characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            handleRead(characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(characteristic, value, status)
        }

        private fun handleRead(ch: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
            if (closed) return
            val ok = status == BluetoothGatt.GATT_SUCCESS && value != null
            if (ok && value != null) handleCharacteristicChange(ch, value)
            queue.complete(GattOpKind.Read, ch.uuid, ok)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleCharacteristicChange(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicChange(characteristic, value)
        }

        private fun handleCharacteristicChange(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (closed) return
            val service = ch.service?.uuid ?: return
            when (BleGattPlanner.channelOf(service, ch.uuid)) {
                BleChannel.LocationAndSpeed -> onLocationData(value)
                BleChannel.PositionQuality -> onPositionQualityData(value)
                BleChannel.Pressure -> BleEssParser.parsePressure(value)?.let(onPressureData)
                BleChannel.Temperature -> BleEssParser.parseTemperature(value)?.let(onTemperatureData)
                BleChannel.Humidity -> BleEssParser.parseHumidity(value)?.let(onHumidityData)
                BleChannel.NmeaSerial -> onNmeaData(String(value, Charsets.US_ASCII)) // every chunk: sentences split anywhere
                null -> Unit
            }
        }
    }

    private companion object {
        /** A GATT operation that has not completed after this long is abandoned and the queue moves on. */
        const val OP_TIMEOUT_MS = 5_000L
    }
}
