package com.abuzahra.manager.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.abuzahra.manager.Config
import com.abuzahra.manager.R
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenStreamService - Foreground service for screen streaming with MediaProjection
 * Supports H264/H265 encoding, configurable FPS (15-60), adaptive bitrate, battery optimization
 */
class ScreenStreamService : Service() {
    
    companion object {
        private const val TAG = "ScreenStreamService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "screen_stream_channel"
        
        // Permission data (persisted from permission request)
        var lastResultCode: Int = 0
        var lastPermissionData: Intent? = null
        
        // Intent actions
        const val ACTION_START_STREAM = "com.abuzahra.manager.START_SCREEN_STREAM"
        const val ACTION_STOP_STREAM = "com.abuzahra.manager.STOP_SCREEN_STREAM"
        const val ACTION_PAUSE_STREAM = "com.abuzahra.manager.PAUSE_SCREEN_STREAM"
        const val ACTION_RESUME_STREAM = "com.abuzahra.manager.RESUME_SCREEN_STREAM"
        const val ACTION_UPDATE_CONFIG = "com.abuzahra.manager.UPDATE_SCREEN_STREAM_CONFIG"
        
        // Intent extras
        const val EXTRA_CONFIG = "config"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        // Stream state
        private var currentStreamState: StreamState? = null
        
        /**
         * Set permission data from activity result
         */
        fun setPermissionData(resultCode: Int, data: Intent) {
            lastResultCode = resultCode
            lastPermissionData = data
        }
        
        /**
         * Check if permission is granted
         */
        fun hasPermission(): Boolean {
            return lastResultCode != 0 && lastPermissionData != null
        }
        
        /**
         * Get current stream state
         */
        fun getStreamState(): StreamState? = currentStreamState
        
        /**
         * Check if streaming is active
         */
        fun isStreaming(): Boolean = currentStreamState?.isActive ?: false
    }
    
    // Service scope for coroutines
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // Encoders
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    
    // Configuration
    private var config: StreamConfig.Configuration = StreamConfig.Presets.screenShareMedium()
    
    // Stream state
    private val isStreaming = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var streamJob: Job? = null
    private var stateJob: Job? = null
    
    // WebSocket client
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    
    // Statistics
    private var startTime = 0L
    private var totalBytesSent = 0L
    private var totalFramesEncoded = 0L
    
    // Battery optimization
    private var lastBatteryLevel = 100
    private var batteryWarningSent = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    // SharedPreferences for state persistence
    private lateinit var prefs: SharedPreferences
    
    /**
     * Stream state data class
     */
    data class StreamState(
        val streamId: String,
        var isActive: Boolean,
        var isPaused: Boolean,
        val startTime: Long,
        var totalBytesSent: Long,
        var totalFramesEncoded: Long,
        var currentBitrate: Int,
        var lastError: String? = null
    )
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("screen_stream_prefs", Context.MODE_PRIVATE)
        
        // Initialize HTTP client
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        // Load saved state
        loadSavedState()
        
        Log.i(TAG, "ScreenStreamService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service with proper type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        when (intent?.action) {
            ACTION_START_STREAM -> {
                val configExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CONFIG, StreamConfig.Configuration::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig.Configuration
                }
                configExtra?.let { config = it }
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, lastResultCode)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                } ?: lastPermissionData
                
                startStreaming(resultCode, resultData)
            }
            ACTION_STOP_STREAM -> stopStreaming()
            ACTION_PAUSE_STREAM -> pauseStreaming()
            ACTION_RESUME_STREAM -> resumeStreaming()
            ACTION_UPDATE_CONFIG -> {
                val newConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CONFIG, StreamConfig.Configuration::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig.Configuration
                }
                newConfig?.let { updateConfig(it) }
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Initialize MediaProjection
     */
    private fun initMediaProjection(resultCode: Int, data: Intent?): Boolean {
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Invalid permission data")
            return false
        }
        
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            
            // Register callback for projection stop
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                    stopStreaming()
                }
            }, handler)
            
            // Get screen metrics
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                screenDensity = resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                screenDensity = metrics.densityDpi
            }
            
            // Adjust resolution based on config
            if (config.width < screenWidth || config.height < screenHeight) {
                val scale = minOf(
                    config.width.toFloat() / screenWidth,
                    config.height.toFloat() / screenHeight
                )
                screenWidth = (screenWidth * scale).toInt()
                screenHeight = (screenHeight * scale).toInt()
            }
            
            Log.i(TAG, "MediaProjection initialized: ${screenWidth}x${screenHeight}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            return false
        }
    }
    
    /**
     * Start screen streaming
     */
    private fun startStreaming(resultCode: Int, data: Intent?) {
        if (isStreaming.get()) {
            Log.w(TAG, "Already streaming")
            return
        }
        
        // Initialize MediaProjection
        if (!initMediaProjection(resultCode, data)) {
            updateStreamState(error = "Failed to initialize MediaProjection")
            return
        }
        
        // Update config with actual resolution
        config = config.copy(
            width = screenWidth,
            height = screenHeight
        )
        
        // Initialize encoders
        if (!initEncoders()) {
            updateStreamState(error = "Failed to initialize encoders")
            return
        }
        
        // Connect to server
        if (!connectToServer()) {
            updateStreamState(error = "Failed to connect to server")
            cleanup()
            return
        }
        
        // Create VirtualDisplay
        if (!createVirtualDisplay()) {
            updateStreamState(error = "Failed to create VirtualDisplay")
            cleanup()
            return
        }
        
        // Start streaming
        isStreaming.set(true)
        startTime = System.currentTimeMillis()
        
        // Initialize state
        currentStreamState = StreamState(
            streamId = config.streamId,
            isActive = true,
            isPaused = false,
            startTime = startTime,
            totalBytesSent = 0,
            totalFramesEncoded = 0,
            currentBitrate = config.videoBitrate
        )
        
        // Start state monitoring
        startStateMonitoring()
        
        // Update notification
        updateNotification("Streaming Active")
        
        Log.i(TAG, "Screen streaming started: ${config.streamId}")
    }
    
    /**
     * Initialize video and audio encoders
     */
    private fun initEncoders(): Boolean {
        // Initialize video encoder
        videoEncoder = VideoEncoder(config)
        
        if (!videoEncoder!!.init()) {
            Log.e(TAG, "Failed to initialize video encoder")
            return false
        }
        
        // Set encoded data callback
        videoEncoder!!.setEncodedDataCallback { frame ->
            onVideoFrameEncoded(frame)
        }
        
        // Initialize audio encoder if enabled
        if (config.audioEnabled) {
            audioEncoder = AudioEncoder(
                sampleRate = StreamConfig.AUDIO_SAMPLE_RATE,
                channelCount = StreamConfig.AUDIO_CHANNEL_COUNT,
                bitrate = config.audioBitrate
            )
            
            if (!audioEncoder!!.init()) {
                Log.w(TAG, "Failed to initialize audio encoder, continuing without audio")
                audioEncoder = null
            } else {
                audioEncoder!!.setEncodedDataCallback { frame ->
                    onAudioFrameEncoded(frame)
                }
            }
        }
        
        return true
    }
    
    /**
     * Create VirtualDisplay for screen capture
     */
    private fun createVirtualDisplay(): Boolean {
        val inputSurface = videoEncoder?.getInputSurface()
        if (inputSurface == null) {
            Log.e(TAG, "Input surface is null")
            return false
        }
        
        try {
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenStream_${config.streamId}",
                screenWidth,
                screenHeight,
                screenDensity,
                flags,
                inputSurface,
                null,
                handler
            )
            
            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay")
                return false
            }
            
            // Start video encoder
            if (!videoEncoder!!.start()) {
                Log.e(TAG, "Failed to start video encoder")
                return false
            }
            
            // Start audio encoder
            audioEncoder?.start()
            
            Log.i(TAG, "VirtualDisplay created successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating VirtualDisplay", e)
            return false
        }
    }
    
    /**
     * Connect to streaming server via WebSocket
     */
    private fun connectToServer(): Boolean {
        val serverUrl = config.serverUrl.ifEmpty {
            StreamConfig.getWebSocketUrl(this)
        }
        
        try {
            val request = Request.Builder()
                .url(serverUrl)
                .build()
            
            webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected")
                    
                    // Send stream configuration
                    val configJson = com.google.gson.Gson().toJson(config.toMap())
                    webSocket.send(configJson)
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text)
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closing: $code - $reason")
                    webSocket.close(1000, null)
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code - $reason")
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    updateStreamState(error = "WebSocket failure: ${t.message}")
                    
                    // Attempt reconnection
                    if (isStreaming.get()) {
                        handler.postDelayed({
                            if (isStreaming.get()) {
                                connectToServer()
                            }
                        }, StreamConfig.WEBSOCKET_RECONNECT_DELAY_MS)
                    }
                }
            })
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
            return false
        }
    }
    
    /**
     * Handle message from server
     */
    private fun handleServerMessage(message: String) {
        try {
            val map = com.google.gson.Gson().fromJson(message, Map::class.java)
            
            when (map["type"]) {
                "config_update" -> {
                    // Update configuration from server
                    val newBitrate = (map["bitrate"] as? Number)?.toInt()
                    if (newBitrate != null && config.enableAdaptiveBitrate) {
                        videoEncoder?.updateBitrate(newBitrate)
                        config = config.copy(videoBitrate = newBitrate)
                    }
                }
                "request_keyframe" -> {
                    videoEncoder?.requestKeyframe()
                }
                "stop_stream" -> {
                    stopStreaming()
                }
                "pause_stream" -> {
                    pauseStreaming()
                }
                "resume_stream" -> {
                    resumeStreaming()
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse server message: $message")
        }
    }
    
    /**
     * Handle encoded video frame
     */
    private fun onVideoFrameEncoded(frame: VideoEncoder.EncodedFrame) {
        if (!isStreaming.get() || isPaused.get()) return
        
        totalFramesEncoded++
        
        // Send frame data via WebSocket
        try {
            // Create frame packet
            val packet = mapOf(
                "type" to "video",
                "stream_id" to config.streamId,
                "timestamp" to frame.presentationTimeUs,
                "is_keyframe" to frame.isKeyFrame,
                "codec" to frame.codec.name,
                "size" to frame.size,
                "data" to android.util.Base64.encodeToString(frame.data, android.util.Base64.NO_WRAP)
            )
            
            val json = com.google.gson.Gson().toJson(packet)
            webSocket?.send(json)
            
            totalBytesSent += frame.size
            currentStreamState?.totalFramesEncoded = totalFramesEncoded
            currentStreamState?.totalBytesSent = totalBytesSent
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send video frame", e)
        }
    }
    
    /**
     * Handle encoded audio frame
     */
    private fun onAudioFrameEncoded(frame: AudioEncoder.EncodedAudioFrame) {
        if (!isStreaming.get() || isPaused.get()) return
        
        // Send audio data via WebSocket
        try {
            val packet = mapOf(
                "type" to "audio",
                "stream_id" to config.streamId,
                "timestamp" to frame.presentationTimeUs,
                "size" to frame.size,
                "data" to android.util.Base64.encodeToString(frame.data, android.util.Base64.NO_WRAP)
            )
            
            val json = com.google.gson.Gson().toJson(packet)
            webSocket?.send(json)
            
            totalBytesSent += frame.size
            currentStreamState?.totalBytesSent = totalBytesSent
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame", e)
        }
    }
    
    /**
     * Pause streaming
     */
    private fun pauseStreaming() {
        if (!isStreaming.get() || isPaused.get()) return
        
        isPaused.set(true)
        currentStreamState?.isPaused = true
        
        // Pause virtual display by releasing and recreating later
        virtualDisplay?.release()
        virtualDisplay = null
        
        updateNotification("Streaming Paused")
        Log.i(TAG, "Screen streaming paused")
    }
    
    /**
     * Resume streaming
     */
    private fun resumeStreaming() {
        if (!isStreaming.get() || !isPaused.get()) return
        
        isPaused.set(false)
        currentStreamState?.isPaused = false
        
        // Recreate virtual display
        createVirtualDisplay()
        
        // Request keyframe for clean resume
        videoEncoder?.requestKeyframe()
        
        updateNotification("Streaming Active")
        Log.i(TAG, "Screen streaming resumed")
    }
    
    /**
     * Update stream configuration
     */
    private fun updateConfig(newConfig: StreamConfig.Configuration) {
        val bitrateChanged = newConfig.videoBitrate != config.videoBitrate
        config = newConfig
        
        if (bitrateChanged && isStreaming.get()) {
            videoEncoder?.updateBitrate(config.videoBitrate)
        }
        
        Log.i(TAG, "Stream config updated: $config")
    }
    
    /**
     * Stop streaming
     */
    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return
        
        isPaused.set(false)
        
        // Close WebSocket
        webSocket?.close(1000, "Stream stopped")
        webSocket = null
        
        // Stop encoders
        videoEncoder?.stop()
        audioEncoder?.stop()
        
        // Release virtual display
        virtualDisplay?.release()
        virtualDisplay = null
        
        // Stop state monitoring
        stateJob?.cancel()
        stateJob = null
        
        // Update state
        currentStreamState?.isActive = false
        
        // Save final state
        saveState()
        
        // Stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        Log.i(TAG, "Screen streaming stopped")
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        videoEncoder?.release()
        videoEncoder = null
        
        audioEncoder?.release()
        audioEncoder = null
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        mediaProjection?.stop()
        mediaProjection = null
    }
    
    /**
     * Start state monitoring
     */
    private fun startStateMonitoring() {
        stateJob = serviceScope.launch {
            while (isStreaming.get()) {
                delay(5000) // Check every 5 seconds
                
                // Check battery level
                checkBatteryLevel()
                
                // Save state periodically
                saveState()
                
                // Update bitrate if adaptive
                if (config.enableAdaptiveBitrate) {
                    adaptBitrateToNetwork()
                }
            }
        }
    }
    
    /**
     * Check battery level for optimization
     */
    private fun checkBatteryLevel() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        if (level < 15 && !batteryWarningSent && level < lastBatteryLevel) {
            // Low battery warning
            batteryWarningSent = true
            updateStreamState(error = "Low battery: $level%")
            
            // Reduce quality to save battery
            if (config.fps > 15) {
                config = config.copy(fps = 15)
                videoEncoder?.updateBitrate((config.videoBitrate * 0.7).toInt())
            }
        }
        
        lastBatteryLevel = level
    }
    
    /**
     * Adapt bitrate based on network conditions
     */
    private fun adaptBitrateToNetwork() {
        // This would be implemented with actual network quality monitoring
        // For now, it's a placeholder that could be enhanced with AdaptiveBitrateController
    }
    
    /**
     * Update stream state
     */
    private fun updateStreamState(error: String? = null) {
        currentStreamState?.let { state ->
            state.lastError = error
            
            // Notify server of error
            if (error != null) {
                try {
                    val errorPacket = mapOf(
                        "type" to "error",
                        "stream_id" to config.streamId,
                        "error" to error
                    )
                    webSocket?.send(com.google.gson.Gson().toJson(errorPacket))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send error notification", e)
                }
            }
        }
    }
    
    /**
     * Save state to SharedPreferences
     */
    private fun saveState() {
        prefs.edit().apply {
            putString("stream_id", config.streamId)
            putLong("start_time", startTime)
            putLong("bytes_sent", totalBytesSent)
            putLong("frames_encoded", totalFramesEncoded)
            putBoolean("was_streaming", isStreaming.get())
            apply()
        }
    }
    
    /**
     * Load saved state from SharedPreferences
     */
    private fun loadSavedState() {
        val wasStreaming = prefs.getBoolean("was_streaming", false)
        if (wasStreaming) {
            Log.i(TAG, "Previous streaming session detected, but not auto-resuming")
            // Could implement auto-resume logic here if needed
        }
    }
    
    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background screen streaming service"
                setShowBadge(false)
            }
            
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create foreground notification
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Stream Active")
            .setContentText("Streaming screen content")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Update notification
     */
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        stopStreaming()
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "ScreenStreamService destroyed")
    }
}
