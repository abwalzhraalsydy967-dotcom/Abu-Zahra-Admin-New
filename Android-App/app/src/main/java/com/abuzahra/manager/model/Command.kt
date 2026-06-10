package com.abuzahra.manager.model

import com.google.gson.annotations.SerializedName

data class Command(
    @SerializedName("id") val id: String = "",
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("command") val command: String = "",
    @SerializedName("params") val params: Map<String, Any> = emptyMap(),
    @SerializedName("status") val status: String = "pending",
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("server_domain") val serverDomain: String = "",
    @SerializedName("server_port") val serverPort: Int = 8443
)
