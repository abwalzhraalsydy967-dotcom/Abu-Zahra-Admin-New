package com.abuzahra.manager.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.repository.DeviceEventRepository
import com.abuzahra.manager.storage.StorageCleaner
import com.abuzahra.manager.storage.StorageManager
import com.abuzahra.manager.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * HealthMonitor - Monitors device health and app status
 * Tracks memory, battery, network, and storage health
 */
object HealthMonitor {
    
    private const val TAG = "HealthMonitor"
    
    private lateinit var eventRepository: DeviceEventRepository
    
    /**
     * Initialize health monitor
     */
    fun initialize(context: Context) {
        eventRepository = DeviceEventRepository(context)
        Log.i(TAG, "HealthMonitor initialized")
    }
    
    /**
     * Perform health check
     */
    suspend fun checkHealth(context: Context): HealthReport = withContext(Dispatchers.IO) {
        val memoryInfo = getMemoryInfo(context)
        val batteryInfo = getBatteryInfo(context)
        val networkInfo = getNetworkInfo(context)
        val storageInfo = getStorageInfo(context)
        val appHealth = getAppHealth(context)
        
        val overallHealth = calculateOverallHealth(
            memoryInfo, batteryInfo, networkInfo, storageInfo, appHealth
        )
        
        val report = HealthReport(
            timestamp = System.currentTimeMillis(),
            memory = memoryInfo,
            battery = batteryInfo,
            network = networkInfo,
            storage = storageInfo,
            appHealth = appHealth,
            overallHealth = overallHealth
        )
        
        // Log critical issues
        if (overallHealth == HealthStatus.CRITICAL) {
            eventRepository.logEvent(
                "health_critical",
                "system",
                mapOf("report" to report.toString()),
                severity = 3
            )
        }
        
        // Send to server
        try {
            ApiClient.sendHealthReport(report.toMap())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send health report", e)
        }
        
        report
    }
    
    /**
     * Get memory information
     */
    private fun getMemoryInfo(context: Context): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMemory = memInfo.totalMem // Available since API 16
        val availableMemory = memInfo.availMem
        val usedMemory = totalMemory - availableMemory
        val percentUsed = if (totalMemory > 0) (usedMemory * 100 / totalMemory).toInt() else 0
        
        return MemoryInfo(
            totalMemory = totalMemory,
            availableMemory = availableMemory,
            usedMemory = usedMemory,
            usedPercentage = percentUsed,
            isLowMemory = memInfo.lowMemory,
            threshold = memInfo.threshold,
            status = when {
                memInfo.lowMemory -> HealthStatus.CRITICAL
                usedMemory.toDouble() / totalMemory > 0.9 -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            }
        )
    }
    
    /**
     * Get battery information
     */
    private fun getBatteryInfo(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            batteryManager.computeChargeTimeRemaining()
        } else -1L
        
        // Get battery status from intent
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = context.registerReceiver(null, filter)
        
        val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
        val voltage = status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = status?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) 
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        
        return BatteryInfo(
            level = level,
            isCharging = isCharging,
            temperature = temperature,
            voltage = voltage,
            health = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            },
            chargeTimeRemaining = chargeTime,
            status = when {
                level < 10 && !isCharging -> HealthStatus.CRITICAL
                level < 20 && !isCharging -> HealthStatus.WARNING
                temperature > 45 -> HealthStatus.WARNING
                health != BatteryManager.BATTERY_HEALTH_GOOD -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            }
        )
    }
    
    /**
     * Get network information
     */
    private fun getNetworkInfo(context: Context): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        
        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "bluetooth"
            else -> "unknown"
        }
        
        val downSpeed = capabilities?.linkDownstreamBandwidthKbps ?: 0
        val upSpeed = capabilities?.linkUpstreamBandwidthKbps ?: 0
        
        return NetworkInfo(
            isConnected = isConnected,
            isValidated = isValidated,
            networkType = networkType,
            downSpeedKbps = downSpeed,
            upSpeedKbps = upSpeed,
            status = when {
                !isConnected -> HealthStatus.CRITICAL
                !isValidated -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            }
        )
    }
    
    /**
     * Get storage information
     */
    private fun getStorageInfo(context: Context): StorageInfo {
        val internalPath = Environment.getDataDirectory()
        val stat = StatFs(internalPath.path)
        
        val totalSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.totalBytes
        } else {
            @Suppress("DEPRECATION")
            stat.blockCount.toLong() * stat.blockSize
        }
        
        val freeSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.availableBytes
        } else {
            @Suppress("DEPRECATION")
            stat.availableBlocks.toLong() * stat.blockSize
        }
        
        val usedSpace = totalSpace - freeSpace
        val usedPercentage = (usedSpace.toDouble() / totalSpace * 100).toInt()
        
        val appStorageStats = StorageManager.getStorageStats()
        
        return StorageInfo(
            totalSpace = totalSpace,
            freeSpace = freeSpace,
            usedSpace = usedSpace,
            usedPercentage = usedPercentage,
            appUsedSpace = appStorageStats.usedSpace,
            storageHealth = StorageCleaner.getStorageHealth(),
            status = when {
                StorageCleaner.isStorageCritical() -> HealthStatus.CRITICAL
                StorageCleaner.isStorageLow() -> HealthStatus.WARNING
                usedPercentage > 90 -> HealthStatus.WARNING
                else -> HealthStatus.HEALTHY
            }
        )
    }
    
    /**
     * Get app health
     */
    private fun getAppHealth(context: Context): AppHealth {
        val syncStats = SyncManager.getStats()
        val storageHealth = StorageCleaner.getStorageHealth()
        
        return AppHealth(
            isServiceRunning = isServiceRunning(context),
            pendingSyncItems = syncStats.pendingCount,
            failedSyncItems = syncStats.failedCount,
            totalSynced = syncStats.totalSynced,
            lastSyncTime = syncStats.lastSyncTime,
            storageHealth = storageHealth.name,
            status = when {
                !isServiceRunning(context) -> HealthStatus.WARNING
                syncStats.failedCount > 10 -> HealthStatus.WARNING
                storageHealth == StorageCleaner.StorageHealth.CRITICAL -> HealthStatus.CRITICAL
                else -> HealthStatus.HEALTHY
            }
        )
    }
    
    /**
     * Check if main service is running.
     * Uses the static isRunning flag from CommandService instead of the deprecated
     * ActivityManager.getRunningServices() which only returns the app's own services on Android 12+.
     */
    private fun isServiceRunning(context: Context): Boolean {
        return com.abuzahra.manager.service.CommandService.isRunning
    }
    
    /**
     * Calculate overall health
     */
    private fun calculateOverallHealth(
        memory: MemoryInfo,
        battery: BatteryInfo,
        network: NetworkInfo,
        storage: StorageInfo,
        app: AppHealth
    ): HealthStatus {
        val statuses = listOf(
            memory.status,
            battery.status,
            network.status,
            storage.status,
            app.status
        )
        
        return when {
            statuses.any { it == HealthStatus.CRITICAL } -> HealthStatus.CRITICAL
            statuses.any { it == HealthStatus.WARNING } -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }
    }
    
    // Data classes
    enum class HealthStatus {
        HEALTHY,
        WARNING,
        CRITICAL
    }
    
    data class HealthReport(
        val timestamp: Long,
        val memory: MemoryInfo,
        val battery: BatteryInfo,
        val network: NetworkInfo,
        val storage: StorageInfo,
        val appHealth: AppHealth,
        val overallHealth: HealthStatus
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "timestamp" to timestamp,
            "memory" to mapOf(
                "total" to memory.totalMemory,
                "available" to memory.availableMemory,
                "used_percentage" to memory.usedPercentage,
                "low_memory" to memory.isLowMemory
            ),
            "battery" to mapOf(
                "level" to battery.level,
                "charging" to battery.isCharging,
                "temperature" to battery.temperature
            ),
            "network" to mapOf(
                "connected" to network.isConnected,
                "type" to network.networkType
            ),
            "storage" to mapOf(
                "free" to storage.freeSpace,
                "used_percentage" to storage.usedPercentage
            ),
            "overall_health" to overallHealth.name
        )
    }
    
    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long,
        val usedMemory: Long,
        val usedPercentage: Int,
        val isLowMemory: Boolean,
        val threshold: Long,
        val status: HealthStatus
    )
    
    data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean,
        val temperature: Float,
        val voltage: Int,
        val health: String,
        val chargeTimeRemaining: Long,
        val status: HealthStatus
    )
    
    data class NetworkInfo(
        val isConnected: Boolean,
        val isValidated: Boolean,
        val networkType: String,
        val downSpeedKbps: Int,
        val upSpeedKbps: Int,
        val status: HealthStatus
    )
    
    data class StorageInfo(
        val totalSpace: Long,
        val freeSpace: Long,
        val usedSpace: Long,
        val usedPercentage: Int,
        val appUsedSpace: Long,
        val storageHealth: StorageCleaner.StorageHealth,
        val status: HealthStatus
    )
    
    data class AppHealth(
        val isServiceRunning: Boolean,
        val pendingSyncItems: Int,
        val failedSyncItems: Int,
        val totalSynced: Long,
        val lastSyncTime: Long,
        val storageHealth: String,
        val status: HealthStatus
    )
}
