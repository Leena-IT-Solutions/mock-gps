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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isMockRunning = false
    private var isRouteMode = false
    private var selectedDurationMinutes = 15

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockLocationService.ACTION_STATUS_CHANGED -> {
                    val active = intent.getBooleanExtra(MockLocationService.EXTRA_IS_ACTIVE, false)
                    val route = intent.getBooleanExtra(MockLocationService.EXTRA_IS_ROUTE, false)
                    updateUiState(active, route)
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
        setupMapWebView()
    }

    override fun onResume() {
        super.onResume()
        checkMockLocationPermission()
        val filter = IntentFilter().apply {
            addAction(MockLocationService.ACTION_STATUS_CHANGED)
            addAction(MockLocationService.ACTION_ROUTE_PROGRESS)
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
            switchMode(false)
        }

        binding.btnModeRoute.setOnClickListener {
            switchMode(true)
        }
    }

    private fun switchMode(routeMode: Boolean) {
        isRouteMode = routeMode
        if (routeMode) {
            binding.btnModeRoute.setBackgroundColor(Color.parseColor("#6366F1"))
            binding.btnModeRoute.setTextColor(Color.WHITE)
            binding.btnModeFixed.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeFixed.setTextColor(Color.parseColor("#94A3B8"))

            binding.cardFixedSection.visibility = android.view.View.GONE
            binding.cardRouteSection.visibility = android.view.View.VISIBLE
            binding.btnStartMock.text = "START ROUTE MOCK"
            binding.tvMapHint.text = "Build a route to start road simulation"

            binding.mapWebView.evaluateJavascript("if (window.setRouteMode) { window.setRouteMode(true); }", null)
        } else {
            binding.btnModeFixed.setBackgroundColor(Color.parseColor("#6366F1"))
            binding.btnModeFixed.setTextColor(Color.WHITE)
            binding.btnModeRoute.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeRoute.setTextColor(Color.parseColor("#94A3B8"))

            binding.cardFixedSection.visibility = android.view.View.VISIBLE
            binding.cardRouteSection.visibility = android.view.View.GONE
            binding.btnStartMock.text = "START MOCK"
            binding.tvMapHint.text = "Tap to place pin • Drag to pan map"

            binding.mapWebView.evaluateJavascript("if (window.setRouteMode) { window.setRouteMode(false); }", null)
        }
    }

    private fun setupListeners() {
        binding.btnOpenDevSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Please open Settings -> Developer options manually", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnStartMock.setOnClickListener {
            if (isRouteMode) {
                startRouteMockService()
            } else {
                startFixedMockService()
            }
        }

        binding.btnStopMock.setOnClickListener {
            stopMockService()
        }

        // Quick City Presets
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

        // Duration chips
        val durButtons = listOf(
            binding.btnDur5m to 5,
            binding.btnDur15m to 15,
            binding.btnDur30m to 30,
            binding.btnDur45m to 45,
            binding.btnDur60m to 60
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
                triggerBuildRoute()
            }
        }

        binding.btnBuildRoute.setOnClickListener {
            triggerBuildRoute()
        }
    }

    private fun triggerBuildRoute() {
        val from = binding.etRouteFrom.text.toString().trim()
        val to = binding.etRouteTo.text.toString().trim()
        if (from.isEmpty() || to.isEmpty()) {
            Toast.makeText(this, "Please enter both From and To locations", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvRouteSummary.text = "Calculating driving route..."
        binding.mapWebView.evaluateJavascript("if (window.buildRoute) { window.buildRoute('$from', '$to', $selectedDurationMinutes); }", null)
    }

    private fun setCoordinates(lat: Double, lng: Double) {
        binding.etLatitude.setText(lat.toString())
        binding.etLongitude.setText(lng.toString())
        updateMapMarker(lat, lng)

        if (isMockRunning && !isRouteMode) {
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

        updateUiState(true, false)
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

        updateUiState(true, true)
        Toast.makeText(this, "Route Mocking started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopMockService() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        startService(intent)
        updateUiState(false, isRouteMode)
        Toast.makeText(this, "Mocking stopped. Real GPS restored.", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(active: Boolean, route: Boolean) {
        isMockRunning = active
        if (active) {
            binding.tvStatusBadge.text = if (route) "ROUTE ACTIVE" else "ACTIVE"
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#065F46"))
            binding.tvStatusBadge.setTextColor(Color.parseColor("#34D399"))
        } else {
            binding.tvStatusBadge.text = "IDLE"
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#334155"))
            binding.tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
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
        binding.mapWebView.webViewClient = WebViewClient()

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
                    if (isRouteMode) {
                        // In route mode, clicking sets the destination
                        binding.etRouteTo.setText(String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng))
                        triggerBuildRoute()
                    } else {
                        binding.etLatitude.setText(String.format(java.util.Locale.US, "%.6f", lat))
                        binding.etLongitude.setText(String.format(java.util.Locale.US, "%.6f", lng))
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
                        val pointsList = ArrayList<RoutePoint>(jsonArr.length())
                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            pointsList.add(
                                RoutePoint(
                                    lat = obj.getDouble("lat"),
                                    lng = obj.getDouble("lng"),
                                    bearing = obj.optDouble("bearing", 0.0).toFloat(),
                                    speed = obj.optDouble("speed", 0.0).toFloat()
                                )
                            )
                        }

                        val summary = RouteSummary(
                            originName = binding.etRouteFrom.text.toString(),
                            destinationName = binding.etRouteTo.text.toString(),
                            distanceMeters = distanceMeters,
                            durationSeconds = totalSeconds,
                            totalPoints = pointsList.size
                        )

                        RouteRepository.setRoute(pointsList, summary)

                        val speedKmH = ((distanceMeters / totalSeconds.coerceAtLeast(1)) * 3.6).toInt()
                        binding.tvRouteSummary.text = "Distance: $distText • Avg: $speedKmH km/h (${pointsList.size} pts)"
                        Toast.makeText(this@MainActivity, "Route ready: $distText in $durText", Toast.LENGTH_SHORT).show()
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
        val apiKey = "AIzaSyBwYyZdXko52rrcTzFptPFRNMySD5_yGQQ"

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
                        box-shadow: 0 6px 20px rgba(99, 102, 241, 0.4);
                    }
                    #pac-input::placeholder { color: #94a3b8; }
                    .pac-container {
                        background-color: #1e293b !important;
                        border: 1px solid #475569 !important;
                        border-radius: 8px !important;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.6) !important;
                        z-index: 999999 !important;
                    }
                    .pac-item {
                        color: #f8fafc !important;
                        border-top: 1px solid #334155 !important;
                        padding: 8px 10px !important;
                        font-size: 12px !important;
                    }
                    .pac-item:hover, .pac-item-selected {
                        background-color: #334155 !important;
                    }
                    .pac-item-query {
                        color: #38bdf8 !important;
                        font-weight: bold !important;
                    }
                    .map-controls-group {
                        position: absolute;
                        right: 12px;
                        bottom: 24px;
                        display: flex;
                        flex-direction: column;
                        z-index: 9999;
                    }
                    .map-float-btn {
                        width: 42px;
                        height: 42px;
                        background: #1e293b;
                        border: 1.5px solid #475569;
                        border-radius: 10px;
                        color: #ffffff;
                        font-size: 20px;
                        font-weight: bold;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        box-shadow: 0 6px 16px rgba(0,0,0,0.6);
                        margin-bottom: 8px;
                        cursor: pointer;
                        user-select: none;
                        -webkit-user-select: none;
                    }
                    .map-float-btn:active {
                        background: #334155;
                        transform: scale(0.92);
                    }
                </style>
                <script src="https://maps.googleapis.com/maps/api/js?key=${apiKey}&libraries=places,geometry"></script>
            </head>
            <body>
                <input id="pac-input" type="text" placeholder="🔍 Search place or address..." />
                <div id="map"></div>
                <div class="map-controls-group">
                    <div class="map-float-btn" onclick="zoomIn()">+</div>
                    <div class="map-float-btn" onclick="zoomOut()">−</div>
                    <div class="map-float-btn" onclick="recenterMarker()" style="font-size: 16px;">🎯</div>
                </div>
                <script>
                    var map, marker, carMarker, directionsService, directionsRenderer;
                    var isDragging = false;
                    var isRouteActiveMode = false;

                    function initMap() {
                        var startPos = { lat: $initialLat, lng: $initialLng };
                        map = new google.maps.Map(document.getElementById('map'), {
                            center: startPos,
                            zoom: 15,
                            disableDefaultUI: true,
                            clickableIcons: false,
                            gestureHandling: 'greedy',
                            draggable: true,
                            styles: [
                                { "elementType": "geometry", "stylers": [{ "color": "#242f3e" }] },
                                { "elementType": "labels.text.fill", "stylers": [{ "color": "#746855" }] },
                                { "elementType": "labels.text.stroke", "stylers": [{ "color": "#242f3e" }] },
                                { "featureType": "administrative.locality", "elementType": "labels.text.fill", "stylers": [{ "color": "#d59563" }] },
                                { "featureType": "poi", "elementType": "labels.text.fill", "stylers": [{ "color": "#d59563" }] },
                                { "featureType": "road", "elementType": "geometry", "stylers": [{ "color": "#38414e" }] },
                                { "featureType": "road", "elementType": "geometry.stroke", "stylers": [{ "color": "#212a37" }] },
                                { "featureType": "road", "elementType": "labels.text.fill", "stylers": [{ "color": "#9ca5b3" }] },
                                { "featureType": "road.highway", "elementType": "geometry", "stylers": [{ "color": "#746855" }] },
                                { "featureType": "road.highway", "elementType": "geometry.stroke", "stylers": [{ "color": "#1f2835" }] },
                                { "featureType": "transit", "elementType": "geometry", "stylers": [{ "color": "#2f3948" }] },
                                { "featureType": "water", "elementType": "geometry", "stylers": [{ "color": "#17263c" }] }
                            ]
                        });

                        marker = new google.maps.Marker({
                            position: startPos,
                            map: map,
                            draggable: true,
                            animation: google.maps.Animation.DROP
                        });

                        // Navigation vehicle arrow marker
                        var carIcon = {
                            path: google.maps.SymbolPath.FORWARD_CLOSED_ARROW,
                            scale: 6,
                            fillColor: '#38bdf8',
                            fillOpacity: 1,
                            strokeWeight: 2,
                            strokeColor: '#ffffff',
                            rotation: 0
                        };
                        carMarker = new google.maps.Marker({
                            position: startPos,
                            map: map,
                            icon: carIcon,
                            visible: false
                        });

                        // Directions Service & Renderer
                        directionsService = new google.maps.DirectionsService();
                        directionsRenderer = new google.maps.DirectionsRenderer({
                            map: map,
                            suppressMarkers: false,
                            polylineOptions: {
                                strokeColor: '#38bdf8',
                                strokeWeight: 6,
                                strokeOpacity: 0.9
                            }
                        });

                        function notifyApp(lat, lng) {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onMapClick(lat, lng);
                            }
                        }

                        map.addListener('dragstart', function() {
                            isDragging = true;
                        });

                        map.addListener('dragend', function() {
                            setTimeout(function() {
                                isDragging = false;
                            }, 150);
                        });

                        map.addListener('click', function(e) {
                            if (isDragging) return;
                            var lat = e.latLng.lat();
                            var lng = e.latLng.lng();
                            if (!isRouteActiveMode) {
                                marker.setPosition(e.latLng);
                            }
                            notifyApp(lat, lng);
                        });

                        marker.addListener('dragend', function(e) {
                            var lat = e.latLng.lat();
                            var lng = e.latLng.lng();
                            notifyApp(lat, lng);
                        });

                        // Setup Places Autocomplete Search Box
                        var input = document.getElementById('pac-input');
                        map.controls[google.maps.ControlPosition.TOP_CENTER].push(input);
                        var autocomplete = new google.maps.places.Autocomplete(input);
                        autocomplete.bindTo('bounds', map);
                        autocomplete.addListener('place_changed', function() {
                            var place = autocomplete.getPlace();
                            if (!place.geometry || !place.geometry.location) {
                                return;
                            }
                            var loc = place.geometry.location;
                            map.panTo(loc);
                            map.setZoom(16);
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

                        window.setRouteMode = function(isRoute) {
                            isRouteActiveMode = isRoute;
                            if (isRoute) {
                                marker.setVisible(false);
                            } else {
                                marker.setVisible(true);
                                carMarker.setVisible(false);
                                directionsRenderer.set('directions', null);
                            }
                        };

                        window.updateVehiclePosition = function(lat, lng, bearing) {
                            var pos = { lat: lat, lng: lng };
                            carMarker.setPosition(pos);
                            var icon = carMarker.getIcon();
                            icon.rotation = bearing;
                            carMarker.setIcon(icon);
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

                                    // Extract all detailed polyline vertices
                                    var rawPoints = [];
                                    for (var i = 0; i < leg.steps.length; i++) {
                                        var step = leg.steps[i];
                                        for (var j = 0; j < step.path.length; j++) {
                                            rawPoints.push(step.path[j]);
                                        }
                                    }

                                    // Resample into 1-second waypoints
                                    var cumDistances = [0];
                                    var totalPolylineDistance = 0;
                                    for (var k = 0; k < rawPoints.length - 1; k++) {
                                        var d = google.maps.geometry.spherical.computeDistanceBetween(rawPoints[k], rawPoints[k + 1]);
                                        totalPolylineDistance += d;
                                        cumDistances.push(totalPolylineDistance);
                                    }

                                    var resampledRoute = [];
                                    var distStep = totalPolylineDistance / totalSeconds;
                                    var currentRawIdx = 0;

                                    for (var s = 0; s < totalSeconds; s++) {
                                        var targetDist = s * distStep;
                                        while (currentRawIdx < cumDistances.length - 2 && cumDistances[currentRawIdx + 1] < targetDist) {
                                            currentRawIdx++;
                                        }
                                        var p1 = rawPoints[currentRawIdx];
                                        var p2 = rawPoints[currentRawIdx + 1];
                                        var segStart = cumDistances[currentRawIdx];
                                        var segEnd = cumDistances[currentRawIdx + 1];
                                        var segLength = segEnd - segStart;
                                        var frac = segLength > 0 ? (targetDist - segStart) / segLength : 0;
                                        var pt = google.maps.geometry.spherical.interpolate(p1, p2, frac);

                                        var bearing = google.maps.geometry.spherical.computeHeading(p1, p2);
                                        if (bearing < 0) bearing += 360;

                                        var speed = distStep; // m/s

                                        resampledRoute.push({
                                            lat: pt.lat(),
                                            lng: pt.lng(),
                                            bearing: Math.round(bearing * 10) / 10,
                                            speed: Math.round(speed * 10) / 10
                                        });
                                    }

                                    // Append destination exact point
                                    var destLatLng = leg.end_location;
                                    resampledRoute.push({
                                        lat: destLatLng.lat(),
                                        lng: destLatLng.lng(),
                                        bearing: resampledRoute.length > 0 ? resampledRoute[resampledRoute.length - 1].bearing : 0,
                                        speed: 0
                                    });

                                    // Position car at start
                                    var startPt = resampledRoute[0];
                                    carMarker.setPosition({ lat: startPt.lat, lng: startPt.lng });
                                    carMarker.setVisible(true);

                                    window.AndroidBridge.onRouteCalculated(
                                        JSON.stringify(resampledRoute),
                                        totalDistanceMeters,
                                        totalSeconds,
                                        leg.distance.text,
                                        durationMinutes + " min"
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
                            if (isRouteActiveMode) {
                                map.panTo(carMarker.getPosition());
                            } else {
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
