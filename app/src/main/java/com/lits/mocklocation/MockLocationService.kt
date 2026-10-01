package com.lits.mocklocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.random.Random

class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var isMocking = false
    private var isRouteMode = false

    private var targetLatitude = 19.1989
    private var targetLongitude = 73.1918
    private var currentBearing = 0f
    private var currentSpeed = 0f

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                if (isRouteMode) {
                    stepRouteMocking()
                } else {
                    stepFixedMocking()
                }
                // Stream updates at exactly 1Hz (1000ms)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopMocking()
            stopSelf()
            return START_NOT_STICKY
        }

        isRouteMode = (action == ACTION_START_ROUTE)

        if (isRouteMode) {
            RouteRepository.isRouteActive = true
            val firstPoint = RouteRepository.getCurrentPoint()
            if (firstPoint != null) {
                targetLatitude = firstPoint.lat
                targetLongitude = firstPoint.lng
                currentBearing = firstPoint.bearing
                currentSpeed = firstPoint.speed
            }
        } else {
            RouteRepository.isRouteActive = false
            targetLatitude = intent?.getDoubleExtra(EXTRA_LATITUDE, targetLatitude) ?: targetLatitude
            targetLongitude = intent?.getDoubleExtra(EXTRA_LONGITUDE, targetLongitude) ?: targetLongitude
            currentBearing = Random.nextFloat() * 360f
            currentSpeed = 0f
        }

        startForegroundWithNotification()
        startMocking()

        return START_STICKY
    }

    private fun startMocking() {
        isMocking = true
        isRunning = true

        setupTestProvider(LocationManager.GPS_PROVIDER)
        setupTestProvider(LocationManager.NETWORK_PROVIDER)

        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)

        broadcastStatus(true)
    }

    private fun setupTestProvider(provider: String) {
        try {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false, true, true, true,
                    0, 1
                )
            }
            locationManager.setTestProviderEnabled(provider, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stepFixedMocking() {
        // Natural micro-jitter (1-2 meters)
        val jitterLat = targetLatitude + (Random.nextDouble(-0.000008, 0.000008))
        val jitterLng = targetLongitude + (Random.nextDouble(-0.000008, 0.000008))

        pushToProvider(LocationManager.GPS_PROVIDER, jitterLat, jitterLng, currentBearing, 0.0f)
        pushToProvider(LocationManager.NETWORK_PROVIDER, jitterLat, jitterLng, currentBearing, 0.0f)
    }

    private fun stepRouteMocking() {
        val point = RouteRepository.getCurrentPoint()
        if (point == null) {
            stepFixedMocking()
            return
        }

        targetLatitude = point.lat
        targetLongitude = point.lng
        currentBearing = point.bearing
        currentSpeed = point.speed

        // Small vehicular road jitter (~0.5m)
        val jitterLat = targetLatitude + (Random.nextDouble(-0.000004, 0.000004))
        val jitterLng = targetLongitude + (Random.nextDouble(-0.000004, 0.000004))

        pushToProvider(LocationManager.GPS_PROVIDER, jitterLat, jitterLng, currentBearing, currentSpeed)
        pushToProvider(LocationManager.NETWORK_PROVIDER, jitterLat, jitterLng, currentBearing, currentSpeed)

        val progress = RouteRepository.getProgressPercent()
        val reached = RouteRepository.hasReachedDestination()

        // Update notification progress
        updateRouteNotification(progress, currentSpeed, reached)

        // Broadcast progress to UI for live map vehicle animation
        broadcastRouteProgress(progress, targetLatitude, targetLongitude, currentBearing, currentSpeed, reached)

        // Advance to next waypoint if not yet arrived
        if (!reached) {
            RouteRepository.nextPoint()
        }
    }

    private fun pushToProvider(provider: String, lat: Double, lng: Double, bearing: Float, speed: Float) {
        try {
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lng
                altitude = 15.0
                this.bearing = bearing
                this.speed = speed
                accuracy = 1.2f + Random.nextFloat() * 0.8f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 1.0f
                    verticalAccuracyMeters = 0.8f
                    speedAccuracyMetersPerSecond = 0.3f
                }
            }
            locationManager.setTestProviderLocation(provider, loc)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopMocking() {
        isMocking = false
        isRunning = false
        RouteRepository.isRouteActive = false
        handler.removeCallbacks(updateRunnable)

        removeTestProviderSafe(LocationManager.GPS_PROVIDER)
        removeTestProviderSafe(LocationManager.NETWORK_PROVIDER)

        broadcastStatus(false)
    }

    private fun removeTestProviderSafe(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: Exception) {}
    }

    private fun startForegroundWithNotification() {
        val notification = buildCurrentNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun buildCurrentNotification(): Notification {
        val stopPendingIntent = getStopPendingIntent()
        val mainPendingIntent = getMainPendingIntent()

        val title = if (isRouteMode) "🚗 Route Mocking Active" else "📍 Mock Location Active"
        val content = if (isRouteMode) {
            val progress = RouteRepository.getProgressPercent()
            val speedKmH = (currentSpeed * 3.6).toInt()
            "Progress: $progress% • Speed: $speedKmH km/h"
        } else {
            "Lat: %.4f, Lng: %.4f".format(targetLatitude, targetLongitude)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateRouteNotification(progress: Int, speedMs: Float, reached: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val stopPendingIntent = getStopPendingIntent()
        val mainPendingIntent = getMainPendingIntent()

        val title = if (reached) "🏁 Route Completed" else "🚗 In Transit: $progress%"
        val speedKmH = (speedMs * 3.6).toInt()
        val content = if (reached) {
            "Arrived at destination • Mocking stationary"
        } else {
            val remainingSecs = (RouteRepository.getPoints().size - RouteRepository.currentIndex).coerceAtLeast(0)
            val remainingMins = (remainingSecs + 59) / 60
            "Speed: $speedKmH km/h • ~$remainingMins min left"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun getStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getMainPendingIntent(): PendingIntent {
        val mainIntent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            102,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun broadcastStatus(active: Boolean) {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_ACTIVE, active)
            putExtra(EXTRA_IS_ROUTE, isRouteMode)
            putExtra(EXTRA_LATITUDE, targetLatitude)
            putExtra(EXTRA_LONGITUDE, targetLongitude)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastRouteProgress(
        progress: Int,
        lat: Double,
        lng: Double,
        bearing: Float,
        speed: Float,
        reached: Boolean
    ) {
        val intent = Intent(ACTION_ROUTE_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_LATITUDE, lat)
            putExtra(EXTRA_LONGITUDE, lng)
            putExtra(EXTRA_BEARING, bearing)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_REACHED, reached)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "mock_location_service_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.lits.mocklocation.ACTION_START"
        const val ACTION_START_ROUTE = "com.lits.mocklocation.ACTION_START_ROUTE"
        const val ACTION_STOP = "com.lits.mocklocation.ACTION_STOP"
        const val ACTION_STATUS_CHANGED = "com.lits.mocklocation.ACTION_STATUS_CHANGED"
        const val ACTION_ROUTE_PROGRESS = "com.lits.mocklocation.ACTION_ROUTE_PROGRESS"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_BEARING = "extra_bearing"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_REACHED = "extra_reached"
        const val EXTRA_IS_ACTIVE = "extra_is_active"
        const val EXTRA_IS_ROUTE = "extra_is_route"

        var isRunning = false
            private set
    }
}
