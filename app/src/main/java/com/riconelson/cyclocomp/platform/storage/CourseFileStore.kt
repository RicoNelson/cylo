package com.riconelson.cyclocomp.platform.storage

import android.content.Context
import com.riconelson.cyclocomp.core.navigation.GpxParser
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.core.navigation.toGpxXml
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class SavedRoute(
    val id: String,
    val name: String,
    val route: GpxRoute,
    val gpxXml: String,
    val isActive: Boolean = false,
)

/** Stores and manages multiple course routes locally and tracks which route is active. */
class CourseFileStore(
    filesDir: File,
) {
    constructor(context: Context) : this(context.filesDir)

    private val legacyCourseFile = File(filesDir, "selected-course.gpx")
    private val coursesDir = File(filesDir, "courses").apply { if (!exists()) mkdirs() }
    private val activeRouteFile = File(coursesDir, ".active_route")

    @Synchronized
    fun listRoutes(): List<SavedRoute> {
        migrateLegacyIfNeeded()
        var files = coursesDir.listFiles { f -> f.isFile && f.extension == "gpx" }?.toList().orEmpty()
        if (files.isEmpty()) {
            saveRoute(SEED_GPX, makeActive = false)
            files = coursesDir.listFiles { f -> f.isFile && f.extension == "gpx" }?.toList().orEmpty()
        }

        val activeId = getActiveRouteId()
        return files.mapNotNull { file ->
            val id = file.nameWithoutExtension
            val xml = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val route = runCatching { GpxParser.parse(xml) }.getOrNull() ?: return@mapNotNull null
            val name = route.name ?: "Untitled Route"
            SavedRoute(
                id = id,
                name = name,
                route = route,
                gpxXml = xml,
                isActive = (id == activeId),
            )
        }.sortedWith(compareByDescending<SavedRoute> { it.isActive }.thenBy { it.name })
    }

    @Synchronized
    fun getActiveRouteId(): String? {
        if (!activeRouteFile.exists()) return null
        return runCatching { activeRouteFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun getActiveRoute(): GpxRoute? {
        val activeId = getActiveRouteId() ?: return null
        val file = File(coursesDir, "$activeId.gpx")
        if (!file.exists()) return null
        val xml = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { GpxParser.parse(xml) }.getOrNull()
    }

    @Synchronized
    fun load(): GpxRoute? {
        listRoutes()
        return getActiveRoute()
    }

    @Synchronized
    fun saveRoute(gpx: String, nameOverride: String? = null, makeActive: Boolean = false): SavedRoute {
        val parsed = GpxParser.parse(gpx)
        val finalName = nameOverride ?: parsed.name ?: "Saved Route"
        val xmlToSave = if (nameOverride != null && nameOverride != parsed.name) {
            parsed.toGpxXml(finalName)
        } else {
            gpx
        }
        val routeToSave = GpxParser.parse(xmlToSave)

        val id = "route_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val file = File(coursesDir, "$id.gpx")
        atomicWrite(file, xmlToSave)

        if (makeActive) {
            setActiveRoute(id)
        }

        return SavedRoute(
            id = id,
            name = finalName,
            route = routeToSave,
            gpxXml = xmlToSave,
            isActive = (id == getActiveRouteId()),
        )
    }

    @Synchronized
    fun updateRouteName(id: String, newName: String): SavedRoute? {
        val file = File(coursesDir, "$id.gpx")
        if (!file.exists()) return null
        val currentXml = runCatching { file.readText() }.getOrNull() ?: return null
        val route = runCatching { GpxParser.parse(currentXml) }.getOrNull() ?: return null

        val updatedXml = route.toGpxXml(newName)
        val updatedRoute = GpxParser.parse(updatedXml)
        atomicWrite(file, updatedXml)

        return SavedRoute(
            id = id,
            name = newName,
            route = updatedRoute,
            gpxXml = updatedXml,
            isActive = (id == getActiveRouteId()),
        )
    }

    @Synchronized
    fun setActiveRoute(id: String?): GpxRoute? {
        if (id == null) {
            if (activeRouteFile.exists()) activeRouteFile.delete()
            return null
        }
        val file = File(coursesDir, "$id.gpx")
        if (!file.exists()) return null
        atomicWrite(activeRouteFile, id)
        val xml = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { GpxParser.parse(xml) }.getOrNull()
    }

    @Synchronized
    fun deleteRoute(id: String) {
        val file = File(coursesDir, "$id.gpx")
        if (file.exists()) file.delete()
        if (getActiveRouteId() == id) {
            if (activeRouteFile.exists()) activeRouteFile.delete()
        }
    }

    @Synchronized
    fun clearAll() {
        coursesDir.listFiles()?.forEach { it.delete() }
        if (legacyCourseFile.exists()) legacyCourseFile.delete()
    }

    @Synchronized
    fun resetToSeedRoute(): GpxRoute {
        clearAll()
        val saved = saveRoute(SEED_GPX, makeActive = true)
        return saved.route
    }

    @Synchronized
    fun save(gpx: String): GpxRoute {
        return saveRoute(gpx, makeActive = true).route
    }

    @Synchronized
    fun clear() {
        clearAll()
    }

    private fun migrateLegacyIfNeeded() {
        if (legacyCourseFile.exists() && legacyCourseFile.length() > 0L) {
            val xml = runCatching { legacyCourseFile.readText() }.getOrNull()
            if (xml != null) {
                saveRoute(xml, makeActive = true)
            }
            legacyCourseFile.delete()
        }
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        val SEED_GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Cyclocomp Seed Route"
                 xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
              <metadata>
                <name>Central Park Cycling Loop</name>
                <desc>Public sample cycling route for testing navigation, turn cues, and off-course detection.</desc>
                <time>2026-05-10T08:00:00Z</time>
              </metadata>
              <wpt lat="37.7749" lon="-122.4194">
                <name>Start / Finish Hub</name>
                <desc>Main clubhouse and water refill station</desc>
              </wpt>
              <wpt lat="37.7771" lon="-122.4150">
                <name>Summit Viewpoint</name>
                <desc>Highest elevation on the loop</desc>
              </wpt>
              <wpt lat="37.7772" lon="-122.4185">
                <name>Cafe Stop</name>
                <desc>Mid-ride espresso and snacks</desc>
              </wpt>
              <trk>
                <name>Central Park Cycling Loop</name>
                <type>Cycling</type>
                <trkseg>
                  <trkpt lat="37.7749" lon="-122.4194"><ele>15.0</ele></trkpt>
                  <trkpt lat="37.7758" lon="-122.4194"><ele>16.5</ele></trkpt>
                  <trkpt lat="37.7758" lon="-122.4160"><ele>19.0</ele></trkpt>
                  <trkpt lat="37.7771" lon="-122.4150"><ele>24.5</ele></trkpt>
                  <trkpt lat="37.7771" lon="-122.4180"><ele>21.0</ele></trkpt>
                  <trkpt lat="37.7760" lon="-122.4194"><ele>17.5</ele></trkpt>
                  <trkpt lat="37.7749" lon="-122.4194"><ele>15.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
    }
}
