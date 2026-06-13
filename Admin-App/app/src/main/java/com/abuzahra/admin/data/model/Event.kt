package com.abuzahra.admin.data.model

import com.google.gson.annotations.SerializedName

data class Event(
    @SerializedName("id") val id: String = "",
    @SerializedName("event") val event: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("details") val details: String? = null,
    @SerializedName("timestamp") val timestamp: String = "",
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("data") val data: Map<String, Any>? = null
) {
    val displayEvent: String
        get() = when (event.lowercase()) {
            "device_online", "online" -> "الجهاز متصل"
            "device_offline", "offline" -> "الجهاز غير متصل"
            "command_sent" -> "تم إرسال أمر"
            "command_result" -> "نتيجة أمر"
            "screenshot_taken" -> "تم التقاط لقطة شاشة"
            "location_update" -> "تحديث الموقع"
            "battery_low" -> "بطارية منخفضة"
            "app_installed" -> "تطبيق مثبّت"
            "app_uninstalled" -> "تطبيق محذوف"
            "sim_changed" -> "تغيير الشريحة"
            else -> event
        }

    val eventTypeCategory: String
        get() = when {
            event.contains("online", ignoreCase = true) || event.contains("offline", ignoreCase = true) -> "اتصال"
            event.contains("command", ignoreCase = true) -> "أوامر"
            else -> "تنبيهات"
        }

    val displayTime: String
        get() {
            if (timestamp.isNullOrEmpty()) return ""
            return try {
                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                val date = inputFormat.parse(timestamp) ?: return timestamp
                val outputFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
                outputFormat.format(date)
            } catch (e: Exception) {
                timestamp
            }
        }

    val relativeTime: String
        get() {
            if (timestamp.isNullOrEmpty()) return ""
            return try {
                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                val date = inputFormat.parse(timestamp) ?: return timestamp
                val now = System.currentTimeMillis()
                val diff = now - date.time

                val seconds = diff / 1000
                val minutes = seconds / 60
                val hours = minutes / 60
                val days = hours / 24

                when {
                    seconds < 60 -> "الآن"
                    minutes < 60 -> "منذ ${minutes} دقيقة"
                    hours < 24 -> "منذ ${hours} ساعة"
                    days < 7 -> "منذ ${days} يوم"
                    else -> displayTime
                }
            } catch (e: Exception) {
                timestamp
            }
        }
}