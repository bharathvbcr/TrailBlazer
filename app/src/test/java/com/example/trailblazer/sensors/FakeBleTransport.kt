package com.example.trailblazer.sensors

class FakeBleTransport : BleTransport {
    var isAvailable = true
    var isEnabled = true
    var scanning = false
    var connectedAddress: String? = null

    /** Number of scan starts and stops, so tests can prove a scan was (or was not) left running. */
    var scanStarts = 0
        private set
    var scanStops = 0
        private set

    /** Make [startScan] fail the way a missing permission does: it reports a reason and returns false. */
    var scanStartFailure: String? = null

    private var onDeviceFoundCallback: ((BleDeviceInfo) -> Unit)? = null
    private var onScanFailedCallback: ((String) -> Unit)? = null
    private var onConnectedCallback: ((Set<BleServiceType>) -> Unit)? = null
    private var onDisconnectedCallback: (() -> Unit)? = null
    private var onLocationDataCallback: ((ByteArray) -> Unit)? = null
    private var onPositionQualityCallback: ((ByteArray) -> Unit)? = null
    private var onNmeaDataCallback: ((String) -> Unit)? = null
    private var onPressureDataCallback: ((Double) -> Unit)? = null
    private var onTemperatureDataCallback: ((Double) -> Unit)? = null
    private var onHumidityDataCallback: ((Double) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    override fun isBluetoothAvailable(): Boolean = isAvailable
    override fun isBluetoothEnabled(): Boolean = isEnabled

    override fun startScan(onDeviceFound: (BleDeviceInfo) -> Unit, onScanFailed: (String) -> Unit): Boolean {
        if (!isEnabled || !isAvailable) return false
        scanStartFailure?.let {
            onScanFailed(it)
            return false
        }
        scanning = true
        scanStarts++
        onDeviceFoundCallback = onDeviceFound
        onScanFailedCallback = onScanFailed
        return true
    }

    override fun stopScan() {
        if (scanning) scanStops++
        scanning = false
        onDeviceFoundCallback = null
        onScanFailedCallback = null
    }

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
        if (!isEnabled || !isAvailable) return false
        connectedAddress = address
        onConnectedCallback = onConnected
        onDisconnectedCallback = onDisconnected
        onLocationDataCallback = onLocationData
        onPositionQualityCallback = onPositionQualityData
        onNmeaDataCallback = onNmeaData
        onPressureDataCallback = onPressureData
        onTemperatureDataCallback = onTemperatureData
        onHumidityDataCallback = onHumidityData
        onErrorCallback = onError
        return true
    }

    override fun disconnect() {
        connectedAddress = null
        onDisconnectedCallback?.invoke()
    }

    fun emitDevice(device: BleDeviceInfo) {
        onDeviceFoundCallback?.invoke(device)
    }

    fun emitScanFailure(reason: String) {
        onScanFailedCallback?.invoke(reason)
    }

    fun emitConnected(services: Set<BleServiceType>) {
        onConnectedCallback?.invoke(services)
    }

    fun emitDisconnected() {
        connectedAddress = null
        onDisconnectedCallback?.invoke()
    }

    fun emitLocation(bytes: ByteArray) {
        onLocationDataCallback?.invoke(bytes)
    }

    fun emitPositionQuality(bytes: ByteArray) {
        onPositionQualityCallback?.invoke(bytes)
    }

    fun emitNmea(chunk: String) {
        onNmeaDataCallback?.invoke(chunk)
    }

    fun emitPressure(hpa: Double) {
        onPressureDataCallback?.invoke(hpa)
    }

    fun emitTemperature(tempC: Double) {
        onTemperatureDataCallback?.invoke(tempC)
    }

    fun emitHumidity(humidityPct: Double) {
        onHumidityDataCallback?.invoke(humidityPct)
    }

    fun emitError(msg: String) {
        onErrorCallback?.invoke(msg)
    }
}
