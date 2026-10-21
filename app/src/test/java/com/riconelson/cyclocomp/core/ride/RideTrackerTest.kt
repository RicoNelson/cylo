package com.riconelson.cyclocomp.core.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackerTest {
    private val tracker = RideTracker(RideTrackerConfig(autoPauseAfterMillis = 8_000))

    @Test
    fun `counts a moving GPS segment with haversine distance`() {
        val started = tracker.start(0)
        val first = tracker.onLocation(started, point(0, -6.2000, 106.8166))
        val moved = tracker.onLocation(first, point(10_000, -6.2000, 106.8176))

        assertEquals(110.5, moved.metrics.distanceMeters, 2.0)
        assertEquals(10_000, moved.metrics.movingTimeMillis)
        assertEquals(10_000, moved.metrics.elapsedTimeMillis)
    }

    @Test
    fun `auto pauses after stationary threshold and clears when moving`() {
        var state = tracker.onLocation(tracker.start(0), point(0, -6.2, 106.8))
        state = tracker.onLocation(state, point(5_000, -6.2, 106.8, speed = 0f))
        assertFalse(state.metrics.autoPaused)

        state = tracker.onLocation(state, point(8_000, -6.2, 106.8, speed = 0f))
        assertTrue(state.metrics.autoPaused)

        state = tracker.onLocation(state, point(9_000, -6.2, 106.8001))
        assertFalse(state.metrics.autoPaused)
        assertEquals(1_000, state.metrics.movingTimeMillis)
    }

    @Test
    fun `ignores an inaccurate point`() {
        val state = tracker.start(0)
        val ignored = tracker.onLocation(state, point(1_000, -6.2, 106.8, accuracy = 75f))

        assertNull(ignored.lastAcceptedPoint)
    }

    @Test
    fun `ignores an impossible GPS jump`() {
        val first = tracker.onLocation(tracker.start(0), point(0, -6.2, 106.8))
        val jumped = tracker.onLocation(first, point(1_000, -6.2, 106.9))

        assertEquals(first, jumped)
    }

    @Test
    fun `does not count a long GPS gap as movement`() {
        val first = tracker.onLocation(tracker.start(0), point(0, -6.2, 106.8))
        val afterGap = tracker.onLocation(first, point(16_000, -6.2, 106.801))

        assertEquals(0.0, afterGap.metrics.distanceMeters, 0.01)
        assertTrue(afterGap.metrics.autoPaused)
        assertEquals(16_000, afterGap.metrics.elapsedTimeMillis)
    }

    @Test
    fun `aggregates sensor heart rate cadence and power averages and max`() {
        val started = tracker.start(0)
        val p1 = point(0, -6.2, 106.8, hr = 140, cad = 80, pwr = 200)
        val p2 = point(1_000, -6.2, 106.8001, hr = 160, cad = 90, pwr = 250)

        var state = tracker.onLocation(started, p1)
        state = tracker.onLocation(state, p2)

        assertEquals(160, state.metrics.currentHeartRateBpm)
        assertEquals(150, state.metrics.avgHeartRateBpm)
        assertEquals(160, state.metrics.maxHeartRateBpm)

        assertEquals(90, state.metrics.currentCadenceRpm)
        assertEquals(85, state.metrics.avgCadenceRpm)
        assertEquals(90, state.metrics.maxCadenceRpm)

        assertEquals(250, state.metrics.currentPowerWatts)
        assertEquals(225, state.metrics.avgPowerWatts)
        assertEquals(250, state.metrics.maxPowerWatts)
    }

    private fun point(
        time: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float = 5f,
        speed: Float? = null,
        hr: Int? = null,
        cad: Int? = null,
        pwr: Int? = null,
    ) = RidePoint(
        timestampMillis = time,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        reportedSpeedMetersPerSecond = speed,
        heartRateBpm = hr,
        cadenceRpm = cad,
        powerWatts = pwr,
    )
}
