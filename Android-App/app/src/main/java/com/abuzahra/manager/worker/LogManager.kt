package com.abuzahra.manager.worker

import android.content.Context
import android.util.Log
import com.abuzahra.manager.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LogManager - Centralized logging system
 * Manages app logs, crash reports, and event logging
 */
object LogManager {
    
    private const val TAG = "LogManager"
    private const val MAX_LOG_SIZE = 10000
    private const val MAX_LOG_FILES = 30
    
    private lateinit var logDir: File
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    /**
     * Log levels
     */
    enum class Level(val priority: Int, val label: String) {
        VERBOSE(2, "V"),
        DEBUG(3, "D"),
        INFO(4, "I"),
        WARNING(5, "W"),
        ERROR(6, "E"),
        CRITICAL(7, "C")
    }
    
    /**
     * Log categories
     */
    enum class Category(val value: String) {
        SYSTEM("system"),
        NETWORK("network"),
        SYNC("sync"),
        STORAGE("storage"),
        COMMAND("command"),
        PERMISSION("permission"),
        ERROR("error"),
        PERFORMANCE("performance"),
        SECURITY("security")
    }
    
    /**
     * Initialize log manager
     */
    fun initialize(context: Context) {
        logDir = StorageManager.getDirectory(StorageManager.Dir.LOGS)
        Log.i(TAG, "LogManager initialized at: ${logDir.absolutePath}")
    }
    
    /**
     * Log entry
     */
    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val category: Category,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
        val metadata: Map<String, Any>?
    )
    
    /**
     * Log verbose
     */
    fun v(tag: String, message: String, category: Category = Category.SYSTEM) {
        log(Level.VERBOSE, category, tag, message)
    }
    
    /**
     * Log debug
     */
    fun d(tag: String, message: String, category: Category = Category.SYSTEM) {
        log(Level.DEBUG, category, tag, message)
    }
    
    /**
     * Log info
     */
    fun i(tag: String, message: String, category: Category = Category.SYSTEM) {
        log(Level.INFO, category, tag, message)
    }
    
    /**
     * Log warning
     */
    fun w(tag: String, message: String, category: Category = Category.SYSTEM) {
        log(Level.WARNING, category, tag, message)
    }
    
    /**
     * Log error
     */
    fun e(tag: String, message: String, throwable: Throwable? = null, category: Category = Category.ERROR) {
        log(Level.ERROR, category, tag, message, throwable)
    }
    
    /**
     * Log critical
     */
    fun c(tag: String, message: String, throwable: Throwable? = null, category: Category = Category.ERROR) {
        log(Level.CRITICAL, category, tag, message, throwable)
    }
    
    /**
     * Write log entry
     */
    private fun log(
        level: Level,
        category: Category,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any>? = null
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            tag = tag,
            message = message,
            throwable = throwable,
            metadata = metadata
        )
        
        // Add to buffer
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOG_SIZE) {
            logBuffer.poll()
        }
        
        // Log to Android
        when (level) {
            Level.VERBOSE -> Log.v(tag, message)
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARNING -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message, throwable)
            Level.CRITICAL -> Log.e(tag, "[CRITICAL] $message", throwable)
        }
        
        // Write to file for errors and critical
        if (level.priority >= Level.ERROR.priority) {
            writeToFile(entry)
        }
    }
    
    /**
     * Write log entry to file
     */
    private fun writeToFile(entry: LogEntry) {
        try {
            val logFile = getCurrentLogFile()
            val logLine = formatLogLine(entry)
            logFile.appendText(logLine + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }
    
    /**
     * Get current log file
     */
    private fun getCurrentLogFile(): File {
        val today = fileDateFormat.format(Date())
        return File(logDir, "app_$today.log")
    }
    
    /**
     * Format log line
     */
    private fun formatLogLine(entry: LogEntry): String {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val level = entry.level.label
        val category = entry.category.value
        val throwableStr = entry.throwable?.let { "\n${getStackTraceString(it)}" } ?: ""
        
        return "$timestamp [$level][$category][${entry.tag}] ${entry.message}$throwableStr"
    }
    
    /**
     * Get stack trace string
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }
    
    /**
     * Get recent logs
     */
    fun getRecentLogs(count: Int = 100): List<String> {
        return logBuffer.toList()
            .takeLast(count)
            .map { formatLogLine(it) }
    }
    
    /**
     * Get logs by level
     */
    fun getLogsByLevel(level: Level): List<LogEntry> {
        return logBuffer.filter { it.level == level }.toList()
    }
    
    /**
     * Get logs by category
     */
    fun getLogsByCategory(category: Category): List<LogEntry> {
        return logBuffer.filter { it.category == category }.toList()
    }
    
    /**
     * Export logs to file
     */
    suspend fun exportLogs(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val logs = logBuffer.toList()
            outputFile.writeText(logs.joinToString("\n") { formatLogLine(it) })
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            false
        }
    }
    
    /**
     * Export logs as JSON
     */
    suspend fun exportLogsAsJson(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonArray = org.json.JSONArray()
            logBuffer.forEach { entry ->
                val json = JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("datetime", dateFormat.format(Date(entry.timestamp)))
                    put("level", entry.level.name)
                    put("category", entry.category.value)
                    put("tag", entry.tag)
                    put("message", entry.message)
                    entry.throwable?.let { put("throwable", getStackTraceString(it)) }
                    entry.metadata?.let { put("metadata", JSONObject(it)) }
                }
                jsonArray.put(json)
            }
            outputFile.writeText(jsonArray.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs as JSON", e)
            false
        }
    }
    
    /**
     * Clear log buffer
     */
    fun clearBuffer() {
        logBuffer.clear()
    }
    
    /**
     * Clean old log files
     */
    fun cleanOldLogFiles(maxAgeDays: Int = 7): Int {
        return StorageManager.cleanOldFiles(StorageManager.Dir.LOGS, maxAgeDays)
    }
    
    /**
     * Get log file list
     */
    fun getLogFiles(): List<File> {
        return logDir.listFiles()
            ?.filter { it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    /**
     * Get log statistics
     */
    fun getLogStats(): LogStats {
        val logs = logBuffer.toList()
        
        return LogStats(
            totalEntries = logs.size,
            errorCount = logs.count { it.level == Level.ERROR },
            warningCount = logs.count { it.level == Level.WARNING },
            criticalCount = logs.count { it.level == Level.CRITICAL },
            logFilesCount = getLogFiles().size,
            oldestEntry = logs.minByOrNull { it.timestamp }?.timestamp,
            newestEntry = logs.maxByOrNull { it.timestamp }?.timestamp
        )
    }
    
    data class LogStats(
        val totalEntries: Int,
        val errorCount: Int,
        val warningCount: Int,
        val criticalCount: Int,
        val logFilesCount: Int,
        val oldestEntry: Long?,
        val newestEntry: Long?
    )
}

/**
 * CrashReporter - Handles uncaught exceptions
 */
object CrashReporter : Thread.UncaughtExceptionHandler {
    
    private const val TAG = "CrashReporter"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var context: Context
    
    /**
     * Initialize crash reporter
     */
    fun initialize(ctx: Context) {
        context = ctx
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "CrashReporter initialized")
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception", throwable)
        
        // Log to LogManager
        LogManager.c(
            TAG,
            "Crash: ${throwable.message}",
            throwable,
            LogManager.Category.ERROR
        )
        
        // Save crash report
        saveCrashReport(throwable)
        
        // Call default handler
        defaultHandler?.uncaughtException(thread, throwable)
    }
    
    private fun saveCrashReport(throwable: Throwable) {
        try {
            val crashFile = File(
                StorageManager.getDirectory(StorageManager.Dir.LOGS),
                "crash_${System.currentTimeMillis()}.log"
            )
            
            val report = buildString {
                appendLine("=== CRASH REPORT ===")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                appendLine("App Version: ${com.abuzahra.manager.Config.APP_VERSION}")
                appendLine()
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine()
                appendLine("Stack Trace:")
                throwable.printStackTrace(java.io.PrintWriter(this))
            }
            
            crashFile.writeText(report)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report", e)
        }
    }
}
