package com.abuzahra.manager.repository

import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.CallLog
import android.util.Log
import com.abuzahra.manager.database.AbuZahraDatabase
import com.abuzahra.manager.database.entity.*
import com.abuzahra.manager.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * ContactRepository - Manages contact data operations
 */
class ContactRepository(private val context: Context) {
    
    private val database = AbuZahraDatabase.getInstance(context)
    private val contactDao = database.contactDao()
    
    /**
     * Sync contacts from device to local database
     */
    suspend fun syncFromDevice(): Int = withContext(Dispatchers.IO) {
        var count = 0
        
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.TIMES_CONTACTED,
            ContactsContract.Contacts.LAST_TIME_CONTACTED
        )
        
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val starredIndex = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
            val timesContactedIndex = cursor.getColumnIndex(ContactsContract.Contacts.TIMES_CONTACTED)
            val lastContactedIndex = cursor.getColumnIndex(ContactsContract.Contacts.LAST_TIME_CONTACTED)
            
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                val starred = cursor.getInt(starredIndex) == 1
                val timesContacted = cursor.getInt(timesContactedIndex)
                val lastContacted = cursor.getLong(lastContactedIndex)
                
                // Get phone numbers
                val phones = getPhoneNumbers(contactId)
                
                phones.forEach { phone ->
                    val entity = ContactEntity(
                        contactId = contactId,
                        name = name,
                        phoneNumber = phone,
                        starred = starred,
                        timesContacted = timesContacted,
                        lastContacted = if (lastContacted > 0) lastContacted else null
                    )
                    
                    contactDao.insert(entity)
                    count++
                }
            }
        }
        
        // Add to sync queue
        SyncManager.addToQueue(SyncManager.SyncType.CONTACTS, "batch_${System.currentTimeMillis()}")
        
        count
    }
    
    private fun getPhoneNumbers(contactId: String): List<String> {
        val phones = mutableListOf<String>()
        
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)
        
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex)
                if (number.isNotBlank()) {
                    phones.add(number)
                }
            }
        }
        
        return phones
    }
    
    suspend fun getAll(): List<ContactEntity> = contactDao.getAll()
    
    suspend fun search(query: String): List<ContactEntity> = contactDao.search(query)
    
    suspend fun getCount(): Int = contactDao.getCount()
}

/**
 * SmsRepository - Manages SMS data operations
 */
class SmsRepository(private val context: Context) {
    
    private val database = AbuZahraDatabase.getInstance(context)
    private val smsDao = database.smsDao()
    
    suspend fun syncFromDevice(): Int = withContext(Dispatchers.IO) {
        var count = 0
        
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.STATUS,
            Telephony.Sms.THREAD_ID
        )
        
        context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndex(Telephony.Sms.DATE)
            val dateSentIndex = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
            val typeIndex = cursor.getColumnIndex(Telephony.Sms.TYPE)
            val readIndex = cursor.getColumnIndex(Telephony.Sms.READ)
            val statusIndex = cursor.getColumnIndex(Telephony.Sms.STATUS)
            val threadIdIndex = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
            
            while (cursor.moveToNext()) {
                val entity = SmsEntity(
                    messageId = cursor.getLong(idIndex).toString(),
                    address = cursor.getString(addressIndex) ?: "",
                    body = cursor.getString(bodyIndex) ?: "",
                    date = cursor.getLong(dateIndex),
                    dateSent = cursor.getLong(dateSentIndex),
                    type = cursor.getInt(typeIndex),
                    read = cursor.getInt(readIndex) == 1,
                    status = cursor.getInt(statusIndex),
                    threadId = cursor.getLong(threadIdIndex),
                    person = null
                )
                
                smsDao.insert(entity)
                count++
            }
        }
        
        SyncManager.addToQueue(SyncManager.SyncType.SMS, "batch_${System.currentTimeMillis()}")
        
        count
    }
    
    suspend fun getAll(): List<SmsEntity> = smsDao.getAll()
    
    suspend fun getByAddress(address: String): List<SmsEntity> = smsDao.getByAddress(address)
    
    suspend fun search(query: String): List<SmsEntity> = smsDao.search(query)
    
    suspend fun getCount(): Int = smsDao.getCount()
    
    suspend fun getUnreadCount(): Int = smsDao.getUnreadCount()
}

/**
 * CallRepository - Manages call log data operations
 */
class CallRepository(private val context: Context) {
    
    private val database = AbuZahraDatabase.getInstance(context)
    private val callDao = database.callDao()
    
    suspend fun syncFromDevice(): Int = withContext(Dispatchers.IO) {
        var count = 0
        
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_NUMBER_TYPE,
            CallLog.Calls.CACHED_NUMBER_LABEL,
            CallLog.Calls.COUNTRY_ISO
        )
        
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null, null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberTypeIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
            val numberLabelIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NUMBER_LABEL)
            val countryIsoIndex = cursor.getColumnIndex(CallLog.Calls.COUNTRY_ISO)
            
            while (cursor.moveToNext()) {
                val entity = CallEntity(
                    callId = cursor.getLong(idIndex).toString(),
                    number = cursor.getString(numberIndex) ?: "",
                    date = cursor.getLong(dateIndex),
                    duration = cursor.getLong(durationIndex),
                    type = cursor.getInt(typeIndex),
                    name = cursor.getString(nameIndex),
                    cachedNumberType = cursor.getInt(numberTypeIndex),
                    cachedNumberLabel = cursor.getString(numberLabelIndex),
                    countryIso = cursor.getString(countryIsoIndex)
                )
                
                callDao.insert(entity)
                count++
            }
        }
        
        SyncManager.addToQueue(SyncManager.SyncType.CALLS, "batch_${System.currentTimeMillis()}")
        
        count
    }
    
    suspend fun getAll(): List<CallEntity> = callDao.getAll()
    
    suspend fun getByNumber(number: String): List<CallEntity> = callDao.getByNumber(number)
    
    suspend fun getByType(type: Int): List<CallEntity> = callDao.getByType(type)
    
    suspend fun getCount(): Int = callDao.getCount()
    
    suspend fun getTotalDuration(): Long = callDao.getTotalDuration() ?: 0L
}

/**
 * NotificationRepository - Manages notification data operations
 */
class NotificationRepository(private val context: Context) {
    
    private val database = AbuZahraDatabase.getInstance(context)
    private val notificationDao = database.notificationDao()
    
    suspend fun save(notification: NotificationEntity): Long {
        val id = notificationDao.insert(notification)
        SyncManager.addToQueue(SyncManager.SyncType.NOTIFICATIONS, notification.packageName)
        return id
    }
    
    suspend fun getAll(): List<NotificationEntity> = notificationDao.getAll()
    
    suspend fun getByPackage(packageName: String): List<NotificationEntity> = 
        notificationDao.getByPackage(packageName)
    
    suspend fun search(query: String): List<NotificationEntity> = notificationDao.search(query)
    
    suspend fun getCount(): Int = notificationDao.getCount()
    
    suspend fun deleteOlderThan(days: Int): Int {
        val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return notificationDao.deleteOlderThan(cutoffTime)
    }
}

/**
 * DeviceEventRepository - Manages device event logging
 */
class DeviceEventRepository(private val context: Context) {
    
    private val database = AbuZahraDatabase.getInstance(context)
    private val eventDao = database.deviceEventDao()
    
    suspend fun logEvent(
        type: String,
        category: String,
        data: Map<String, Any>? = null,
        severity: Int = 0
    ): Long {
        val eventData = data?.let { 
            com.google.gson.Gson().toJson(it) 
        }
        
        val event = DeviceEventEntity(
            eventType = type,
            eventCategory = category,
            eventData = eventData,
            timestamp = System.currentTimeMillis(),
            severity = severity
        )
        
        return eventDao.insert(event)
    }
    
    suspend fun logDeviceStarted() = logEvent("device_started", "system")
    
    suspend fun logPermissionGranted(permission: String) = 
        logEvent("permission_granted", "permissions", mapOf("permission" to permission))
    
    suspend fun logBackupCreated(type: String, size: Long) = 
        logEvent("backup_created", "backup", mapOf("type" to type, "size" to size))
    
    suspend fun logUploadSuccess(fileName: String) = 
        logEvent("upload_success", "sync", mapOf("file" to fileName))
    
    suspend fun logUploadFailed(fileName: String, error: String) = 
        logEvent("upload_failed", "sync", mapOf("file" to fileName, "error" to error), severity = 2)
    
    suspend fun getRecentEvents(limit: Int = 100): List<DeviceEventEntity> {
        return eventDao.getAll().take(limit)
    }
    
    suspend fun getByType(type: String): List<DeviceEventEntity> = eventDao.getByType(type)
    
    suspend fun getByCategory(category: String): List<DeviceEventEntity> = 
        eventDao.getByCategory(category)
}
