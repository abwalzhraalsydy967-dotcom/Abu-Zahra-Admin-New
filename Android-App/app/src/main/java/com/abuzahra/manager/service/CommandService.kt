package com.abuzahra.manager.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abuzahra.manager.R
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.api.FirebaseManager
import com.abuzahra.manager.executor.CommandExecutor
import com.abuzahra.manager.executor.DataCollector
import com.abuzahra.manager.model.Command
import com.abuzahra.manager.util.DeviceUtils
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CommandService : Service() {

    private val TAG = "CommandService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandListener: ChildEventListener? = null
    private var retryCount = 0
    private var heartbeatJob: Job? = null
    private var locationJob: Job? = null
    private var settingsJob: Job? = null
    private var restApiPollingJob: Job? = null
    private var firebaseListenerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val commandCounter = AtomicInteger(0)

    companion object {
        const val CHANNEL_ID = "abuzahra_service"
        const val CHANNEL_ID_ALERTS = "abuzahra_alerts"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommandService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification("Starting service..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Starting service..."))
        }
        Log.i(TAG, "Service created")

        // Request battery optimization exemption
        requestBatteryOptimization()

        // Acquire wake lock (10 hours max, Android limit)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abuzahra:service")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)

        // Renew wake lock every 9 hours
        serviceScope.launch {
            while (isActive) {
                delay(9 * 60 * 60 * 1000L) // 9 hours
                try {
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                    val pmRenew = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = pmRenew.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abuzahra:service")
                    wakeLock?.acquire(10 * 60 * 60 * 1000L)
                    Log.i(TAG, "WakeLock renewed")
                } catch (e: Exception) {
                    Log.e(TAG, "WakeLock renewal failed", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Log.i(TAG, "Service started (startId=$startId)")

        // Cancel existing jobs to prevent duplicates on repeated calls
        heartbeatJob?.cancel()
        locationJob?.cancel()
        settingsJob?.cancel()
        restApiPollingJob?.cancel()
        firebaseListenerJob?.cancel()

        updateNotification("Online - Waiting for commands")

        // Start Firebase command listener
        startFirebaseListener()

        // Start heartbeat
        startHeartbeat()

        // Start location tracking
        startLocationTracking()

        // Load settings
        loadSettings()

        // Also poll REST API as backup
        startRestApiPolling()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "Service destroyed - cleaning up")

        // Cancel scope FIRST to stop all coroutines (including wake lock renewal)
        // This prevents the renewal coroutine from re-acquiring the wake lock
        serviceScope.cancel()
        heartbeatJob?.cancel()
        locationJob?.cancel()
        settingsJob?.cancel()
        restApiPollingJob?.cancel()
        firebaseListenerJob?.cancel()

        // Remove Firebase listener
        commandListener?.let {
            try {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReferenceFromUrl(com.abuzahra.manager.Config.FIREBASE_RTDB_URL)
                    .child("commands/${DeviceUtils.getDeviceId(this)}")
                    .removeEventListener(it)
            } catch (_: Exception) {}
        }

        // Release wake lock AFTER scope is cancelled
        wakeLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) {}
        }

        // START_STICKY handles system-initiated restart; no manual Handler restart needed
        super.onDestroy()
    }

    // ===== FIREBASE LISTENER =====
    private fun startFirebaseListener() {
        if (!FirebaseManager.isAvailable()) {
            Log.w(TAG, "Firebase not available, skipping listener. Error: ${FirebaseManager.getLastError()}")
            updateNotification("Online (Firebase offline)")
            return
        }

        val deviceId = DeviceUtils.getDeviceId(this)
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReferenceFromUrl(com.abuzahra.manager.Config.FIREBASE_RTDB_URL)
            .child("commands/$deviceId")

        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val data = snapshot.value as? Map<*, *> ?: run {
                        Log.w(TAG, "Firebase: unexpected data type at ${snapshot.key}")
                        return
                    }
                    val json = Gson().toJson(data)
                    val command = Gson().fromJson(json, Command::class.java)
                    val count = commandCounter.incrementAndGet()
                    Log.i(TAG, "Firebase command #$count: ${command.command} (id=${command.id})")
                    updateNotification("Executing: ${command.command}")
                    CommandExecutor.execute(this@CommandService, command)
                    // Remove after reading
                    snapshot.ref.removeValue().addOnFailureListener { err ->
                        Log.w(TAG, "Failed to remove Firebase command ${command.id}: ${err.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Firebase onChildAdded error", e)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, prev: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${error.toException()}")
                updateNotification("Firebase error - retrying...")
                retryCount++
                val backoff = minOf(5000L * (1 shl minOf(retryCount, 5)), 300000L) // max 5 min
                serviceScope.launch {
                    delay(backoff)
                    commandListener = null
                    startFirebaseListener()
                }
            }
        }
        ref.addChildEventListener(commandListener!!)
        Log.i(TAG, "Firebase command listener active for device: $deviceId")
    }

    // ===== REST API POLLING (BACKUP) =====
    private fun startRestApiPolling() {
        restApiPollingJob = serviceScope.launch {
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val commands = ApiClient.getCommands(this@CommandService)
                    if (commands.isNotEmpty()) {
                        Log.i(TAG, "REST API: ${commands.size} command(s) received")
                        commands.forEach { cmd ->
                            val count = commandCounter.incrementAndGet()
                            Log.i(TAG, "REST command #$count: ${cmd.command} (id=${cmd.id})")
                            updateNotification("Executing: ${cmd.command}")
                            CommandExecutor.execute(this@CommandService, cmd)
                        }
                    }
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "REST API polling error (#$consecutiveErrors)", e)
                    if (consecutiveErrors >= 3) {
                        updateNotification("Server connection issues...")
                        // Back off exponentially, max 2 minutes
                        delay(minOf(2000L * consecutiveErrors, 120000L))
                        continue
                    }
                }
                delay(10000) // Poll every 10 seconds
            }
        }
    }

    // ===== HEARTBEAT =====
    @SuppressLint("BatteryHint")
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    val batteryInfo = DataCollector.getBattery(this@CommandService)
                    val level = (batteryInfo["level"] as? Int) ?: 0
                    ApiClient.sendHeartbeat(this@CommandService, level, "online")

                    // Update notification with status
                    val charging = batteryInfo["status"] == "charging"
                    val status = if (charging) "Charging" else "Battery $level%"
                    updateNotification("Online - $status - ${commandCounter.get()} cmds")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
                delay(60000) // Every 60 seconds
            }
        }
    }

    // ===== LOCATION TRACKING =====
    private fun startLocationTracking() {
        locationJob = serviceScope.launch {
            while (isActive) {
                try {
                    val location = DataCollector.getLastLocation(this@CommandService)
                    val lat = location["lat"] as? Double ?: 0.0
                    val lon = location["lon"] as? Double ?: 0.0
                    val error = location["error"]
                    if (error == null && (lat != 0.0 || lon != 0.0)) {
                        // Send location data
                        ApiClient.sendData(this@CommandService, "location", location)
                        // Add to history
                        com.abuzahra.manager.executor.MonitorExecutor.addLocationToHistory(lat, lon)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Location tracking error", e)
                }
                delay(300000) // Every 5 minutes
            }
        }
    }

    // ===== SETTINGS =====
    private fun loadSettings() {
        settingsJob = serviceScope.launch {
            try {
                val settings = ApiClient.getSettings(this@CommandService)
                Log.d(TAG, "Settings loaded: ${settings.size} items")
                // Apply settings if needed
                settings.forEach { (key, value) ->
                    Log.d(TAG, "  Setting: $key = $value")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Settings load error", e)
            }
        }
    }

    // ===== NOTIFICATION =====
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Main service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Abu-Zahra Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for command processing"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Alerts channel (for important notifications)
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Abu-Zahra Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Important alerts from admin panel"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Abu-Zahra Admin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.abuzahra.manager.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val prefs = getSharedPreferences("abuzahra", MODE_PRIVATE)
            if (prefs.getBoolean("battery_opt_requested", false)) return
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                prefs.edit().putBoolean("battery_opt_requested", true).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Battery optimization request failed", e)
            }
        }
    }
}
