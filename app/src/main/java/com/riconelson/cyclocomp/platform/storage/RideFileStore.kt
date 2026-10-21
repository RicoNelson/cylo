package com.riconelson.cyclocomp.platform.storage

import android.content.Context
import com.riconelson.cyclocomp.core.ride.RidePoint
import com.riconelson.cyclocomp.core.ride.RideState
import com.riconelson.cyclocomp.core.ride.RideStatus
import com.riconelson.cyclocomp.core.ride.RideTracker
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.math.roundToInt

data class SavedRideFile(
    val file: File,
    val recordedAtMillis: Long,
    val byteSize: Long,
    val distanceMeters: Double? = null,
    val movingTimeMillis: Long? = null,
    val elapsedTimeMillis: Long? = null,
    val averageSpeedMetersPerSecond: Double? = null,
    val maxSpeedMetersPerSecond: Double? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val avgCadenceRpm: Int? = null,
    val maxCadenceRpm: Int? = null,
    val avgPowerWatts: Int? = null,
    val maxPowerWatts: Int? = null,
    val elevationGainMeters: Double? = null,
    val maxElevationMeters: Double? = null,
    val minElevationMeters: Double? = null,
)

data class RideDetailPoint(
    val timestampMillis: Long,
    val timeOffsetMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val distanceMetersFromStart: Double = 0.0,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
)

data class RideSplitInterval(
    val splitNumber: Int,
    val distanceKmLabel: String,
    val durationMillis: Long,
    val averageSpeedMps: Double,
    val avgHeartRateBpm: Int?,
    val avgCadenceRpm: Int?,
    val avgPowerWatts: Int?,
    val elevationGainMeters: Double,
)

data class DetailedRideData(
    val summary: SavedRideFile,
    val points: List<RideDetailPoint>,
    val splits: List<RideSplitInterval>,
)

/** Writes completed rides privately to the app's files directory as portable GPX with Garmin track extensions. */
class RideFileStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)

    fun save(state: RideState, points: List<RidePoint>): SavedRideFile {
        require(state.status == RideStatus.FINISHED) { "Only completed rides can be saved" }
        require(points.isNotEmpty()) { "A ride needs at least one GPS point" }
        if (!directory.exists()) check(directory.mkdirs()) { "Unable to create ride storage" }

        val recordedAt = state.startedAtMillis ?: points.first().timestampMillis
        val output = File(directory, "ride-$recordedAt.gpx")
        val temporary = File(directory, ".ride-$recordedAt.gpx.tmp")

        val elevationStats = computeElevationStats(points)
        val averageSpeed = state.metrics.movingTimeMillis.takeIf { it > 0 }
            ?.let { state.metrics.distanceMeters / (it / 1_000.0) }

        val savedRide = SavedRideFile(
            file = output,
            recordedAtMillis = recordedAt,
            byteSize = 0, // updated after atomic move
            distanceMeters = state.metrics.distanceMeters,
            movingTimeMillis = state.metrics.movingTimeMillis,
            elapsedTimeMillis = state.metrics.elapsedTimeMillis,
            averageSpeedMetersPerSecond = averageSpeed,
            maxSpeedMetersPerSecond = state.metrics.maxSpeedMetersPerSecond,
            avgHeartRateBpm = state.metrics.avgHeartRateBpm,
            maxHeartRateBpm = state.metrics.maxHeartRateBpm,
            avgCadenceRpm = state.metrics.avgCadenceRpm,
            maxCadenceRpm = state.metrics.maxCadenceRpm,
            avgPowerWatts = state.metrics.avgPowerWatts,
            maxPowerWatts = state.metrics.maxPowerWatts,
            elevationGainMeters = elevationStats.gain,
            maxElevationMeters = elevationStats.max,
            minElevationMeters = elevationStats.min,
        )

        FileOutputStream(temporary).use { stream ->
            stream.write(gpx(savedRide, points, recordedAt).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            check(temporary.renameTo(output)) { "Unable to save ride" }
        }
        return savedRide.copy(byteSize = output.length())
    }

    fun listRideFiles(): List<SavedRideFile> = directory
        .listFiles { file -> file.isFile && file.extension.equals("gpx", ignoreCase = true) }
        ?.map { file ->
            val summary = readSummary(file)
            summary ?: SavedRideFile(
                file = file,
                recordedAtMillis = file.nameWithoutExtension.removePrefix("ride-").toLongOrNull() ?: file.lastModified(),
                byteSize = file.length(),
            )
        }
        ?.sortedByDescending(SavedRideFile::recordedAtMillis)
        .orEmpty()

    fun deleteRideFile(file: File): Boolean = runCatching {
        if (!file.exists()) true else file.delete()
    }.getOrDefault(false)

    fun deleteAllRideFiles(): Boolean = runCatching {
        if (!directory.exists()) return@runCatching true
        val files = directory.listFiles() ?: return@runCatching true
        var allDeleted = true
        for (file in files) {
            if (file.isFile && !file.delete()) {
                allDeleted = false
            }
        }
        allDeleted
    }.getOrDefault(false)

    fun readRideDetails(file: File): DetailedRideData? = runCatching {
        if (!file.exists()) return@runCatching null
        val text = file.readText()
        val summary = readSummaryFromFile(file, text) ?: return@runCatching null
        val detailPoints = parseDetailPoints(text)
        val splits = calculateSplits(detailPoints)
        DetailedRideData(summary = summary, points = detailPoints, splits = splits)
    }.getOrNull()

    private fun gpx(summary: SavedRideFile, points: List<RidePoint>, recordedAt: Long): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<gpx version=\"1.1\" creator=\"Cyclocomp\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" xmlns:cyclocomp=\"https://cyclocomp.app/gpx/1\">")
        append("  <metadata><time>${Instant.ofEpochMilli(recordedAt)}</time><extensions><cyclocomp:summary")
        summary.distanceMeters?.let { append(" distanceMeters=\"$it\"") }
        summary.movingTimeMillis?.let { append(" movingTimeMillis=\"$it\"") }
        summary.elapsedTimeMillis?.let { append(" elapsedTimeMillis=\"$it\"") }
        summary.averageSpeedMetersPerSecond?.let { append(" averageSpeedMetersPerSecond=\"$it\"") }
        summary.maxSpeedMetersPerSecond?.let { append(" maxSpeedMetersPerSecond=\"$it\"") }
        summary.avgHeartRateBpm?.let { append(" avgHeartRateBpm=\"$it\"") }
        summary.maxHeartRateBpm?.let { append(" maxHeartRateBpm=\"$it\"") }
        summary.avgCadenceRpm?.let { append(" avgCadenceRpm=\"$it\"") }
        summary.maxCadenceRpm?.let { append(" maxCadenceRpm=\"$it\"") }
        summary.avgPowerWatts?.let { append(" avgPowerWatts=\"$it\"") }
        summary.maxPowerWatts?.let { append(" maxPowerWatts=\"$it\"") }
        summary.elevationGainMeters?.let { append(" elevationGainMeters=\"$it\"") }
        summary.maxElevationMeters?.let { append(" maxElevationMeters=\"$it\"") }
        summary.minElevationMeters?.let { append(" minElevationMeters=\"$it\"") }
        appendLine("/></extensions></metadata>")

        appendLine("  <trk><name>Cyclocomp ride</name><trkseg>")
        points.forEach { point ->
            if (point.startsNewSegment) appendLine("  </trkseg><trkseg>")
            append("    <trkpt lat=\"").append(point.latitude).append("\" lon=\"").append(point.longitude).appendLine("\">")
            point.altitudeMeters?.let { append("      <ele>").append(it).appendLine("</ele>") }
            append("      <time>").append(Instant.ofEpochMilli(point.timestampMillis)).appendLine("</time>")
            if (point.heartRateBpm != null || point.cadenceRpm != null || point.powerWatts != null) {
                appendLine("      <extensions>")
                appendLine("        <gpxtpx:TrackPointExtension>")
                point.heartRateBpm?.let { append("          <gpxtpx:hr>").append(it).appendLine("</gpxtpx:hr>") }
                point.cadenceRpm?.let { append("          <gpxtpx:cad>").append(it).appendLine("</gpxtpx:cad>") }
                point.powerWatts?.let { append("          <cyclocomp:pwr>").append(it).appendLine("</cyclocomp:pwr>") }
                appendLine("        </gpxtpx:TrackPointExtension>")
                appendLine("      </extensions>")
            }
            appendLine("    </trkpt>")
        }
        appendLine("  </trkseg></trk>")
        appendLine("</gpx>")
    }

    private fun readSummary(file: File): SavedRideFile? = runCatching {
        if (!file.exists()) return@runCatching null
        val text = file.readText()
        readSummaryFromFile(file, text)
    }.getOrNull()

    private fun readSummaryFromFile(file: File, text: String): SavedRideFile? {
        val recordedAt = file.nameWithoutExtension.removePrefix("ride-").toLongOrNull() ?: file.lastModified()
        val byteSize = file.length()

        val summaryMatch = SUMMARY_TAG_REGEX.find(text)
        if (summaryMatch != null) {
            val tagText = summaryMatch.value
            return SavedRideFile(
                file = file,
                recordedAtMillis = recordedAt,
                byteSize = byteSize,
                distanceMeters = getAttrDouble(tagText, "distanceMeters"),
                movingTimeMillis = getAttrLong(tagText, "movingTimeMillis"),
                elapsedTimeMillis = getAttrLong(tagText, "elapsedTimeMillis"),
                averageSpeedMetersPerSecond = getAttrDouble(tagText, "averageSpeedMetersPerSecond"),
                maxSpeedMetersPerSecond = getAttrDouble(tagText, "maxSpeedMetersPerSecond"),
                avgHeartRateBpm = getAttrInt(tagText, "avgHeartRateBpm"),
                maxHeartRateBpm = getAttrInt(tagText, "maxHeartRateBpm"),
                avgCadenceRpm = getAttrInt(tagText, "avgCadenceRpm"),
                maxCadenceRpm = getAttrInt(tagText, "maxCadenceRpm"),
                avgPowerWatts = getAttrInt(tagText, "avgPowerWatts"),
                maxPowerWatts = getAttrInt(tagText, "maxPowerWatts"),
                elevationGainMeters = getAttrDouble(tagText, "elevationGainMeters"),
                maxElevationMeters = getAttrDouble(tagText, "maxElevationMeters"),
                minElevationMeters = getAttrDouble(tagText, "minElevationMeters"),
            )
        }

        // Fallback for older GPX files without <cyclocomp:summary> extension
        val detailPoints = parseDetailPoints(text)
        if (detailPoints.isEmpty()) return null

        val startTime = detailPoints.first().timestampMillis
        val endTime = detailPoints.last().timestampMillis
        val elapsedTime = (endTime - startTime).coerceAtLeast(0)
        val totalDist = detailPoints.last().distanceMetersFromStart

        var movingTime = 0L
        var maxSpeed = 0.0
        var hrSum = 0L
        var hrCount = 0
        var maxHr = 0
        var cadSum = 0L
        var cadCount = 0
        var maxCad = 0
        var pwrSum = 0L
        var pwrCount = 0
        var maxPwr = 0

        for (i in detailPoints.indices) {
            val p = detailPoints[i]
            if (i > 0) {
                val prev = detailPoints[i - 1]
                val dt = p.timestampMillis - prev.timestampMillis
                val spd = p.speedMetersPerSecond ?: 0.0
                if (spd >= 0.7 && dt in 1..15_000) {
                    movingTime += dt
                }
            }
            p.speedMetersPerSecond?.let { if (it > maxSpeed) maxSpeed = it }
            p.heartRateBpm?.let {
                hrSum += it
                hrCount++
                if (it > maxHr) maxHr = it
            }
            p.cadenceRpm?.let {
                cadSum += it
                cadCount++
                if (it > maxCad) maxCad = it
            }
            p.powerWatts?.let {
                pwrSum += it
                pwrCount++
                if (it > maxPwr) maxPwr = it
            }
        }

        val avgSpeed = if (movingTime > 0) totalDist / (movingTime / 1000.0) else 0.0
        val elevations = detailPoints.mapNotNull { it.altitudeMeters }
        var gain = 0.0
        for (i in 1 until elevations.size) {
            val diff = elevations[i] - elevations[i - 1]
            if (diff > 0.5) gain += diff
        }

        return SavedRideFile(
            file = file,
            recordedAtMillis = recordedAt,
            byteSize = byteSize,
            distanceMeters = totalDist,
            movingTimeMillis = movingTime.takeIf { it > 0 } ?: elapsedTime,
            elapsedTimeMillis = elapsedTime,
            averageSpeedMetersPerSecond = avgSpeed,
            maxSpeedMetersPerSecond = maxSpeed,
            avgHeartRateBpm = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
            maxHeartRateBpm = if (maxHr > 0) maxHr else null,
            avgCadenceRpm = if (cadCount > 0) (cadSum / cadCount).toInt() else null,
            maxCadenceRpm = if (maxCad > 0) maxCad else null,
            avgPowerWatts = if (pwrCount > 0) (pwrSum / pwrCount).toInt() else null,
            maxPowerWatts = if (maxPwr > 0) maxPwr else null,
            elevationGainMeters = if (elevations.isNotEmpty()) gain else null,
            maxElevationMeters = elevations.maxOrNull(),
            minElevationMeters = elevations.minOrNull(),
        )
    }

    private fun parseDetailPoints(gpxText: String): List<RideDetailPoint> {
        val trkptMatches = TRKPT_REGEX.findAll(gpxText).toList()
        if (trkptMatches.isEmpty()) return emptyList()

        val list = mutableListOf<RideDetailPoint>()
        var startMillis: Long? = null
        var cumulativeDist = 0.0
        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevTime: Long? = null

        for (match in trkptMatches) {
            val block = match.value
            val lat = LAT_REGEX.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = LON_REGEX.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val ele = ELE_REGEX.find(block)?.groupValues?.get(1)?.toDoubleOrNull()
            val timeStr = TIME_REGEX.find(block)?.groupValues?.get(1) ?: continue
            val timestamp = runCatching { Instant.parse(timeStr).toEpochMilli() }.getOrNull() ?: continue

            val hr = HR_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()
            val cad = CAD_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()
            val pwr = PWR_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull()

            if (startMillis == null) startMillis = timestamp

            var calcSpeed = 0.0
            if (prevLat != null && prevLon != null && prevTime != null) {
                val dt = timestamp - prevTime
                if (dt in 1..15_000) {
                    val segDist = RideTracker.haversineMeters(
                        RidePoint(0, prevLat, prevLon, accuracyMeters = 0f),
                        RidePoint(0, lat, lon, accuracyMeters = 0f),
                    )
                    calcSpeed = segDist / (dt / 1000.0)
                    if (calcSpeed > 25.0) { // filter bad spikes
                        calcSpeed = 0.0
                    } else {
                        cumulativeDist += segDist
                    }
                }
            }

            prevLat = lat
            prevLon = lon
            prevTime = timestamp

            list.add(
                RideDetailPoint(
                    timestampMillis = timestamp,
                    timeOffsetMillis = timestamp - startMillis,
                    latitude = lat,
                    longitude = lon,
                    altitudeMeters = ele,
                    speedMetersPerSecond = calcSpeed,
                    distanceMetersFromStart = cumulativeDist,
                    heartRateBpm = hr,
                    cadenceRpm = cad,
                    powerWatts = pwr,
                ),
            )
        }
        return list
    }

    private fun calculateSplits(points: List<RideDetailPoint>): List<RideSplitInterval> {
        if (points.isEmpty()) return emptyList()

        val splits = mutableListOf<RideSplitInterval>()
        var splitIndex = 1
        var splitStartIndex = 0
        var targetDist = 1000.0 // 1 km splits

        for (i in points.indices) {
            val p = points[i]
            val isLast = i == points.lastIndex
            if (p.distanceMetersFromStart >= targetDist || (isLast && i > splitStartIndex)) {
                val startPt = points[splitStartIndex]
                val endPt = p
                val dist = endPt.distanceMetersFromStart - startPt.distanceMetersFromStart
                val duration = (endPt.timestampMillis - startPt.timestampMillis).coerceAtLeast(0)
                val avgSpeed = if (duration > 0) dist / (duration / 1000.0) else 0.0

                var hrSum = 0L
                var hrCount = 0
                var cadSum = 0L
                var cadCount = 0
                var pwrSum = 0L
                var pwrCount = 0
                var elevGain = 0.0

                for (j in splitStartIndex..i) {
                    val pt = points[j]
                    pt.heartRateBpm?.let { hrSum += it; hrCount++ }
                    pt.cadenceRpm?.let { cadSum += it; cadCount++ }
                    pt.powerWatts?.let { pwrSum += it; pwrCount++ }
                    if (j > splitStartIndex) {
                        val prevPt = points[j - 1]
                        if (pt.altitudeMeters != null && prevPt.altitudeMeters != null) {
                            val diff = pt.altitudeMeters - prevPt.altitudeMeters
                            if (diff > 0.5) elevGain += diff
                        }
                    }
                }

                val distKm = dist / 1000.0
                val label = if (distKm >= 0.95 && distKm <= 1.05) "1.0 km" else "%.2f km".format(distKm)

                splits.add(
                    RideSplitInterval(
                        splitNumber = splitIndex++,
                        distanceKmLabel = label,
                        durationMillis = duration,
                        averageSpeedMps = avgSpeed,
                        avgHeartRateBpm = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
                        avgCadenceRpm = if (cadCount > 0) (cadSum / cadCount).toInt() else null,
                        avgPowerWatts = if (pwrCount > 0) (pwrSum / pwrCount).toInt() else null,
                        elevationGainMeters = elevGain,
                    ),
                )

                splitStartIndex = i
                targetDist += 1000.0
            }
        }
        return splits
    }

    private data class ElevationStats(val gain: Double?, val max: Double?, val min: Double?)

    private fun computeElevationStats(points: List<RidePoint>): ElevationStats {
        val altitudes = points.mapNotNull { it.altitudeMeters }
        if (altitudes.isEmpty()) return ElevationStats(null, null, null)

        var gain = 0.0
        for (i in 1 until altitudes.size) {
            val diff = altitudes[i] - altitudes[i - 1]
            if (diff > 0.5) gain += diff
        }
        return ElevationStats(gain = gain, max = altitudes.maxOrNull(), min = altitudes.minOrNull())
    }

    private companion object {
        const val DIRECTORY_NAME = "cyclocomp-rides"
        val SUMMARY_TAG_REGEX = Regex("<cyclocomp:summary\\b[^>]*/>")
        val TRKPT_REGEX = Regex("<trkpt\\b[\\s\\S]*?</trkpt>")
        val LAT_REGEX = Regex("lat=\\\"([^\\\"]+)\\\"")
        val LON_REGEX = Regex("lon=\\\"([^\\\"]+)\\\"")
        val ELE_REGEX = Regex("<ele>([^<]+)</ele>")
        val TIME_REGEX = Regex("<time>([^<]+)</time>")
        val HR_REGEX = Regex("<(?:gpxtpx:)?hr>(\\d+)</(?:gpxtpx:)?hr>")
        val CAD_REGEX = Regex("<(?:gpxtpx:)?cad>(\\d+)</(?:gpxtpx:)?cad>")
        val PWR_REGEX = Regex("<(?:cyclocomp:)?pwr>(\\d+)</(?:cyclocomp:)?pwr>")

        fun getAttrDouble(text: String, attr: String): Double? {
            val match = Regex("$attr=\\\"([^\\\"]+)\\\"").find(text)
            return match?.groupValues?.get(1)?.toDoubleOrNull()
        }

        fun getAttrLong(text: String, attr: String): Long? {
            val match = Regex("$attr=\\\"([^\\\"]+)\\\"").find(text)
            return match?.groupValues?.get(1)?.toLongOrNull()
        }

        fun getAttrInt(text: String, attr: String): Int? {
            val match = Regex("$attr=\\\"([^\\\"]+)\\\"").find(text)
            return match?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()
        }
    }
}
