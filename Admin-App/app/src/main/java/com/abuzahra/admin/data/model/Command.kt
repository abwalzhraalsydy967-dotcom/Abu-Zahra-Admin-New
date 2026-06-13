package com.abuzahra.admin.data.model

import com.google.gson.annotations.SerializedName

data class Command(
    @SerializedName("id") val id: String = "",
    @SerializedName("command") val command: String = "",
    @SerializedName("status") val status: String = "pending",
    @SerializedName("result") val result: String? = null,
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("args") val args: Map<String, Any>? = null,
    @SerializedName("error") val error: String? = null
) {
    val displayStatus: String
        get() = when (status.lowercase()) {
            "success", "completed" -> "ناجح"
            "failed", "error" -> "فاشل"
            "pending" -> "قيد الانتظار"
            "delivered" -> "تم التسليم"
            else -> status
        }

    val statusColor: Int
        get() = when (status.lowercase()) {
            "success", "completed", "delivered" -> 0 // green/success
            "failed", "error" -> 1 // red/error
            else -> 2 // yellow/pending
        }

    val displayTime: String
        get() = if (createdAt.isNullOrEmpty()) "" else try {
            val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val date = inputFormat.parse(createdAt) ?: return createdAt
            val outputFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
            outputFormat.format(date)
        } catch (e: Exception) {
            createdAt
        }
}