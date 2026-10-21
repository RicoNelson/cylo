package com.riconelson.cyclocomp.platform.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.riconelson.cyclocomp.core.navigation.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors hardware orientation sensors (Rotation Vector / Magnetometer) and exposes
 * smoothed True-North heading readings for stationary or low-speed navigation orientation.
 */
class OrientationSensorManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _headingState = MutableStateFlow<Float?>(null)
    val headingState: StateFlow<Float?> = _headingState.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private val accelerometerValues = FloatArray(3)
    private val magnetometerValues = FloatArray(3)

    private var hasAccel = false
    private var hasMag = false
    private var isListening = false

    private var currentLocation: GeoPoint? = null
    private var geomagneticField: GeomagneticField? = null

    private var filteredHeading: Float? = null

    fun updateLocation(location: GeoPoint?) {
        if (location == null) return
        if (currentLocation == null ||
            kotlin.math.abs(location.latitude - (currentLocation?.latitude ?: 0.0)) > 0.1 ||
            kotlin.math.abs(location.longitude - (currentLocation?.longitude ?: 0.0)) > 0.1
        ) {
            currentLocation = location
            geomagneticField = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                (location.elevationMeters ?: 0.0).toFloat(),
                System.currentTimeMillis(),
            )
        }
    }

    fun start() {
        if (isListening || sensorManager == null) return
        isListening = true

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelerometerSensor != null && magnetometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(this, magnetometerSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!isListening || sensorManager == null) return
        isListening = false
        sensorManager.unregisterListener(this)
        hasAccel = false
        hasMag = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isListening) return

        var rawMagneticHeading: Float? = null

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                val azimuthRad = orientationValues[0]
                rawMagneticHeading = (Math.toDegrees(azimuthRad.toDouble()).toFloat() + 360f) % 360f
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
                hasAccel = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerValues, 0, 3)
                hasMag = true
            }
        }

        if (rawMagneticHeading == null && hasAccel && hasMag) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)) {
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                val azimuthRad = orientationValues[0]
                rawMagneticHeading = (Math.toDegrees(azimuthRad.toDouble()).toFloat() + 360f) % 360f
            }
        }

        if (rawMagneticHeading != null) {
            val declination = geomagneticField?.declination ?: 0f
            val trueNorthHeading = (rawMagneticHeading + declination + 360f) % 360f

            // Exponential low-pass filter with shortest-angle interpolation
            val current = filteredHeading
            if (current == null) {
                filteredHeading = trueNorthHeading
            } else {
                val delta = ((trueNorthHeading - current + 540f) % 360f) - 180f
                if (kotlin.math.abs(delta) > 1.5f) { // 1.5 degree deadband to prevent idle jitter
                    val alpha = 0.20f
                    filteredHeading = (current + alpha * delta + 360f) % 360f
                }
            }

            _headingState.value = filteredHeading
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
