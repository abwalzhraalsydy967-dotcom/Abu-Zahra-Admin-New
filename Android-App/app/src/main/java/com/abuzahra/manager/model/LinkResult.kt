package com.abuzahra.manager.model

data class LinkResult(
    val ok: Boolean = false,
    val success: Boolean = false,
    val device_token: String? = null,
    val token: String? = null,
    val server_domain: String? = null,
    val message: String = "",
    val error: String = ""
)
