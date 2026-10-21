package com.riconelson.cyclocomp.core.ride

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class RideStatus { IDLE, RECORDING, PAUSED, SAVING, SAVE_FAILED, FINISHED }

data class RidePoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float,
    val reportedSpeedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val startsNewSegment: Boolean = false,
)

data class RideMetrics(
    val elapsedTimeMillis: Long = 0,
    val movingTimeMillis: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMetersPerSecond: Double = 0.0,
    val maxSpeedMetersPerSecond: Double = 0.0,
    val autoPaused: Boolean = false,
    val currentHeartRateBpm: Int? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val currentCadenceRpm: Int? = null,
    val avgCadenceRpm: Int? = null,
    val maxCadenceRpm: Int? = null,
    val currentPowerWatts: Int? = null,
    val avgPowerWatts: Int? = null,
    val maxPowerWatts: Int? = null,
    internal val heartRateSum: Long = 0,
    internal val heartRateCount: Int = 0,
    internal val cadenceSum: Long = 0,
    internal val cadenceCount: Int = 0,
    internal val powerSum: Long = 0,
    internal val powerCount: Int = 0,
)

data class RideState(
    val status: RideStatus = RideStatus.IDLE,
    val startedAtMillis: Long? = null,
    val pausedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val manualPausedDurationMillis: Long = 0,
    val lastAcceptedPoint: RidePoint? = null,
    val lastPointStartsNewSegment: Boolean = false,
    val lastMotionAtMillis: Long? = null,
    val metrics: RideMetrics = RideMetrics(),
    val saveError: String? = null,
)

data class RideTrackerConfig(
    val maximumAccuracyMeters: Float = 50f,
    val minimumMovingSpeedMetersPerSecond: Double = 0.7,
    val autoPauseAfterMillis: Long = 8_000,
    val maximumSpeedMetersPerSecond: Double = 25.0,
    val maximumGapMillis: Long = 15_000,
)

/**
 * Pure state reducer for ride recording. Android location and persistence code stay outside it.
 */
class RideTracker(private val config: RideTrackerConfig = RideTrackerConfig()) {
    fun start(atMillis: Long): RideState = RideState(
        status = RideStatus.RECORDING,
        startedAtMillis = atMillis,
        lastMotionAtMillis = atMillis,
    )

    fun onLocation(state: RideState, point: RidePoint): RideState {
        if (state.status != RideStatus.RECORDING || !point.isUsable(config.maximumAccuracyMeters)) return state

        val previous = state.lastAcceptedPoint
        val sensorMetrics = updateSensorMetrics(state.metrics, point)

        if (previous == null) {
            return state.copy(
                lastAcceptedPoint = point,
                lastPointStartsNewSegment = false,
                metrics = sensorMetrics.copy(elapsedTimeMillis = state.elapsedAt(point.timestampMillis)),
            )
        }
        val elapsed = point.timestampMillis - previous.timestampMillis
        if (elapsed <= 0) return state

        val segmentDistance = haversineMeters(previous, point)
        val measuredSpeed = point.reportedSpeedMetersPerSecond?.toDouble()?.takeIf { it >= 0.0 }
            ?: segmentDistance / (elapsed / 1_000.0)
        if (measuredSpeed > config.maximumSpeedMetersPerSecond) return state

        // A GPS outage must not add a false line or distance between two distant fixes.
        if (elapsed > config.maximumGapMillis) {
            return state.copy(
                lastAcceptedPoint = point,
                lastPointStartsNewSegment = true,
                lastMotionAtMillis = point.timestampMillis,
                metrics = sensorMetrics.copy(
                    elapsedTimeMillis = state.elapsedAt(point.timestampMillis),
                    currentSpeedMetersPerSecond = 0.0,
                    autoPaused = true,
                ),
            )
        }
        val moving = measuredSpeed >= config.minimumMovingSpeedMetersPerSecond
        val wasMovingAt = if (moving) point.timestampMillis else state.lastMotionAtMillis ?: previous.timestampMillis
        val updatedMetrics = sensorMetrics.copy(
            elapsedTimeMillis = state.elapsedAt(point.timestampMillis),
            movingTimeMillis = state.metrics.movingTimeMillis + if (moving) elapsed else 0,
            distanceMeters = state.metrics.distanceMeters + if (moving) segmentDistance else 0.0,
            currentSpeedMetersPerSecond = measuredSpeed,
            maxSpeedMetersPerSecond = maxOf(state.metrics.maxSpeedMetersPerSecond, measuredSpeed),
            autoPaused = !moving && point.timestampMillis - wasMovingAt >= config.autoPauseAfterMillis,
        )
        return state.copy(
            lastAcceptedPoint = point,
            lastPointStartsNewSegment = false,
            lastMotionAtMillis = wasMovingAt,
            metrics = updatedMetrics,
        )
    }

    private fun updateSensorMetrics(current: RideMetrics, point: RidePoint): RideMetrics {
        val newHrSum = current.heartRateSum + (point.heartRateBpm ?: 0)
        val newHrCount = current.heartRateCount + if (point.heartRateBpm != null) 1 else 0
        val avgHr = if (newHrCount > 0) (newHrSum / newHrCount).toInt() else null
        val maxHr = if (point.heartRateBpm != null) maxOf(current.maxHeartRateBpm ?: 0, point.heartRateBpm) else current.maxHeartRateBpm

        val newCadSum = current.cadenceSum + (point.cadenceRpm ?: 0)
        val newCadCount = current.cadenceCount + if (point.cadenceRpm != null) 1 else 0
        val avgCad = if (newCadCount > 0) (newCadSum / newCadCount).toInt() else null
        val maxCad = if (point.cadenceRpm != null) maxOf(current.maxCadenceRpm ?: 0, point.cadenceRpm) else current.maxCadenceRpm

        val newPwrSum = current.powerSum + (point.powerWatts ?: 0)
        val newPwrCount = current.powerCount + if (point.powerWatts != null) 1 else 0
        val avgPwr = if (newPwrCount > 0) (newPwrSum / newPwrCount).toInt() else null
        val maxPwr = if (point.powerWatts != null) maxOf(current.maxPowerWatts ?: 0, point.powerWatts) else current.maxPowerWatts

        return current.copy(
            currentHeartRateBpm = point.heartRateBpm ?: current.currentHeartRateBpm,
            avgHeartRateBpm = avgHr,
            maxHeartRateBpm = maxHr,
            heartRateSum = newHrSum,
            heartRateCount = newHrCount,

            currentCadenceRpm = point.cadenceRpm ?: current.currentCadenceRpm,
            avgCadenceRpm = avgCad,
            maxCadenceRpm = maxCad,
            cadenceSum = newCadSum,
            cadenceCount = newCadCount,

            currentPowerWatts = point.powerWatts ?: current.currentPowerWatts,
            avgPowerWatts = avgPwr,
            maxPowerWatts = maxPwr,
            powerSum = newPwrSum,
            powerCount = newPwrCount,
        )
    }

    fun pause(state: RideState, atMillis: Long): RideState {
        if (state.status != RideStatus.RECORDING || atMillis < (state.startedAtMillis ?: atMillis)) return state
        return state.copy(
            status = RideStatus.PAUSED,
            pausedAtMillis = atMillis,
            metrics = state.metrics.copy(elapsedTimeMillis = state.elapsedAt(atMillis), currentSpeedMetersPerSecond = 0.0),
        )
    }

    fun resume(state: RideState, atMillis: Long): RideState {
        if (state.status != RideStatus.PAUSED || atMillis < (state.pausedAtMillis ?: atMillis)) return state
        val pausedDuration = atMillis - requireNotNull(state.pausedAtMillis)
        return state.copy(
            status = RideStatus.RECORDING,
            pausedAtMillis = null,
            manualPausedDurationMillis = state.manualPausedDurationMillis + pausedDuration,
            lastAcceptedPoint = null,
            lastMotionAtMillis = atMillis,
            metrics = state.metrics.copy(currentSpeedMetersPerSecond = 0.0, autoPaused = false),
        )
    }

    fun end(state: RideState, atMillis: Long): RideState {
        if (state.status != RideStatus.RECORDING && state.status != RideStatus.PAUSED) return state
        val finishedAt = maxOf(atMillis, state.startedAtMillis ?: atMillis)
        val elapsedAtEnd = when (state.status) {
            RideStatus.RECORDING -> state.elapsedAt(finishedAt)
            RideStatus.PAUSED -> state.elapsedAt(state.pausedAtMillis ?: finishedAt)
            else -> state.metrics.elapsedTimeMillis
        }
        return state.copy(
            status = RideStatus.FINISHED,
            endedAtMillis = finishedAt,
            metrics = state.metrics.copy(elapsedTimeMillis = elapsedAtEnd, currentSpeedMetersPerSecond = 0.0),
        )
    }

    private fun RideState.elapsedAt(atMillis: Long): Long {
        val started = startedAtMillis ?: return metrics.elapsedTimeMillis
        return (atMillis - started - manualPausedDurationMillis).coerceAtLeast(0)
    }

    private fun RidePoint.isUsable(maximumAccuracyMeters: Float): Boolean =
        accuracyMeters in 0f..maximumAccuracyMeters &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {
        fun haversineMeters(from: RidePoint, to: RidePoint): Double {
            val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
            val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
            val fromLatitude = Math.toRadians(from.latitude)
            val toLatitude = Math.toRadians(to.latitude)
            val a = sin(latitudeDelta / 2).let { it * it } +
                cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2).let { it * it }
            return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
