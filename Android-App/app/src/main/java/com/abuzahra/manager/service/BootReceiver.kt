package com.abuzahra.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abuzahra.manager.util.DeviceUtils

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Device booted or app updated (action=$action)")
            if (DeviceUtils.isLinked(context)) {
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    // ACTION_BOOT_COMPLETED is exempt from Android 12+ FGS restrictions
                    try {
                        CommandService.start(context)
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Failed to start CommandService", e)
                    }
                } else {
                    // ACTION_MY_PACKAGE_REPLACED is NOT exempt on Android 12+;
                    // use WorkManager to defer the foreground service start
                    try {
                        com.abuzahra.manager.worker.WorkScheduler.scheduleServiceRestart(context)
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Failed to schedule service restart", e)
                    }
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