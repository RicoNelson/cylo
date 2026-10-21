package com.riconelson.cyclocomp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riconelson.cyclocomp.platform.storage.DetailedRideData
import com.riconelson.cyclocomp.platform.storage.RideFileStore
import com.riconelson.cyclocomp.platform.storage.RideSplitInterval
import com.riconelson.cyclocomp.platform.storage.SavedRideFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    rides: List<SavedRideFile>,
    onRefresh: () -> Unit,
    onDeleteRide: (SavedRideFile) -> Unit = {},
    onDeleteAllRides: () -> Unit = {},
    onShareRide: (SavedRideFile) -> Unit = {},
) {
    val context = LocalContext.current
    var selectedRideFile by remember { mutableStateOf<SavedRideFile?>(null) }
    var detailedRideData by remember { mutableStateOf<DetailedRideData?>(null) }
    var rideToDelete by remember { mutableStateOf<SavedRideFile?>(null) }
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(selectedRideFile) {
        val ride = selectedRideFile
        if (ride != null) {
            detailedRideData = withContext(Dispatchers.IO) {
                RideFileStore(context).readRideDetails(ride.file)
            }
        } else {
            detailedRideData = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "History",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (rides.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showDeleteAllConfirmation = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete All")
                }
            }
            Button(onClick = onRefresh) { Text("Refresh") }
        }

        if (rides.isEmpty()) {
            Spacer(Modifier.weight(1f))
            Text(
                "Completed rides are saved privately on this phone as GPX files.",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rides, key = { it.file.absolutePath }) { ride ->
                    SavedRideCard(
                        ride = ride,
                        onClick = { selectedRideFile = ride },
                        onDelete = { rideToDelete = ride },
                        onShare = { onShareRide(ride) },
                    )
                }
            }
        }
    }

    selectedRideFile?.let { ride ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedRideFile = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            RideDetailView(
                rideFile = ride,
                details = detailedRideData,
                onClose = { selectedRideFile = null },
                onDelete = { rideToDelete = ride },
                onShare = { onShareRide(ride) },
            )
        }
    }

    rideToDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { rideToDelete = null },
            title = { Text("Delete Ride") },
            text = {
                val formattedDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(ride.recordedAtMillis))
                Text("Are you sure you want to delete the ride recorded on $formattedDate? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedRideFile == ride) {
                            selectedRideFile = null
                        }
                        onDeleteRide(ride)
                        rideToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { rideToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmation = false },
            title = { Text("Delete All History") },
            text = {
                Text("Are you sure you want to delete all ${rides.size} saved rides? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedRideFile = null
                        onDeleteAllRides()
                        showDeleteAllConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteAllConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SavedRideCard(
    ride: SavedRideFile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(ride.recordedAtMillis)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text("Share GPX", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete", fontSize = 12.sp)
                    }
                }
            }

            // Primary Metrics Grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricSummaryItem(
                        label = "Distance",
                        value = ride.distanceMeters?.let { "%.2f km".format(it / 1_000.0) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricSummaryItem(
                        label = "Elapsed",
                        value = ride.elapsedTimeMillis?.let(::formatDuration) ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricSummaryItem(
                        label = "Moving",
                        value = ride.movingTimeMillis?.let(::formatDuration) ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricSummaryItem(
                        label = "Avg Speed",
                        value = ride.averageSpeedMetersPerSecond?.let { "%.1f km/h".format(it * 3.6) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricSummaryItem(
                        label = "Max Speed",
                        value = ride.maxSpeedMetersPerSecond?.let { "%.1f km/h".format(it * 3.6) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Optional sensor telemetry tags row
            val sensorBadges = buildList {
                ride.avgHeartRateBpm?.let { add("♥ $it avg (${ride.maxHeartRateBpm ?: it} max) bpm") }
                ride.avgCadenceRpm?.let { add("⟳ $it avg rpm") }
                ride.avgPowerWatts?.let { add("⚡ $it avg W") }
                ride.elevationGainMeters?.let { add("▲ +${it.toInt()}m") }
            }
            if (sensorBadges.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sensorBadges.forEach { badge ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricSummaryItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RideDetailView(
    rideFile: SavedRideFile,
    details: DetailedRideData?,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Title bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Ride Summary",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT)
                        .format(Date(rideFile.recordedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
                OutlinedButton(onClick = onShare) {
                    Text("Share GPX")
                }
                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }

        HorizontalDivider()

        TabRow(selectedTabIndex = pagerState.currentPage) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
            ) {
                Text("All Metrics", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
            }
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
            ) {
                Text("Splits (${details?.splits?.size ?: 0})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
            }
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
            ) {
                Text("Timeline Chart", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (page) {
                0 -> MetricsOverviewTab(rideFile)
                1 -> SplitsTab(details?.splits.orEmpty())
                2 -> ChartTab(details)
            }
        }
    }
}

@Composable
private fun MetricsOverviewTab(ride: SavedRideFile) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Speed & Distance Highlight Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("DISTANCE", style = MaterialTheme.typography.labelSmall)
                        Text(
                            ride.distanceMeters?.let { "%.2f km".format(it / 1000.0) } ?: "0.00 km",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ELAPSED", style = MaterialTheme.typography.labelSmall)
                        Text(
                            ride.elapsedTimeMillis?.let(::formatDuration) ?: "00:00",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("MOVING", style = MaterialTheme.typography.labelSmall)
                        Text(
                            ride.movingTimeMillis?.let(::formatDuration) ?: "00:00",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        item {
            MetricCategoryCard(title = "⏱ Speed & Time") {
                MetricRow("Moving Time", ride.movingTimeMillis?.let(::formatDuration) ?: "—")
                MetricRow("Total Elapsed Time", ride.elapsedTimeMillis?.let(::formatDuration) ?: "—")
                MetricRow(
                    "Average Speed",
                    ride.averageSpeedMetersPerSecond?.let { "%.1f km/h".format(it * 3.6) } ?: "—",
                )
                MetricRow(
                    "Max Speed",
                    ride.maxSpeedMetersPerSecond?.let { "%.1f km/h".format(it * 3.6) } ?: "—",
                )
            }
        }

        if (ride.avgHeartRateBpm != null || ride.maxHeartRateBpm != null) {
            item {
                MetricCategoryCard(title = "♥ Heart Rate") {
                    MetricRow("Average Heart Rate", ride.avgHeartRateBpm?.let { "$it bpm" } ?: "—")
                    MetricRow("Max Heart Rate", ride.maxHeartRateBpm?.let { "$it bpm" } ?: "—")
                }
            }
        }

        if (ride.avgCadenceRpm != null || ride.maxCadenceRpm != null) {
            item {
                MetricCategoryCard(title = "⟳ Cadence") {
                    MetricRow("Average Cadence", ride.avgCadenceRpm?.let { "$it rpm" } ?: "—")
                    MetricRow("Max Cadence", ride.maxCadenceRpm?.let { "$it rpm" } ?: "—")
                }
            }
        }

        if (ride.avgPowerWatts != null || ride.maxPowerWatts != null) {
            item {
                MetricCategoryCard(title = "⚡ Power") {
                    MetricRow("Average Power", ride.avgPowerWatts?.let { "$it W" } ?: "—")
                    MetricRow("Max Power", ride.maxPowerWatts?.let { "$it W" } ?: "—")
                }
            }
        }

        if (ride.elevationGainMeters != null || ride.maxElevationMeters != null) {
            item {
                MetricCategoryCard(title = "⛰ Elevation") {
                    MetricRow("Elevation Gain", ride.elevationGainMeters?.let { "+${it.toInt()} m" } ?: "—")
                    MetricRow("Max Elevation", ride.maxElevationMeters?.let { "${it.toInt()} m" } ?: "—")
                    MetricRow("Min Elevation", ride.minElevationMeters?.let { "${it.toInt()} m" } ?: "—")
                }
            }
        }
    }
}

@Composable
private fun MetricCategoryCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SplitsTab(splits: List<RideSplitInterval>) {
    if (splits.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No split data available for this ride.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(splits) { split ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Split #${split.splitNumber} (${split.distanceKmLabel})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Pace: %.1f km/h · Duration: %s".format(
                                    split.averageSpeedMps * 3.6,
                                    formatDuration(split.durationMillis),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            split.avgHeartRateBpm?.let {
                                Text("♥ $it bpm", style = MaterialTheme.typography.bodySmall)
                            }
                            split.avgCadenceRpm?.let {
                                Text("⟳ $it rpm", style = MaterialTheme.typography.bodySmall)
                            }
                            split.avgPowerWatts?.let {
                                Text("⚡ $it W", style = MaterialTheme.typography.bodySmall)
                            }
                            if (split.elevationGainMeters > 0) {
                                Text("▲ +${split.elevationGainMeters.toInt()}m", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartTab(details: DetailedRideData?) {
    val points = details?.points.orEmpty()
    if (points.size < 2) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not enough track points to build chart timeline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val maxSpeed = points.mapNotNull { it.speedMetersPerSecond }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val speedLineColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Speed Progression over Time",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                val width = size.width
                val height = size.height

                val path = Path()
                val totalTime = (points.last().timestampMillis - points.first().timestampMillis).coerceAtLeast(1)

                points.forEachIndexed { index, pt ->
                    val x = ((pt.timestampMillis - points.first().timestampMillis).toFloat() / totalTime) * width
                    val speed = pt.speedMetersPerSecond ?: 0.0
                    val y = height - ((speed / maxSpeed).toFloat() * height)

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = speedLineColor,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("00:00", style = MaterialTheme.typography.labelSmall)
            Text(
                "Max: %.1f km/h".format(maxSpeed * 3.6),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                formatDuration(points.last().timestampMillis - points.first().timestampMillis),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
