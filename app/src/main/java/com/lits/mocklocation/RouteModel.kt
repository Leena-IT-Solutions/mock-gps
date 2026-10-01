package com.lits.mocklocation

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
    var summary: RouteSummary? = null
        private set

    var currentIndex = 0
    var isRouteActive = false

    @Synchronized
    fun setRoute(newPoints: List<RoutePoint>, newSummary: RouteSummary) {
        points.clear()
        points.addAll(newPoints)
        summary = newSummary
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
        summary = null
        currentIndex = 0
        isRouteActive = false
    }

    @Synchronized
    fun saveToDisk(context: android.content.Context) {
        try {
            val file = java.io.File(context.filesDir, "active_route.json")
            val root = org.json.JSONObject()
            root.put("currentIndex", currentIndex)
            root.put("isRouteActive", isRouteActive)
            summary?.let {
                val sumObj = org.json.JSONObject().apply {
                    put("originName", it.originName)
                    put("destinationName", it.destinationName)
                    put("distanceMeters", it.distanceMeters)
                    put("durationSeconds", it.durationSeconds)
                    put("totalPoints", it.totalPoints)
                }
                root.put("summary", sumObj)
            }
            val arr = org.json.JSONArray()
            for (p in points) {
                val pObj = org.json.JSONObject().apply {
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("bearing", p.bearing.toDouble())
                    put("speed", p.speed.toDouble())
                }
                arr.put(pObj)
            }
            root.put("points", arr)
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun loadFromDisk(context: android.content.Context): Boolean {
        return try {
            val file = java.io.File(context.filesDir, "active_route.json")
            if (!file.exists()) return false
            val text = file.readText()
            val root = org.json.JSONObject(text)
            val arr = root.getJSONArray("points")
            val loaded = mutableListOf<RoutePoint>()
            for (i in 0 until arr.length()) {
                val pObj = arr.getJSONObject(i)
                loaded.add(
                    RoutePoint(
                        lat = pObj.getDouble("lat"),
                        lng = pObj.getDouble("lng"),
                        bearing = pObj.optDouble("bearing", 0.0).toFloat(),
                        speed = pObj.optDouble("speed", 0.0).toFloat()
                    )
                )
            }
            points.clear()
            points.addAll(loaded)
            currentIndex = root.optInt("currentIndex", 0).coerceIn(0, (points.size - 1).coerceAtLeast(0))
            isRouteActive = root.optBoolean("isRouteActive", true)
            if (root.has("summary")) {
                val sumObj = root.getJSONObject("summary")
                summary = RouteSummary(
                    originName = sumObj.optString("originName"),
                    destinationName = sumObj.optString("destinationName"),
                    distanceMeters = sumObj.optDouble("distanceMeters"),
                    durationSeconds = sumObj.optInt("durationSeconds"),
                    totalPoints = sumObj.optInt("totalPoints")
                )
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

