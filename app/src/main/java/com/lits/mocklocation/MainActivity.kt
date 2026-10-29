package com.lits.mocklocation

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lits.mocklocation.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isMockRunning = false
    private var currentMode = MockLocationService.MODE_FIXED
    private val isRouteMode get() = (currentMode == MockLocationService.MODE_ROUTE)
    private var selectedDurationMinutes = 15

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockLocationService.ACTION_STATUS_CHANGED -> {
                    val active = intent.getBooleanExtra(MockLocationService.EXTRA_IS_ACTIVE, false)
                    val mode = intent.getIntExtra(MockLocationService.EXTRA_MODE, currentMode)
                    updateUiState(active, mode)
                }
                MockLocationService.ACTION_ROUTE_PROGRESS -> {
                    val progress = intent.getIntExtra(MockLocationService.EXTRA_PROGRESS, 0)
                    val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LATITUDE, 0.0)
                    val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LONGITUDE, 0.0)
                    val bearing = intent.getFloatExtra(MockLocationService.EXTRA_BEARING, 0f)
                    val speed = intent.getFloatExtra(MockLocationService.EXTRA_SPEED, 0f)
                    val reached = intent.getBooleanExtra(MockLocationService.EXTRA_REACHED, false)

                    updateRouteProgress(progress, lat, lng, bearing, speed, reached)
                }
                MockLocationService.ACTION_RANDOM_UPDATE -> {
                    val cityName = intent.getStringExtra(MockLocationService.EXTRA_CITY_NAME) ?: "Tokyo"
                    val countryName = intent.getStringExtra(MockLocationService.EXTRA_COUNTRY_NAME) ?: "Japan"
                    val flag = intent.getStringExtra(MockLocationService.EXTRA_FLAG_EMOJI) ?: "🇯🇵"
                    val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LATITUDE, 35.6762)
                    val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LONGITUDE, 139.6503)
                    val cityIdx = intent.getIntExtra(MockLocationService.EXTRA_CITY_INDEX, 0)
                    val totalCities = intent.getIntExtra(MockLocationService.EXTRA_TOTAL_CITIES, WorldCityRepository.CITIES.size)
                    val remainingSecs = intent.getIntExtra(MockLocationService.EXTRA_SECONDS_REMAINING, WorldCityRepository.INTERVAL_SECONDS)
                    val changed = intent.getBooleanExtra(MockLocationService.EXTRA_CITY_CHANGED, false)

                    updateRandomCityProgress(cityName, countryName, flag, lat, lng, cityIdx, totalCities, remainingSecs, changed)
                }
            }
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Permissions handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val extraBottom = (12 * density).toInt()
            view.setPadding(
                (16 * density).toInt(),
                insets.top.coerceAtLeast((10 * density).toInt()),
                (16 * density).toInt(),
                insets.bottom + extraBottom
            )
            windowInsets
        }

        askRequiredPermissions()
        setupModeSwitching()
        setupListeners()
        updateUiState(false, MockLocationService.MODE_FIXED)
        setupMapWebView()
    }

    override fun onResume() {
        super.onResume()
        checkMockLocationPermission()
        checkBatteryOptimizationStatus()
        syncStateWithRunningService()

        val filter = IntentFilter().apply {
            addAction(MockLocationService.ACTION_STATUS_CHANGED)
            addAction(MockLocationService.ACTION_ROUTE_PROGRESS)
            addAction(MockLocationService.ACTION_RANDOM_UPDATE)
        }
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Please allow unrestricted battery in app settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
            if (isIgnoring) {
                binding.btnBatteryOptimization.text = "⚡ 24/7 ON"
                binding.btnBatteryOptimization.setTextColor(Color.parseColor("#38BDF8"))
                binding.cardBatteryWarning.visibility = android.view.View.GONE
            } else {
                binding.btnBatteryOptimization.text = "⚠️ 24/7 OFF"
                binding.btnBatteryOptimization.setTextColor(Color.parseColor("#F59E0B"))
                binding.cardBatteryWarning.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun syncStateWithRunningService() {
        val prefs = getSharedPreferences(MockLocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val isMocking = prefs.getBoolean(MockLocationService.KEY_IS_MOCKING, false) || MockLocationService.isRunning
        val mode = prefs.getInt(MockLocationService.KEY_MOCK_MODE, MockLocationService.currentMockMode)

        if (isMocking) {
            updateUiState(true, mode)
            when (mode) {
                MockLocationService.MODE_RANDOM -> {
                    switchMode(MockLocationService.MODE_RANDOM)
                    val cityIdx = prefs.getInt(MockLocationService.KEY_RANDOM_CITY_INDEX, WorldCityRepository.currentCityIndex)
                    val remaining = prefs.getInt(MockLocationService.KEY_RANDOM_SECS_REMAINING, WorldCityRepository.INTERVAL_SECONDS)
                    val city = WorldCityRepository.setCityByIndex(cityIdx)
                    updateRandomCityUi(city, remaining)
                    binding.mapWebView.evaluateJavascript("if (window.setCityLocation) { window.setCityLocation(${city.lat}, ${city.lng}); }", null)
                }
                MockLocationService.MODE_ROUTE -> {
                    switchMode(MockLocationService.MODE_ROUTE)
                    if (RouteRepository.getPoints().isEmpty()) {
                        RouteRepository.loadFromDisk(this)
                    }
                    RouteRepository.summary?.let {
                        if (binding.etRouteFrom.text.isNullOrEmpty()) binding.etRouteFrom.setText(it.originName)
                        if (binding.etRouteTo.text.isNullOrEmpty()) binding.etRouteTo.setText(it.destinationName)
                    }
                    val point = RouteRepository.getCurrentPoint()
                    val lat = point?.lat ?: MockLocationService.currentMockLat
                    val lng = point?.lng ?: MockLocationService.currentMockLng
                    val bearing = point?.bearing ?: MockLocationService.currentMockBearing
                    val speed = point?.speed ?: MockLocationService.currentMockSpeed
                    val reached = RouteRepository.hasReachedDestination()
                    val progress = RouteRepository.getProgressPercent()
                    updateRouteProgress(progress, lat, lng, bearing, speed, reached)
                }
                else -> {
                    switchMode(MockLocationService.MODE_FIXED)
                    val lat = prefs.getFloat(MockLocationService.KEY_TARGET_LAT, 19.1989f).toDouble()
                    val lng = prefs.getFloat(MockLocationService.KEY_TARGET_LNG, 73.1918f).toDouble()
                    binding.etLatitude.setText(String.format(Locale.US, "%.6f", lat))
                    binding.etLongitude.setText(String.format(Locale.US, "%.6f", lng))
                    updateMapMarker(lat, lng)
                }
            }
        } else {
            updateUiState(false, currentMode)
        }
    }

    private fun checkMockLocationPermission() {
        val isMockAllowed = isMockLocationAllowed()
        binding.cardWarning.visibility = if (isMockAllowed) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isMockLocationAllowed(): Boolean {
        val opsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                opsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                opsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun setupModeSwitching() {
        binding.btnModeFixed.setOnClickListener {
            switchMode(MockLocationService.MODE_FIXED)
        }

        binding.btnModeRoute.setOnClickListener {
            switchMode(MockLocationService.MODE_ROUTE)
        }

        binding.btnModeRandom.setOnClickListener {
            switchMode(MockLocationService.MODE_RANDOM)
        }
    }

    private fun switchMode(mode: Int) {
        currentMode = mode
        when (mode) {
            MockLocationService.MODE_FIXED -> {
                binding.btnModeFixed.setBackgroundColor(Color.parseColor("#6366F1"))
                binding.btnModeFixed.setTextColor(Color.WHITE)
                binding.btnModeRoute.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeRoute.setTextColor(Color.parseColor("#94A3B8"))
                binding.btnModeRandom.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeRandom.setTextColor(Color.parseColor("#94A3B8"))

                binding.cardFixedSection.visibility = android.view.View.VISIBLE
                binding.cardRouteSection.visibility = android.view.View.GONE
                binding.cardRandomSection.visibility = android.view.View.GONE
                binding.btnStartMock.text = "START MOCK"
                binding.tvMapHint.text = "Tap to place pin • Drag to pan map"

                binding.mapWebView.evaluateJavascript("if (window.setRouteMode) { window.setRouteMode(false); }", null)
            }
            MockLocationService.MODE_ROUTE -> {
                binding.btnModeRoute.setBackgroundColor(Color.parseColor("#6366F1"))
                binding.btnModeRoute.setTextColor(Color.WHITE)
                binding.btnModeFixed.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeFixed.setTextColor(Color.parseColor("#94A3B8"))
                binding.btnModeRandom.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeRandom.setTextColor(Color.parseColor("#94A3B8"))

                binding.cardFixedSection.visibility = android.view.View.GONE
                binding.cardRouteSection.visibility = android.view.View.VISIBLE
                binding.cardRandomSection.visibility = android.view.View.GONE
                binding.btnStartMock.text = "START ROUTE MOCK"
                binding.tvMapHint.text = "Build a route to start road simulation"

                binding.mapWebView.evaluateJavascript("if (window.setRouteMode) { window.setRouteMode(true); }", null)
            }
            MockLocationService.MODE_RANDOM -> {
                binding.btnModeRandom.setBackgroundColor(Color.parseColor("#6366F1"))
                binding.btnModeRandom.setTextColor(Color.WHITE)
                binding.btnModeFixed.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeFixed.setTextColor(Color.parseColor("#94A3B8"))
                binding.btnModeRoute.setBackgroundColor(Color.TRANSPARENT)
                binding.btnModeRoute.setTextColor(Color.parseColor("#94A3B8"))

                binding.cardFixedSection.visibility = android.view.View.GONE
                binding.cardRouteSection.visibility = android.view.View.GONE
                binding.cardRandomSection.visibility = android.view.View.VISIBLE
                binding.btnStartMock.text = "START RANDOM MOCK"
                binding.tvMapHint.text = "Major world cities • Auto jumps every 2 mins"

                val city = WorldCityRepository.getCurrentCity()
                updateRandomCityUi(city, WorldCityRepository.secondsRemaining)
                binding.mapWebView.evaluateJavascript("if (window.setCityLocation) { window.setCityLocation(${city.lat}, ${city.lng}); }", null)
            }
        }
    }

    private fun setupListeners() {
        binding.btnBatteryOptimization.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        binding.btnGrantBatteryExemption.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        binding.btnOpenDevSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Please open Settings -> Developer options manually", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnStartMock.setOnClickListener {
            if (isMockRunning) return@setOnClickListener
            when (currentMode) {
                MockLocationService.MODE_ROUTE -> startRouteMockService()
                MockLocationService.MODE_RANDOM -> startRandomMockService()
                else -> startFixedMockService()
            }
        }

        binding.btnStopMock.setOnClickListener {
            if (!isMockRunning) return@setOnClickListener
            stopMockService()
        }

        // Quick City Presets (Fixed Mode)
        binding.btnPresetAmbernath.setOnClickListener { setCoordinates(19.1989, 73.1918) }
        binding.btnPresetMumbai.setOnClickListener { setCoordinates(19.0657, 72.8687) }
        binding.btnPresetThane.setOnClickListener { setCoordinates(19.2183, 72.9781) }
        binding.btnPresetPune.setOnClickListener { setCoordinates(18.5204, 73.8567) }
        binding.btnPresetDelhi.setOnClickListener { setCoordinates(28.6139, 77.2090) }

        // Route Controls
        binding.btnSwapRoute.setOnClickListener {
            val from = binding.etRouteFrom.text.toString()
            val to = binding.etRouteTo.text.toString()
            binding.etRouteFrom.setText(to)
            binding.etRouteTo.setText(from)
            triggerBuildRoute()
        }

        // Duration chips up to 12 hours
        val durButtons = listOf(
            binding.btnDur15m to 15,
            binding.btnDur30m to 30,
            binding.btnDur1h to 60,
            binding.btnDur2h to 120,
            binding.btnDur3h to 180,
            binding.btnDur4h to 240,
            binding.btnDur6h to 360,
            binding.btnDur8h to 480,
            binding.btnDur10h to 600,
            binding.btnDur12h to 720
        )

        for ((btn, mins) in durButtons) {
            btn.setOnClickListener {
                selectedDurationMinutes = mins
                durButtons.forEach { (b, m) ->
                    if (m == mins) {
                        b.setBackgroundColor(Color.parseColor("#4F46E5"))
                        b.setTextColor(Color.WHITE)
                    } else {
                        b.setBackgroundColor(Color.parseColor("#1E293B"))
                        b.setTextColor(Color.parseColor("#94A3B8"))
                    }
                }
                binding.btnDurCustom.setBackgroundColor(Color.parseColor("#1E293B"))
                binding.btnDurCustom.setTextColor(Color.parseColor("#94A3B8"))
                triggerBuildRoute()
            }
        }

        binding.btnDurCustom.setOnClickListener {
            showCustomDurationDialog(durButtons)
        }

        binding.btnBuildRoute.setOnClickListener {
            triggerBuildRoute()
        }

        // Random Mode: Next City button
        binding.btnNextCity.setOnClickListener {
            if (isMockRunning && currentMode == MockLocationService.MODE_RANDOM) {
                val intent = Intent(this, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_NEXT_RANDOM_CITY
                }
                startService(intent)
            } else {
                val nextCity = WorldCityRepository.nextCity()
                updateRandomCityUi(nextCity, WorldCityRepository.INTERVAL_SECONDS)
                binding.mapWebView.evaluateJavascript("if (window.setCityLocation) { window.setCityLocation(${nextCity.lat}, ${nextCity.lng}); }", null)
            }
        }

        // Random Mode: Quick City Jump Chips
        val quickCityChips = listOf(
            binding.btnQuickTokyo to "Tokyo",
            binding.btnQuickParis to "Paris",
            binding.btnQuickNYC to "New York City",
            binding.btnQuickLondon to "London",
            binding.btnQuickDubai to "Dubai",
            binding.btnQuickSingapore to "Singapore",
            binding.btnQuickSydney to "Sydney",
            binding.btnQuickRome to "Rome"
        )
        for ((btn, cityName) in quickCityChips) {
            btn.setOnClickListener {
                jumpToWorldCity(cityName)
            }
        }
    }

    private fun jumpToWorldCity(cityName: String) {
        val idx = WorldCityRepository.CITIES.indexOfFirst { it.name.equals(cityName, ignoreCase = true) }
        if (idx >= 0) {
            val city = WorldCityRepository.setCityByIndex(idx)
            updateRandomCityUi(city, WorldCityRepository.INTERVAL_SECONDS)
            binding.mapWebView.evaluateJavascript("if (window.setCityLocation) { window.setCityLocation(${city.lat}, ${city.lng}); }", null)

            if (isMockRunning && currentMode == MockLocationService.MODE_RANDOM) {
                startRandomMockService()
            }
        }
    }

    private fun showCustomDurationDialog(durButtons: List<Pair<com.google.android.material.button.MaterialButton, Int>>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_duration, null)
        val npHours = dialogView.findViewById<android.widget.NumberPicker>(R.id.npHours)
        val npMinutes = dialogView.findViewById<android.widget.NumberPicker>(R.id.npMinutes)
        val tvPreview = dialogView.findViewById<android.widget.TextView>(R.id.tvDurationResultPreview)

        npHours.minValue = 0
        npHours.maxValue = 12
        npHours.value = (selectedDurationMinutes / 60).coerceIn(0, 12)

        npMinutes.minValue = 0
        npMinutes.maxValue = 59
        npMinutes.value = (selectedDurationMinutes % 60).coerceIn(0, 59)

        fun updatePreview() {
            val h = npHours.value
            val m = npMinutes.value
            tvPreview.text = "Total: ${h} hr ${m} min"
        }

        npHours.setOnValueChangedListener { _, _, _ -> updatePreview() }
        npMinutes.setOnValueChangedListener { _, _, _ -> updatePreview() }
        updatePreview()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val totalMins = (npHours.value * 60 + npMinutes.value).coerceIn(1, 720)
                selectedDurationMinutes = totalMins

                // Uncheck preset buttons
                durButtons.forEach { (b, _) ->
                    b.setBackgroundColor(Color.parseColor("#1E293B"))
                    b.setTextColor(Color.parseColor("#94A3B8"))
                }

                // Highlight custom button
                val h = totalMins / 60
                val m = totalMins % 60
                binding.btnDurCustom.text = if (h > 0 && m > 0) "${h}h ${m}m ✎" else if (h > 0) "${h}h ✎" else "${m}m ✎"
                binding.btnDurCustom.setBackgroundColor(Color.parseColor("#4F46E5"))
                binding.btnDurCustom.setTextColor(Color.WHITE)

                triggerBuildRoute()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerBuildRoute() {
        val from = binding.etRouteFrom.text.toString().trim()
        val to = binding.etRouteTo.text.toString().trim()
        if (from.isEmpty() || to.isEmpty()) {
            Toast.makeText(this, "Please enter both From and To locations", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvRouteSummary.text = "Calculating driving route..."
        val safeFrom = JSONObject.quote(from)
        val safeTo = JSONObject.quote(to)
        binding.mapWebView.evaluateJavascript("if (window.buildRoute) { window.buildRoute($safeFrom, $safeTo, $selectedDurationMinutes); }", null)
    }

    private fun setCoordinates(lat: Double, lng: Double) {
        binding.etLatitude.setText(lat.toString())
        binding.etLongitude.setText(lng.toString())
        updateMapMarker(lat, lng)

        if (isMockRunning && currentMode == MockLocationService.MODE_FIXED) {
            startFixedMockService()
        }
    }

    private fun startFixedMockService() {
        if (!isMockLocationAllowed()) {
            Toast.makeText(this, "Please select this app in Developer Options first!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            return
        }

        val latStr = binding.etLatitude.text.toString().trim()
        val lngStr = binding.etLongitude.text.toString().trim()

        val lat = latStr.toDoubleOrNull() ?: 19.1989
        val lng = lngStr.toDoubleOrNull() ?: 73.1918

        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_LATITUDE, lat)
            putExtra(MockLocationService.EXTRA_LONGITUDE, lng)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUiState(true, MockLocationService.MODE_FIXED)
        Toast.makeText(this, "Fixed Mock GPS started!", Toast.LENGTH_SHORT).show()
    }

    private fun startRouteMockService() {
        if (!isMockLocationAllowed()) {
            Toast.makeText(this, "Please select this app in Developer Options first!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            return
        }

        if (RouteRepository.getPoints().isEmpty()) {
            Toast.makeText(this, "Please tap 'Build Route' first!", Toast.LENGTH_SHORT).show()
            triggerBuildRoute()
            return
        }

        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUiState(true, MockLocationService.MODE_ROUTE)
        Toast.makeText(this, "Route Mocking started!", Toast.LENGTH_SHORT).show()
    }

    private fun startRandomMockService() {
        if (!isMockLocationAllowed()) {
            Toast.makeText(this, "Please select this app in Developer Options first!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            return
        }

        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_RANDOM
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUiState(true, MockLocationService.MODE_RANDOM)
        Toast.makeText(this, "🌍 Random World Cities Mock started! (2 min intervals)", Toast.LENGTH_SHORT).show()
    }

    private fun stopMockService() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        startService(intent)
        updateUiState(false, currentMode)
        Toast.makeText(this, "Mocking stopped. Real GPS restored.", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(active: Boolean, mode: Int = currentMode) {
        isMockRunning = active
        if (active) {
            binding.tvStatusBadge.text = when (mode) {
                MockLocationService.MODE_ROUTE -> "ROUTE ACTIVE"
                MockLocationService.MODE_RANDOM -> "RANDOM ACTIVE"
                else -> "ACTIVE"
            }
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#065F46"))
            binding.tvStatusBadge.setTextColor(Color.parseColor("#34D399"))

            // Disable Start button
            binding.btnStartMock.isEnabled = false
            binding.btnStartMock.alpha = 0.40f

            // Enable Stop button
            binding.btnStopMock.isEnabled = true
            binding.btnStopMock.alpha = 1.0f
        } else {
            binding.tvStatusBadge.text = "IDLE"
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#334155"))
            binding.tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"))

            // Enable Start button
            binding.btnStartMock.isEnabled = true
            binding.btnStartMock.alpha = 1.0f

            // Disable Stop button
            binding.btnStopMock.isEnabled = false
            binding.btnStopMock.alpha = 0.40f
        }
    }

    private fun updateRouteProgress(
        progress: Int,
        lat: Double,
        lng: Double,
        bearing: Float,
        speed: Float,
        reached: Boolean
    ) {
        val speedKmH = (speed * 3.6).toInt()
        binding.tvStatusBadge.text = if (reached) "ARRIVED" else "$progress%"
        binding.tvRouteSummary.text = if (reached) {
            "🏁 Arrived at Destination • Mocking active"
        } else {
            "Progress: $progress% • Speed: $speedKmH km/h • Bearing: ${bearing.toInt()}°"
        }

        binding.mapWebView.evaluateJavascript(
            "if (window.updateVehiclePosition) { window.updateVehiclePosition($lat, $lng, $bearing); }",
            null
        )
    }

    private fun updateRandomCityProgress(
        cityName: String,
        countryName: String,
        flag: String,
        lat: Double,
        lng: Double,
        cityIdx: Int,
        totalCities: Int,
        remainingSecs: Int,
        changed: Boolean
    ) {
        binding.tvCityFlag.text = flag
        binding.tvCityName.text = "$cityName, $countryName"
        binding.tvCityCoords.text = String.format(Locale.US, "%.6f, %.6f", lat, lng)
        val m = (remainingSecs.coerceAtLeast(0)) / 60
        val s = (remainingSecs.coerceAtLeast(0)) % 60
        binding.tvRandomCountdown.text = "%dm %02ds left".format(m, s)
        binding.tvCityIndex.text = "City ${cityIdx + 1} of $totalCities"
        binding.progressRandomTimer.progress = (WorldCityRepository.INTERVAL_SECONDS - remainingSecs).coerceIn(0, WorldCityRepository.INTERVAL_SECONDS)

        if (isMockRunning && currentMode == MockLocationService.MODE_RANDOM) {
            binding.tvStatusBadge.text = "%dm %02ds".format(m, s)
        }

        if (changed) {
            binding.mapWebView.evaluateJavascript("if (window.setCityLocation) { window.setCityLocation($lat, $lng); }", null)
        }
    }

    private fun updateRandomCityUi(city: WorldCity, remainingSecs: Int) {
        binding.tvCityFlag.text = city.flag
        binding.tvCityName.text = "${city.name}, ${city.country}"
        binding.tvCityCoords.text = String.format(Locale.US, "%.6f, %.6f", city.lat, city.lng)
        val m = (remainingSecs.coerceAtLeast(0)) / 60
        val s = (remainingSecs.coerceAtLeast(0)) % 60
        binding.tvRandomCountdown.text = "%dm %02ds left".format(m, s)
        binding.tvCityIndex.text = "City ${WorldCityRepository.currentCityIndex + 1} of ${WorldCityRepository.CITIES.size}"
        binding.progressRandomTimer.progress = (WorldCityRepository.INTERVAL_SECONDS - remainingSecs).coerceIn(0, WorldCityRepository.INTERVAL_SECONDS)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupMapWebView() {
        binding.mapWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; motorola edge 50 pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        binding.mapWebView.webChromeClient = WebChromeClient()
        binding.mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                syncStateWithRunningService()
            }
        }

        binding.mapWebView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        binding.mapWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMapClick(lat: Double, lng: Double) {
                runOnUiThread {
                    if (currentMode == MockLocationService.MODE_ROUTE) {
                        // In route mode, clicking sets the destination
                        binding.etRouteTo.setText(String.format(Locale.US, "%.6f, %.6f", lat, lng))
                        triggerBuildRoute()
                    } else if (currentMode == MockLocationService.MODE_FIXED) {
                        binding.etLatitude.setText(String.format(Locale.US, "%.6f", lat))
                        binding.etLongitude.setText(String.format(Locale.US, "%.6f", lng))
                        if (isMockRunning) {
                            startFixedMockService()
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onRouteCalculated(
                routeJson: String,
                distanceMeters: Double,
                totalSeconds: Int,
                distText: String,
                durText: String
            ) {
                runOnUiThread {
                    try {
                        val jsonArr = JSONArray(routeJson)
                        val rawList = ArrayList<Pair<Double, Double>>(jsonArr.length())
                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            rawList.add(Pair(obj.getDouble("lat"), obj.getDouble("lng")))
                        }

                        val summary = RouteSummary(
                            originName = binding.etRouteFrom.text.toString(),
                            destinationName = binding.etRouteTo.text.toString(),
                            distanceMeters = distanceMeters,
                            durationSeconds = totalSeconds,
                            totalPoints = totalSeconds + 1
                        )

                        RouteRepository.setRoute(rawList, summary)

                        val speedKmH = ((distanceMeters / totalSeconds.coerceAtLeast(1)) * 3.6).toInt()
                        val speedDisplay = if (speedKmH == 0) String.format(Locale.US, "%.1f km/h", (distanceMeters / totalSeconds.coerceAtLeast(1)) * 3.6) else "$speedKmH km/h"
                        binding.tvRouteSummary.text = "Distance: $distText • Avg: $speedDisplay ($durText)"
                        Toast.makeText(this@MainActivity, "Route ready: $distText in $durText", Toast.LENGTH_SHORT).show()

                        if (isMockRunning && currentMode == MockLocationService.MODE_ROUTE) {
                            startRouteMockService()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        binding.tvRouteSummary.text = "Error parsing route points"
                    }
                }
            }

            @JavascriptInterface
            fun onRouteError(errorMsg: String) {
                runOnUiThread {
                    binding.tvRouteSummary.text = "Route failed: $errorMsg"
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }, "AndroidBridge")

        val initialLat = binding.etLatitude.text.toString().toDoubleOrNull() ?: 19.1989
        val initialLng = binding.etLongitude.text.toString().toDoubleOrNull() ?: 73.1918
        val apiKey = BuildConfig.MAPS_API_KEY

        val mapHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <style>
                    html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #0f172a; overflow: hidden; }
                    #pac-input {
                        background-color: #1e293b;
                        color: #ffffff;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        font-size: 13px;
                        font-weight: 500;
                        padding: 8px 12px;
                        width: calc(100% - 24px);
                        max-width: 340px;
                        margin-top: 10px;
                        border: 1.5px solid #475569;
                        border-radius: 10px;
                        box-shadow: 0 6px 16px rgba(0,0,0,0.6);
                        outline: none;
                        box-sizing: border-box;
                    }
                    #pac-input:focus {
                        border-color: #6366f1;
                        box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.4);
                    }
                    .custom-zoom-control {
                        position: absolute;
                        right: 14px;
                        bottom: 24px;
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                        z-index: 10;
                    }
                    .zoom-btn {
                        width: 44px;
                        height: 44px;
                        background: #1e293b;
                        border: 1.5px solid #475569;
                        border-radius: 12px;
                        color: #f8fafc;
                        font-size: 22px;
                        font-weight: bold;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        box-shadow: 0 6px 14px rgba(0,0,0,0.5);
                        cursor: pointer;
                        user-select: none;
                    }
                    .zoom-btn:active {
                        background: #334155;
                        transform: scale(0.94);
                    }
                </style>
                <script src="https://maps.googleapis.com/maps/api/js?key=$apiKey&libraries=places,geometry"></script>
            </head>
            <body>
                <input id="pac-input" class="controls" type="text" placeholder="🔍 Search place or address..." />
                <div id="map"></div>

                <div class="custom-zoom-control">
                    <div class="zoom-btn" onclick="window.zoomIn()">+</div>
                    <div class="zoom-btn" onclick="window.zoomOut()">−</div>
                    <div class="zoom-btn" onclick="window.recenterMarker()" style="font-size: 16px;">🎯</div>
                </div>

                <script>
                    var map, marker, carMarker, directionsService, directionsRenderer;
                    var isRouteActiveMode = false;

                    var darkMapStyle = [
                        { "elementType": "geometry", "stylers": [{ "color": "#1d2c4d" }] },
                        { "elementType": "labels.text.fill", "stylers": [{ "color": "#8ec3b9" }] },
                        { "elementType": "labels.text.stroke", "stylers": [{ "color": "#1a3646" }] },
                        {
                            "featureType": "administrative.country",
                            "elementType": "geometry.stroke",
                            "stylers": [{ "color": "#4b6878" }]
                        },
                        {
                            "featureType": "administrative.province",
                            "elementType": "geometry.stroke",
                            "stylers": [{ "color": "#4b6878" }]
                        },
                        {
                            "featureType": "landscape.man_made",
                            "elementType": "geometry.stroke",
                            "stylers": [{ "color": "#334e87" }]
                        },
                        {
                            "featureType": "landscape.natural",
                            "elementType": "geometry",
                            "stylers": [{ "color": "#021019" }]
                        },
                        {
                            "featureType": "poi",
                            "elementType": "geometry",
                            "stylers": [{ "color": "#283d6a" }]
                        },
                        {
                            "featureType": "poi",
                            "elementType": "labels.text.fill",
                            "stylers": [{ "color": "#6f9ba5" }]
                        },
                        {
                            "featureType": "poi",
                            "elementType": "labels.text.stroke",
                            "stylers": [{ "color": "#1d2c4d" }]
                        },
                        {
                            "featureType": "road",
                            "elementType": "geometry",
                            "stylers": [{ "color": "#304a7d" }]
                        },
                        {
                            "featureType": "road",
                            "elementType": "labels.text.fill",
                            "stylers": [{ "color": "#98a5be" }]
                        },
                        {
                            "featureType": "road",
                            "elementType": "labels.text.stroke",
                            "stylers": [{ "color": "#1d2c4d" }]
                        },
                        {
                            "featureType": "road.highway",
                            "elementType": "geometry",
                            "stylers": [{ "color": "#2c6675" }]
                        },
                        {
                            "featureType": "road.highway",
                            "elementType": "geometry.stroke",
                            "stylers": [{ "color": "#255e63" }]
                        },
                        {
                            "featureType": "road.highway",
                            "elementType": "labels.text.fill",
                            "stylers": [{ "color": "#b0d5ce" }]
                        },
                        {
                            "featureType": "road.highway",
                            "elementType": "labels.text.stroke",
                            "stylers": [{ "color": "#023e58" }]
                        },
                        {
                            "featureType": "transit",
                            "elementType": "labels.text.fill",
                            "stylers": [{ "color": "#98a5be" }]
                        },
                        {
                            "featureType": "transit",
                            "elementType": "labels.text.stroke",
                            "stylers": [{ "color": "#1d2c4d" }]
                        },
                        {
                            "featureType": "transit.line",
                            "elementType": "geometry.fill",
                            "stylers": [{ "color": "#283d6a" }]
                        },
                        {
                            "featureType": "transit.station",
                            "elementType": "geometry",
                            "stylers": [{ "color": "#3a4762" }]
                        },
                        {
                            "featureType": "water",
                            "elementType": "geometry",
                            "stylers": [{ "color": "#0e1626" }]
                        },
                        {
                            "featureType": "water",
                            "elementType": "labels.text.fill",
                            "stylers": [{ "color": "#4e6d70" }]
                        }
                    ];

                    function initMap() {
                        var initialPos = { lat: $initialLat, lng: $initialLng };

                        map = new google.maps.Map(document.getElementById('map'), {
                            center: initialPos,
                            zoom: 14,
                            styles: darkMapStyle,
                            disableDefaultUI: true,
                            gestureHandling: 'greedy'
                        });

                        marker = new google.maps.Marker({
                            position: initialPos,
                            map: map,
                            draggable: true,
                            animation: google.maps.Animation.DROP
                        });

                        var carIcon = {
                            path: google.maps.SymbolPath.FORWARD_CLOSED_ARROW,
                            scale: 6,
                            fillColor: "#38BDF8",
                            fillOpacity: 1,
                            strokeWeight: 2,
                            strokeColor: "#FFFFFF",
                            rotation: 0
                        };

                        carMarker = new google.maps.Marker({
                            position: initialPos,
                            map: map,
                            icon: carIcon,
                            visible: false,
                            zIndex: 999
                        });

                        directionsService = new google.maps.DirectionsService();
                        directionsRenderer = new google.maps.DirectionsRenderer({
                            map: map,
                            suppressMarkers: false,
                            polylineOptions: {
                                strokeColor: '#38BDF8',
                                strokeWeight: 6,
                                strokeOpacity: 0.85
                            }
                        });

                        var input = document.getElementById('pac-input');
                        var searchBox = new google.maps.places.SearchBox(input);
                        map.controls[google.maps.ControlPosition.TOP_CENTER].push(input);

                        map.addListener('bounds_changed', function() {
                            searchBox.setBounds(map.getBounds());
                        });

                        function notifyApp(lat, lng) {
                            if (window.AndroidBridge && window.AndroidBridge.onMapClick) {
                                window.AndroidBridge.onMapClick(lat, lng);
                            }
                        }

                        map.addListener('click', function(event) {
                            var lat = event.latLng.lat();
                            var lng = event.latLng.lng();
                            if (!isRouteActiveMode) {
                                marker.setPosition(event.latLng);
                            }
                            notifyApp(lat, lng);
                        });

                        marker.addListener('dragend', function(event) {
                            notifyApp(event.latLng.lat(), event.latLng.lng());
                        });

                        searchBox.addListener('places_changed', function() {
                            var places = searchBox.getPlaces();
                            if (places.length == 0) return;
                            var place = places[0];
                            if (!place.geometry || !place.geometry.location) return;

                            var loc = place.geometry.location;
                            map.setCenter(loc);
                            map.setZoom(15);
                            if (!isRouteActiveMode) {
                                marker.setPosition(loc);
                            }
                            notifyApp(loc.lat(), loc.lng());
                        });

                        window.setMarker = function(lat, lng) {
                            var newPos = { lat: lat, lng: lng };
                            marker.setPosition(newPos);
                            map.panTo(newPos);
                        };

                        window.setCityLocation = function(lat, lng) {
                            var newPos = { lat: lat, lng: lng };
                            marker.setPosition(newPos);
                            marker.setVisible(true);
                            if (carMarker) carMarker.setVisible(false);
                            if (directionsRenderer) directionsRenderer.set('directions', null);
                            map.panTo(newPos);
                            map.setZoom(12);
                        };

                        window.setRouteMode = function(isRoute) {
                            isRouteActiveMode = isRoute;
                            if (isRoute) {
                                marker.setVisible(false);
                            } else {
                                marker.setVisible(true);
                                if (carMarker) carMarker.setVisible(false);
                                if (directionsRenderer) directionsRenderer.set('directions', null);
                            }
                        };

                        window.updateVehiclePosition = function(lat, lng, bearing) {
                            var pos = { lat: lat, lng: lng };
                            carMarker.setPosition(pos);
                            var icon = carMarker.getIcon();
                            if (icon) {
                                icon.rotation = bearing;
                                carMarker.setIcon(icon);
                            }
                            carMarker.setVisible(true);
                        };

                        window.buildRoute = function(fromQuery, toQuery, durationMinutes) {
                            var request = {
                                origin: fromQuery,
                                destination: toQuery,
                                travelMode: google.maps.TravelMode.DRIVING
                            };

                            directionsService.route(request, function(result, status) {
                                if (status === google.maps.DirectionsStatus.OK) {
                                    directionsRenderer.setDirections(result);
                                    marker.setVisible(false);

                                    var route = result.routes[0];
                                    var leg = route.legs[0];
                                    var totalDistanceMeters = leg.distance.value;
                                    var totalSeconds = durationMinutes * 60;

                                    // Extract raw road polyline vertices (~200 - 1500 points)
                                    var rawPoints = [];
                                    for (var i = 0; i < leg.steps.length; i++) {
                                        var step = leg.steps[i];
                                        for (var j = 0; j < step.path.length; j++) {
                                            rawPoints.push({
                                                lat: Math.round(step.path[j].lat() * 1000000) / 1000000,
                                                lng: Math.round(step.path[j].lng() * 1000000) / 1000000
                                            });
                                        }
                                    }

                                    // Position car marker at start point with initial heading
                                    if (rawPoints.length > 0) {
                                        var startPt = rawPoints[0];
                                        carMarker.setPosition({ lat: startPt.lat, lng: startPt.lng });
                                        var heading = 0;
                                        if (rawPoints.length > 1) {
                                            heading = google.maps.geometry.spherical.computeHeading(
                                                new google.maps.LatLng(startPt.lat, startPt.lng),
                                                new google.maps.LatLng(rawPoints[1].lat, rawPoints[1].lng)
                                            );
                                            if (heading < 0) heading += 360;
                                        }
                                        var icon = carMarker.getIcon();
                                        if (icon) {
                                            icon.rotation = heading;
                                            carMarker.setIcon(icon);
                                        }
                                        carMarker.setVisible(true);
                                    }

                                    var durLabel = durationMinutes >= 60 ?
                                        (Math.floor(durationMinutes / 60) + " hr" + (durationMinutes % 60 > 0 ? " " + (durationMinutes % 60) + " min" : "")) :
                                        (durationMinutes + " min");

                                    window.AndroidBridge.onRouteCalculated(
                                        JSON.stringify(rawPoints),
                                        totalDistanceMeters,
                                        totalSeconds,
                                        leg.distance.text,
                                        durLabel
                                    );
                                } else {
                                    window.AndroidBridge.onRouteError("Could not find route: " + status);
                                }
                            });
                        };

                        window.zoomIn = function() {
                            map.setZoom(map.getZoom() + 1);
                        };

                        window.zoomOut = function() {
                            map.setZoom(map.getZoom() - 1);
                        };

                        window.recenterMarker = function() {
                            if (isRouteActiveMode && carMarker && carMarker.getVisible()) {
                                map.panTo(carMarker.getPosition());
                            } else if (marker && marker.getPosition()) {
                                map.panTo(marker.getPosition());
                            }
                        };
                    }

                    window.onload = initMap;
                </script>
            </body>
            </html>
        """.trimIndent()

        binding.mapWebView.loadDataWithBaseURL("https://maps.googleapis.com", mapHtml, "text/html", "UTF-8", null)
    }

    private fun updateMapMarker(lat: Double, lng: Double) {
        binding.mapWebView.evaluateJavascript("if (window.setMarker) { window.setMarker($lat, $lng); }", null)
    }

    private fun askRequiredPermissions() {
        val permissionsToAsk = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToAsk.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToAsk.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToAsk.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToAsk.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToAsk.toTypedArray())
        }
    }
}
