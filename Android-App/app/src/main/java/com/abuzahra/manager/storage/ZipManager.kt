package com.abuzahra.manager.storage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.*

/**
 * ZipManager - Handles file compression and extraction
 * Provides zip/unzip functionality with progress tracking
 */
object ZipManager {
    
    private const val TAG = "ZipManager"
    private const val BUFFER_SIZE = 8192
    
    /**
     * Compress files into a zip archive
     */
    suspend fun compress(
        files: List<File>,
        outputFile: File,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): ZipResult = withContext(Dispatchers.IO) {
        try {
            var totalBytes = 0L
            var processedBytes = 0L
            
            // Calculate total size
            files.forEach { totalBytes += it.length() }
            
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zipOut ->
                zipOut.setLevel(compressionLevel)
                
                files.forEach { file ->
                    if (file.exists() && file.isFile) {
                        addFileToZip(zipOut, file, file.name)
                        processedBytes += file.length()
                        progressCallback?.invoke(
                            (processedBytes * 100 / totalBytes).toInt(),
                            files.size
                        )
                    }
                }
            }
            
            ZipResult(
                success = true,
                outputPath = outputFile.absolutePath,
                originalSize = totalBytes,
                compressedSize = outputFile.length(),
                fileCount = files.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed", e)
            ZipResult(success = false, error = e.message ?: "Compression failed")
        }
    }
    
    /**
     * Compress directory into a zip archive
     */
    suspend fun compressDirectory(
        sourceDir: File,
        outputFile: File,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
        progressCallback: ((Int) -> Unit)? = null
    ): ZipResult = withContext(Dispatchers.IO) {
        try {
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return@withContext ZipResult(success = false, error = "Source directory not found")
            }
            
            var fileCount = 0
            var totalSize = 0L
            
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zipOut ->
                zipOut.setLevel(compressionLevel)
                
                sourceDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relativePath = file.relativeTo(sourceDir).path
                        addFileToZip(zipOut, file, relativePath)
                        totalSize += file.length()
                        fileCount++
                        progressCallback?.invoke(fileCount)
                    }
            }
            
            ZipResult(
                success = true,
                outputPath = outputFile.absolutePath,
                originalSize = totalSize,
                compressedSize = outputFile.length(),
                fileCount = fileCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Directory compression failed", e)
            ZipResult(success = false, error = e.message ?: "Compression failed")
        }
    }
    
    /**
     * Add file to zip
     */
    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        entry.time = file.lastModified()
        zipOut.putNextEntry(entry)
        
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var len: Int
            while (input.read(buffer).also { len = it } > 0) {
                zipOut.write(buffer, 0, len)
            }
        }
        
        zipOut.closeEntry()
    }
    
    /**
     * Extract zip archive
     */
    suspend fun extract(
        zipFile: File,
        targetDir: File,
        overwrite: Boolean = true,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): ZipResult = withContext(Dispatchers.IO) {
        try {
            if (!zipFile.exists()) {
                return@withContext ZipResult(success = false, error = "Zip file not found")
            }
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            var fileCount = 0
            var totalSize = 0L
            var totalEntries = 0
            
            // Count entries first
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
                while (zipIn.nextEntry != null) {
                    totalEntries++
                    zipIn.closeEntry()
                }
            }
            
            // Extract files
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                
                while (entry != null) {
                    val outputFile = File(targetDir, entry.name)
                    
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        
                        if (!outputFile.exists() || overwrite) {
                            BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var len: Int
                                while (zipIn.read(buffer).also { len = it } > 0) {
                                    output.write(buffer, 0, len)
                                    totalSize += len
                                }
                            }
                            fileCount++
                            
                            progressCallback?.invoke(fileCount, totalEntries)
                        }
                    }
                    
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            
            ZipResult(
                success = true,
                outputPath = targetDir.absolutePath,
                originalSize = zipFile.length(),
                compressedSize = totalSize,
                fileCount = fileCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            ZipResult(success = false, error = e.message ?: "Extraction failed")
        }
    }
    
    /**
     * List contents of zip file
     */
    fun listContents(zipFile: File): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                
                while (entry != null) {
                    entries.add(ZipEntryInfo(
                        name = entry.name,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        isDirectory = entry.isDirectory,
                        time = entry.time
                    ))
                    
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list zip contents", e)
        }
        
        return entries
    }
    
    /**
     * Get compression ratio
     */
    fun getCompressionRatio(originalSize: Long, compressedSize: Long): Double {
        if (originalSize == 0L) return 0.0
        return (1.0 - (compressedSize.toDouble() / originalSize)) * 100
    }
    
    /**
     * Validate zip file
     */
    fun isValidZip(file: File): Boolean {
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Add files to existing zip
     */
    suspend fun addToZip(
        zipFile: File,
        files: Map<String, File> // entry name -> file
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(zipFile.parent, "${zipFile.name}.tmp")
            
            // Copy existing entries
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zipOut ->
                // Add existing entries
                if (zipFile.exists()) {
                    ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
                        var entry: ZipEntry? = zipIn.nextEntry
                        
                        while (entry != null) {
                            zipOut.putNextEntry(entry)
                            val buffer = ByteArray(BUFFER_SIZE)
                            var len: Int
                            while (zipIn.read(buffer).also { len = it } > 0) {
                                zipOut.write(buffer, 0, len)
                            }
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                }
                
                // Add new files
                files.forEach { (entryName, file) ->
                    if (file.exists() && file.isFile) {
                        addFileToZip(zipOut, file, entryName)
                    }
                }
            }
            
            // Replace original file
            zipFile.delete()
            tempFile.renameTo(zipFile)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add to zip", e)
            false
        }
    }
    
    // Data classes
    data class ZipResult(
        val success: Boolean,
        val outputPath: String? = null,
        val originalSize: Long = 0,
        val compressedSize: Long = 0,
        val fileCount: Int = 0,
        val error: String? = null
    )
    
    data class ZipEntryInfo(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val time: Long
    )
}
