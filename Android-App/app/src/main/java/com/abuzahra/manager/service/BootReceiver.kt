package com.abuzahra.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abuzahra.manager.util.DeviceUtils

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Device booted or app updated")
            if (DeviceUtils.isLinked(context)) {
                // Use WorkManager to start service (more reliable on Android 10+)
                try {
                    CommandService.start(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start CommandService", e)
                }
                // Re-schedule periodic workers
                try {
                    com.abuzahra.manager.worker.WorkScheduler.scheduleAll(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to schedule workers", e)
                }
            }
        }
    }
}