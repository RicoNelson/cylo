package com.riconelson.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riconelson.cyclocomp.core.navigation.GeoPoint
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.platform.storage.SavedRoute
import com.riconelson.cyclocomp.ui.map.MapViewComposable

@Composable
fun RoutesScreen(
    activeRoute: GpxRoute?,
    savedRoutes: List<SavedRoute>,
    currentLocation: GeoPoint?,
    errorMessage: String?,
    isRouting: Boolean,
    onImport: () -> Unit,
    onLoadSample: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onDeselectActiveRoute: () -> Unit,
    onUpdateRouteName: (id: String, newName: String) -> Unit,
    onDeleteRoute: (id: String) -> Unit,
    onDestinationLongPress: (GeoPoint) -> Unit,
) {
    var editingRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var editingNameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title & Actions Header
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Routes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Save multiple routes, rename them, and select an active route for turn-by-turn navigation.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import GPX")
                }
                OutlinedButton(
                    onClick = onLoadSample,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Sample Route")
                }
            }
        }

        // Active Route Card (if chosen)
        if (activeRoute != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "ACTIVE ROUTE FOR NAVIGATION",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }

                    Text(
                        activeRoute.name ?: "Active Route",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    val totalKm = activeRoute.totalDistanceMeters / 1000.0
                    val durationText = activeRoute.estimatedDurationSeconds?.let { seconds ->
                        val minutes = (seconds / 60).toInt()
                        if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
                    }
                    Text(
                        listOfNotNull("%.2f km".format(totalKm), durationText, "${activeRoute.turnCues.size} turn cues").joinToString(" · "),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // Map Preview
                    MapViewComposable(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        currentLocation = currentLocation ?: activeRoute.points.firstOrNull(),
                        activeRoute = activeRoute,
                        showControls = false,
                        showTurnGuidance = false,
                        onDestinationLongPress = onDestinationLongPress,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = onDeselectActiveRoute,
                        ) {
                            Text("Deselect Active Route")
                        }
                    }
                }
            }
        }



        // Saved Routes List
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Saved Routes (${savedRoutes.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (savedRoutes.isEmpty()) {
                Text(
                    "No saved routes yet. Import a GPX file or long-press the map to create one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                savedRoutes.forEach { savedRoute ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (savedRoute.isActive) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    savedRoute.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (savedRoute.isActive) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text(
                                            "ACTIVE",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }

                            val km = savedRoute.route.totalDistanceMeters / 1000.0
                            val durationText = savedRoute.route.estimatedDurationSeconds?.let { sec ->
                                val min = (sec / 60).toInt()
                                if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
                            }
                            Text(
                                listOfNotNull("%.2f km".format(km), durationText, "${savedRoute.route.turnCues.size} cues").joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (savedRoute.isActive) {
                                    Button(
                                        onClick = { onDeselectActiveRoute() },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Active")
                                    }
                                } else {
                                    Button(
                                        onClick = { onSelectRoute(savedRoute.id) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Select")
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        editingRoute = savedRoute
                                        editingNameText = savedRoute.name
                                    },
                                ) {
                                    Text("Rename")
                                }

                                OutlinedButton(
                                    onClick = { onDeleteRoute(savedRoute.id) },
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Rename Dialog
    if (editingRoute != null) {
        AlertDialog(
            onDismissRequest = { editingRoute = null },
            title = { Text("Rename Route") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new name for this route:")
                    OutlinedTextField(
                        value = editingNameText,
                        onValueChange = { editingNameText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Route Name") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = editingNameText.trim()
                        if (name.isNotEmpty()) {
                            onUpdateRouteName(editingRoute!!.id, name)
                        }
                        editingRoute = null
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingRoute = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
