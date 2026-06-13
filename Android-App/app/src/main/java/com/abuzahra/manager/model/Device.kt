package com.abuzahra.manager.model

@Deprecated("Unused model class. Remove if no longer needed.")
data class Device(
    val id: String = "",
    val token: String = "",
    val name: String = "",
    val model: String = "",
    val brand: String = "",
    val osVersion: String = "",
    var battery: String = "",
    var network: String = "",
    var location: String = "",
    var active: Boolean = false
)
