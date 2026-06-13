package com.abuzahra.admin.data.api

import com.abuzahra.admin.data.model.Command
import com.google.gson.annotations.SerializedName

data class SendCommandResponse(
    @SerializedName("id") val id: String = "",
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("status") val status: String = "",
    @SerializedName("command") val command: Command? = null,
    @SerializedName("message") val message: String = ""
)