package com.riconelson.cyclocomp.platform.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SensorConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class ConnectedSensor(
    val address: String,
    val name: String?,
    val types: Set<SensorType>,
    val connectionState: SensorConnectionState = SensorConnectionState.DISCONNECTED,
    val batteryLevelPercent: Int? = null,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val sensorSpeedMps: Double? = null,
    val lastUpdatedMillis: Long = 0L,
)

data class LiveSensorTelemetry(
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val sensorSpeedMps: Double? = null,
    val connectedSensors: List<ConnectedSensor> = emptyList(),
)

/**
 * Manages Bluetooth GATT connections to standard cycling sensors (HR, CSC, Power).
 */
class BleSensorConnectionManager(private val context: Context) : AutoCloseable {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _telemetry = MutableStateFlow(LiveSensorTelemetry())
    val telemetry: StateFlow<LiveSensorTelemetry> = _telemetry.asStateFlow()

    private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val desiredConnections = mutableSetOf<String>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectJobs = ConcurrentHashMap<String, Runnable>()
    private val sensorStates = ConcurrentHashMap<String, ConnectedSensor>()
    private val lastCrankReadings = ConcurrentHashMap<String, CrankReading>()
    private val lastWheelReadings = ConcurrentHashMap<String, WheelReading>()

    val savedSensorAddresses: Set<String>
        get() = preferences.getStringSet(KEY_SAVED_SENSORS, emptySet()).orEmpty()

    fun saveSensorAddress(address: String, name: String? = null, types: Set<SensorType> = emptySet()) {
        val updated = savedSensorAddresses + address
        val editor = preferences.edit().putStringSet(KEY_SAVED_SENSORS, updated)
        if (!name.isNullOrBlank()) {
            editor.putString("sensor_name_$address", name)
        }
        if (types.isNotEmpty()) {
            editor.putStringSet("sensor_types_$address", types.map { it.name }.toSet())
        }
        editor.apply()
    }

    fun removeSensorAddress(address: String) {
        val updated = savedSensorAddresses - address
        preferences.edit()
            .putStringSet(KEY_SAVED_SENSORS, updated)
            .remove("sensor_name_$address")
            .remove("sensor_types_$address")
            .apply()
        disconnect(address)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, types: Set<SensorType> = emptySet(), name: String? = null) {
        Log.d("BleSensorManager", "connect requested for address=$address, name=$name")
        desiredConnections += address
        cancelReconnect(address)

        saveSensorAddress(address, name, types)

        val existing = sensorStates[address]
        val updatedSensor = if (existing != null) {
            existing.copy(
                name = name ?: existing.name,
                types = if (types.isNotEmpty()) existing.types + types else existing.types,
                connectionState = SensorConnectionState.CONNECTING,
            )
        } else {
            val adapter = bluetoothManager.adapter?.takeIf(BluetoothAdapter::isEnabled)
            val devName = runCatching { adapter?.getRemoteDevice(address)?.name }.getOrNull()
            ConnectedSensor(
                address = address,
                name = name ?: devName ?: "Cycling sensor",
                types = types,
                connectionState = SensorConnectionState.CONNECTING,
            )
        }
        sensorStates[address] = updatedSensor
        updateTelemetryState()

        val existingGatt = activeGatts[address]
        if (existingGatt != null) {
            val current = sensorStates[address]
            if (current?.connectionState == SensorConnectionState.CONNECTED || current?.connectionState == SensorConnectionState.CONNECTING) {
                return
            }
            activeGatts.remove(address)
            runCatching { existingGatt.disconnect() }
            runCatching { existingGatt.close() }
        }

        val adapter = bluetoothManager.adapter?.takeIf(BluetoothAdapter::isEnabled) ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return

        val callback = SensorGattCallback(address)
        // The user pressed Connect, so ask for an immediate LE connection. autoConnect=true
        // is a deferred/background reconnect mode and can leave an iPhone broadcaster waiting.
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        activeGatts[address] = gatt
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        Log.d("BleSensorManager", "disconnect requested for address=$address")
        desiredConnections -= address
        cancelReconnect(address)
        activeGatts.remove(address)?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        sensorStates.remove(address)
        lastCrankReadings.remove(address)
        lastWheelReadings.remove(address)
        updateTelemetryState()
    }

    @SuppressLint("MissingPermission")
    fun connectSavedSensors() {
        savedSensorAddresses.forEach { address ->
            if (activeGatts[address] == null) {
                val savedName = preferences.getString("sensor_name_$address", null)
                val savedTypeNames = preferences.getStringSet("sensor_types_$address", emptySet()).orEmpty()
                val savedTypes = savedTypeNames.mapNotNull { typeName ->
                    runCatching { SensorType.valueOf(typeName) }.getOrNull()
                }.toSet()
                connect(address, savedTypes, savedName)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        reconnectJobs.values.forEach(reconnectHandler::removeCallbacks)
        reconnectJobs.clear()
        desiredConnections.clear()
        activeGatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        activeGatts.clear()
        sensorStates.clear()
        lastCrankReadings.clear()
        lastWheelReadings.clear()
        _telemetry.value = LiveSensorTelemetry()
    }

    private fun updateTelemetryState() {
        val connectedList = sensorStates.values.toList()

        val primaryHr = connectedList
            .filter { it.connectionState == SensorConnectionState.CONNECTED && it.heartRateBpm != null }
            .maxByOrNull { it.lastUpdatedMillis }?.heartRateBpm

        val primaryCadence = connectedList
            .filter { it.connectionState == SensorConnectionState.CONNECTED && it.cadenceRpm != null }
            .maxByOrNull { it.lastUpdatedMillis }?.cadenceRpm

        val primaryPower = connectedList
            .filter { it.connectionState == SensorConnectionState.CONNECTED && it.powerWatts != null }
            .maxByOrNull { it.lastUpdatedMillis }?.powerWatts

        val primarySpeed = connectedList
            .filter { it.connectionState == SensorConnectionState.CONNECTED && it.sensorSpeedMps != null }
            .maxByOrNull { it.lastUpdatedMillis }?.sensorSpeedMps

        _telemetry.value = LiveSensorTelemetry(
            heartRateBpm = primaryHr,
            cadenceRpm = primaryCadence,
            powerWatts = primaryPower,
            sensorSpeedMps = primarySpeed,
            connectedSensors = connectedList,
        )
    }

    private sealed interface GattOperation {
        data class EnableNotification(val characteristic: BluetoothGattCharacteristic) : GattOperation
        data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : GattOperation
    }

    private inner class SensorGattCallback(private val address: String) : BluetoothGattCallback() {
        private val gattQueue = ArrayDeque<GattOperation>()
        private var isOperationPending = false

        private val timeoutRunnable = Runnable {
            Log.w("BleSensorManager", "GATT operation timed out on $address, continuing queue")
            isOperationPending = false
            activeGatts[address]?.let { processNextOperation(it) }
        }

        @Synchronized
        private fun enqueueOperation(gatt: BluetoothGatt, op: GattOperation) {
            gattQueue.add(op)
            processNextOperation(gatt)
        }

        @Synchronized
        private fun processNextOperation(gatt: BluetoothGatt) {
            if (isOperationPending) return
            val nextOp = if (gattQueue.isNotEmpty()) gattQueue.removeFirst() else return
            isOperationPending = true
            reconnectHandler.postDelayed(timeoutRunnable, 2000L)

            val initiated = when (nextOp) {
                is GattOperation.EnableNotification -> doEnableNotification(gatt, nextOp.characteristic)
                is GattOperation.ReadCharacteristic -> doReadCharacteristic(gatt, nextOp.characteristic)
            }

            if (!initiated) {
                Log.e("BleSensorManager", "Failed to initiate GATT operation $nextOp")
                reconnectHandler.removeCallbacks(timeoutRunnable)
                isOperationPending = false
                processNextOperation(gatt)
            }
        }

        @Synchronized
        private fun onOperationCompleted(gatt: BluetoothGatt) {
            reconnectHandler.removeCallbacks(timeoutRunnable)
            isOperationPending = false
            processNextOperation(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BleSensorManager", "onConnectionStateChange status=$status, newState=$newState (2=CONNECTED, 0=DISCONNECTED)")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val devName = runCatching { gatt.device.name }.getOrNull()
                    val current = sensorStates[address] ?: ConnectedSensor(address, devName, emptySet())
                    sensorStates[address] = current.copy(
                        name = current.name ?: devName,
                        connectionState = SensorConnectionState.CONNECTED,
                    )
                    updateTelemetryState()
                    val discovered = gatt.discoverServices()
                    Log.d("BleSensorManager", "discoverServices initial result=$discovered")
                    if (!discovered) {
                        reconnectHandler.postDelayed({
                            runCatching { gatt.discoverServices() }
                        }, 250L)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    reconnectHandler.removeCallbacks(timeoutRunnable)
                    gattQueue.clear()
                    isOperationPending = false
                    if (activeGatts[address] === gatt) {
                        activeGatts.remove(address)
                    }
                    runCatching { gatt.close() }
                    val current = sensorStates[address]
                    if (current != null) {
                        sensorStates[address] = current.copy(
                            connectionState = SensorConnectionState.DISCONNECTED,
                            heartRateBpm = null,
                            cadenceRpm = null,
                            powerWatts = null,
                            sensorSpeedMps = null,
                        )
                        updateTelemetryState()
                    }
                    if (address in desiredConnections) scheduleReconnect(address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("BleSensorManager", "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val discoveredTypes = mutableSetOf<SensorType>()
            gatt.services.forEach { service ->
                Log.d("BleSensorManager", "Discovered Service: ${service.uuid}")
                service.characteristics.forEach { chara ->
                    Log.d("BleSensorManager", "  Chara: ${chara.uuid}, props=${chara.properties}")
                    chara.descriptors.forEach { desc ->
                        Log.d("BleSensorManager", "    Desc: ${desc.uuid}")
                    }
                }

                when (service.uuid) {
                    BleDataParsers.HEART_RATE_SERVICE_UUID -> {
                        discoveredTypes += SensorType.HEART_RATE
                        service.characteristics.firstOrNull { it.uuid == BleDataParsers.HEART_RATE_MEASUREMENT_UUID }
                            ?.let { enqueueOperation(gatt, GattOperation.EnableNotification(it)) }
                    }
                    BleDataParsers.CSC_SERVICE_UUID -> {
                        discoveredTypes += SensorType.SPEED_CADENCE
                        service.characteristics.firstOrNull { it.uuid == BleDataParsers.CSC_MEASUREMENT_UUID }
                            ?.let { enqueueOperation(gatt, GattOperation.EnableNotification(it)) }
                    }
                    BleDataParsers.POWER_SERVICE_UUID -> {
                        discoveredTypes += SensorType.POWER
                        service.characteristics.firstOrNull { it.uuid == BleDataParsers.POWER_MEASUREMENT_UUID }
                            ?.let { enqueueOperation(gatt, GattOperation.EnableNotification(it)) }
                    }
                    BleDataParsers.BATTERY_SERVICE_UUID -> {
                        service.characteristics.firstOrNull { it.uuid == BleDataParsers.BATTERY_LEVEL_UUID }
                            ?.let { enqueueOperation(gatt, GattOperation.ReadCharacteristic(it)) }
                    }
                }
            }
            if (discoveredTypes.isNotEmpty()) {
                sensorStates[address]?.let { current ->
                    val newTypes = current.types + discoveredTypes
                    sensorStates[address] = current.copy(types = newTypes)
                    saveSensorAddress(address, current.name ?: runCatching { gatt.device.name }.getOrNull(), newTypes)
                    updateTelemetryState()
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun doEnableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
            val setNotifRes = gatt.setCharacteristicNotification(characteristic, true)
            Log.d("BleSensorManager", "setCharacteristicNotification for ${characteristic.uuid} returned $setNotifRes")

            val descriptor = characteristic.getDescriptor(BleDataParsers.CCCD_UUID)
                ?: characteristic.descriptors.firstOrNull { it.uuid == BleDataParsers.CCCD_UUID }

            if (descriptor == null) {
                Log.e("BleSensorManager", "CCCD descriptor NOT FOUND for characteristic ${characteristic.uuid}")
                return false
            }

            val payload = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusCode = gatt.writeDescriptor(descriptor, payload)
                Log.d("BleSensorManager", "writeDescriptor (TIRAMISU) returned status code: $statusCode")
                statusCode == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = payload
                @Suppress("DEPRECATION")
                val writeRes = gatt.writeDescriptor(descriptor)
                Log.d("BleSensorManager", "writeDescriptor (legacy) returned $writeRes")
                writeRes
            }
            return initiated
        }

        @SuppressLint("MissingPermission")
        private fun doReadCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
            Log.d("BleSensorManager", "Reading characteristic ${characteristic.uuid}...")
            return gatt.readCharacteristic(characteristic)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d("BleSensorManager", "onDescriptorWrite desc=${descriptor.uuid}, chara=${descriptor.characteristic.uuid}, status=$status (0=SUCCESS)")
            onOperationCompleted(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            Log.d("BleSensorManager", "onCharacteristicRead (API 33) chara=${characteristic.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                processCharacteristic(characteristic.uuid, value)
            }
            onOperationCompleted(gatt)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.d("BleSensorManager", "onCharacteristicRead (legacy) chara=${characteristic.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                processCharacteristic(characteristic.uuid, characteristic.value)
            }
            onOperationCompleted(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.d("BleSensorManager", "onCharacteristicChanged (API 33) chara=${characteristic.uuid}, len=${value.size}")
            processCharacteristic(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            Log.d("BleSensorManager", "onCharacteristicChanged (legacy) chara=${characteristic.uuid}, len=${characteristic.value?.size}")
            processCharacteristic(characteristic.uuid, characteristic.value)
        }

        private fun processCharacteristic(uuid: UUID, value: ByteArray?) {
            Log.d("BleSensorManager", "processCharacteristic uuid=$uuid, address=$address, bytes=${value?.joinToString(" ") { "%02X".format(it) }}")
            if (value == null || value.isEmpty()) return
            val now = System.currentTimeMillis()
            var updated = false

            when (uuid) {
                BleDataParsers.HEART_RATE_MEASUREMENT_UUID -> {
                    BleDataParsers.parseHeartRate(value)?.let { hr ->
                        Log.d("BleSensorManager", "Parsed Heart Rate for $address: $hr BPM")
                        sensorStates[address]?.let { current ->
                            sensorStates[address] = current.copy(
                                heartRateBpm = hr,
                                lastUpdatedMillis = now,
                            )
                            updated = true
                        }
                    }
                }
                BleDataParsers.CSC_MEASUREMENT_UUID -> {
                    BleDataParsers.parseCsc(value)?.let { csc ->
                        var cadence: Int? = null
                        var speed: Double? = null

                        csc.crank?.let { crank ->
                            cadence = BleDataParsers.computeCadenceRpm(lastCrankReadings[address], crank)
                            lastCrankReadings[address] = crank
                        }
                        csc.wheel?.let { wheel ->
                            speed = BleDataParsers.computeWheelSpeedMps(lastWheelReadings[address], wheel)
                            lastWheelReadings[address] = wheel
                        }

                        sensorStates[address]?.let { current ->
                            sensorStates[address] = current.copy(
                                cadenceRpm = cadence ?: current.cadenceRpm,
                                sensorSpeedMps = speed ?: current.sensorSpeedMps,
                                lastUpdatedMillis = now,
                            )
                            updated = true
                        }
                    }
                }
                BleDataParsers.POWER_MEASUREMENT_UUID -> {
                    BleDataParsers.parsePower(value)?.let { power ->
                        var cadence: Int? = null
                        power.crank?.let { crank ->
                            cadence = BleDataParsers.computeCadenceRpm(lastCrankReadings[address], crank)
                            lastCrankReadings[address] = crank
                        }

                        sensorStates[address]?.let { current ->
                            sensorStates[address] = current.copy(
                                powerWatts = power.instantaneousPowerWatts,
                                cadenceRpm = cadence ?: current.cadenceRpm,
                                lastUpdatedMillis = now,
                            )
                            updated = true
                        }
                    }
                }
                BleDataParsers.BATTERY_LEVEL_UUID -> {
                    BleDataParsers.parseBatteryLevel(value)?.let { battery ->
                        Log.d("BleSensorManager", "Parsed Battery Level for $address: $battery%")
                        sensorStates[address]?.let { current ->
                            sensorStates[address] = current.copy(batteryLevelPercent = battery)
                            updated = true
                        }
                    }
                }
            }

            if (updated) {
                updateTelemetryState()
            }
        }
    }

    private fun scheduleReconnect(address: String) {
        cancelReconnect(address)
        val job = Runnable {
            reconnectJobs.remove(address)
            if (address in desiredConnections && activeGatts[address] == null) connect(address)
        }
        reconnectJobs[address] = job
        reconnectHandler.postDelayed(job, RECONNECT_DELAY_MILLIS)
    }

    private fun cancelReconnect(address: String) {
        reconnectJobs.remove(address)?.let(reconnectHandler::removeCallbacks)
    }

    private companion object {
        const val PREFS_NAME = "cyclocomp_sensors_prefs"
        const val KEY_SAVED_SENSORS = "saved_sensor_addresses"
        const val RECONNECT_DELAY_MILLIS = 1_500L
    }
}
