package com.riconelson.cyclocomp.platform.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class SensorType { HEART_RATE, SPEED_CADENCE, POWER }

data class DiscoveredSensor(
    val id: String,
    val name: String?,
    val types: Set<SensorType>,
    val rssi: Int,
    val lastSeenMillis: Long,
)

/**
 * Short-lived Bluetooth LE discovery for standard cycling sensors.
 * Callers must hold BLUETOOTH_SCAN (and BLUETOOTH_CONNECT on Android 12+ for device identity).
 */
class BluetoothSensorScanner(context: Context) : AutoCloseable {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _sensors = MutableStateFlow<List<DiscoveredSensor>>(emptyList())
    private val _isScanning = MutableStateFlow(false)

    val sensors: StateFlow<List<DiscoveredSensor>> = _sensors.asStateFlow()
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val stopScan = Runnable { stop() }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::add)
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun start(timeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MILLIS) {
        stop()
        val scanner = bluetoothManager.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        _sensors.value = emptyList()
        scanner.startScan(filters, scanSettings, callback)
        _isScanning.value = true
        handler.postDelayed(stopScan, timeoutMillis.coerceAtLeast(1L))
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(stopScan)
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback)
        _isScanning.value = false
    }

    override fun close() = stop()

    @SuppressLint("MissingPermission")
    private fun add(result: ScanResult) {
        val types = result.scanRecord?.serviceUuids
            .orEmpty()
            .mapNotNull(serviceTypeByUuid::get)
            .toSet()
        if (types.isEmpty()) return

        val id = runCatching { result.device.address }.getOrElse { "sensor-${result.device.hashCode()}" }
        val sensor = DiscoveredSensor(
            id = id,
            name = result.scanRecord?.deviceName,
            types = types,
            rssi = result.rssi,
            lastSeenMillis = System.currentTimeMillis(),
        )
        _sensors.value = (_sensors.value.filterNot { it.id == id } + sensor)
            .sortedByDescending(DiscoveredSensor::lastSeenMillis)
    }

    private companion object {
        const val DEFAULT_SCAN_TIMEOUT_MILLIS = 10_000L

        val heartRate = ParcelUuid(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
        val speedCadence = ParcelUuid(UUID.fromString("00001816-0000-1000-8000-00805f9b34fb"))
        val cyclingPower = ParcelUuid(UUID.fromString("00001818-0000-1000-8000-00805f9b34fb"))
        val serviceTypeByUuid = mapOf(
            heartRate to SensorType.HEART_RATE,
            speedCadence to SensorType.SPEED_CADENCE,
            cyclingPower to SensorType.POWER,
        )
        val filters = serviceTypeByUuid.keys.map { serviceUuid ->
            ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        }
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }
}
