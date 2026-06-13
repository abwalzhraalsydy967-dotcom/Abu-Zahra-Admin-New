package com.abuzahra.manager.executor

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileExecutor {

    private const val TAG = "FileExecutor"

    private fun validatePath(path: String): String? {
        if (path.isBlank()) return "No path provided"
        if (path.contains("..")) return "Path traversal detected"
        // Only allow paths under /storage/emulated/0/ or app-specific dirs
        val normalized = File(path).canonicalPath
        val allowedRoots = mutableListOf(
            "/storage/emulated/0/",
            Environment.getExternalStorageDirectory().canonicalPath
        )
        // Only add app external dir if it's non-null and non-empty
        val appExternalDir = com.abuzahra.manager.App.instance.getExternalFilesDir(null)?.canonicalPath
        if (!appExternalDir.isNullOrBlank()) {
            allowedRoots.add(appExternalDir)
        }
        val isAllowed = allowedRoots.any { normalized.startsWith(it) }
        if (!isAllowed) return "Access denied: path outside allowed directories"
        return null // null means valid
    }

    // ===== LIST FILES =====
    fun listFiles(context: Context, params: Map<String, Any>): List<Map<String, Any>> {
        // Scoped Storage check for Android 11+
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return listOf(mapOf("error" to "MANAGE_EXTERNAL_STORAGE permission required on Android 11+" as Any))
            }
        } catch (_: Exception) {}

        val arg = params["arg"]?.toString() ?: ""
        val offset = (params["offset"] as? Number)?.toInt() ?: 0
        val limit = (params["limit"] as? Number)?.toInt() ?: 100
        val dirPath = when {
            arg.contains("download", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            arg.contains("dcim", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath
            arg.contains("music", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
            arg.contains("video", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
            arg.contains("document", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
            arg.contains("whatsapp", ignoreCase = true) -> "/storage/emulated/0/WhatsApp"
            arg.contains("telegram", ignoreCase = true) -> "/storage/emulated/0/Telegram"
            else -> arg.ifBlank { "/storage/emulated/0/" }
        }

        // Validate custom paths (predefined dirs above are safe)
        val isPredefined = arg.contains("download", ignoreCase = true) ||
            arg.contains("dcim", ignoreCase = true) ||
            arg.contains("music", ignoreCase = true) ||
            arg.contains("video", ignoreCase = true) ||
            arg.contains("document", ignoreCase = true) ||
            arg.contains("whatsapp", ignoreCase = true) ||
            arg.contains("telegram", ignoreCase = true) ||
            arg.isBlank()
        if (!isPredefined) {
            val pathError = validatePath(dirPath)
            if (pathError != null) return listOf(mapOf("error" to pathError as Any))
        }

        val list = mutableListOf<Map<String, Any>>()
        val dir = File(dirPath)
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            files?.sortedByDescending { it.lastModified() }?.drop(offset)?.take(limit)?.forEach { file ->
                list.add(mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to formatFileSize(file.length()),
                    "is_directory" to file.isDirectory,
                    "last_modified" to formatDate(file.lastModified()),
                    "extension" to file.extension
                ))
            }
        } else {
            list.add(mapOf<String, Any>("error" to "Directory not found: $dirPath"))
        }
        return list
    }

    // ===== DELETE FILE =====
    fun deleteFile(context: Context, params: Map<String, Any>): String {
        val path = params["arg"]?.toString() ?: ""
        val error = validatePath(path)
        if (error != null) return error
        return if (path.isNotBlank()) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    if (deleted) "Deleted: $path" else "Failed to delete: $path"
                } else "File not found: $path"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No path provided"
    }

    // ===== RENAME FILE =====
    fun renameFile(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        val parts = arg.split(" ", limit = 2)
        val oldPath = parts.getOrNull(0) ?: ""
        val newName = parts.getOrNull(1) ?: ""
        val error = validatePath(oldPath)
        if (error != null) return error
        return if (oldPath.isNotBlank() && newName.isNotBlank()) {
            try {
                val file = File(oldPath)
                if (file.exists()) {
                    val newFile = File(file.parent, newName)
                    if (file.renameTo(newFile)) "Renamed to: $newName"
                    else "Rename failed"
                } else "File not found"
            } catch (e: Exception) { "Error: ${e.message}" }
        } else "Usage: old_path new_name"
    }

    // ===== COPY FILE =====
    fun copyFile(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        val parts = arg.split(" ", limit = 2)
        val src = parts.getOrNull(0) ?: ""
        val dest = parts.getOrNull(1) ?: ""
        val srcError = validatePath(src)
        if (srcError != null) return srcError
        val destError = validatePath(dest)
        if (destError != null) return destError
        return if (src.isNotBlank() && dest.isNotBlank()) {
            try {
                val srcFile = File(src)
                val destFile = File(dest)
                if (srcFile.exists()) {
                    srcFile.copyTo(destFile, overwrite = true)
                    "Copied to: $dest"
                } else "Source not found"
            } catch (e: Exception) { "Error: ${e.message}" }
        } else "Usage: source_path dest_path"
    }

    // ===== MOVE FILE =====
    fun moveFile(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        val parts = arg.split(" ", limit = 2)
        val src = parts.getOrNull(0) ?: ""
        val dest = parts.getOrNull(1) ?: ""
        val srcError = validatePath(src)
        if (srcError != null) return srcError
        val destError = validatePath(dest)
        if (destError != null) return destError
        return if (src.isNotBlank() && dest.isNotBlank()) {
            try {
                val srcFile = File(src)
                val destFile = File(dest)
                if (srcFile.exists()) {
                    srcFile.renameTo(destFile)
                    "Moved to: $dest"
                } else "Source not found"
            } catch (e: Exception) { "Error: ${e.message}" }
        } else "Usage: source_path dest_path"
    }

    // ===== CREATE FOLDER =====
    fun createFolder(context: Context, params: Map<String, Any>): String {
        val path = params["arg"]?.toString() ?: ""
        val error = validatePath(path)
        if (error != null) return error
        return if (path.isNotBlank()) {
            try {
                val dir = File(path)
                if (dir.mkdirs() || dir.exists()) "Created: $path"
                else "Failed to create: $path"
            } catch (e: Exception) { "Error: ${e.message}" }
        } else "No path provided"
    }

    // ===== SEARCH FILES =====
    fun searchFiles(context: Context, params: Map<String, Any>): List<Map<String, Any>> {
        val query = params["arg"]?.toString() ?: ""
        val results = mutableListOf<Map<String, Any>>()
        if (query.isBlank()) return results

        try {
            searchRecursive(Environment.getExternalStorageDirectory(), query, results, 0, 3)
        } catch (e: Exception) {
            Log.e(TAG, "searchFiles error", e)
        }
        return results
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<Map<String, Any>>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        dir.listFiles()?.forEach { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                results.add(mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to formatFileSize(file.length()),
                    "is_directory" to file.isDirectory
                ))
            }
            if (file.isDirectory && depth < maxDepth) {
                searchRecursive(file, query, results, depth + 1, maxDepth)
            }
            if (results.size >= 50) return
        }
    }

    // ===== RECENT FILES =====
    fun recentFiles(context: Context): List<Map<String, Any>> {
        // Scoped Storage check for Android 11+
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return listOf(mapOf("error" to "MANAGE_EXTERNAL_STORAGE permission required on Android 11+" as Any))
            }
        } catch (_: Exception) {}

        val list = mutableListOf<Map<String, Any>>()
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )
        dirs.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (!file.isDirectory) {
                    list.add(mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "size" to formatFileSize(file.length()),
                        "last_modified" to formatDate(file.lastModified()),
                        "extension" to file.extension
                    ))
                }
            }
        }
        return list.sortedByDescending {
            (it["last_modified"] as? String) ?: ""
        }.take(50)
    }

    // ===== FILE INFO =====
    fun getFileInfo(context: Context, params: Map<String, Any>): Map<String, Any> {
        val path = params["arg"]?.toString() ?: ""
        val error = validatePath(path)
        if (error != null) return mapOf("error" to error as Any)
        return if (path.isNotBlank()) {
            try {
                val file = File(path)
                if (file.exists()) {
                    mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "size" to formatFileSize(file.length()),
                        "size_bytes" to file.length(),
                        "is_directory" to file.isDirectory,
                        "is_file" to file.isFile,
                        "last_modified" to formatDate(file.lastModified()),
                        "can_read" to file.canRead(),
                        "can_write" to file.canWrite(),
                        "extension" to file.extension
                    )
                } else mapOf<String,Any>("error" to "File not found")
            } catch (e: Exception) { mapOf("error" to (e.message ?: "") as Any) }
        } else mapOf<String,Any>("error" to "No path")
    }

    // ===== FOLDER SIZE =====
    fun getFolderSize(context: Context, params: Map<String, Any>): Map<String, Any> {
        val path = params["arg"]?.toString() ?: ""
        val error = validatePath(path)
        if (error != null) return mapOf("error" to error as Any)
        return if (path.isNotBlank()) {
            try {
                val dir = File(path)
                val size = calculateSize(dir)
                mapOf("path" to path, "size" to formatFileSize(size), "bytes" to size)
            } catch (e: Exception) { mapOf("error" to (e.message ?: "") as Any) }
        } else mapOf<String,Any>("error" to "No path")
    }

    private fun calculateSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateSize(file) else file.length()
        }
        return size
    }

    // ===== HELPERS =====
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups.coerceAtMost(4)])
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}
