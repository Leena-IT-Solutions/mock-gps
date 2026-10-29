package com.lits.mocklocation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val bearing: Float = 0f,
    val speed: Float = 0f
)

data class RouteSummary(
    val originName: String = "",
    val destinationName: String = "",
    val distanceMeters: Double = 0.0,
    val durationSeconds: Int = 0,
    val totalPoints: Int = 0
)

object RouteRepository {
    private val points = mutableListOf<RoutePoint>()
    private val rawVertices = mutableListOf<Pair<Double, Double>>()
    var summary: RouteSummary? = null
        private set

    var currentIndex = 0
    var isRouteActive = false

    @Synchronized
    fun setRoute(
        newRawVertices: List<Pair<Double, Double>>,
        newSummary: RouteSummary
    ) {
        rawVertices.clear()
        rawVertices.addAll(newRawVertices)
        summary = newSummary

        val resampled = resamplePath(newRawVertices, newSummary.durationSeconds)
        points.clear()
        points.addAll(resampled)

        currentIndex = 0
        isRouteActive = false
    }

    @Synchronized
    fun getPoints(): List<RoutePoint> = points.toList()

    @Synchronized
    fun getCurrentPoint(): RoutePoint? {
        if (points.isEmpty()) return null
        val idx = currentIndex.coerceIn(0, points.size - 1)
        return points[idx]
    }

    @Synchronized
    fun nextPoint(): RoutePoint? {
        if (points.isEmpty()) return null
        if (currentIndex < points.size - 1) {
            currentIndex++
        }
        return points[currentIndex]
    }

    @Synchronized
    fun getProgressPercent(): Int {
        if (points.isEmpty()) return 0
        return ((currentIndex.toDouble() / (points.size - 1).coerceAtLeast(1)) * 100).toInt()
    }

    @Synchronized
    fun hasReachedDestination(): Boolean {
        return points.isNotEmpty() && currentIndex >= points.size - 1
    }

    @Synchronized
    fun reset() {
        points.clear()
        rawVertices.clear()
        summary = null
        currentIndex = 0
        isRouteActive = false
    }

    @Synchronized
    fun saveToDisk(context: Context) {
        if (rawVertices.size < 2) return
        try {
            val file = File(context.filesDir, "active_route.json")
            val root = JSONObject()
            root.put("currentIndex", currentIndex)
            root.put("isRouteActive", isRouteActive)
            summary?.let {
                val sumObj = JSONObject().apply {
                    put("originName", it.originName)
                    put("destinationName", it.destinationName)
                    put("distanceMeters", it.distanceMeters)
                    put("durationSeconds", it.durationSeconds)
                    put("totalPoints", it.totalPoints)
                }
                root.put("summary", sumObj)
            }
            val rawArr = JSONArray()
            for (v in rawVertices) {
                val vArr = JSONArray().apply {
                    put(v.first)
                    put(v.second)
                }
                rawArr.put(vArr)
            }
            root.put("rawVertices", rawArr)
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun loadFromDisk(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, "active_route.json")
            if (!file.exists()) return false
            val text = file.readText()
            val root = JSONObject(text)
            val sumObj = root.optJSONObject("summary")
            val durationSecs = sumObj?.optInt("durationSeconds", 900) ?: 900

            val rawArr = root.optJSONArray("rawVertices")
            val loadedRaw = mutableListOf<Pair<Double, Double>>()
            if (rawArr != null) {
                for (i in 0 until rawArr.length()) {
                    val pt = rawArr.getJSONArray(i)
                    loadedRaw.add(Pair(pt.getDouble(0), pt.getDouble(1)))
                }
            }

            if (loadedRaw.size >= 2) {
                rawVertices.clear()
                rawVertices.addAll(loadedRaw)
                val resampled = resamplePath(loadedRaw, durationSecs)
                points.clear()
                points.addAll(resampled)
            }

            currentIndex = root.optInt("currentIndex", 0).coerceIn(0, (points.size - 1).coerceAtLeast(0))
            isRouteActive = root.optBoolean("isRouteActive", true)
            if (sumObj != null) {
                summary = RouteSummary(
                    originName = sumObj.optString("originName"),
                    destinationName = sumObj.optString("destinationName"),
                    distanceMeters = sumObj.optDouble("distanceMeters"),
                    durationSeconds = sumObj.optInt("durationSeconds"),
                    totalPoints = points.size
                )
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun resamplePath(raw: List<Pair<Double, Double>>, totalSeconds: Int): List<RoutePoint> {
        if (raw.size < 2) return emptyList()

        val cumDistances = DoubleArray(raw.size)
        var totalDist = 0.0
        cumDistances[0] = 0.0
        for (i in 0 until raw.size - 1) {
            val d = computeDistance(raw[i].first, raw[i].second, raw[i + 1].first, raw[i + 1].second)
            totalDist += d
            cumDistances[i + 1] = totalDist
        }

        val count = totalSeconds.coerceAtLeast(1)
        val distStep = totalDist / count
        val result = ArrayList<RoutePoint>(count + 1)
        var currentRawIdx = 0

        for (s in 0..count) {
            val targetDist = s * distStep
            while (currentRawIdx < raw.size - 2 && cumDistances[currentRawIdx + 1] < targetDist) {
                currentRawIdx++
            }
            val p1 = raw[currentRawIdx]
            val p2 = raw[currentRawIdx + 1]
            val segStart = cumDistances[currentRawIdx]
            val segEnd = cumDistances[currentRawIdx + 1]
            val segLen = segEnd - segStart
            val frac = if (segLen > 0.0) ((targetDist - segStart) / segLen).coerceIn(0.0, 1.0) else 0.0

            val lat = p1.first + (p2.first - p1.first) * frac
            val lng = p1.second + (p2.second - p1.second) * frac
            val bearing = computeHeading(p1.first, p1.second, p2.first, p2.second)
            val speed = distStep.toFloat()

            result.add(RoutePoint(lat, lng, bearing, speed))
        }
        return result
    }

    private fun computeDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun computeHeading(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
        var heading = Math.toDegrees(atan2(y, x)).toFloat()
        if (heading < 0) heading += 360f
        return heading
    }
}
