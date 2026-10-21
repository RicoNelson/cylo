package com.riconelson.cyclocomp.platform.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CourseFileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: CourseFileStore
    private lateinit var targetDir: File

    @Before
    fun setUp() {
        targetDir = tempFolder.newFolder("files")
        store = CourseFileStore(targetDir)
    }

    @Test
    fun `listRoutes automatically seeds default GPX when no course file exists`() {
        val routes = store.listRoutes()
        assertTrue(routes.isNotEmpty())
        assertEquals("Central Park Cycling Loop", routes.first().name)
        assertEquals(7, routes.first().route.points.size)
    }

    @Test
    fun `save persists new GPX and clear removes it`() {
        val customGpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Custom Trail</name><trkseg>
                <trkpt lat="1.0" lon="1.0"/>
                <trkpt lat="2.0" lon="2.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val savedRoute = store.save(customGpx)
        assertEquals("Custom Trail", savedRoute.name)

        val loadedRoute = store.load()
        assertEquals("Custom Trail", loadedRoute?.name)

        store.clear()
        val coursesDir = File(targetDir, "courses")
        val files = coursesDir.listFiles()?.filter { it.extension == "gpx" }
        assertTrue(files.isNullOrEmpty())
    }

    @Test
    fun `resetToSeedRoute overwrites active course with default seed GPX`() {
        val customGpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Old Course</name><trkseg>
                <trkpt lat="1.0" lon="1.0"/>
                <trkpt lat="2.0" lon="2.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        store.save(customGpx)

        val resetRoute = store.resetToSeedRoute()
        assertEquals("Central Park Cycling Loop", resetRoute.name)

        val loadedRoute = store.load()
        assertEquals("Central Park Cycling Loop", loadedRoute?.name)
    }

    @Test
    fun `saveRoute supports multiple routes and explicit active selection`() {
        val gpx1 = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Route Alpha</name><trkseg>
                <trkpt lat="1.0" lon="1.0"/>
                <trkpt lat="2.0" lon="2.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val gpx2 = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Route Beta</name><trkseg>
                <trkpt lat="3.0" lon="3.0"/>
                <trkpt lat="4.0" lon="4.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val route1 = store.saveRoute(gpx1, makeActive = true)
        val route2 = store.saveRoute(gpx2, makeActive = false)

        val routes = store.listRoutes()
        assertTrue(routes.size >= 2)
        val alpha = routes.find { it.id == route1.id }
        val beta = routes.find { it.id == route2.id }

        assertNotNull(alpha)
        assertNotNull(beta)
        assertTrue(alpha!!.isActive)
        assertFalse(beta!!.isActive)
        assertEquals("Route Alpha", store.getActiveRoute()?.name)

        // Select Route Beta
        store.setActiveRoute(route2.id)
        assertEquals("Route Beta", store.getActiveRoute()?.name)

        // Deselect active route
        store.setActiveRoute(null)
        assertNull(store.getActiveRoute())
    }

    @Test
    fun `updateRouteName updates route name in file and returns updated SavedRoute`() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Old Name</name><trkseg>
                <trkpt lat="1.0" lon="1.0"/>
                <trkpt lat="2.0" lon="2.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val saved = store.saveRoute(gpx, makeActive = true)
        val updated = store.updateRouteName(saved.id, "New Scenic Highway")

        assertNotNull(updated)
        assertEquals("New Scenic Highway", updated?.name)
        assertEquals("New Scenic Highway", store.getActiveRoute()?.name)

        val routeInList = store.listRoutes().find { it.id == saved.id }
        assertEquals("New Scenic Highway", routeInList?.name)
    }

    @Test
    fun `deleteRoute removes route from store and clears active status if active`() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Temporary Route</name><trkseg>
                <trkpt lat="1.0" lon="1.0"/>
                <trkpt lat="2.0" lon="2.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val saved = store.saveRoute(gpx, makeActive = true)
        assertEquals("Temporary Route", store.getActiveRoute()?.name)

        store.deleteRoute(saved.id)
        val routes = store.listRoutes()
        assertNull(routes.find { it.id == saved.id })
        assertNull(store.getActiveRoute())
    }
}
