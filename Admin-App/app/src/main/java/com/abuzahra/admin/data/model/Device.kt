package com.abuzahra.admin.data.model

import com.google.gson.annotations.SerializedName

data class Device(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String = "",
    @SerializedName("model") val model: String = "",
    @SerializedName("brand") val brand: String = "",
    @SerializedName("os_version") val osVersion: String = "",
    @SerializedName("battery_level") val batteryLevel: Int = 0,
    @SerializedName("battery_status") val batteryStatus: String = "",
    @SerializedName("ip_address") val ipAddress: String = "",
    @SerializedName("phone_number") val phoneNumber: String = "",
    @SerializedName("last_seen") val lastSeen: String = "",
    @SerializedName("is_online") val isOnline: Boolean = false,
    @SerializedName("android_version") val androidVersion: String = "",
    @SerializedName("sdk_version") val sdkVersion: Int = 0,
    @SerializedName("app_version") val appVersion: String = "",
    @SerializedName("screen_resolution") val screenResolution: String = "",
    @SerializedName("imei") val imei: String = "",
    @SerializedName("serial") val serial: String = ""
) {
    val displayLastSeen: String
        get() = if (lastSeen.isNullOrEmpty()) "أبداً" else formatRelativeTime(lastSeen)

    val displayBattery: String
        get() = "${batteryLevel}%"

    val batteryColor: Int
        get() = when {
            batteryLevel > 50 -> 0 // battery_high
            batteryLevel > 20 -> 1 // battery_medium
            else -> 2 // battery_low
        }

    companion object {
        fun formatRelativeTime(dateStr: String): String {
            return try {
                val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                val date = inputFormat.parse(dateStr) ?: return dateStr
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
                    else -> {
                        val outputFormat = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
                        outputFormat.format(date)
                    }
                }
            } catch (e: Exception) {
                dateStr
            }
        }
    }
}