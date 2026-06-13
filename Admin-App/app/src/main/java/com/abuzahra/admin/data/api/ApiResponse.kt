package com.abuzahra.admin.data.api

import com.google.gson.annotations.SerializedName

// Login
data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("token") val token: String = "",
    @SerializedName("message") val message: String = "",
    @SerializedName("success") val success: Boolean = false
)

// Stats
data class StatsResponse(
    @SerializedName("devices_count") val devicesCount: Int = 0,
    @SerializedName("online_count") val onlineCount: Int = 0,
    @SerializedName("offline_count") val offlineCount: Int = 0,
    @SerializedName("commands_today") val commandsToday: Int = 0,
    @SerializedName("events_today") val eventsToday: Int = 0
)

// Generic API Response
data class ApiErrorResponse(
    @SerializedName("message") val message: String = "",
    @SerializedName("error") val error: String = "",
    @SerializedName("detail") val detail: String = ""
) {
    val displayMessage: String
        get() = when {
            message.isNotEmpty() -> message
            error.isNotEmpty() -> error
            detail.isNotEmpty() -> detail
            else -> "حدث خطأ غير معروف"
        }
}

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = -1) : ApiResult<Nothing>()
    object Loading : ApiResult<Nothing>()
}