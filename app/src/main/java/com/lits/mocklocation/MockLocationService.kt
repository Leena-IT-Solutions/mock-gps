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

    private var mockMode = MODE_FIXED

    private var targetLatitude = 19.1989
    private var targetLongitude = 73.1918
    private var currentBearing = 0f
    private var currentSpeed = 0f

    private var randomSecondsRemaining = WorldCityRepository.INTERVAL_SECONDS

    private var wakeLock: PowerManager.WakeLock? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                when (mockMode) {
                    MODE_ROUTE -> stepRouteMocking()
                    MODE_RANDOM -> stepRandomCityMocking()
                    else -> stepFixedMocking()
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
            stopForegroundCompat()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_NEXT_RANDOM_CITY) {
            if (isMocking && mockMode == MODE_RANDOM) {
                val nextCity = WorldCityRepository.nextCity()
                randomSecondsRemaining = WorldCityRepository.INTERVAL_SECONDS
                targetLatitude = nextCity.lat
                targetLongitude = nextCity.lng
                currentMockLat = targetLatitude
                currentMockLng = targetLongitude
                currentCityName = nextCity.name
                currentCityCountry = nextCity.country
                currentCityFlag = nextCity.flag
                currentRemainingSecs = randomSecondsRemaining
                saveState()
                updateRandomNotification(nextCity, randomSecondsRemaining)
                broadcastRandomCityUpdate(nextCity, randomSecondsRemaining, true)
            }
            return START_STICKY
        }

        if (action == ACTION_REQUEST_STATUS) {
            broadcastStatus(isMocking)
            return START_STICKY
        }

        if (intent == null || action == ACTION_RESUME) {
            // Service restarted by Android OS, BootReceiver, or onTaskRemoved alarm
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wasMocking = prefs.getBoolean(KEY_IS_MOCKING, false)
            if (!wasMocking) {
                stopSelf()
                return START_NOT_STICKY
            }
            mockMode = prefs.getInt(KEY_MOCK_MODE, MODE_FIXED)
            targetLatitude = prefs.getFloat(KEY_TARGET_LAT, 19.1989f).toDouble()
            targetLongitude = prefs.getFloat(KEY_TARGET_LNG, 73.1918f).toDouble()
            currentBearing = prefs.getFloat(KEY_BEARING, 0f)
            currentSpeed = prefs.getFloat(KEY_SPEED, 0f)

            if (mockMode == MODE_ROUTE) {
                if (RouteRepository.getPoints().isEmpty()) {
                    RouteRepository.loadFromDisk(this)
                }
                val savedIdx = prefs.getInt(KEY_ROUTE_INDEX, RouteRepository.currentIndex)
                val maxIdx = (RouteRepository.getPoints().size - 1).coerceAtLeast(0)
                RouteRepository.currentIndex = savedIdx.coerceIn(0, maxIdx)
                RouteRepository.isRouteActive = true
                WorldCityRepository.isRandomActive = false
                RouteRepository.getCurrentPoint()?.let { pt ->
                    targetLatitude = pt.lat
                    targetLongitude = pt.lng
                    currentBearing = pt.bearing
                    currentSpeed = pt.speed
                }
            } else if (mockMode == MODE_RANDOM) {
                val cityIdx = prefs.getInt(KEY_RANDOM_CITY_INDEX, 0)
                WorldCityRepository.setCityByIndex(cityIdx)
                randomSecondsRemaining = prefs.getInt(KEY_RANDOM_SECS_REMAINING, WorldCityRepository.INTERVAL_SECONDS)
                    .coerceIn(1, WorldCityRepository.INTERVAL_SECONDS)
                WorldCityRepository.secondsRemaining = randomSecondsRemaining
                val city = WorldCityRepository.getCurrentCity()
                targetLatitude = city.lat
                targetLongitude = city.lng
                currentCityName = city.name
                currentCityCountry = city.country
                currentCityFlag = city.flag
                currentRemainingSecs = randomSecondsRemaining
                RouteRepository.isRouteActive = false
                WorldCityRepository.isRandomActive = true
            } else {
                RouteRepository.isRouteActive = false
                WorldCityRepository.isRandomActive = false
            }
        } else {
            when (action) {
                ACTION_START_RANDOM -> {
                    mockMode = MODE_RANDOM
                    RouteRepository.isRouteActive = false
                    WorldCityRepository.isRandomActive = true
                    val city = WorldCityRepository.getCurrentCity()
                    targetLatitude = city.lat
                    targetLongitude = city.lng
                    currentBearing = 0f
                    currentSpeed = 0f
                    randomSecondsRemaining = WorldCityRepository.INTERVAL_SECONDS
                    currentCityName = city.name
                    currentCityCountry = city.country
                    currentCityFlag = city.flag
                    currentRemainingSecs = randomSecondsRemaining
                }
                ACTION_START_ROUTE -> {
                    mockMode = MODE_ROUTE
                    if (RouteRepository.getPoints().isEmpty()) {
                        RouteRepository.loadFromDisk(this)
                    } else {
                        RouteRepository.saveToDisk(this)
                    }
                    RouteRepository.isRouteActive = true
                    WorldCityRepository.isRandomActive = false
                    val firstPoint = RouteRepository.getCurrentPoint()
                    if (firstPoint != null) {
                        targetLatitude = firstPoint.lat
                        targetLongitude = firstPoint.lng
                        currentBearing = firstPoint.bearing
                        currentSpeed = firstPoint.speed
                    }
                }
                else -> {
                    mockMode = MODE_FIXED
                    RouteRepository.isRouteActive = false
                    WorldCityRepository.isRandomActive = false
                    targetLatitude = intent.getDoubleExtra(EXTRA_LATITUDE, targetLatitude)
                    targetLongitude = intent.getDoubleExtra(EXTRA_LONGITUDE, targetLongitude)
                    currentBearing = Random.nextFloat() * 360f
                    currentSpeed = 0f
                }
            }
        }

        currentMockMode = mockMode
        currentMockLat = targetLatitude
        currentMockLng = targetLongitude
        currentMockBearing = currentBearing
        currentMockSpeed = currentSpeed
        isRouteRunning = (mockMode == MODE_ROUTE)
        isRandomRunning = (mockMode == MODE_RANDOM)

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
                putInt(KEY_MOCK_MODE, mockMode)
                putBoolean(KEY_IS_ROUTE_MODE, mockMode == MODE_ROUTE)
                putFloat(KEY_TARGET_LAT, targetLatitude.toFloat())
                putFloat(KEY_TARGET_LNG, targetLongitude.toFloat())
                putFloat(KEY_BEARING, currentBearing)
                putFloat(KEY_SPEED, currentSpeed)
                putInt(KEY_ROUTE_INDEX, RouteRepository.currentIndex)
                putInt(KEY_RANDOM_CITY_INDEX, WorldCityRepository.currentCityIndex)
                putInt(KEY_RANDOM_SECS_REMAINING, randomSecondsRemaining)
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
        isRouteRunning = (mockMode == MODE_ROUTE)
        isRandomRunning = (mockMode == MODE_RANDOM)
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

    private fun stepRandomCityMocking() {
        val city = WorldCityRepository.getCurrentCity()
        targetLatitude = city.lat
        targetLongitude = city.lng
        currentBearing = 0f
        currentSpeed = 0f

        currentMockLat = targetLatitude
        currentMockLng = targetLongitude
        currentMockBearing = 0f
        currentMockSpeed = 0f
        currentCityName = city.name
        currentCityCountry = city.country
        currentCityFlag = city.flag
        currentRemainingSecs = randomSecondsRemaining

        // Natural micro-jitter (1-2 meters) around city landmark
        val jitterLat = targetLatitude + Random.nextDouble(-0.000008, 0.000008)
        val jitterLng = targetLongitude + Random.nextDouble(-0.000008, 0.000008)

        pushToProvider(LocationManager.GPS_PROVIDER, jitterLat, jitterLng, 0f, 0f)
        pushToProvider(LocationManager.NETWORK_PROVIDER, jitterLat, jitterLng, 0f, 0f)

        randomSecondsRemaining--

        if (randomSecondsRemaining <= 0) {
            val nextCity = WorldCityRepository.nextCity()
            randomSecondsRemaining = WorldCityRepository.INTERVAL_SECONDS
            targetLatitude = nextCity.lat
            targetLongitude = nextCity.lng
            currentMockLat = targetLatitude
            currentMockLng = targetLongitude
            currentCityName = nextCity.name
            currentCityCountry = nextCity.country
            currentCityFlag = nextCity.flag
            currentRemainingSecs = randomSecondsRemaining
            saveState()
            broadcastRandomCityUpdate(nextCity, randomSecondsRemaining, true)
            updateRandomNotification(nextCity, randomSecondsRemaining)
        } else {
            if (randomSecondsRemaining % 5 == 0) {
                saveState()
            }
            broadcastRandomCityUpdate(city, randomSecondsRemaining, false)
            updateRandomNotification(city, randomSecondsRemaining)
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
        isRandomRunning = false
        mockMode = MODE_FIXED
        currentMockMode = MODE_FIXED
        RouteRepository.isRouteActive = false
        WorldCityRepository.isRandomActive = false
        handler.removeCallbacks(updateRunnable)
        releaseWakeLock()

        clearState()

        removeTestProviderSafe(LocationManager.GPS_PROVIDER)
        removeTestProviderSafe(LocationManager.NETWORK_PROVIDER)

        broadcastStatus(false)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
        return when (mockMode) {
            MODE_RANDOM -> {
                val city = WorldCityRepository.getCurrentCity()
                val stopPendingIntent = getStopPendingIntent()
                val mainPendingIntent = getMainPendingIntent()
                val nextPendingIntent = getNextCityPendingIntent()
                val timeText = formatCountdown(randomSecondsRemaining)
                val title = "${city.flag} ${city.name}, ${city.country}"
                val content = "Next city in $timeText • 24/7 Random Mock"
                val elapsed = (WorldCityRepository.INTERVAL_SECONDS - randomSecondsRemaining).coerceAtLeast(0)

                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(R.drawable.ic_location)
                    .setContentIntent(mainPendingIntent)
                    .addAction(R.drawable.ic_swap, "Next City ⏭", nextPendingIntent)
                    .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
                    .setProgress(WorldCityRepository.INTERVAL_SECONDS, elapsed, false)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            }
            MODE_ROUTE -> {
                val stopPendingIntent = getStopPendingIntent()
                val mainPendingIntent = getMainPendingIntent()
                val progress = RouteRepository.getProgressPercent()
                val speedKmH = (currentSpeed * 3.6).toInt()

                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🚗 Route Mocking Active (24/7)")
                    .setContentText("Progress: $progress% • Speed: $speedKmH km/h")
                    .setSmallIcon(R.drawable.ic_location)
                    .setContentIntent(mainPendingIntent)
                    .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            }
            else -> {
                val stopPendingIntent = getStopPendingIntent()
                val mainPendingIntent = getMainPendingIntent()

                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("📍 Mock Location Active (24/7)")
                    .setContentText("Lat: %.4f, Lng: %.4f • Background active".format(targetLatitude, targetLongitude))
                    .setSmallIcon(R.drawable.ic_location)
                    .setContentIntent(mainPendingIntent)
                    .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            }
        }
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

    private fun updateRandomNotification(city: WorldCity, remainingSecs: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val stopPendingIntent = getStopPendingIntent()
        val mainPendingIntent = getMainPendingIntent()
        val nextPendingIntent = getNextCityPendingIntent()

        val timeText = formatCountdown(remainingSecs)
        val title = "${city.flag} ${city.name}, ${city.country}"
        val content = "Next city in $timeText • 24/7 Random Mock"
        val elapsed = (WorldCityRepository.INTERVAL_SECONDS - remainingSecs).coerceAtLeast(0)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_swap, "Next City ⏭", nextPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
            .setProgress(WorldCityRepository.INTERVAL_SECONDS, elapsed, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun formatCountdown(secs: Int): String {
        val m = (secs.coerceAtLeast(0)) / 60
        val s = (secs.coerceAtLeast(0)) % 60
        return "%dm %02ds".format(m, s)
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

    private fun getNextCityPendingIntent(): PendingIntent {
        val nextIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_NEXT_RANDOM_CITY
        }
        return PendingIntent.getService(
            this,
            104,
            nextIntent,
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
            putExtra(EXTRA_MODE, mockMode)
            putExtra(EXTRA_IS_ROUTE, mockMode == MODE_ROUTE)
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

    private fun broadcastRandomCityUpdate(
        city: WorldCity,
        remainingSecs: Int,
        cityChanged: Boolean
    ) {
        val intent = Intent(ACTION_RANDOM_UPDATE).apply {
            putExtra(EXTRA_CITY_NAME, city.name)
            putExtra(EXTRA_COUNTRY_NAME, city.country)
            putExtra(EXTRA_FLAG_EMOJI, city.flag)
            putExtra(EXTRA_LATITUDE, city.lat)
            putExtra(EXTRA_LONGITUDE, city.lng)
            putExtra(EXTRA_CITY_INDEX, WorldCityRepository.currentCityIndex)
            putExtra(EXTRA_TOTAL_CITIES, WorldCityRepository.CITIES.size)
            putExtra(EXTRA_SECONDS_REMAINING, remainingSecs)
            putExtra(EXTRA_CITY_CHANGED, cityChanged)
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
                    action = ACTION_RESUME
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
        const val KEY_MOCK_MODE = "key_mock_mode"
        const val KEY_IS_ROUTE_MODE = "key_is_route_mode"
        const val KEY_TARGET_LAT = "key_target_lat"
        const val KEY_TARGET_LNG = "key_target_lng"
        const val KEY_BEARING = "key_bearing"
        const val KEY_SPEED = "key_speed"
        const val KEY_ROUTE_INDEX = "key_route_index"
        const val KEY_RANDOM_CITY_INDEX = "key_random_city_index"
        const val KEY_RANDOM_SECS_REMAINING = "key_random_secs_remaining"

        const val MODE_FIXED = 0
        const val MODE_ROUTE = 1
        const val MODE_RANDOM = 2

        const val ACTION_START = "com.lits.mocklocation.ACTION_START"
        const val ACTION_START_ROUTE = "com.lits.mocklocation.ACTION_START_ROUTE"
        const val ACTION_START_RANDOM = "com.lits.mocklocation.ACTION_START_RANDOM"
        const val ACTION_RESUME = "com.lits.mocklocation.ACTION_RESUME"
        const val ACTION_NEXT_RANDOM_CITY = "com.lits.mocklocation.ACTION_NEXT_RANDOM_CITY"
        const val ACTION_STOP = "com.lits.mocklocation.ACTION_STOP"
        const val ACTION_STATUS_CHANGED = "com.lits.mocklocation.ACTION_STATUS_CHANGED"
        const val ACTION_ROUTE_PROGRESS = "com.lits.mocklocation.ACTION_ROUTE_PROGRESS"
        const val ACTION_RANDOM_UPDATE = "com.lits.mocklocation.ACTION_RANDOM_UPDATE"
        const val ACTION_REQUEST_STATUS = "com.lits.mocklocation.ACTION_REQUEST_STATUS"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_BEARING = "extra_bearing"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_REACHED = "extra_reached"
        const val EXTRA_IS_ACTIVE = "extra_is_active"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_IS_ROUTE = "extra_is_route"

        const val EXTRA_CITY_NAME = "extra_city_name"
        const val EXTRA_COUNTRY_NAME = "extra_country_name"
        const val EXTRA_FLAG_EMOJI = "extra_flag_emoji"
        const val EXTRA_CITY_INDEX = "extra_city_index"
        const val EXTRA_TOTAL_CITIES = "extra_total_cities"
        const val EXTRA_SECONDS_REMAINING = "extra_seconds_remaining"
        const val EXTRA_CITY_CHANGED = "extra_city_changed"

        var isRunning = false
            private set
        var currentMockMode = MODE_FIXED
            private set
        var isRouteRunning = false
            private set
        var isRandomRunning = false
            private set

        var currentMockLat = 19.1989
            private set
        var currentMockLng = 73.1918
            private set
        var currentMockBearing = 0f
            private set
        var currentMockSpeed = 0f
            private set

        var currentCityName = "Tokyo"
            private set
        var currentCityCountry = "Japan"
            private set
        var currentCityFlag = "🇯🇵"
            private set
        var currentRemainingSecs = 120
            private set
    }
}
