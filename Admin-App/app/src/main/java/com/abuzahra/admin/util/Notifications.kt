package com.abuzahra.admin.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abuzahra.admin.R
import com.abuzahra.admin.ui.dashboard.DashboardActivity

class Notifications(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "device_events"
        const val CHANNEL_NAME = "أحداث الأجهزة"
        const val CHANNEL_DESC = "إشعارات أحداث ومراقبة الأجهزة"

        private const val NOTIF_DEVICE_ONLINE = 1001
        private const val NOTIF_DEVICE_OFFLINE = 1002
        private const val NOTIF_NEW_EVENT = 1003
        private const val NOTIF_COMMAND_RESULT = 1004

        @Volatile
        private var instance: Notifications? = null

        fun getInstance(context: Context): Notifications {
            return instance ?: synchronized(this) {
                instance ?: Notifications(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = Preferences.getInstance(context)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showDeviceOnlineNotification(deviceName: String) {
        if (!prefs.notificationsEnabled || !prefs.onlineNotifications) return
        if (!hasNotificationPermission()) return

        val intent = DashboardActivity.newIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        showNotification(
            NOTIF_DEVICE_ONLINE,
            "جهاز متصل",
            "$deviceName أصبح متصلاً الآن",
            intent
        )
    }

    fun showDeviceOfflineNotification(deviceName: String) {
        if (!prefs.notificationsEnabled || !prefs.offlineNotifications) return
        if (!hasNotificationPermission()) return

        val intent = DashboardActivity.newIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        showNotification(
            NOTIF_DEVICE_OFFLINE,
            "جهاز غير متصل",
            "$deviceName تم قطع الاتصال",
            intent
        )
    }

    fun showEventNotification(title: String, message: String) {
        if (!prefs.notificationsEnabled || !prefs.eventNotifications) return
        if (!hasNotificationPermission()) return

        val intent = DashboardActivity.newIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        showNotification(
            NOTIF_NEW_EVENT,
            title,
            message,
            intent
        )
    }

    fun showCommandResultNotification(commandName: String, success: Boolean) {
        if (!hasNotificationPermission()) return

        val intent = DashboardActivity.newIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        showNotification(
            NOTIF_COMMAND_RESULT,
            "نتيجة الأمر",
            if (success) "تم تنفيذ $commandName بنجاح" else "فشل تنفيذ $commandName",
            intent
        )
    }

    private fun showNotification(id: Int, title: String, message: String, intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}