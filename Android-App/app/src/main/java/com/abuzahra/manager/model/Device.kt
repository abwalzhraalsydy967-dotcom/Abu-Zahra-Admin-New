package com.abuzahra.manager.model

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
