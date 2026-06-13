package com.abuzahra.manager.executor

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.abuzahra.manager.service.MyAccessibilityService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppExecutor {

    private const val TAG = "AppExecutor"

    // ===== OPEN APP =====
    fun openApp(context: Context, params: Map<String, Any>): String {
        val packageName = params["arg"]?.toString() ?: ""
        return if (packageName.isNotBlank()) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Opened: $packageName"
                } else {
                    "App not found: $packageName"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No package name provided"
    }

    // ===== CLOSE APP =====
    fun closeApp(context: Context, params: Map<String, Any>): String {
        val packageName = params["arg"]?.toString() ?: ""
        return if (packageName.isNotBlank()) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Requires REAL_GET_TASKS permission or is a system app
                    am.killBackgroundProcesses(packageName)
                }
                var result = "Force stopped: $packageName"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    result += " (Note: may not work on Android 10+ without system privileges)"
                }
                result
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No package name provided"
    }

    // ===== INSTALL APP =====
    fun installApp(context: Context, params: Map<String, Any>): String {
        val url = params["arg"]?.toString() ?: ""
        if (url.isBlank()) return "No URL provided"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "Error: Invalid URL - must start with http:// or https://"
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "install_${System.currentTimeMillis()}.apk"
            val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            val destUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Downloading APK")
                setDescription("Installing app from: $url")
                setDestinationUri(destUri)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadId = dm.enqueue(request)

            // Track download completion and trigger install
            Thread {
                var finished = false
                var lastProgress = 0
                val startTime = System.currentTimeMillis()
                while (!finished && System.currentTimeMillis() - startTime < 60000) {
                    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                finished = true
                                try {
                                    val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                                        data = destUri
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                    }
                                    context.startActivity(installIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Install trigger failed", e)
                                }
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                finished = true
                                Log.e(TAG, "Download failed: reason=$reason")
                            }
                        }
                    }
                    if (!finished) Thread.sleep(2000)
                }
                if (!finished) {
                    Log.w(TAG, "Download tracking timed out after 60s for ID: $downloadId")
                }
            }.start()

            "APK download started (ID: $downloadId). Installation will begin after download completes."
        } catch (e: SecurityException) {
            "Error: INSTALL_PACKAGES permission required for silent install: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Install app error", e)
            "Error: ${e.message}"
        }
    }

    // ===== UNINSTALL APP =====
    fun uninstallApp(context: Context, params: Map<String, Any>): String {
        val packageName = params["arg"]?.toString() ?: ""
        return if (packageName.isNotBlank()) {
            try {
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Uninstalling: $packageName"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No package name provided"
    }

    // ===== CLEAR APP DATA =====
    fun clearAppData(context: Context, params: Map<String, Any>): String {
        val packageName = params["arg"]?.toString() ?: ""
        return if (packageName.isNotBlank()) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                // Use reflection to call clearApplicationUserData(String) with package name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val method = android.app.ActivityManager::class.java.getMethod(
                        "clearApplicationUserData", String::class.java
                    )
                    val result = method.invoke(am, packageName) as? Boolean ?: false
                    if (result) "Data cleared for: $packageName" else "Failed to clear data for: $packageName"
                } else {
                    "Not supported on this Android version"
                }
            } catch (e: Exception) {
                Log.w(TAG, "clearAppData failed (requires system/root privileges)", e)
                "Error: ${e.message}"
            }
        } else "No package name provided"
    }

    // ===== FORCE STOP APP =====
    fun forceStopApp(context: Context, params: Map<String, Any>): String {
        return closeApp(context, params) // Same implementation
    }

    // ===== APP INFO =====
    fun getAppInfo(context: Context, params: Map<String, Any>): Map<String, Any> {
        val packageName = params["arg"]?.toString() ?: ""
        return if (packageName.isNotBlank()) {
            try {
                val pm = context.packageManager
                val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                val appInfo = info.applicationInfo
                mapOf(
                    "package" to info.packageName,
                    "name" to (appInfo.loadLabel(pm).toString()),
                    "version" to info.versionName,
                    "version_code" to info.versionCode,
                    "target_sdk" to info.applicationInfo.targetSdkVersion,
                    "first_install" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.firstInstallTime)),
                    "last_update" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.lastUpdateTime)),
                    "data_dir" to info.applicationInfo.dataDir,
                    "uid" to info.applicationInfo.uid
                )
            } catch (e: Exception) {
                mapOf("error" to (e.message ?: "Not found") as Any)
            }
        } else mapOf("error" to "No package name" as Any)
    }

    // ===== BLOCK / UNBLOCK APP =====
    fun blockApp(context: Context, params: Map<String, Any>): String {
        val packageName = params["arg"]?.toString() ?: ""
        if (packageName.isBlank()) return "No package name provided"
        return try {
            // Strategy 1: Try AccessibilityService to hide the app
            val accessibility = MyAccessibilityService.getInstance()
            if (accessibility != null) {
                // Hide the app by disabling its launcher component
                val pm = context.packageManager
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        val componentName = launchIntent.component
                        if (componentName != null) {
                            pm.setComponentEnabledSetting(
                                componentName,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP
                            )
                            return "App blocked (launcher hidden): $packageName via AccessibilityService"
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "blockApp error", e) }
            }

            // Strategy 2: Try Device Admin to hide app (requires device owner)
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(context, com.abuzahra.manager.service.DeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                return try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        dpm.setApplicationHidden(adminComponent, packageName, true)
                        "App blocked (hidden via Device Admin): $packageName"
                    } else {
                        "App blocking requires Android 5.0+ for Device Admin hiding"
                    }
                } catch (e: Exception) {
                    "Device Admin hide failed (requires Device Owner): ${e.message}"
                }
            }

            "Block app: $packageName - requires AccessibilityService or Device Admin (Device Owner)"
        } catch (e: Exception) {
            Log.e(TAG, "Block app error", e)
            "Error: ${e.message}"
        }
    }

    fun unblockApp(context: Context, params: Map<String, Any>): String {
        val packageName = params["arg"]?.toString() ?: ""
        if (packageName.isBlank()) return "No package name provided"
        return try {
            // Strategy 1: Re-enable launcher component
            val pm = context.packageManager
            try {
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    val componentName = launchIntent.component
                    if (componentName != null) {
                        pm.setComponentEnabledSetting(
                            componentName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        return "App unblocked (launcher restored): $packageName"
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "unblockApp error", e) }

            // Strategy 2: Device Admin unhide
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(context, com.abuzahra.manager.service.DeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                return try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        dpm.setApplicationHidden(adminComponent, packageName, false)
                        "App unblocked (unhidden via Device Admin): $packageName"
                    } else {
                        "App unblocking requires Android 5.0+ for Device Admin"
                    }
                } catch (e: Exception) {
                    "Device Admin unhide failed (requires Device Owner): ${e.message}"
                }
            }

            "Unblock app: $packageName - requires AccessibilityService or Device Admin (Device Owner)"
        } catch (e: Exception) {
            Log.e(TAG, "Unblock app error", e)
            "Error: ${e.message}"
        }
    }

    // ===== SCREEN TIME =====
    fun getScreenTime(context: Context): Map<String, Any> {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)

            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTime, endTime
            )

            val appUsage = stats.sortedByDescending { it.lastTimeUsed }.take(20).map { stat ->
                mapOf(
                    "package" to stat.packageName,
                    "last_used" to stat.lastTimeUsed,
                    "foreground" to stat.totalTimeInForeground
                )
            }

            val totalTime = stats.sumOf { it.totalTimeInForeground }
            val hours = totalTime / (1000 * 60 * 60)
            val minutes = (totalTime % (1000 * 60 * 60)) / (1000 * 60)

            mapOf(
                "total_screen_time" to "${hours}h ${minutes}m",
                "top_apps" to appUsage
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "UsageStatsManager access denied"))
        }
    }
}
