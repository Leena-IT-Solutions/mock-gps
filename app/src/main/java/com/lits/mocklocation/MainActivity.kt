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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lits.mocklocation.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isMockRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MockLocationService.ACTION_STATUS_CHANGED) {
                val active = intent.getBooleanExtra(MockLocationService.EXTRA_IS_ACTIVE, false)
                updateUiState(active)
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
            val extraBottom = (14 * density).toInt()
            view.setPadding(
                (16 * density).toInt(),
                insets.top.coerceAtLeast((10 * density).toInt()),
                (16 * density).toInt(),
                insets.bottom + extraBottom
            )
            windowInsets
        }

        askRequiredPermissions()
        setupListeners()
        setupMapWebView()
    }

    override fun onResume() {
        super.onResume()
        checkMockLocationPermission()
        val filter = IntentFilter(MockLocationService.ACTION_STATUS_CHANGED)
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

    private fun setupListeners() {
        binding.btnOpenDevSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Please open Settings -> Developer options manually", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnStartMock.setOnClickListener {
            startMockService()
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
    }

    private fun setCoordinates(lat: Double, lng: Double) {
        binding.etLatitude.setText(lat.toString())
        binding.etLongitude.setText(lng.toString())
        updateMapMarker(lat, lng)

        if (isMockRunning) {
            // Update live running mock location immediately
            startMockService()
        }
    }

    private fun startMockService() {
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

        updateUiState(true)
        Toast.makeText(this, "Mocking GPS started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopMockService() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        startService(intent)
        updateUiState(false)
        Toast.makeText(this, "Mocking stopped. Real GPS restored.", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState(active: Boolean) {
        isMockRunning = active
        if (active) {
            binding.tvStatusBadge.text = "ACTIVE"
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#065F46"))
            binding.tvStatusBadge.setTextColor(Color.parseColor("#34D399"))
        } else {
            binding.tvStatusBadge.text = "IDLE"
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#334155"))
            binding.tvStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
        }
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
                    binding.etLatitude.setText(String.format(java.util.Locale.US, "%.6f", lat))
                    binding.etLongitude.setText(String.format(java.util.Locale.US, "%.6f", lng))
                    if (isMockRunning) {
                        startMockService()
                    }
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
                        font-size: 14px;
                        font-weight: 500;
                        padding: 10px 14px;
                        width: calc(100% - 28px);
                        max-width: 360px;
                        margin-top: 12px;
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
                        right: 14px;
                        bottom: 30px;
                        display: flex;
                        flex-direction: column;
                        z-index: 9999;
                    }
                    .map-float-btn {
                        width: 44px;
                        height: 44px;
                        background: #1e293b;
                        border: 1.5px solid #475569;
                        border-radius: 12px;
                        color: #ffffff;
                        font-size: 22px;
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
                <script src="https://maps.googleapis.com/maps/api/js?key=${apiKey}&libraries=places"></script>
            </head>
            <body>
                <input id="pac-input" type="text" placeholder="🔍 Search place or address..." />
                <div id="map"></div>
                <div class="map-controls-group">
                    <div class="map-float-btn" onclick="zoomIn()">+</div>
                    <div class="map-float-btn" onclick="zoomOut()">−</div>
                    <div class="map-float-btn" onclick="recenterMarker()" style="font-size: 18px;">🎯</div>
                </div>
                <script>
                    var map, marker;
                    var isDragging = false;

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
                            marker.setPosition(e.latLng);
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
                            marker.setPosition(loc);
                            notifyApp(loc.lat(), loc.lng());
                        });

                        window.setMarker = function(lat, lng) {
                            var newPos = { lat: lat, lng: lng };
                            marker.setPosition(newPos);
                            map.panTo(newPos);
                        };

                        window.zoomIn = function() {
                            map.setZoom(map.getZoom() + 1);
                        };

                        window.zoomOut = function() {
                            map.setZoom(map.getZoom() - 1);
                        };

                        window.recenterMarker = function() {
                            map.panTo(marker.getPosition());
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
