package com.riconelson.cyclocomp.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import com.riconelson.cyclocomp.core.navigation.GeoPoint
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.core.navigation.NavigationStatus
import com.riconelson.cyclocomp.core.navigation.RouteNavigation
import com.riconelson.cyclocomp.core.navigation.toGpxXml
import com.riconelson.cyclocomp.core.ride.RidePoint
import com.riconelson.cyclocomp.core.ride.RideState
import com.riconelson.cyclocomp.core.ride.RideStatus
import com.riconelson.cyclocomp.platform.location.RideTrackingService
import com.riconelson.cyclocomp.platform.routing.BicycleRouter
import com.riconelson.cyclocomp.platform.sensors.BluetoothSensorScanner
import com.riconelson.cyclocomp.platform.sensors.ConnectedSensor
import com.riconelson.cyclocomp.platform.sensors.LiveSensorTelemetry
import com.riconelson.cyclocomp.platform.sensors.SensorConnectionState
import com.riconelson.cyclocomp.platform.sensors.SensorType
import com.riconelson.cyclocomp.platform.storage.RideFileStore
import com.riconelson.cyclocomp.platform.storage.SavedRideFile
import com.riconelson.cyclocomp.platform.storage.CourseFileStore
import com.riconelson.cyclocomp.platform.storage.SavedRoute
import com.riconelson.cyclocomp.ui.map.MapScreen
import com.riconelson.cyclocomp.ui.map.MapViewComposable
import com.riconelson.cyclocomp.ui.navigation.CourseNavigationPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val OutdoorColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    onPrimary = Color(0xFF10255E),
    primaryContainer = Color(0xFF29457E),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283044),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF252931),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

data class LiveRideUiState(
    val speedKmh: Double = 0.0,
    val distanceKm: Double = 0.0,
    val movingSeconds: Long = 0,
    val elapsedSeconds: Long = 0,
    val gpsAccuracyMeters: Float? = null,
    val status: RideStatus = RideStatus.IDLE,
    val saveError: String? = null,
    val heartRateBpm: Int? = null,
    val avgHeartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val avgCadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val avgPowerWatts: Int? = null,
    val connectedSensors: List<ConnectedSensor> = emptyList(),
)

private enum class RidePage(val label: String) {
    Map("Map"),
    Primary("Primary"),
    Sensors("Sensors"),
    Course("Course"),
}

private enum class AppDestination(val label: String, val symbol: String) {
    Ride("Ride", "●"),
    Routes("Routes", "⌖"),
    History("History", "◷"),
    Devices("Devices", "⌁"),
}

@Composable
fun CyclocompApp() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(AppDestination.Ride) }
    val service = rememberRideService()
    val sensorScanner = remember(applicationContext) { BluetoothSensorScanner(applicationContext) }
    val discoveredSensors by sensorScanner.sensors.collectAsState()
    val isScanning by sensorScanner.isScanning.collectAsState()

    val sensorTelemetry by (service?.sensorManager?.telemetry ?: remember { mutableStateOf(LiveSensorTelemetry()) }).let {
        if (service != null) service.sensorManager.telemetry.collectAsState() else remember { mutableStateOf(LiveSensorTelemetry()) }
    }

    var rideState by remember { mutableStateOf(RideState()) }
    var latestPoint by remember { mutableStateOf<RidePoint?>(null) }
    var recordedPoints by remember { mutableStateOf<List<RidePoint>>(emptyList()) }
    var activeRoute by remember { mutableStateOf<GpxRoute?>(null) }
    var savedRoutes by remember { mutableStateOf<List<SavedRoute>>(emptyList()) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var savedRides by remember { mutableStateOf<List<SavedRideFile>>(emptyList()) }

    val refreshRoutes = {
        scope.launch(Dispatchers.IO) {
            val store = CourseFileStore(context)
            val list = store.listRoutes()
            val active = store.getActiveRoute()
            withContext(Dispatchers.Main) {
                savedRoutes = list
                activeRoute = active
            }
        }
    }

    val hasFineLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)

    val startRide = {
        ContextCompat.startForegroundService(context, RideTrackingService.startIntent(context))
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRide()
    }
    val gpsPreviewPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) service?.startGpsPreview()
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) sensorScanner.start()
    }
    val triggerScan = remember(context, sensorScanner) {
        {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                sensorScanner.start()
            } else {
                bluetoothPermission.launch(permissions)
            }
        }
    }
    val gpxImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            CourseFileStore(context).saveRoute(reader.readText(), makeActive = false)
                        } ?: error("Cyclocomp could not read that file")
                    }
                }
                result.onSuccess {
                    refreshRoutes()
                    routeError = null
                }.onFailure { error ->
                    routeError = error.message ?: "That file is not a usable GPX route"
                }
            }
        }
    }

    LaunchedEffect(service) {
        if (service != null) {
            service.sensorManager.connectSavedSensors()
            launch { service.rideState.collect { rideState = it } }
            launch { service.latestPoint.collect { latestPoint = it } }
            launch { service.recordedPointsState.collect { recordedPoints = it } }
        }
    }
    LaunchedEffect(service, hasFineLocationPermission, isLocationEnabled) {
        when {
            service == null || !isLocationEnabled -> Unit
            hasFineLocationPermission -> service.startGpsPreview()
            else -> gpsPreviewPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    DisposableEffect(sensorScanner) {
        onDispose { sensorScanner.close() }
    }
    DisposableEffect(destination) {
        if (destination == AppDestination.Devices) {
            triggerScan()
        }
        onDispose {
            sensorScanner.stop()
        }
    }
    LaunchedEffect(destination) {
        if (destination == AppDestination.History) savedRides = RideFileStore(context).listRideFiles()
        if (destination == AppDestination.Routes) refreshRoutes()
    }
    LaunchedEffect(Unit) {
        refreshRoutes()
    }

    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rideState.status) {
        while (rideState.status == RideStatus.RECORDING) {
            kotlinx.coroutines.delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val currentPoint = rideState.lastAcceptedPoint ?: latestPoint
    val currentLocation = currentPoint?.let { GeoPoint(it.latitude, it.longitude) }
    val recordedTrack = recordedPoints.map { GeoPoint(it.latitude, it.longitude) }
    val requestRoute: (GeoPoint) -> Unit = remember(currentLocation, scope) {
        { destination ->
            val origin = currentLocation
            if (origin == null) {
                routeError = "Waiting for a GPS fix. Keep the map open briefly, then choose the destination again."
            } else if (!isRouting) {
                isRouting = true
                routeError = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { BicycleRouter.route(origin, destination) }
                    }
                    result.onSuccess { route ->
                        withContext(Dispatchers.IO) {
                            val gpxXml = route.toGpxXml("Route to ${"%.3f".format(destination.latitude)}, ${"%.3f".format(destination.longitude)}")
                            CourseFileStore(context).saveRoute(gpxXml, makeActive = false)
                        }
                        refreshRoutes()
                    }.onFailure { error ->
                        routeError = error.message ?: "Could not calculate a bicycle route"
                    }
                    isRouting = false
                }
            }
        }
    }

    val navigationStatus = activeRoute?.let { route ->
        currentPoint?.let { point ->
            RouteNavigation.evaluate(route, GeoPoint(point.latitude, point.longitude))
        }
    }

    MaterialTheme(colorScheme = OutdoorColorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (destination) {
                        AppDestination.Ride -> RideScreen(
                            state = rideState.toUiState(sensorTelemetry, latestPoint, nowMillis),
                            hasLocationPermission = hasFineLocationPermission,
                            isLocationEnabled = isLocationEnabled,
                            routeName = activeRoute?.name,
                            navigationStatus = navigationStatus,
                            currentLocation = currentLocation,
                            recordedTrack = recordedTrack,
                            activeRoute = activeRoute,
                            bearingDegrees = currentPoint?.bearingDegrees,
                            onStart = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    startRide()
                                } else {
                                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            onPause = { context.startService(RideTrackingService.pauseIntent(context)) },
                            onResume = { ContextCompat.startForegroundService(context, RideTrackingService.resumeIntent(context)) },
                            onEnd = { context.startService(RideTrackingService.stopIntent(context)) },
                            onRetrySave = { context.startService(RideTrackingService.retrySaveIntent(context)) },
                        )
                        AppDestination.Routes -> RoutesScreen(
                            activeRoute = activeRoute,
                            savedRoutes = savedRoutes,
                            currentLocation = currentLocation,
                            errorMessage = routeError,
                            isRouting = isRouting,
                            onImport = {
                                gpxImport.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
                            },
                            onLoadSample = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { CourseFileStore(context).resetToSeedRoute() }
                                    refreshRoutes()
                                    routeError = null
                                }
                            },
                            onSelectRoute = { id ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { CourseFileStore(context).setActiveRoute(id) }
                                    refreshRoutes()
                                    routeError = null
                                }
                            },
                            onDeselectActiveRoute = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { CourseFileStore(context).setActiveRoute(null) }
                                    refreshRoutes()
                                    routeError = null
                                }
                            },
                            onUpdateRouteName = { id, newName ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { CourseFileStore(context).updateRouteName(id, newName) }
                                    refreshRoutes()
                                    routeError = null
                                }
                            },
                            onDeleteRoute = { id ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { CourseFileStore(context).deleteRoute(id) }
                                    refreshRoutes()
                                    routeError = null
                                }
                            },
                            onDestinationLongPress = requestRoute,
                        )
                        AppDestination.History -> HistoryScreen(
                            rides = savedRides,
                            onRefresh = { savedRides = RideFileStore(context).listRideFiles() },
                            onDeleteRide = { ride ->
                                RideFileStore(context).deleteRideFile(ride.file)
                                savedRides = RideFileStore(context).listRideFiles()
                            },
                            onDeleteAllRides = {
                                RideFileStore(context).deleteAllRideFiles()
                                savedRides = RideFileStore(context).listRideFiles()
                            },
                            onShareRide = { ride -> shareGpxFile(context, ride) },
                        )
                        AppDestination.Devices -> DevicesScreen(
                            sensors = discoveredSensors,
                            connectedSensors = sensorTelemetry.connectedSensors,
                            isScanning = isScanning,
                            telemetry = sensorTelemetry,
                            onScan = triggerScan,
                            onConnect = { sensor ->
                                service?.sensorManager?.connect(sensor.id, sensor.types, sensor.name)
                            },
                            onDisconnect = { address ->
                                service?.sensorManager?.removeSensorAddress(address)
                            },
                        )
                    }
                }

                NavigationBar {
                    AppDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { destination = item },
                            icon = { Text(item.symbol, fontSize = 20.sp) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberRideService(): RideTrackingService? {
    val context = LocalContext.current
    var service by remember { mutableStateOf<RideTrackingService?>(null) }
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as RideTrackingService.LocalBinder).service()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
    }
    DisposableEffect(context, connection) {
        val isBound = context.bindService(Intent(context, RideTrackingService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose { if (isBound) context.unbindService(connection) }
    }
    return service
}

private fun RideState.toUiState(
    telemetry: LiveSensorTelemetry,
    latestPoint: RidePoint?,
    nowMillis: Long = System.currentTimeMillis(),
): LiveRideUiState {
    val elapsedMs = when (status) {
        RideStatus.RECORDING -> startedAtMillis?.let { (nowMillis - it - manualPausedDurationMillis).coerceAtLeast(0) } ?: metrics.elapsedTimeMillis
        RideStatus.PAUSED -> startedAtMillis?.let { ((pausedAtMillis ?: nowMillis) - it - manualPausedDurationMillis).coerceAtLeast(0) } ?: metrics.elapsedTimeMillis
        RideStatus.FINISHED, RideStatus.SAVING, RideStatus.SAVE_FAILED -> metrics.elapsedTimeMillis
        RideStatus.IDLE -> 0L
    }
    return LiveRideUiState(
        speedKmh = metrics.currentSpeedMetersPerSecond * 3.6,
        distanceKm = metrics.distanceMeters / 1_000.0,
        movingSeconds = metrics.movingTimeMillis / 1_000,
        elapsedSeconds = elapsedMs / 1_000,
        gpsAccuracyMeters = lastAcceptedPoint?.accuracyMeters ?: latestPoint?.accuracyMeters,
        status = status,
        saveError = saveError,
        heartRateBpm = telemetry.heartRateBpm ?: metrics.currentHeartRateBpm,
        avgHeartRateBpm = metrics.avgHeartRateBpm,
        cadenceRpm = telemetry.cadenceRpm ?: metrics.currentCadenceRpm,
        avgCadenceRpm = metrics.avgCadenceRpm,
        powerWatts = telemetry.powerWatts ?: metrics.currentPowerWatts,
        avgPowerWatts = metrics.avgPowerWatts,
        connectedSensors = telemetry.connectedSensors,
    )
}

@Composable
fun RideScreen(
    state: LiveRideUiState,
    hasLocationPermission: Boolean,
    isLocationEnabled: Boolean,
    routeName: String?,
    navigationStatus: NavigationStatus?,
    currentLocation: GeoPoint?,
    recordedTrack: List<GeoPoint>,
    activeRoute: GpxRoute?,
    bearingDegrees: Float? = null,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onRetrySave: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = RidePage.Primary.ordinal,
        pageCount = { RidePage.entries.size },
    )
    val scope = rememberCoroutineScope()
    var confirmFinish by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Ride", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    rideStatusLabel(state.status),
                    color = rideStatusColor(state.status),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = gpsLabel(state.gpsAccuracyMeters, hasLocationPermission, isLocationEnabled),
                color = when {
                    !hasLocationPermission || !isLocationEnabled -> MaterialTheme.colorScheme.error
                    state.gpsAccuracyMeters == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.gpsAccuracyMeters <= 10f -> Color(0xFF8DE8AF)
                    else -> Color(0xFFFFD54F)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }

        TabRow(selectedTabIndex = pagerState.currentPage) {
            RidePage.entries.forEachIndexed { index, page ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(page.label) },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            when (RidePage.entries[page]) {
                RidePage.Map -> MapScreen(
                    state = state,
                    currentLocation = currentLocation,
                    recordedTrack = recordedTrack,
                    activeRoute = activeRoute,
                    navigationStatus = navigationStatus,
                    bearingDegrees = bearingDegrees,
                )
                RidePage.Primary -> PrimaryMetricsPage(
                    state = state,
                    currentLocation = currentLocation,
                    recordedTrack = recordedTrack,
                    activeRoute = activeRoute,
                    navigationStatus = navigationStatus,
                    onOpenMap = {
                        scope.launch { pagerState.animateScrollToPage(RidePage.Map.ordinal) }
                    },
                )
                RidePage.Sensors -> SensorMetricsPage(state)
                RidePage.Course -> CourseNavigationPage(
                    activeRoute = activeRoute,
                    navigationStatus = navigationStatus,
                    onOpenMap = {
                        scope.launch { pagerState.animateScrollToPage(RidePage.Map.ordinal) }
                    },
                )
            }
        }

        when (state.status) {
            RideStatus.IDLE, RideStatus.FINISHED -> RideButton("Start ride", onStart)
            RideStatus.PAUSED -> {
                RideButton("Resume ride", onResume)
                EndButton { confirmFinish = true }
            }
            RideStatus.RECORDING -> {
                RideButton("Pause ride", onPause, primary = false)
                EndButton { confirmFinish = true }
            }
            RideStatus.SAVING -> Text(
                "Saving ride…",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RideStatus.SAVE_FAILED -> {
                Text(
                    state.saveError ?: "Cyclocomp could not save this ride.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                RideButton("Retry saving", onRetrySave)
            }
        }
        if (confirmFinish) {
            AlertDialog(
                onDismissRequest = { confirmFinish = false },
                title = { Text("Finish ride?") },
                text = { Text("${"%.2f".format(state.distanceKm)} km · ${formatDuration(state.elapsedSeconds)} elapsed (${formatDuration(state.movingSeconds)} moving)") },
                confirmButton = { Button(onClick = { confirmFinish = false; onEnd() }) { Text("Finish & save") } },
                dismissButton = { Button(onClick = { confirmFinish = false }) { Text("Keep riding") } },
            )
        }
    }
}

@Composable
private fun PrimaryMetricsPage(
    state: LiveRideUiState,
    currentLocation: GeoPoint?,
    recordedTrack: List<GeoPoint>,
    activeRoute: GpxRoute?,
    navigationStatus: NavigationStatus?,
    bearingDegrees: Float? = null,
    onOpenMap: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RideMetric(
            label = "Speed",
            value = "%.1f".format(state.speedKmh),
            unit = "km/h",
            emphasized = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RideMetric(
                label = "Distance",
                value = "%.2f".format(state.distanceKm),
                unit = "km",
                modifier = Modifier.weight(1f),
            )
            RideMetric(
                label = "Stopwatch",
                value = formatDuration(state.elapsedSeconds),
                unit = "elapsed",
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RideMetric(
                label = "Moving time",
                value = formatDuration(state.movingSeconds),
                unit = "moving",
                modifier = Modifier.weight(1f),
            )
            RideMetric(
                label = "Heart Rate",
                value = state.heartRateBpm?.toString() ?: "--",
                unit = "BPM",
                modifier = Modifier.weight(1f),
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clickable { onOpenMap() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MapViewComposable(
                    currentLocation = currentLocation,
                    recordedPoints = recordedTrack,
                    activeRoute = activeRoute,
                    navigationStatus = navigationStatus,
                    speedKmh = state.speedKmh,
                    bearingDegrees = bearingDegrees,
                    isRideStarted = (state.status == RideStatus.RECORDING || state.status == RideStatus.PAUSED),
                    showControls = false,
                    showTurnGuidance = false,
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "Tap for Map ⤢",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (state.cadenceRpm != null || state.powerWatts != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.cadenceRpm != null) {
                    RideMetric(
                        label = "Cadence",
                        value = "${state.cadenceRpm}",
                        unit = "RPM",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.powerWatts != null) {
                    RideMetric(
                        label = "Power",
                        value = "${state.powerWatts}",
                        unit = "W",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorMetricsPage(state: LiveRideUiState) {
    val connected = state.connectedSensors.filter { it.connectionState == SensorConnectionState.CONNECTED }

    val hrSensors = connected.filter { SensorType.HEART_RATE in it.types || it.heartRateBpm != null }
    val cadSensors = connected.filter { SensorType.SPEED_CADENCE in it.types || it.cadenceRpm != null }
    val pwrSensors = connected.filter { SensorType.POWER in it.types || it.powerWatts != null }

    val hrItems = if (hrSensors.size > 1) {
        hrSensors.map { sensor ->
            SensorMetricItem(
                label = "Heart Rate (${sensor.name ?: "Sensor"})",
                value = sensor.heartRateBpm?.toString() ?: "--",
                unit = "BPM",
            )
        }
    } else {
        val single = hrSensors.firstOrNull()
        val nameSuffix = if (!single?.name.isNullOrBlank()) " (${single.name})" else ""
        listOf(
            SensorMetricItem(
                label = "Heart Rate$nameSuffix",
                value = single?.heartRateBpm?.toString() ?: state.heartRateBpm?.toString() ?: "--",
                unit = "BPM",
            )
        )
    }

    val cadItems = if (cadSensors.size > 1) {
        cadSensors.map { sensor ->
            SensorMetricItem(
                label = "Cadence (${sensor.name ?: "Sensor"})",
                value = sensor.cadenceRpm?.toString() ?: "--",
                unit = "RPM",
            )
        }
    } else {
        val single = cadSensors.firstOrNull()
        val nameSuffix = if (!single?.name.isNullOrBlank()) " (${single.name})" else ""
        listOf(
            SensorMetricItem(
                label = "Cadence$nameSuffix",
                value = single?.cadenceRpm?.toString() ?: state.cadenceRpm?.toString() ?: "--",
                unit = "RPM",
            )
        )
    }

    val pwrItems = if (pwrSensors.size > 1) {
        pwrSensors.map { sensor ->
            SensorMetricItem(
                label = "Power (${sensor.name ?: "Sensor"})",
                value = sensor.powerWatts?.toString() ?: "--",
                unit = "Watts",
            )
        }
    } else {
        val single = pwrSensors.firstOrNull()
        val nameSuffix = if (!single?.name.isNullOrBlank()) " (${single.name})" else ""
        listOf(
            SensorMetricItem(
                label = "Power$nameSuffix",
                value = single?.powerWatts?.toString() ?: state.powerWatts?.toString() ?: "--",
                unit = "Watts",
            )
        )
    }

    val allItems = hrItems + cadItems + pwrItems

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Live sensor data",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Available before and during a ride. Averages are recorded only after you start.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        allItems.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { item ->
                    RideMetric(
                        label = item.label,
                        value = item.value,
                        unit = item.unit,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class SensorMetricItem(
    val label: String,
    val value: String,
    val unit: String,
)

@Composable
private fun RideMetric(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = if (emphasized) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun RideButton(label: String, onClick: () -> Unit, primary: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text("End ride", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun gpsLabel(
    accuracyMeters: Float?,
    hasLocationPermission: Boolean,
    isLocationEnabled: Boolean,
): String = when {
    !hasLocationPermission -> "Location permission required"
    !isLocationEnabled -> "System Location disabled"
    accuracyMeters == null -> "Waiting for GPS"
    accuracyMeters <= 10f -> "GPS ready · ±${accuracyMeters.toInt()} m"
    else -> "GPS weak · ±${accuracyMeters.toInt()} m"
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%02d:%02d".format(minutes, remainingSeconds)
}

private fun rideStatusLabel(status: RideStatus): String = when (status) {
    RideStatus.IDLE -> "Ready"
    RideStatus.RECORDING -> "Recording"
    RideStatus.PAUSED -> "Manually paused"
    RideStatus.SAVING -> "Saving"
    RideStatus.SAVE_FAILED -> "Save failed"
    RideStatus.FINISHED -> "Saved"
}

@Composable
private fun rideStatusColor(status: RideStatus): Color = when (status) {
    RideStatus.RECORDING -> Color(0xFF8DE8AF)
    RideStatus.SAVE_FAILED -> MaterialTheme.colorScheme.error
    RideStatus.PAUSED, RideStatus.SAVING -> Color(0xFFFFD54F)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun shareGpxFile(context: Context, ride: SavedRideFile) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ride.file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share GPX Ride"))
    }
}
