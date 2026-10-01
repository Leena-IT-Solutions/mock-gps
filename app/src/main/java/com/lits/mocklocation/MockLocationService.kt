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

    private var targetLatitude = 19.1989
    private var targetLongitude = 73.1918

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                pushMockLocation()
                // Update every 1000ms
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

        targetLatitude = intent?.getDoubleExtra(EXTRA_LATITUDE, targetLatitude) ?: targetLatitude
        targetLongitude = intent?.getDoubleExtra(EXTRA_LONGITUDE, targetLongitude) ?: targetLongitude

        startForegroundWithNotification()
        startMocking()

        return START_STICKY
    }

    private fun startMocking() {
        isMocking = true

        setupTestProvider(LocationManager.GPS_PROVIDER)
        setupTestProvider(LocationManager.NETWORK_PROVIDER)

        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)

        broadcastStatus(true)
    }

    private fun setupTestProvider(provider: String) {
        try {
            // Remove previous instance if any
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

    private fun pushMockLocation() {
        // Natural micro-jitter (1-2 meters) so apps treat it as active real GPS
        val jitterLat = targetLatitude + (Random.nextDouble(-0.000008, 0.000008))
        val jitterLng = targetLongitude + (Random.nextDouble(-0.000008, 0.000008))

        pushToProvider(LocationManager.GPS_PROVIDER, jitterLat, jitterLng)
        pushToProvider(LocationManager.NETWORK_PROVIDER, jitterLat, jitterLng)
    }

    private fun pushToProvider(provider: String, lat: Double, lng: Double) {
        try {
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lng
                altitude = 12.0
                bearing = Random.nextFloat() * 360f
                speed = 0.0f
                accuracy = 1.5f + Random.nextFloat() * 1.5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 0.5f
                    verticalAccuracyMeters = 0.8f
                    speedAccuracyMetersPerSecond = 0.2f
                }
            }
            locationManager.setTestProviderLocation(provider, loc)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopMocking() {
        isMocking = false
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
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            102,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 Mock Location Active")
            .setContentText("Lat: %.4f, Lng: %.4f".format(targetLatitude, targetLongitude))
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop Mocking", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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
            putExtra(EXTRA_LATITUDE, targetLatitude)
            putExtra(EXTRA_LONGITUDE, targetLongitude)
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
        const val ACTION_STOP = "com.lits.mocklocation.ACTION_STOP"
        const val ACTION_STATUS_CHANGED = "com.lits.mocklocation.ACTION_STATUS_CHANGED"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_IS_ACTIVE = "extra_is_active"

        var isRunning = false
            private set
    }
}
