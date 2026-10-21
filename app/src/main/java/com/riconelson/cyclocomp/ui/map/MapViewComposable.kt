@file:Suppress("DEPRECATION")

package com.riconelson.cyclocomp.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.preference.PreferenceManager
import android.view.MotionEvent
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riconelson.cyclocomp.core.navigation.GeoPoint
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.core.navigation.NavigationStatus
import com.riconelson.cyclocomp.platform.sensors.OrientationSensorManager
import com.riconelson.cyclocomp.ui.navigation.NavigationGuidanceCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

enum class MapNavigationMode {
    NORTH_UP,
    HEADING_UP,
    FREE,
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapViewComposable(
    modifier: Modifier = Modifier,
    currentLocation: GeoPoint?,
    recordedPoints: List<GeoPoint> = emptyList(),
    activeRoute: GpxRoute? = null,
    navigationStatus: NavigationStatus? = null,
    speedKmh: Double = 0.0,
    bearingDegrees: Float? = null,
    isRideStarted: Boolean = false,
    showControls: Boolean = true,
    showTurnGuidance: Boolean = true,
    onDestinationLongPress: ((GeoPoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var navigationMode by remember {
        mutableStateOf(if (isRideStarted) MapNavigationMode.HEADING_UP else MapNavigationMode.NORTH_UP)
    }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    val destinationLongPress by rememberUpdatedState(onDestinationLongPress)

    // Hardware Compass Orientation Sensor
    val sensorManager = remember(context) { OrientationSensorManager(context) }
    val compassHeading by sensorManager.headingState.collectAsState()

    // Smooth Camera Animation State
    var currentMapOrientation by remember { mutableFloatStateOf(0f) }
    var currentMapCenter by remember { mutableStateOf<OsmGeoPoint?>(null) }
    var currentZoomLevel by remember { mutableDoubleStateOf(15.5) }
    var lastKnownHeading by remember { mutableFloatStateOf(0f) }

    // Rider Location Icon
    val riderMarkerDrawable = remember(context) { createRiderLocationDrawable(context) }

    // Synchronize mode when ride start status changes
    LaunchedEffect(isRideStarted) {
        navigationMode = if (isRideStarted) MapNavigationMode.HEADING_UP else MapNavigationMode.NORTH_UP
    }

    // Pass location to device compass sensor for magnetic declination correction
    LaunchedEffect(currentLocation) {
        sensorManager.updateLocation(currentLocation)
    }

    // Determine Effective Bearing (GPS Course when moving, Compass when stationary)
    val speedMps = speedKmh / 3.6
    val effectiveHeading = remember(bearingDegrees, compassHeading, speedMps) {
        val h = when {
            speedMps >= 0.8 && bearingDegrees != null -> bearingDegrees
            compassHeading != null -> compassHeading
            bearingDegrees != null -> bearingDegrees
            else -> lastKnownHeading
        }
        if (h != null) lastKnownHeading = h
        h ?: lastKnownHeading
    }

    // Configure osmdroid cache and agent settings
    remember(context) {
        val basePath = File(context.cacheDir, "osmdroid")
        val tileCache = File(basePath, "tiles")
        if (!basePath.exists()) basePath.mkdirs()
        if (!tileCache.exists()) tileCache.mkdirs()

        val config = Configuration.getInstance()
        config.userAgentValue = context.packageName
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = tileCache
        config.load(context, PreferenceManager.getDefaultSharedPreferences(context))
        true
    }

    // Sensor & Lifecycle Management
    DisposableEffect(lifecycleOwner, sensorManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapViewInstance?.onResume()
                    sensorManager.start()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapViewInstance?.onPause()
                    sensorManager.stop()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        sensorManager.start()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.stop()
            mapViewInstance?.onDetach()
        }
    }

    // Smooth Camera Position, Bearing, and Zoom Animation Loop
    LaunchedEffect(mapViewInstance, currentLocation, effectiveHeading, navigationMode, isRideStarted) {
        val mapView = mapViewInstance ?: return@LaunchedEffect

        while (isActive) {
            var needsFrameUpdate = false

            // 1. Smooth Zoom Interpolation
            if (navigationMode != MapNavigationMode.FREE) {
                val targetZoom = if (isRideStarted) 17.5 else 15.0
                val zoomDiff = targetZoom - currentZoomLevel
                if (kotlin.math.abs(zoomDiff) > 0.02) {
                    currentZoomLevel += 0.15 * zoomDiff
                    mapView.controller.setZoom(currentZoomLevel)
                    needsFrameUpdate = true
                }
            } else {
                currentZoomLevel = mapView.zoomLevelDouble
            }

            // 2. Calculate Target Map Orientation
            val targetOrientation = when (navigationMode) {
                MapNavigationMode.HEADING_UP -> (360f - effectiveHeading) % 360f
                MapNavigationMode.NORTH_UP -> 0f
                MapNavigationMode.FREE -> currentMapOrientation
            }

            // Shortest angle rotation delta
            val deltaOrient = ((targetOrientation - currentMapOrientation + 540f) % 360f) - 180f
            if (kotlin.math.abs(deltaOrient) > 0.05f) {
                currentMapOrientation = (currentMapOrientation + 0.22f * deltaOrient + 360f) % 360f
                mapView.mapOrientation = currentMapOrientation
                needsFrameUpdate = true
            } else if (currentMapOrientation != targetOrientation && navigationMode != MapNavigationMode.FREE) {
                currentMapOrientation = targetOrientation
                mapView.mapOrientation = currentMapOrientation
                needsFrameUpdate = true
            }

            // 3. Calculate Target Map Center
            if (navigationMode != MapNavigationMode.FREE && currentLocation != null) {
                val targetCenter = calculateTargetCenter(
                    mapView = mapView,
                    location = currentLocation,
                    mode = navigationMode,
                    effectiveHeading = effectiveHeading,
                )

                val currCenter = currentMapCenter ?: targetCenter
                val latDelta = targetCenter.latitude - currCenter.latitude
                val lonDelta = targetCenter.longitude - currCenter.longitude

                if (kotlin.math.abs(latDelta) > 1e-7 || kotlin.math.abs(lonDelta) > 1e-7) {
                    val newLat = currCenter.latitude + 0.22 * latDelta
                    val newLon = currCenter.longitude + 0.22 * lonDelta
                    val animatedCenter = OsmGeoPoint(newLat, newLon)
                    currentMapCenter = animatedCenter
                    mapView.controller.setCenter(animatedCenter)
                    needsFrameUpdate = true
                } else if (currentMapCenter != targetCenter) {
                    currentMapCenter = targetCenter
                    mapView.controller.setCenter(targetCenter)
                }
            }

            if (needsFrameUpdate) {
                mapView.invalidate()
                delay(16) // Smooth ~60 FPS interpolation
            } else {
                delay(80) // Idle check interval when settled
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setUseDataConnection(true)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    minZoomLevel = 4.0
                    maxZoomLevel = 20.0
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(if (isRideStarted) 17.5 else 15.0)
                    controller.setCenter(OsmGeoPoint(-6.2, 106.8166))
                    onResume()

                    // Touch listener to pause automatic tracking when user manually pans or rotates
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                                navigationMode = MapNavigationMode.FREE
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }

                    // Map gestures overlay to capture pan / multi-touch
                    overlays.add(object : Overlay() {
                        override fun onTouchEvent(event: MotionEvent?, mapView: MapView?): Boolean {
                            if (event?.action == MotionEvent.ACTION_DOWN || event?.action == MotionEvent.ACTION_MOVE) {
                                navigationMode = MapNavigationMode.FREE
                            }
                            return false
                        }
                    })

                    // Long press gesture overlay for setting route destination
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean = false
                        override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                            if (p != null) {
                                destinationLongPress?.invoke(GeoPoint(p.latitude, p.longitude))
                                return true
                            }
                            return false
                        }
                    }))

                    mapViewInstance = this
                }
            },
            update = { mapView ->
                // Keep base gesture overlays (index 0, 1); replace route/track/location overlays
                while (mapView.overlays.size > 2) {
                    val overlay = mapView.overlays.removeAt(2)
                    runCatching { overlay.onDetach(mapView) }
                }

                // 1. Draw Active GPX Route Overlay (High-contrast Electric Cyan line with dark casing)
                activeRoute?.let { route ->
                    if (route.points.isNotEmpty()) {
                        val osmRoutePoints = route.points.map { OsmGeoPoint(it.latitude, it.longitude) }

                        val routeCasing = Polyline(mapView).apply {
                            outlinePaint.color = AndroidColor.parseColor("#000000")
                            outlinePaint.strokeWidth = 20f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            setPoints(osmRoutePoints)
                        }
                        mapView.overlays.add(routeCasing)

                        val routePolyline = Polyline(mapView).apply {
                            outlinePaint.color = AndroidColor.parseColor("#00E5FF")
                            outlinePaint.strokeWidth = 12f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            setPoints(osmRoutePoints)
                        }
                        mapView.overlays.add(routePolyline)

                        val startPoint = route.points.first()
                        val startMarker = Marker(mapView).apply {
                            position = OsmGeoPoint(startPoint.latitude, startPoint.longitude)
                            title = "Start"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(startMarker)

                        val endPoint = route.points.last()
                        val endMarker = Marker(mapView).apply {
                            position = OsmGeoPoint(endPoint.latitude, endPoint.longitude)
                            title = "Finish"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(endMarker)
                    }
                }

                // 2. Draw Live Recorded Ride Track Overlay (Bright Green line with dark casing)
                if (recordedPoints.size >= 2) {
                    val osmTrackPoints = recordedPoints.map { OsmGeoPoint(it.latitude, it.longitude) }

                    val trackCasing = Polyline(mapView).apply {
                        outlinePaint.color = AndroidColor.parseColor("#000000")
                        outlinePaint.strokeWidth = 22f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        setPoints(osmTrackPoints)
                    }
                    mapView.overlays.add(trackCasing)

                    val trackPolyline = Polyline(mapView).apply {
                        outlinePaint.color = AndroidColor.parseColor("#00E676")
                        outlinePaint.strokeWidth = 14f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        setPoints(osmTrackPoints)
                    }
                    mapView.overlays.add(trackPolyline)
                }

                // 3. Draw Directional Rider Location Marker
                currentLocation?.let { loc ->
                    val osmLoc = OsmGeoPoint(loc.latitude, loc.longitude)
                    val locationMarker = Marker(mapView).apply {
                        position = osmLoc
                        title = "You are here"
                        icon = riderMarkerDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        isFlat = false
                        // Rotate arrow on screen to point in geographic heading direction
                        rotation = (effectiveHeading + mapView.mapOrientation) % 360f
                    }
                    mapView.overlays.add(locationMarker)
                }

                mapView.invalidate()
            },
        )

        Text(
            "© OpenStreetMap contributors",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )

        // Fit Route Bounds in North-Up Overview Mode before ride start
        LaunchedEffect(activeRoute, isRideStarted) {
            val route = activeRoute
            val map = mapViewInstance
            if (!isRideStarted && route != null && route.points.isNotEmpty() && map != null) {
                val osmPoints = route.points.map { OsmGeoPoint(it.latitude, it.longitude) }
                val box = BoundingBox.fromGeoPoints(osmPoints)
                map.post {
                    map.zoomToBoundingBox(box, true, 80)
                    currentZoomLevel = map.zoomLevelDouble
                }
            }
        }

        if (currentLocation == null && activeRoute == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
            ) {
                Text(
                    "Waiting for GPS to center the map",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Floating Turn Guidance Overlay Card
        if (showTurnGuidance && activeRoute != null && navigationStatus != null) {
            NavigationGuidanceCard(
                activeRoute = activeRoute,
                navigationStatus = navigationStatus,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            )
        }

        // Map Controls Overlay (Compass + Recenter/Heading + Zoom)
        if (showControls) {
            // Compass Button (Top-Right of Map)
            SmallFloatingActionButton(
                onClick = {
                    navigationMode = MapNavigationMode.NORTH_UP
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (showTurnGuidance && activeRoute != null) 90.dp else 16.dp, end = 16.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(-currentMapOrientation),
                    contentAlignment = Alignment.Center,
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        val center = size.minDimension / 2f
                        val radius = center * 0.85f

                        // North Needle (Red triangle pointing UP)
                        val northPath = Path().apply {
                            moveTo(center, center - radius)
                            lineTo(center + radius * 0.38f, center)
                            lineTo(center - radius * 0.38f, center)
                            close()
                        }
                        drawPath(northPath, Color(0xFFFF5252))

                        // South Needle (Gray triangle pointing DOWN)
                        val southPath = Path().apply {
                            moveTo(center, center + radius)
                            lineTo(center + radius * 0.38f, center)
                            lineTo(center - radius * 0.38f, center)
                            close()
                        }
                        drawPath(southPath, Color(0xFFB0BEC5))
                    }
                }
            }

            // Navigation & Zoom Controls (Bottom-Right of Map)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        val newZoom = currentZoomLevel + 1.0
                        currentZoomLevel = newZoom
                        mapViewInstance?.controller?.zoomIn()
                    },
                    shape = CircleShape,
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))

                SmallFloatingActionButton(
                    onClick = {
                        val newZoom = currentZoomLevel - 1.0
                        currentZoomLevel = newZoom
                        mapViewInstance?.controller?.zoomOut()
                    },
                    shape = CircleShape,
                ) {
                    Text("-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                if (activeRoute != null && activeRoute.points.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SmallFloatingActionButton(
                        onClick = {
                            val osmPoints = activeRoute.points.map { OsmGeoPoint(it.latitude, it.longitude) }
                            val box = BoundingBox.fromGeoPoints(osmPoints)
                            mapViewInstance?.zoomToBoundingBox(box, true, 80)
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text("⤢", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Recenter / Navigation Mode Toggle Button
                FloatingActionButton(
                    onClick = {
                        val newMode = when (navigationMode) {
                            MapNavigationMode.HEADING_UP -> MapNavigationMode.NORTH_UP
                            MapNavigationMode.NORTH_UP -> MapNavigationMode.HEADING_UP
                            MapNavigationMode.FREE -> MapNavigationMode.HEADING_UP
                        }
                        navigationMode = newMode
                        currentLocation?.let { loc ->
                            val mapView = mapViewInstance ?: return@let
                            val targetCenter = calculateTargetCenter(mapView, loc, newMode, effectiveHeading)
                            currentMapCenter = targetCenter
                            mapView.controller.animateTo(targetCenter)
                        }
                    },
                    containerColor = when (navigationMode) {
                        MapNavigationMode.HEADING_UP -> MaterialTheme.colorScheme.primary
                        MapNavigationMode.NORTH_UP -> MaterialTheme.colorScheme.secondaryContainer
                        MapNavigationMode.FREE -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = when (navigationMode) {
                        MapNavigationMode.HEADING_UP -> MaterialTheme.colorScheme.onPrimary
                        MapNavigationMode.NORTH_UP -> MaterialTheme.colorScheme.onSecondaryContainer
                        MapNavigationMode.FREE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = CircleShape,
                ) {
                    Text(
                        if (navigationMode == MapNavigationMode.HEADING_UP) "▲" else "⌖",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Calculates geographic center coordinate required to position the user location
 * at approximately 70% down from top of map (30% up from bottom) in HEADING_UP mode.
 */
private fun calculateTargetCenter(
    mapView: MapView,
    location: GeoPoint,
    mode: MapNavigationMode,
    effectiveHeading: Float,
): OsmGeoPoint {
    val osmLoc = OsmGeoPoint(location.latitude, location.longitude)
    if (mode != MapNavigationMode.HEADING_UP) return osmLoc

    val width = mapView.width
    val height = mapView.height
    if (width <= 0 || height <= 0) return osmLoc

    val cosLat = kotlin.math.cos(Math.toRadians(location.latitude)).coerceAtLeast(0.01)
    val metersPerPixel = 156543.03392 * cosLat / Math.pow(2.0, mapView.zoomLevelDouble)
    val offsetMeters = height * 0.20 * metersPerPixel
    val bearingRad = Math.toRadians(effectiveHeading.toDouble())

    val latDelta = (offsetMeters * kotlin.math.cos(bearingRad)) / 111139.0
    val lonDelta = (offsetMeters * kotlin.math.sin(bearingRad)) / (111139.0 * cosLat)

    return OsmGeoPoint(location.latitude + latDelta, location.longitude + lonDelta)
}

/**
 * Programmatically constructs a crisp, directional vector icon for the cyclist location marker.
 */
private fun createRiderLocationDrawable(context: Context): Drawable {
    val sizePx = (44 * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val radius = sizePx * 0.32f

    // Translucent pulse aura
    val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius * 1.35f, auraPaint)

    // Outer white border ring
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 2f, AndroidColor.parseColor("#66000000"))
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    // Vivid primary blue inner circle
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#0288D1")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius * 0.78f, innerPaint)

    // Directional navigation arrowhead pointing UP
    val arrowPath = AndroidPath().apply {
        moveTo(center, center - radius * 0.70f)
        lineTo(center + radius * 0.48f, center + radius * 0.42f)
        lineTo(center, center + radius * 0.15f)
        lineTo(center - radius * 0.48f, center + radius * 0.42f)
        close()
    }
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawPath(arrowPath, arrowPaint)

    return BitmapDrawable(context.resources, bitmap)
}
