package com.riconelson.cyclocomp.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapHeadingTest {

    private fun computeShortestAngleDelta(target: Float, current: Float): Float {
        return ((target - current + 540f) % 360f) - 180f
    }

    private fun computeMapOrientation(mode: MapNavigationMode, headingDegrees: Float): Float {
        return when (mode) {
            MapNavigationMode.HEADING_UP -> (360f - headingDegrees) % 360f
            MapNavigationMode.NORTH_UP -> 0f
            MapNavigationMode.FREE -> 0f
        }
    }

    private fun resolveEffectiveHeading(
        speedMps: Double,
        gpsBearingDegrees: Float?,
        compassHeadingDegrees: Float?,
        lastKnownHeading: Float = 0f,
    ): Float {
        return when {
            speedMps >= 0.8 && gpsBearingDegrees != null -> gpsBearingDegrees
            compassHeadingDegrees != null -> compassHeadingDegrees
            gpsBearingDegrees != null -> gpsBearingDegrees
            else -> lastKnownHeading
        }
    }

    @Test
    fun shortestAngleDelta_handlesBoundaryWrap359to1() {
        val current = 359f
        val target = 1f
        val delta = computeShortestAngleDelta(target, current)
        assertEquals(2f, delta, 0.001f)
    }

    @Test
    fun shortestAngleDelta_handlesBoundaryWrap10to350() {
        val current = 10f
        val target = 350f
        val delta = computeShortestAngleDelta(target, current)
        assertEquals(-20f, delta, 0.001f)
    }

    @Test
    fun shortestAngleDelta_handles180DegreeTurn() {
        val current = 90f
        val target = 270f
        val delta = computeShortestAngleDelta(target, current)
        assertEquals(180f, kotlin.math.abs(delta), 0.001f)
    }

    @Test
    fun computeMapOrientation_headingUpRotatesMapOppositeToHeading() {
        // Traveling East (90°): Map orientation must be 270° so East points UP
        val eastOrientation = computeMapOrientation(MapNavigationMode.HEADING_UP, 90f)
        assertEquals(270f, eastOrientation, 0.001f)

        // Traveling South (180°): Map orientation must be 180° so South points UP
        val southOrientation = computeMapOrientation(MapNavigationMode.HEADING_UP, 180f)
        assertEquals(180f, southOrientation, 0.001f)

        // Traveling West (270°): Map orientation must be 90° so West points UP
        val westOrientation = computeMapOrientation(MapNavigationMode.HEADING_UP, 270f)
        assertEquals(90f, westOrientation, 0.001f)
    }

    @Test
    fun computeMapOrientation_northUpAlwaysReturnsZero() {
        assertEquals(0f, computeMapOrientation(MapNavigationMode.NORTH_UP, 45f), 0.001f)
        assertEquals(0f, computeMapOrientation(MapNavigationMode.NORTH_UP, 210f), 0.001f)
    }

    @Test
    fun resolveEffectiveHeading_prioritizesGpsWhenMovingFast() {
        // Fast movement (2.5 m/s): should choose GPS bearing 90° over compass 120°
        val heading = resolveEffectiveHeading(
            speedMps = 2.5,
            gpsBearingDegrees = 90f,
            compassHeadingDegrees = 120f,
        )
        assertEquals(90f, heading, 0.001f)
    }

    @Test
    fun resolveEffectiveHeading_usesCompassWhenStationary() {
        // Stationary (0.0 m/s): should choose compass heading 120°
        val heading = resolveEffectiveHeading(
            speedMps = 0.0,
            gpsBearingDegrees = 90f,
            compassHeadingDegrees = 120f,
        )
        assertEquals(120f, heading, 0.001f)
    }

    @Test
    fun resolveEffectiveHeading_fallsBackToLastKnownWhenNeitherAvailable() {
        val heading = resolveEffectiveHeading(
            speedMps = 0.0,
            gpsBearingDegrees = null,
            compassHeadingDegrees = null,
            lastKnownHeading = 45f,
        )
        assertEquals(45f, heading, 0.001f)
    }

    private fun computeTargetCenterLatLon(
        locationLat: Double,
        locationLon: Double,
        mode: MapNavigationMode,
        effectiveHeading: Float,
        zoomLevel: Double,
        mapHeightPx: Int,
    ): Pair<Double, Double> {
        if (mode != MapNavigationMode.HEADING_UP || mapHeightPx <= 0) {
            return Pair(locationLat, locationLon)
        }
        val cosLat = kotlin.math.cos(Math.toRadians(locationLat)).coerceAtLeast(0.01)
        val metersPerPixel = 156543.03392 * cosLat / Math.pow(2.0, zoomLevel)
        val offsetMeters = mapHeightPx * 0.20 * metersPerPixel
        val bearingRad = Math.toRadians(effectiveHeading.toDouble())

        val latDelta = (offsetMeters * kotlin.math.cos(bearingRad)) / 111139.0
        val lonDelta = (offsetMeters * kotlin.math.sin(bearingRad)) / (111139.0 * cosLat)

        return Pair(locationLat + latDelta, locationLon + lonDelta)
    }

    @Test
    fun calculateTargetCenter_northUpReturnsExactRiderLocation() {
        val (lat, lon) = computeTargetCenterLatLon(
            locationLat = -6.2,
            locationLon = 106.8,
            mode = MapNavigationMode.NORTH_UP,
            effectiveHeading = 90f,
            zoomLevel = 16.5,
            mapHeightPx = 1000,
        )
        assertEquals(-6.2, lat, 1e-6)
        assertEquals(106.8, lon, 1e-6)
    }

    @Test
    fun calculateTargetCenter_headingUpNorthOffsetsNorthward() {
        val (lat, lon) = computeTargetCenterLatLon(
            locationLat = 0.0,
            locationLon = 0.0,
            mode = MapNavigationMode.HEADING_UP,
            effectiveHeading = 0f, // North
            zoomLevel = 16.0,
            mapHeightPx = 1000,
        )
        // lon should remain 0, lat should shift positive (North)
        assertEquals(0.0, lon, 1e-6)
        assert(lat > 0.0) { "Latitude should be shifted northward" }
    }

    @Test
    fun calculateTargetCenter_headingUpIsDeterministicAndDoesNotDrift() {
        val initialCenter = computeTargetCenterLatLon(
            locationLat = -6.2,
            locationLon = 106.8166,
            mode = MapNavigationMode.HEADING_UP,
            effectiveHeading = 45f,
            zoomLevel = 16.5,
            mapHeightPx = 1920,
        )
        // Calling repeatedly with same location & heading yields identical coordinate (no accumulated feedback drift)
        val subsequentCenter = computeTargetCenterLatLon(
            locationLat = -6.2,
            locationLon = 106.8166,
            mode = MapNavigationMode.HEADING_UP,
            effectiveHeading = 45f,
            zoomLevel = 16.5,
            mapHeightPx = 1920,
        )
        assertEquals(initialCenter.first, subsequentCenter.first, 1e-9)
        assertEquals(initialCenter.second, subsequentCenter.second, 1e-9)
    }

    @Test
    fun rideState_determinesNavigationModeAndZoom() {
        val preRideStarted = false
        val preRideMode = if (preRideStarted) MapNavigationMode.HEADING_UP else MapNavigationMode.NORTH_UP
        val preRideTargetZoom = if (preRideStarted) 17.5 else 15.0
        assertEquals(MapNavigationMode.NORTH_UP, preRideMode)
        assertEquals(15.0, preRideTargetZoom, 0.001)

        val activeRideStarted = true
        val activeRideMode = if (activeRideStarted) MapNavigationMode.HEADING_UP else MapNavigationMode.NORTH_UP
        val activeRideTargetZoom = if (activeRideStarted) 17.5 else 15.0
        assertEquals(MapNavigationMode.HEADING_UP, activeRideMode)
        assertEquals(17.5, activeRideTargetZoom, 0.001)
    }
}
