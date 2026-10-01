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
}
