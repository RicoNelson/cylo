package com.riconelson.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riconelson.cyclocomp.platform.sensors.ConnectedSensor
import com.riconelson.cyclocomp.platform.sensors.DiscoveredSensor
import com.riconelson.cyclocomp.platform.sensors.LiveSensorTelemetry
import com.riconelson.cyclocomp.platform.sensors.SensorConnectionState
import com.riconelson.cyclocomp.platform.sensors.SensorType

@Composable
fun DevicesScreen(
    sensors: List<DiscoveredSensor>,
    connectedSensors: List<ConnectedSensor>,
    isScanning: Boolean,
    telemetry: LiveSensorTelemetry,
    onScan: () -> Unit,
    onConnect: (DiscoveredSensor) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Devices",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (isScanning) "Searching for nearby sensors…" else "BLE sensors · Location required on Android 11",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onScan,
                    enabled = !isScanning,
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(if (isScanning) "Scanning…" else "Scan")
                }
            }
        }

        // Live Telemetry Card if sensors connected
        if (connectedSensors.isNotEmpty()) {
            item {
                LiveSensorMetrics(telemetry)
            }

            item {
                Text(
                    "Connected Sensors (${connectedSensors.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            items(connectedSensors, key = { "connected_${it.address}" }) { sensor ->
                ConnectedSensorCard(
                    sensor = sensor,
                    onDisconnect = { onDisconnect(sensor.address) },
                )
            }
        }

        // Discovered Sensors Section
        item {
            Text(
                "Discovered Sensors (${sensors.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (sensors.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        if (isScanning) "Keep your sensor awake and nearby…" else "No sensor found yet. Tap Scan to discover nearby BLE sensors.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(sensors, key = { "discovered_${it.id}" }) { sensor ->
                val isConnected = connectedSensors.any { it.address == sensor.id }
                DiscoveredSensorCard(
                    sensor = sensor,
                    isConnected = isConnected,
                    onConnect = { onConnect(sensor) },
                    onDisconnect = { onDisconnect(sensor.id) },
                )
            }
        }
    }
}

@Composable
private fun LiveSensorMetrics(telemetry: LiveSensorTelemetry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Live Telemetry",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LiveMetric("Heart Rate", telemetry.heartRateBpm?.toString() ?: "--", "BPM", Modifier.weight(1f))
                LiveMetric("Cadence", telemetry.cadenceRpm?.toString() ?: "--", "RPM", Modifier.weight(1f))
                LiveMetric("Power", telemetry.powerWatts?.toString() ?: "--", "W", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LiveMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ConnectedSensorCard(
    sensor: ConnectedSensor,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    sensor.name ?: "Cycling Sensor",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    statusLabel(sensor.connectionState, sensor.batteryLevelPercent),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
                val telemetryStr = sensorSummary(sensor)
                if (telemetryStr.isNotBlank()) {
                    Text(
                        telemetryStr,
                        color = Color(0xFF8DE8AF),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            OutlinedButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun DiscoveredSensorCard(
    sensor: DiscoveredSensor,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    sensor.name ?: "Cycling sensor",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    sensor.types.sortedBy(SensorType::ordinal).joinToString(" · ") { it.label() },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Signal ${sensor.rssi} dBm",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (isConnected) {
                OutlinedButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Connect")
                }
            }
        }
    }
}

private fun statusLabel(state: SensorConnectionState, batteryPercent: Int?): String = when (state) {
    SensorConnectionState.CONNECTED -> "Connected" + (batteryPercent?.let { " · Battery $it%" } ?: "")
    SensorConnectionState.CONNECTING -> "Connecting…"
    SensorConnectionState.DISCONNECTED -> "Disconnected"
}

private fun sensorSummary(sensor: ConnectedSensor): String = buildList {
    sensor.heartRateBpm?.let { add("$it BPM") }
    sensor.cadenceRpm?.let { add("$it RPM") }
    sensor.powerWatts?.let { add("${it}W") }
}.joinToString(" · ")

private fun SensorType.label(): String = when (this) {
    SensorType.HEART_RATE -> "Heart rate"
    SensorType.SPEED_CADENCE -> "Speed & cadence"
    SensorType.POWER -> "Power"
}
