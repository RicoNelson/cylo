package com.riconelson.cyclocomp.platform.routing

import com.riconelson.cyclocomp.core.navigation.GeoPoint
import com.riconelson.cyclocomp.core.navigation.GpxRoute
import com.riconelson.cyclocomp.core.navigation.TurnCue
import com.riconelson.cyclocomp.core.navigation.TurnType
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Fetches a road-following bicycle route instead of drawing a straight line between two pins. */
object BicycleRouter {
    private const val BASE_URL = "https://routing.openstreetmap.de/routed-bike/route/v1/driving"

    fun route(origin: GeoPoint, destination: GeoPoint): GpxRoute {
        val url = URL(
            "$BASE_URL/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&steps=true&alternatives=false",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Cyclocomp/0.1")
        }

        try {
            val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            check(connection.responseCode in 200..299) { "Routing service is unavailable (${connection.responseCode})" }
            return parse(response)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(response: String): GpxRoute {
        val root = JSONObject(response)
        check(root.optString("code") == "Ok") { root.optString("message", "No bicycle route was found") }
        val route = root.getJSONArray("routes").getJSONObject(0)
        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = List(coordinates.length()) { index ->
            coordinates.getJSONArray(index).let { GeoPoint(latitude = it.getDouble(1), longitude = it.getDouble(0)) }
        }
        check(points.size >= 2) { "The routing service returned an incomplete route" }

        val turnCues = mutableListOf<TurnCue>()
        val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        for (stepIndex in 0 until steps.length()) {
            val step = steps.getJSONObject(stepIndex)
            val maneuver = step.getJSONObject("maneuver")
            val type = turnType(maneuver.optString("type"), maneuver.optString("modifier")) ?: continue
            val location = maneuver.getJSONArray("location")
            val point = GeoPoint(latitude = location.getDouble(1), longitude = location.getDouble(0))
            val pointIndex = points.indices.minBy { index ->
                val candidate = points[index]
                (candidate.latitude - point.latitude) * (candidate.latitude - point.latitude) +
                    (candidate.longitude - point.longitude) * (candidate.longitude - point.longitude)
            }
            val roadName = step.optString("name").takeIf { it.isNotBlank() }
            val action = type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            turnCues += TurnCue(
                pointIndex = pointIndex,
                point = point,
                turnType = type,
                description = listOfNotNull(action, roadName).joinToString(" onto "),
            )
        }
        if (turnCues.none { it.turnType == TurnType.FINISH }) {
            turnCues += TurnCue(points.lastIndex, points.last(), TurnType.FINISH, "Destination ahead")
        }

        return GpxRoute(
            name = "Bicycle route",
            points = points,
            waypoints = emptyList(),
            routedTurnCues = turnCues.sortedBy { it.pointIndex },
            estimatedDurationSeconds = route.optDouble("duration").takeIf { !it.isNaN() },
        )
    }

    private fun turnType(type: String, modifier: String): TurnType? = when (type) {
        "arrive" -> TurnType.FINISH
        "uturn" -> TurnType.U_TURN
        "turn", "end of road", "fork", "merge", "roundabout", "rotary" -> when (modifier) {
            "slight left" -> TurnType.SLIGHT_LEFT
            "left" -> TurnType.LEFT
            "sharp left" -> TurnType.SHARP_LEFT
            "slight right" -> TurnType.SLIGHT_RIGHT
            "right" -> TurnType.RIGHT
            "sharp right" -> TurnType.SHARP_RIGHT
            "uturn" -> TurnType.U_TURN
            else -> TurnType.STRAIGHT
        }
        else -> null
    }
}
