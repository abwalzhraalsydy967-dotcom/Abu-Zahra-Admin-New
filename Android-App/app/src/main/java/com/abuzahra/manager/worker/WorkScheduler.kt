package com.abuzahra.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.abuzahra.manager.storage.BackupManager
import com.abuzahra.manager.storage.StorageCleaner
import com.abuzahra.manager.sync.SyncManager
import java.util.concurrent.TimeUnit

/**
 * WorkScheduler - Schedules periodic background tasks using WorkManager
 */
object WorkScheduler {
    
    private const val TAG = "WorkScheduler"
    
    // Work names
    private const val SYNC_WORK = "sync_work"
    private const val BACKUP_WORK = "backup_work"
    private const val CLEANUP_WORK = "cleanup_work"
    private const val HEALTH_CHECK_WORK = "health_check_work"
    
    /**
     * Schedule all periodic tasks
     */
    fun scheduleAll(context: Context) {
        schedulePeriodicSync(context)
        schedulePeriodicBackup(context)
        schedulePeriodicCleanup(context)
        scheduleHealthCheck(context)
        
        Log.i(TAG, "All periodic tasks scheduled")
    }
    
    /**
     * Schedule periodic sync
     */
    fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        
        val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            syncWork
        )
        
        Log.i(TAG, "Periodic sync scheduled: every $intervalMinutes minutes")
    }
    
    /**
     * Schedule periodic backup
     */
    fun schedulePeriodicBackup(context: Context, intervalHours: Long = 24) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()
        
        val backupWork = PeriodicWorkRequestBuilder<BackupWorker>(
            intervalHours, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BACKUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            backupWork
        )
        
        Log.i(TAG, "Periodic backup scheduled: every $intervalHours hours")
    }
    
    /**
     * Schedule periodic cleanup
     */
    fun schedulePeriodicCleanup(context: Context, intervalDays: Long = 7) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()
        
        val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(
            intervalDays, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CLEANUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
        
        Log.i(TAG, "Periodic cleanup scheduled: every $intervalDays days")
    }
    
    /**
     * Schedule health check
     */
    fun scheduleHealthCheck(context: Context, intervalHours: Long = 1) {
        val constraints = Constraints.Builder()
            .build()
        
        val healthWork = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            intervalHours, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEALTH_CHECK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            healthWork
        )
        
        Log.i(TAG, "Health check scheduled: every $intervalHours hours")
    }
    
    /**
     * Run immediate sync
     */
    fun runSyncNow(context: Context) {
        val syncWork = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${SYNC_WORK}_immediate",
            ExistingWorkPolicy.REPLACE,
            syncWork
        )
    }
    
    /**
     * Run immediate backup
     */
    fun runBackupNow(context: Context) {
        val backupWork = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${BACKUP_WORK}_immediate",
            ExistingWorkPolicy.REPLACE,
            backupWork
        )
    }
    
    /**
     * Run immediate cleanup
     */
    fun runCleanupNow(context: Context) {
        val cleanupWork = OneTimeWorkRequestBuilder<CleanupWorker>().build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${CLEANUP_WORK}_immediate",
            ExistingWorkPolicy.REPLACE,
            cleanupWork
        )
    }
    
    /**
     * Schedule a one-time work to restart the CommandService via a Worker.
     * Safe to call from a BroadcastReceiver on Android 12+ where
     * startForegroundService() is restricted for non-exempted actions.
     */
    fun scheduleServiceRestart(context: Context) {
        val restartWork = OneTimeWorkRequestBuilder<ServiceRestartWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "service_restart",
            ExistingWorkPolicy.REPLACE,
            restartWork
        )

        Log.i(TAG, "Service restart scheduled via WorkManager")
    }

    /**
     * Cancel all work
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        Log.i(TAG, "All work cancelled")
    }
    
    /**
     * Get work info
     */
    fun getWorkStatus(context: Context, workName: String) = 
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(workName)
}

/**
 * Sync Worker
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            Log.i("SyncWorker", "Starting sync")
            
            SyncManager.startSync(forced = true)
            
            // Reset failure count on success
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            prefs.edit().remove("SyncWorker_fails").apply()
            
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync failed", e)
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            val key = "SyncWorker_fails"
            val fails = prefs.getInt(key, 0) + 1
            prefs.edit().putInt(key, fails).apply()
            Log.w("SyncWorker", "Worker failed, attempt $fails/5")
            if (fails >= 5) {
                prefs.edit().remove(key).apply()
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}

/**
 * Backup Worker
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            Log.i("BackupWorker", "Starting backup")
            
            BackupManager.createBackup(applicationContext, BackupManager.BackupType.FULL)
            
            // Reset failure count on success
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            prefs.edit().remove("BackupWorker_fails").apply()
            
            Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Backup failed", e)
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            val key = "BackupWorker_fails"
            val fails = prefs.getInt(key, 0) + 1
            prefs.edit().putInt(key, fails).apply()
            Log.w("BackupWorker", "Worker failed, attempt $fails/5")
            if (fails >= 5) {
                prefs.edit().remove(key).apply()
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}

/**
 * Cleanup Worker
 */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            Log.i("CleanupWorker", "Starting cleanup")
            
            StorageCleaner.performFullCleanup(applicationContext)
            
            // Reset failure count on success
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            prefs.edit().remove("CleanupWorker_fails").apply()
            
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupWorker", "Cleanup failed", e)
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            val key = "CleanupWorker_fails"
            val fails = prefs.getInt(key, 0) + 1
            prefs.edit().putInt(key, fails).apply()
            Log.w("CleanupWorker", "Worker failed, attempt $fails/5")
            if (fails >= 5) {
                prefs.edit().remove(key).apply()
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}

/**
 * Health Check Worker
 */
class HealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            Log.i("HealthCheckWorker", "Running health check")
            
            HealthMonitor.checkHealth(applicationContext)
            
            // Reset failure count on success
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            prefs.edit().remove("HealthCheckWorker_fails").apply()
            
            Result.success()
        } catch (e: Exception) {
            Log.e("HealthCheckWorker", "Health check failed", e)
            val prefs = applicationContext.getSharedPreferences("worker_retries", Context.MODE_PRIVATE)
            val key = "HealthCheckWorker_fails"
            val fails = prefs.getInt(key, 0) + 1
            prefs.edit().putInt(key, fails).apply()
            Log.w("HealthCheckWorker", "Worker failed, attempt $fails/5")
            if (fails >= 5) {
                prefs.edit().remove(key).apply()
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}

/**
 * Worker that restarts the CommandService foreground service.
 * Used as a safe alternative to directly calling startForegroundService
 * from a BroadcastReceiver on Android 12+.
 */
class ServiceRestartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.i("ServiceRestartWorker", "Attempting to restart CommandService")
            com.abuzahra.manager.service.CommandService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("ServiceRestartWorker", "Failed to restart CommandService", e)
            Result.retry()
        }
    }
}
