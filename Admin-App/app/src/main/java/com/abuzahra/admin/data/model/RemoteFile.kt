package com.abuzahra.admin.data.model

import com.google.gson.annotations.SerializedName

data class RemoteFile(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("is_directory") val isDirectory: Boolean = false,
    @SerializedName("size") val size: Long = 0,
    @SerializedName("last_modified") val lastModified: String = "",
    @SerializedName("mime_type") val mimeType: String = "",
    @SerializedName("extension") val extension: String = ""
) {
    val displaySize: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(size / (1024.0 * 1024 * 1024))} GB"
        }
}