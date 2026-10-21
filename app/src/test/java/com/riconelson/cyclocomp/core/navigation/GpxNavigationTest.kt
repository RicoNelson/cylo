package com.riconelson.cyclocomp.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxNavigationTest {
    @Test
    fun `parser reads track points route name and waypoints`() {
        val route = GpxParser.parse(
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Morning loop</name><trkseg>
                <trkpt lat="-6.1754" lon="106.8272"><ele>12.5</ele></trkpt>
                <trkpt lat="-6.1750" lon="106.8280"/>
              </trkseg></trk>
              <wpt lat="-6.1751" lon="106.8276"><name>Water</name><desc>Refill bottle</desc></wpt>
            </gpx>
            """.trimIndent(),
        )

        assertEquals("Morning loop", route.name)
        assertEquals(2, route.points.size)
        assertEquals(12.5, route.points.first().elevationMeters!!, 0.001)
        assertEquals("Water", route.waypoints.single().name)
    }

    @Test
    fun `navigation reports nearest route point and off course state`() {
        val route = GpxRoute(
            name = null,
            points = listOf(GeoPoint(-6.1754, 106.8272), GeoPoint(-6.1754, 106.8282)),
            waypoints = emptyList(),
        )

        val onCourse = RouteNavigation.evaluate(route, GeoPoint(-6.1754, 106.82725), 50.0)
        val offCourse = RouteNavigation.evaluate(route, GeoPoint(-6.1740, 106.8272), 50.0)

        assertEquals(0, onCourse.nearestPointIndex)
        assertFalse(onCourse.isOffCourse)
        assertTrue(onCourse.distanceToNextPointMeters!! > 0)
        assertTrue(offCourse.isOffCourse)
    }

    @Test
    fun `navigation measures distance to a segment rather than only a route point`() {
        val route = GpxRoute(
            name = null,
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.002)),
            waypoints = emptyList(),
        )

        val status = RouteNavigation.evaluate(route, GeoPoint(0.0, 0.001), 20.0)

        assertFalse(status.isOffCourse)
        assertTrue(status.distanceToRouteMeters < 0.1)
    }

    @Test
    fun `calculateBearing returns correct directional degrees`() {
        val start = GeoPoint(0.0, 0.0)
        val north = GeoPoint(1.0, 0.0)
        val east = GeoPoint(0.0, 1.0)

        assertEquals(0.0, RouteNavigation.calculateBearing(start, north), 0.1)
        assertEquals(90.0, RouteNavigation.calculateBearing(start, east), 0.1)
    }

    @Test
    fun `generateTurnCues identifies right turn and finish`() {
        val route = GpxRoute(
            name = "L-Route",
            points = listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.001, 0.0),   // Heading North
                GeoPoint(0.001, 0.001),  // Turn East (Right turn)
            ),
            waypoints = emptyList(),
        )

        val cues = route.turnCues
        assertTrue(cues.isNotEmpty())

        val rightTurn = cues.firstOrNull { it.turnType == TurnType.RIGHT }
        assertNotNull(rightTurn)
        assertEquals(1, rightTurn?.pointIndex)

        val finish = cues.last()
        assertEquals(TurnType.FINISH, finish.turnType)
    }

    @Test
    fun `routing engine turn cues take precedence over geometry guesses`() {
        val route = GpxRoute(
            name = "Routed",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.001, 0.001)),
            waypoints = emptyList(),
            routedTurnCues = listOf(TurnCue(1, GeoPoint(0.001, 0.0), TurnType.LEFT, "Left onto Test Road")),
        )

        assertEquals(1, route.turnCues.size)
        assertEquals(TurnType.LEFT, route.turnCues.single().turnType)
    }

    @Test
    fun `evaluate provides next turn cue during navigation`() {
        val route = GpxRoute(
            name = "Test Route",
            points = listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.001, 0.0),
                GeoPoint(0.001, 0.001),
            ),
            waypoints = emptyList(),
        )

        val status = RouteNavigation.evaluate(route, GeoPoint(0.0, 0.0))
        assertNotNull(status.nextTurnCue)
        assertEquals(TurnType.RIGHT, status.nextTurnCue?.turnType)
    }

    @Test
    fun `seed GPX parses route details waypoints and generates turn cues`() {
        val seedXml = com.riconelson.cyclocomp.platform.storage.CourseFileStore.SEED_GPX
        val route = GpxParser.parse(seedXml)

        assertEquals("Central Park Cycling Loop", route.name)
        assertEquals(7, route.points.size)
        assertEquals(3, route.waypoints.size)
        assertTrue("Route distance should be ~1.1km", route.totalDistanceMeters in 800.0..2000.0)

        // Verify waypoints
        val startWpt = route.waypoints.firstOrNull { it.name == "Start / Finish Hub" }
        assertNotNull(startWpt)

        // Verify turn cues generated
        val cues = route.turnCues
        assertTrue("Should have multiple turn cues including finish", cues.size >= 3)
        assertEquals(TurnType.FINISH, cues.last().turnType)
    }

    @Test
    fun `seed GPX routing correctly evaluates position on-course and off-course`() {
        val seedXml = com.riconelson.cyclocomp.platform.storage.CourseFileStore.SEED_GPX
        val route = GpxParser.parse(seedXml)

        // Rider at start location
        val startLoc = GeoPoint(37.7749, -122.4194)
        val startNav = RouteNavigation.evaluate(route, startLoc, 50.0)

        assertEquals(0, startNav.nearestPointIndex)
        assertFalse("Rider at start should be on course", startNav.isOffCourse)
        assertNotNull("Should have a next turn cue", startNav.nextTurnCue)
        assertEquals(TurnType.RIGHT, startNav.nextTurnCue?.turnType)

        // Rider approaching first turn at (37.7758, -122.4194)
        val turnLoc = GeoPoint(37.7755, -122.4194)
        val turnNav = RouteNavigation.evaluate(route, turnLoc, 50.0)

        assertFalse("Rider near turn should be on course", turnNav.isOffCourse)
        assertNotNull(turnNav.distanceToNextTurnMeters)
        assertTrue("Distance to turn should be under 50 meters", turnNav.distanceToNextTurnMeters!! < 50.0)

        // Rider off course (1 km North)
        val offCourseLoc = GeoPoint(37.7900, -122.4194)
        val offNav = RouteNavigation.evaluate(route, offCourseLoc, 50.0)

        assertTrue("Rider far away should be detected as off course", offNav.isOffCourse)
        assertTrue("Distance to route should exceed 50m threshold", offNav.distanceToRouteMeters > 500.0)
    }

    @Test
    fun `inspect turn cues for New file 1 gpx`() {
        val gpxFile = java.io.File("/Users/riconelson/Downloads/New file 1.gpx")
        if (gpxFile.exists()) {
            val route = GpxParser.parse(gpxFile.readText())
            println("NEW FILE 1 GPX - Points: ${route.points.size}, Cues: ${route.turnCues.size}")
            route.turnCues.forEach { cue ->
                println("Cue at index ${cue.pointIndex}: ${cue.turnType} - ${cue.description}")
            }
        }
    }
}
