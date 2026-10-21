package com.riconelson.cyclocomp.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riconelson.cyclocomp.core.navigation.GeoPoint
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.core.navigation.NavigationStatus
import com.riconelson.cyclocomp.ui.LiveRideUiState
import com.riconelson.cyclocomp.ui.navigation.NavigationGuidanceCard

@Composable
fun MapScreen(
    state: LiveRideUiState,
    currentLocation: GeoPoint?,
    recordedTrack: List<GeoPoint>,
    activeRoute: GpxRoute?,
    navigationStatus: NavigationStatus?,
    bearingDegrees: Float? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        // Base Map Canvas
        MapViewComposable(
            modifier = Modifier.fillMaxSize(),
            currentLocation = currentLocation,
            recordedPoints = recordedTrack,
            activeRoute = activeRoute,
            navigationStatus = navigationStatus,
            speedKmh = state.speedKmh,
            bearingDegrees = bearingDegrees,
            isRideStarted = (state.status == com.riconelson.cyclocomp.core.ride.RideStatus.RECORDING || state.status == com.riconelson.cyclocomp.core.ride.RideStatus.PAUSED),
            showControls = true,
            showTurnGuidance = false,
        )

        // Top Overlay: Single Cohesive HUD (Turn Navigation + Telemetry)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Turn Guidance Cue (if active GPX course loaded)
            if (activeRoute != null && navigationStatus != null) {
                NavigationGuidanceCard(
                    activeRoute = activeRoute,
                    navigationStatus = navigationStatus,
                )
            }

            // Live Telemetry HUD Bar
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Speed Metric
                    Column {
                        Text(
                            "SPEED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.1f".format(state.speedKmh),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "km/h",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Distance Metric
                    Column {
                        Text(
                            "DISTANCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.2f".format(state.distanceKm),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "km",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Time Metric
                    Column {
                        Text(
                            "TIME",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                        Text(
                            formatDuration(state.elapsedSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Heart Rate if available
                    if (state.heartRateBpm != null) {
                        Column {
                            Text(
                                "HR",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    state.heartRateBpm.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "bpm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%02d:%02d".format(minutes, remainingSeconds)
}
