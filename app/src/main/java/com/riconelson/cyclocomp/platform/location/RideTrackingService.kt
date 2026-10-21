package com.riconelson.cyclocomp.platform.location

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.riconelson.cyclocomp.MainActivity
import com.riconelson.cyclocomp.core.ride.RidePoint
import com.riconelson.cyclocomp.core.ride.RideState
import com.riconelson.cyclocomp.core.ride.RideStatus
import com.riconelson.cyclocomp.core.ride.RideTracker
import com.riconelson.cyclocomp.platform.sensors.BleSensorConnectionManager
import com.riconelson.cyclocomp.platform.storage.RideFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns GPS and connected BLE sensors only during a user-started ride. Persistence remains outside this service.
 */
class RideTrackingService : Service() {
    private val binder = LocalBinder()
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val rideFileStore by lazy { RideFileStore(this) }
    val sensorManager by lazy { BleSensorConnectionManager(this) }
    private val tracker = RideTracker()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recordedPoints = mutableListOf<RidePoint>()
    private val _latestPoint = MutableStateFlow<RidePoint?>(null)
    private val _rideState = MutableStateFlow(RideState())
    private val _recordedPointsState = MutableStateFlow<List<RidePoint>>(emptyList())

    val latestPoint: StateFlow<RidePoint?> = _latestPoint.asStateFlow()
    val rideState: StateFlow<RideState> = _rideState.asStateFlow()
    val recordedPointsState: StateFlow<List<RidePoint>> = _recordedPointsState.asStateFlow()

    private val listener = LocationListener { location ->
        if (location.hasAccuracy()) {
            val telemetry = sensorManager.telemetry.value
            val speedOverride = telemetry.sensorSpeedMps?.toFloat() ?: location.takeIf(Location::hasSpeed)?.speed

            val point = RidePoint(
                timestampMillis = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = location.takeIf(Location::hasAltitude)?.altitude,
                accuracyMeters = location.accuracy,
                reportedSpeedMetersPerSecond = speedOverride,
                bearingDegrees = location.takeIf(Location::hasBearing)?.bearing,
                heartRateBpm = telemetry.heartRateBpm,
                cadenceRpm = telemetry.cadenceRpm,
                powerWatts = telemetry.powerWatts,
            )
            _latestPoint.value = point
            if (_rideState.value.status == RideStatus.RECORDING) {
                val updatedState = tracker.onLocation(_rideState.value, point)
                if (updatedState.lastAcceptedPoint === point) {
                    recordedPoints += point.copy(startsNewSegment = updatedState.lastPointStartsNewSegment)
                    _recordedPointsState.value = recordedPoints.toList()
                }
                _rideState.value = updatedState
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        startGpsPreview()
        sensorManager.connectSavedSensors()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (_rideState.value.status != RideStatus.RECORDING) {
            locationManager.removeUpdates(listener)
        }
        return super.onUnbind(intent)
    }

    /** Updates the map while the app is open, without recording a ride or running a foreground service. */
    fun startGpsPreview() {
        if (_rideState.value.status == RideStatus.IDLE) requestUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = when (intent?.action) {
        ACTION_START -> {
            beginTracking()
            START_NOT_STICKY
        }
        ACTION_PAUSE -> {
            _rideState.value = tracker.pause(_rideState.value, System.currentTimeMillis())
            locationManager.removeUpdates(listener)
            startForegroundCompat("Ride paused · resume or finish in Cyclocomp")
            START_NOT_STICKY
        }
        ACTION_RESUME -> {
            startForegroundCompat()
            _rideState.value = tracker.resume(_rideState.value, System.currentTimeMillis())
            requestUpdates()
            START_NOT_STICKY
        }
        ACTION_STOP -> {
            val finished = tracker.end(_rideState.value, System.currentTimeMillis())
            _rideState.value = finished.copy(status = RideStatus.SAVING, saveError = null)
            locationManager.removeUpdates(listener)
            startForegroundCompat("Saving your ride…")
            saveCompletedRide(finished)
            START_NOT_STICKY
        }
        ACTION_RETRY_SAVE -> {
            val state = _rideState.value
            if (state.status == RideStatus.SAVE_FAILED) {
                _rideState.value = state.copy(status = RideStatus.SAVING, saveError = null)
                startForegroundCompat("Saving your ride…")
                saveCompletedRide(state.copy(status = RideStatus.FINISHED))
            }
            START_NOT_STICKY
        }
        else -> START_NOT_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(listener)
        sensorManager.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun beginTracking() {
        startForegroundCompat()
        if (!hasFineLocationPermission() || !isAnyLocationProviderEnabled()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        sensorManager.connectSavedSensors()
        _rideState.value = when (_rideState.value.status) {
            RideStatus.PAUSED -> tracker.resume(_rideState.value, System.currentTimeMillis())
            RideStatus.RECORDING -> _rideState.value
            else -> {
                recordedPoints.clear()
                _recordedPointsState.value = emptyList()
                tracker.start(System.currentTimeMillis())
            }
        }
        requestUpdates()
    }

    private fun isAnyLocationProviderEnabled(): Boolean {
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val fusedEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
        return gpsEnabled || networkEnabled || fusedEnabled
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!hasFineLocationPermission() || !isAnyLocationProviderEnabled()) return
        runCatching {
            locationManager.removeUpdates(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    GPS_INTERVAL_MILLIS,
                    MINIMUM_DISTANCE_METERS,
                    listener,
                )
            } else {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        GPS_INTERVAL_MILLIS,
                        MINIMUM_DISTANCE_METERS,
                        listener,
                    )
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        GPS_INTERVAL_MILLIS,
                        MINIMUM_DISTANCE_METERS,
                        listener,
                    )
                }
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun saveCompletedRide(finished: RideState) {
        val pointsToSave = recordedPoints.toList()
        serviceScope.launch {
            runCatching { rideFileStore.save(finished, pointsToSave) }
                .onSuccess {
                    _rideState.value = finished
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                .onFailure { error ->
                    _rideState.value = finished.copy(
                        status = RideStatus.SAVE_FAILED,
                        saveError = error.message ?: "Cyclocomp could not save this ride",
                    )
                    startForegroundCompat("Ride needs saving · open Cyclocomp to retry")
                }
        }
    }

    private fun startForegroundCompat(statusText: String = "GPS ride tracking is active") {
        val notification = trackingNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun trackingNotification(statusText: String): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride tracking", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Cyclocomp is recording")
            .setContentText(statusText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    inner class LocalBinder : Binder() {
        fun service(): RideTrackingService = this@RideTrackingService
    }

    companion object {
        const val ACTION_START = "com.riconelson.cyclocomp.action.START_RIDE_TRACKING"
        const val ACTION_PAUSE = "com.riconelson.cyclocomp.action.PAUSE_RIDE_TRACKING"
        const val ACTION_RESUME = "com.riconelson.cyclocomp.action.RESUME_RIDE_TRACKING"
        const val ACTION_STOP = "com.riconelson.cyclocomp.action.STOP_RIDE_TRACKING"
        const val ACTION_RETRY_SAVE = "com.riconelson.cyclocomp.action.RETRY_SAVE_RIDE"
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val GPS_INTERVAL_MILLIS = 1_000L
        private const val MINIMUM_DISTANCE_METERS = 0f

        fun startIntent(context: Context): Intent = Intent(context, RideTrackingService::class.java).apply {
            action = ACTION_START
        }

        fun stopIntent(context: Context): Intent = Intent(context, RideTrackingService::class.java).apply {
            action = ACTION_STOP
        }

        fun pauseIntent(context: Context): Intent = Intent(context, RideTrackingService::class.java).apply {
            action = ACTION_PAUSE
        }

        fun resumeIntent(context: Context): Intent = Intent(context, RideTrackingService::class.java).apply {
            action = ACTION_RESUME
        }

        fun retrySaveIntent(context: Context): Intent = Intent(context, RideTrackingService::class.java).apply {
            action = ACTION_RETRY_SAVE
        }
    }
}
