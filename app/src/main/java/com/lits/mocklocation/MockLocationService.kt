package com.lits.mocklocation

import android.app.AlarmManager
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
import android.os.PowerManager
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

    private var wakeLock: PowerManager.WakeLock? = null

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

        if (action == ACTION_REQUEST_STATUS) {
            broadcastStatus(isMocking)
            return START_STICKY
        }

        if (intent == null) {
            // Service restarted by Android OS (e.g. low memory recovery or 24/7 background keep-alive)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wasMocking = prefs.getBoolean(KEY_IS_MOCKING, false)
            if (!wasMocking) {
                stopSelf()
                return START_NOT_STICKY
            }
            isRouteMode = prefs.getBoolean(KEY_IS_ROUTE_MODE, false)
            targetLatitude = prefs.getFloat(KEY_TARGET_LAT, 19.1989f).toDouble()
            targetLongitude = prefs.getFloat(KEY_TARGET_LNG, 73.1918f).toDouble()
            currentBearing = prefs.getFloat(KEY_BEARING, 0f)
            currentSpeed = prefs.getFloat(KEY_SPEED, 0f)

            if (isRouteMode) {
                RouteRepository.loadFromDisk(this)
                RouteRepository.currentIndex = prefs.getInt(KEY_ROUTE_INDEX, 0)
                RouteRepository.isRouteActive = true
            }
        } else {
            isRouteMode = (action == ACTION_START_ROUTE)

            if (isRouteMode) {
                RouteRepository.isRouteActive = true
                RouteRepository.saveToDisk(this)
                val firstPoint = RouteRepository.getCurrentPoint()
                if (firstPoint != null) {
                    targetLatitude = firstPoint.lat
                    targetLongitude = firstPoint.lng
                    currentBearing = firstPoint.bearing
                    currentSpeed = firstPoint.speed
                }
            } else {
                RouteRepository.isRouteActive = false
                targetLatitude = intent.getDoubleExtra(EXTRA_LATITUDE, targetLatitude)
                targetLongitude = intent.getDoubleExtra(EXTRA_LONGITUDE, targetLongitude)
                currentBearing = Random.nextFloat() * 360f
                currentSpeed = 0f
            }
        }

        currentMockLat = targetLatitude
        currentMockLng = targetLongitude
        currentMockBearing = currentBearing
        currentMockSpeed = currentSpeed
        isRouteRunning = isRouteMode

        saveState()
        startForegroundWithNotification()
        startMocking()

        // START_STICKY ensures Android recreates the service if it's ever killed under low memory pressure
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MockGPS::KeepAliveWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L) // 24 hours max per acquire
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveState() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_IS_MOCKING, isMocking)
                putBoolean(KEY_IS_ROUTE_MODE, isRouteMode)
                putFloat(KEY_TARGET_LAT, targetLatitude.toFloat())
                putFloat(KEY_TARGET_LNG, targetLongitude.toFloat())
                putFloat(KEY_BEARING, currentBearing)
                putFloat(KEY_SPEED, currentSpeed)
                putInt(KEY_ROUTE_INDEX, RouteRepository.currentIndex)
                apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearState() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_IS_MOCKING, false)
                apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startMocking() {
        isMocking = true
        isRunning = true
        isRouteRunning = isRouteMode
        acquireWakeLock()

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

        currentMockLat = targetLatitude
        currentMockLng = targetLongitude
        currentMockBearing = currentBearing
        currentMockSpeed = 0.0f

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

        currentMockLat = targetLatitude
        currentMockLng = targetLongitude
        currentMockBearing = currentBearing
        currentMockSpeed = currentSpeed

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
            // Persist index occasionally
            if (RouteRepository.currentIndex % 5 == 0) {
                saveState()
            }
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
        isRouteRunning = false
        RouteRepository.isRouteActive = false
        handler.removeCallbacks(updateRunnable)
        releaseWakeLock()

        clearState()

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

        val title = if (isRouteMode) "🚗 Route Mocking Active (24/7)" else "📍 Mock Location Active (24/7)"
        val content = if (isRouteMode) {
            val progress = RouteRepository.getProgressPercent()
            val speedKmH = (currentSpeed * 3.6).toInt()
            "Progress: $progress% • Speed: $speedKmH km/h"
        } else {
            "Lat: %.4f, Lng: %.4f • Background active".format(targetLatitude, targetLongitude)
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

        val title = if (reached) "🏁 Destination Arrived (Mocking 24/7)" else "🚗 In Transit: $progress%"
        val speedKmH = (speedMs * 3.6).toInt()
        val content = if (reached) {
            "Arrived at destination • Mocking stationary 24/7"
        } else {
            val remainingSecs = (RouteRepository.getPoints().size - RouteRepository.currentIndex).coerceAtLeast(0)
            val remainingMins = (remainingSecs + 59) / 60
            val timeText = if (remainingMins >= 60) {
                val hrs = remainingMins / 60
                val mins = remainingMins % 60
                if (mins > 0) "${hrs}h ${mins}m left" else "${hrs}h left"
            } else {
                "$remainingMins min left"
            }
            val speedDisplay = if (speedKmH == 0) String.format(java.util.Locale.US, "%.1f km/h", speedMs * 3.6) else "$speedKmH km/h"
            "Speed: $speedDisplay • ~$timeText"
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // User swiped the app away from Recent Apps.
        // We MUST ensure the Foreground Service continues running 24/7!
        if (isMocking) {
            saveState()
            try {
                val restartIntent = Intent(applicationContext, MockLocationService::class.java).apply {
                    action = if (isRouteMode) ACTION_START_ROUTE else ACTION_START
                    putExtra(EXTRA_LATITUDE, targetLatitude)
                    putExtra(EXTRA_LONGITUDE, targetLongitude)
                }
                val pendingIntent = PendingIntent.getService(
                    applicationContext,
                    103,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        if (isMocking) {
            // If Android OS destroys the service under high memory pressure, save state so it auto-resumes!
            saveState()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "mock_location_service_channel"
        const val NOTIFICATION_ID = 2026

        const val PREFS_NAME = "mock_location_prefs"
        const val KEY_IS_MOCKING = "key_is_mocking"
        const val KEY_IS_ROUTE_MODE = "key_is_route_mode"
        const val KEY_TARGET_LAT = "key_target_lat"
        const val KEY_TARGET_LNG = "key_target_lng"
        const val KEY_BEARING = "key_bearing"
        const val KEY_SPEED = "key_speed"
        const val KEY_ROUTE_INDEX = "key_route_index"

        const val ACTION_START = "com.lits.mocklocation.ACTION_START"
        const val ACTION_START_ROUTE = "com.lits.mocklocation.ACTION_START_ROUTE"
        const val ACTION_STOP = "com.lits.mocklocation.ACTION_STOP"
        const val ACTION_STATUS_CHANGED = "com.lits.mocklocation.ACTION_STATUS_CHANGED"
        const val ACTION_ROUTE_PROGRESS = "com.lits.mocklocation.ACTION_ROUTE_PROGRESS"
        const val ACTION_REQUEST_STATUS = "com.lits.mocklocation.ACTION_REQUEST_STATUS"

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
        var isRouteRunning = false
            private set
        var currentMockLat = 19.1989
            private set
        var currentMockLng = 73.1918
            private set
        var currentMockBearing = 0f
            private set
        var currentMockSpeed = 0f
            private set
    }
}
