package com.abuzahra.admin.data.api

import com.google.gson.annotations.SerializedName

data class SendCommandRequest(
    @SerializedName("command") val command: String,
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("params") val params: Map<String, Any>? = null
)