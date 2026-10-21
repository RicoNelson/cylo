package com.riconelson.cyclocomp.core.navigation

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
)

data class RouteWaypoint(
    val point: GeoPoint,
    val name: String? = null,
    val note: String? = null,
)

data class GpxRoute(
    val name: String?,
    val points: List<GeoPoint>,
    val waypoints: List<RouteWaypoint>,
    /** Directions supplied by a routing engine are more reliable than deriving turns from line geometry. */
    val routedTurnCues: List<TurnCue>? = null,
    val estimatedDurationSeconds: Double? = null,
) {
    val totalDistanceMeters: Double by lazy {
        RouteNavigation.calculateTotalDistanceMeters(points)
    }

    val turnCues: List<TurnCue> by lazy {
        routedTurnCues ?: RouteNavigation.generateTurnCues(this)
    }
}

enum class TurnType(val symbol: String) {
    STRAIGHT("⬆"),
    SLIGHT_LEFT("↖"),
    LEFT("↰"),
    SHARP_LEFT("↲"),
    SLIGHT_RIGHT("↗"),
    RIGHT("↱"),
    SHARP_RIGHT("↳"),
    U_TURN("↶"),
    FINISH("⚑"),
}

data class TurnCue(
    val pointIndex: Int,
    val point: GeoPoint,
    val turnType: TurnType,
    val description: String,
    val distanceToTurnMeters: Double = 0.0,
)

data class NavigationStatus(
    val nearestPointIndex: Int,
    val distanceToRouteMeters: Double,
    val distanceToNextPointMeters: Double?,
    val isOffCourse: Boolean,
    val nextTurnCue: TurnCue? = null,
    val distanceToNextTurnMeters: Double? = null,
    val totalRemainingDistanceMeters: Double? = null,
)

/** Minimal, local-only GPX import for following a recorded route. */
object GpxParser {
    fun parse(xml: String): GpxRoute {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val trackPoints = document.elementsNamed("trkpt").mapNotNull(::pointFrom)
        val routePoints = document.elementsNamed("rtept").mapNotNull(::pointFrom)
        val points = if (trackPoints.isNotEmpty()) trackPoints else routePoints
        require(points.size >= 2) { "GPX needs at least two track or route points" }

        val waypoints = document.elementsNamed("wpt").mapNotNull { element ->
            pointFrom(element)?.let { point ->
                RouteWaypoint(point, element.childText("name"), element.childText("desc"))
            }
        }
        val name = document.elementsNamed("trk").firstOrNull()?.childText("name")
            ?: document.elementsNamed("rte").firstOrNull()?.childText("name")
            ?: document.rootElement.childText("name")
        return GpxRoute(name, points, waypoints)
    }

    private fun pointFrom(element: Element): GeoPoint? {
        val latitude = element.getAttribute("lat").toDoubleOrNull() ?: return null
        val longitude = element.getAttribute("lon").toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude, element.childText("ele")?.toDoubleOrNull())
    }

    private fun org.w3c.dom.Document.elementsNamed(name: String): List<Element> =
        allElements().filter { it.matchesName(name) }

    private fun org.w3c.dom.Document.allElements(): List<Element> {
        val found = mutableListOf<Element>()
        fun visit(node: Node) {
            if (node is Element) found += node
            for (index in 0 until node.childNodes.length) visit(node.childNodes.item(index))
        }
        visit(documentElement)
        return found
    }

    private fun Element.childText(name: String): String? =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.matchesName(name) }
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun Element.matchesName(name: String): Boolean = localName == name || nodeName.substringAfter(':') == name
    private val org.w3c.dom.Document.rootElement: Element get() = documentElement
}

fun GpxRoute.toGpxXml(nameOverride: String? = null): String {
    val routeName = nameOverride ?: name ?: "Saved Route"
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
    sb.append("""<gpx version="1.1" creator="Cyclocomp" xmlns="http://www.topografix.com/GPX/1/1">""").append("\n")
    sb.append("  <metadata>\n")
    sb.append("    <name>").append(escapeGpxXml(routeName)).append("</name>\n")
    sb.append("  </metadata>\n")
    for (wpt in waypoints) {
        sb.append("""  <wpt lat="${wpt.point.latitude}" lon="${wpt.point.longitude}">""").append("\n")
        if (wpt.name != null) sb.append("    <name>").append(escapeGpxXml(wpt.name)).append("</name>\n")
        if (wpt.note != null) sb.append("    <desc>").append(escapeGpxXml(wpt.note)).append("</desc>\n")
        sb.append("  </wpt>\n")
    }
    sb.append("  <trk>\n")
    sb.append("    <name>").append(escapeGpxXml(routeName)).append("</name>\n")
    sb.append("    <trkseg>\n")
    for (pt in points) {
        sb.append("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
        if (pt.elevationMeters != null) {
            sb.append("<ele>").append(pt.elevationMeters).append("</ele>")
        }
        sb.append("</trkpt>\n")
    }
    sb.append("    </trkseg>\n")
    sb.append("  </trk>\n")
    sb.append("</gpx>\n")
    return sb.toString()
}

private fun escapeGpxXml(input: String): String {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

object RouteNavigation {
    private const val EarthRadiusMeters = 6_371_000.0

    fun calculateTotalDistanceMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        return points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
    }

    fun calculateBearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    /**
     * OsmAnd-style geometry turn cues generation:
     * Uses spatial vector windowing (15-20m lookbehind and lookahead) and corner clustering
     * to eliminate micro-jitter and combine curved corner segments into clean, natural turn cues.
     */
    fun generateTurnCues(
        route: GpxRoute,
        spatialWindowMeters: Double = 18.0,
        clusterDistanceMeters: Double = 30.0,
    ): List<TurnCue> {
        val points = route.points
        if (points.size < 2) return emptyList()

        val rawTurnCandidates = mutableListOf<RawTurnCandidate>()
        var cumulativeDist = 0.0
        val cumulativeDistances = DoubleArray(points.size)
        cumulativeDistances[0] = 0.0

        for (i in 1 until points.size) {
            cumulativeDist += distanceMeters(points[i - 1], points[i])
            cumulativeDistances[i] = cumulativeDist
        }

        for (i in 1 until points.size - 1) {
            // Find lookback index (~18m prior)
            var backIdx = i - 1
            while (backIdx > 0 && (cumulativeDistances[i] - cumulativeDistances[backIdx]) < spatialWindowMeters) {
                backIdx--
            }

            // Find lookahead index (~18m ahead)
            var forwardIdx = i + 1
            while (forwardIdx < points.size - 1 && (cumulativeDistances[forwardIdx] - cumulativeDistances[i]) < spatialWindowMeters) {
                forwardIdx++
            }

            val bearingIn = calculateBearing(points[backIdx], points[i])
            val bearingOut = calculateBearing(points[i], points[forwardIdx])
            val deltaAngle = (bearingOut - bearingIn + 540.0) % 360.0 - 180.0

            if (kotlin.math.abs(deltaAngle) >= 25.0) {
                rawTurnCandidates.add(
                    RawTurnCandidate(
                        index = i,
                        point = points[i],
                        distanceFromStart = cumulativeDistances[i],
                        deltaAngle = deltaAngle,
                    )
                )
            }
        }

        // Chicane / S-bend filter: collapse opposite-sign micro-turns within 22m if net heading change is minor (< 35°)
        val prunedCandidates = mutableListOf<RawTurnCandidate>()
        var candIdx = 0
        while (candIdx < rawTurnCandidates.size) {
            val curr = rawTurnCandidates[candIdx]
            val next = rawTurnCandidates.getOrNull(candIdx + 1)
            if (next != null && (next.distanceFromStart - curr.distanceFromStart) <= 22.0 &&
                ((curr.deltaAngle > 0 && next.deltaAngle < 0) || (curr.deltaAngle < 0 && next.deltaAngle > 0))
            ) {
                val netDelta = curr.deltaAngle + next.deltaAngle
                if (kotlin.math.abs(netDelta) < 35.0) {
                    candIdx += 2
                    continue
                }
            }
            prunedCandidates.add(curr)
            candIdx++
        }

        // Cluster adjacent turn candidates into unified maneuvers
        val clusteredCues = mutableListOf<TurnCue>()
        var candidateIdx = 0
        while (candidateIdx < prunedCandidates.size) {
            val cluster = mutableListOf(prunedCandidates[candidateIdx])
            var nextCandidate = candidateIdx + 1

            while (nextCandidate < prunedCandidates.size) {
                val curr = prunedCandidates[nextCandidate]
                val prev = cluster.last()
                val distBetween = curr.distanceFromStart - prev.distanceFromStart
                val sameSign = (curr.deltaAngle > 0 && prev.deltaAngle > 0) || (curr.deltaAngle < 0 && prev.deltaAngle < 0)

                if (distBetween <= clusterDistanceMeters && sameSign) {
                    cluster.add(curr)
                    nextCandidate++
                } else {
                    break
                }
            }

            // Compute total turn delta through the corner cluster
            val totalDelta = cluster.sumOf { it.deltaAngle }
            val primaryPoint = cluster.maxByOrNull { kotlin.math.abs(it.deltaAngle) } ?: cluster.first()

            val turnType = when {
                totalDelta in 28.0..60.0 -> TurnType.SLIGHT_RIGHT
                totalDelta in 60.0..120.0 -> TurnType.RIGHT
                totalDelta in 120.0..170.0 -> TurnType.SHARP_RIGHT
                totalDelta > 170.0 -> TurnType.U_TURN
                totalDelta in -60.0..-28.0 -> TurnType.SLIGHT_LEFT
                totalDelta in -120.0..-60.0 -> TurnType.LEFT
                totalDelta in -170.0..-120.0 -> TurnType.SHARP_LEFT
                totalDelta < -170.0 -> TurnType.U_TURN
                else -> null
            }

            if (turnType != null) {
                val label = turnType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                clusteredCues.add(
                    TurnCue(
                        pointIndex = primaryPoint.index,
                        point = primaryPoint.point,
                        turnType = turnType,
                        description = "Turn $label",
                        distanceToTurnMeters = primaryPoint.distanceFromStart,
                    )
                )
            }

            candidateIdx = nextCandidate
        }

        // Post-filter cues that are too close together (< 25m) to avoid rapid-fire cues
        val filteredCues = mutableListOf<TurnCue>()
        val minCueSpacingMeters = 25.0

        for (cue in clusteredCues) {
            val lastCue = filteredCues.lastOrNull()
            if (lastCue == null) {
                filteredCues.add(cue)
            } else {
                val spacing = cue.distanceToTurnMeters - lastCue.distanceToTurnMeters
                if (spacing >= minCueSpacingMeters || cue.turnType == TurnType.FINISH) {
                    filteredCues.add(cue)
                }
            }
        }

        if (filteredCues.none { it.turnType == TurnType.FINISH }) {
            val lastIndex = points.size - 1
            val lastPoint = points[lastIndex]
            filteredCues.add(
                TurnCue(
                    pointIndex = lastIndex,
                    point = lastPoint,
                    turnType = TurnType.FINISH,
                    description = "Destination ahead",
                    distanceToTurnMeters = cumulativeDistances.last(),
                )
            )
        }

        return filteredCues
    }

    private data class RawTurnCandidate(
        val index: Int,
        val point: GeoPoint,
        val distanceFromStart: Double,
        val deltaAngle: Double,
    )

    fun evaluate(
        route: GpxRoute,
        current: GeoPoint,
        offCourseThresholdMeters: Double = 50.0,
        previousNearestIndex: Int? = null,
    ): NavigationStatus {
        require(offCourseThresholdMeters > 0) { "Off-course threshold must be positive" }
        val points = route.points
        require(points.isNotEmpty()) { "Route has no points" }

        // Monotonic forward search window if previous index is available
        val searchRange = if (previousNearestIndex != null && previousNearestIndex in points.indices) {
            val start = (previousNearestIndex - 2).coerceAtLeast(0)
            val end = (previousNearestIndex + 15).coerceAtMost(points.lastIndex)
            start..end
        } else {
            points.indices
        }

        var nearest = searchRange.minByOrNull { index -> distanceMeters(current, points[index]) } ?: 0
        val nearestDistance = distanceMeters(current, points[nearest])

        // If Monotonic search yielded off-course distance, fallback to full route search
        if (nearestDistance > offCourseThresholdMeters && searchRange != points.indices) {
            nearest = points.indices.minByOrNull { index -> distanceMeters(current, points[index]) } ?: 0
        }

        val distanceToRoute = points
            .zipWithNext()
            .minOfOrNull { (start, end) -> distanceToSegmentMeters(current, start, end) }
            ?: distanceMeters(current, points[nearest])

        val nextPoint = points.getOrNull(nearest + 1)
        val distanceToNextPoint = nextPoint?.let { distanceMeters(current, it) }

        // Polyline distance to next turn
        val turnCues = route.turnCues
        val nextTurn = turnCues.firstOrNull { it.pointIndex >= nearest }
        val distToTurnPolyline = nextTurn?.let { cue ->
            val distToNearestPoint = distanceMeters(current, points[nearest])
            var distOnPolyline = distToNearestPoint
            for (i in nearest until cue.pointIndex) {
                if (i + 1 < points.size) {
                    distOnPolyline += distanceMeters(points[i], points[i + 1])
                }
            }
            distOnPolyline
        }

        // Cumulative remaining distance to end of route polyline
        var remainingPolyline = distanceMeters(current, points[nearest])
        for (i in nearest until points.lastIndex) {
            remainingPolyline += distanceMeters(points[i], points[i + 1])
        }

        return NavigationStatus(
            nearestPointIndex = nearest,
            distanceToRouteMeters = distanceToRoute,
            distanceToNextPointMeters = distanceToNextPoint,
            isOffCourse = distanceToRoute > offCourseThresholdMeters,
            nextTurnCue = nextTurn,
            distanceToNextTurnMeters = distToTurnPolyline,
            totalRemainingDistanceMeters = remainingPolyline,
        )
    }

    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EarthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val latitudeRadians = Math.toRadians(point.latitude)
        fun projected(location: GeoPoint): Pair<Double, Double> = Pair(
            EarthRadiusMeters * Math.toRadians(location.longitude - point.longitude) * cos(latitudeRadians),
            EarthRadiusMeters * Math.toRadians(location.latitude - point.latitude),
        )
        val (startX, startY) = projected(start)
        val (endX, endY) = projected(end)
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0.0) return sqrt(startX * startX + startY * startY)
        val projection = ((-startX * deltaX) + (-startY * deltaY)) / lengthSquared
        val t = projection.coerceIn(0.0, 1.0)
        val closestX = startX + t * deltaX
        val closestY = startY + t * deltaY
        return sqrt(closestX * closestX + closestY * closestY)
    }
}
