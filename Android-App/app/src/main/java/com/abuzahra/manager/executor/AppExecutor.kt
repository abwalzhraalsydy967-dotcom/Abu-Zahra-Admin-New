package com.abuzahra.manager.executor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
                "Force stopped: $packageName"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No package name provided"
    }

    // ===== INSTALL APP =====
    fun installApp(context: Context, params: Map<String, Any>): String {
        val url = params["arg"]?.toString() ?: ""
        return if (url.isNotBlank()) {
            "Download and install: $url (requires DownloadManager + INSTALL_PACKAGES permission)"
        } else "No URL provided"
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    am.clearApplicationUserData()
                }
                "Clear data: $packageName (requires some limitations)"
            } catch (e: Exception) {
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
        return "Block/unblock app: $packageName (requires device admin or accessibility service)"
    }

    fun unblockApp(context: Context, params: Map<String, Any>): String {
        return blockApp(context, params)
    }

    // ===== SCREEN TIME =====
    fun getScreenTime(context: Context): Map<String, Any> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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
