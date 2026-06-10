package com.abuzahra.manager.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abuzahra.manager.Config
import com.abuzahra.manager.R
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioStreamService - Service for microphone and device audio streaming
 * Supports both microphone audio and device audio (AudioPlaybackCapture for Android 10+), noise suppression
 */
class AudioStreamService : Service() {
    
    companion object {
        private const val TAG = "AudioStreamService"
        private const val NOTIFICATION_ID = 2003
        private const val CHANNEL_ID = "audio_stream_channel"
        
        // Intent actions
        const val ACTION_START_STREAM = "com.abuzahra.manager.START_AUDIO_STREAM"
        const val ACTION_STOP_STREAM = "com.abuzahra.manager.STOP_AUDIO_STREAM"
        const val ACTION_PAUSE_STREAM = "com.abuzahra.manager.PAUSE_AUDIO_STREAM"
        const val ACTION_RESUME_STREAM = "com.abuzahra.manager.RESUME_AUDIO_STREAM"
        const val ACTION_SWITCH_SOURCE = "com.abuzahra.manager.SWITCH_AUDIO_SOURCE"
        const val ACTION_TOGGLE_NOISE_SUPPRESSION = "com.abuzahra.manager.TOGGLE_NOISE_SUPPRESSION"
        
        // Intent extras
        const val EXTRA_CONFIG = "config"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MEDIA_PROJECTION_RESULT_CODE = "result_code"
        const val EXTRA_MEDIA_PROJECTION_DATA = "result_data"
        
        // Audio sources
        const val SOURCE_MICROPHONE = "microphone"
        const val SOURCE_DEVICE_AUDIO = "device_audio"
        const val SOURCE_BOTH = "both"
        
        // Stream state
        private var currentStreamState: StreamState? = null
        
        /**
         * Get current stream state
         */
        fun getStreamState(): StreamState? = currentStreamState
        
        /**
         * Check if streaming is active
         */
        fun isStreaming(): Boolean = currentStreamState?.isActive ?: false
    }
    
    // Service scope
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Audio configuration
    private var config: StreamConfig.Configuration = StreamConfig.Presets.audioOnlyMic()
    private var currentSource: String = SOURCE_MICROPHONE
    
    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: AudioEncoder? = null
    private var mediaProjection: MediaProjection? = null
    
    // Audio settings
    private val sampleRate = StreamConfig.AUDIO_SAMPLE_RATE
    private val channelCount = StreamConfig.AUDIO_CHANNEL_COUNT
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // Buffer sizes
    private var bufferSize = 0
    private var frameBufferSize = 0
    
    // Stream state
    private val isStreaming = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var streamingJob: Job? = null
    
    // Noise suppression
    private var noiseSuppressionEnabled = true
    private var noiseSuppressor: Any? = null // android.media.audiofx.NoiseSuppressor
    
    // WebSocket
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    
    // Statistics
    private var startTime = 0L
    private var totalBytesSent = 0L
    private var totalFramesEncoded = 0L
    
    // Handler
    private val handler = Handler(Looper.getMainLooper())
    
    // SharedPreferences
    private lateinit var prefs: SharedPreferences
    
    /**
     * Stream state
     */
    data class StreamState(
        val streamId: String,
        var isActive: Boolean,
        var isPaused: Boolean,
        val startTime: Long,
        var totalBytesSent: Long,
        var totalFramesEncoded: Long,
        var currentSource: String,
        var noiseSuppressionEnabled: Boolean,
        var lastError: String? = null
    )
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("audio_stream_prefs", Context.MODE_PRIVATE)
        
        // Initialize HTTP client
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        // Calculate buffer sizes
        bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            audioFormat
        )
        frameBufferSize = bufferSize * 2
        
        Log.i(TAG, "AudioStreamService created. Buffer size: $bufferSize")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundType = if (currentSource == SOURCE_MICROPHONE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, createNotification(), foregroundType)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        when (intent?.action) {
            ACTION_START_STREAM -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                configJson?.let { try { config = com.google.gson.Gson().fromJson(it, StreamConfig.Configuration::class.java) } catch(_ : Exception) {} }
                
                val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_MICROPHONE
                val resultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)
                }
                
                startStreaming(source, resultCode, resultData)
            }
            ACTION_STOP_STREAM -> stopStreaming()
            ACTION_PAUSE_STREAM -> pauseStreaming()
            ACTION_RESUME_STREAM -> resumeStreaming()
            ACTION_SWITCH_SOURCE -> {
                val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_MICROPHONE
                switchSource(source)
            }
            ACTION_TOGGLE_NOISE_SUPPRESSION -> toggleNoiseSuppression()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Start audio streaming
     */
    private fun startStreaming(source: String, mediaProjectionResultCode: Int = 0, mediaProjectionData: Intent? = null) {
        if (isStreaming.get()) {
            Log.w(TAG, "Already streaming")
            return
        }
        
        currentSource = source
        
        // Initialize encoder
        if (!initEncoder()) {
            updateStreamState(error = "Failed to initialize encoder")
            return
        }
        
        // Initialize audio source
        when (source) {
            SOURCE_MICROPHONE -> {
                if (!initMicrophoneSource()) {
                    updateStreamState(error = "Failed to initialize microphone")
                    cleanup()
                    return
                }
            }
            SOURCE_DEVICE_AUDIO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!initDeviceAudioSource(mediaProjectionResultCode, mediaProjectionData)) {
                        updateStreamState(error = "Failed to initialize device audio capture")
                        cleanup()
                        return
                    }
                } else {
                    Log.e(TAG, "Device audio capture requires Android 10+")
                    updateStreamState(error = "Device audio capture requires Android 10+")
                    cleanup()
                    return
                }
            }
            SOURCE_BOTH -> {
                // For both sources, we'll use microphone primarily
                // Device audio mixing would require more complex implementation
                if (!initMicrophoneSource()) {
                    updateStreamState(error = "Failed to initialize microphone")
                    cleanup()
                    return
                }
            }
        }
        
        // Connect to server
        if (!connectToServer()) {
            updateStreamState(error = "Failed to connect to server")
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
            currentSource = currentSource,
            noiseSuppressionEnabled = noiseSuppressionEnabled
        )
        
        // Start encoding
        audioEncoder?.start()
        
        // Start recording
        startRecording()
        
        updateNotification("Audio Streaming Active")
        Log.i(TAG, "Audio streaming started: ${config.streamId}, source: $currentSource")
    }
    
    /**
     * Initialize audio encoder
     */
    private fun initEncoder(): Boolean {
        audioEncoder = AudioEncoder(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitrate = config.audioBitrate
        )
        
        if (!audioEncoder!!.init()) {
            Log.e(TAG, "Failed to initialize audio encoder")
            return false
        }
        
        audioEncoder!!.setEncodedDataCallback { frame ->
            onAudioFrameEncoded(frame)
        }
        
        return true
    }
    
    /**
     * Initialize microphone source
     */
    private fun initMicrophoneSource(): Boolean {
        try {
            val channelConfig = if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                frameBufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return false
            }
            
            // Enable noise suppression if available
            if (noiseSuppressionEnabled) {
                enableNoiseSuppression()
            }
            
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize microphone", e)
            return false
        }
    }
    
    /**
     * Initialize device audio capture (Android 10+)
     */
    private fun initDeviceAudioSource(resultCode: Int, data: Intent?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Invalid MediaProjection data for device audio capture")
            return false
        }
        
        try {
            // Get MediaProjection
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            
            // Configure audio playback capture
            val audioPlaybackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            val channelConfig = if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
            
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(audioPlaybackCaptureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(frameBufferSize)
                .build()
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord for device audio not initialized")
                return false
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize device audio capture", e)
            return false
        }
    }
    
    /**
     * Enable noise suppression
     */
    private fun enableNoiseSuppression() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                val audioSessionId = audioRecord?.audioSessionId ?: return
                
                // Check if noise suppression is available
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(audioSessionId)
                    Log.i(TAG, "Noise suppression enabled")
                } else {
                    Log.w(TAG, "Noise suppression not available on this device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable noise suppression", e)
            }
        }
    }
    
    /**
     * Disable noise suppression
     */
    private fun disableNoiseSuppression() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                (noiseSuppressor as? android.media.audiofx.NoiseSuppressor)?.release()
                noiseSuppressor = null
                Log.i(TAG, "Noise suppression disabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable noise suppression", e)
            }
        }
    }
    
    /**
     * Start recording audio
     */
    private fun startRecording() {
        streamingJob = serviceScope.launch {
            try {
                audioRecord?.startRecording()
                
                val buffer = ByteArray(frameBufferSize)
                
                while (isStreaming.get() && !isPaused.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    
                    if (bytesRead > 0) {
                        // Get presentation time
                        val presentationTimeUs = System.nanoTime() / 1000
                        
                        // Copy data to new array (only the bytes read)
                        val audioData = buffer.copyOf(bytesRead)
                        
                        // Queue to encoder
                        audioEncoder?.queueAudioData(audioData, presentationTimeUs)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Error reading audio: $bytesRead")
                        break
                    }
                    
                    // Small delay to prevent busy loop
                    delay(1)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording loop", e)
                updateStreamState(error = "Recording error: ${e.message}")
            }
        }
    }
    
    /**
     * Connect to streaming server
     */
    private fun connectToServer(): Boolean {
        if (config.serverUrl.isBlank()) {
            // Auto-fill from Config if not set
            config = config.copy(serverUrl = Config.getBaseUrl())
        }
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
                    val configJson = Gson().toJson(config.toMap())
                    webSocket.send(configJson)
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text)
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed")
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    updateStreamState(error = "WebSocket failure: ${t.message}")
                    
                    if (isStreaming.get()) {
                        handler.postDelayed({
                            if (isStreaming.get()) connectToServer()
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
     * Handle server message
     */
    private fun handleServerMessage(message: String) {
        try {
            val map = Gson().fromJson(message, Map::class.java)
            
            when (map["type"]) {
                "switch_source" -> {
                    val source = map["source"] as? String
                    source?.let { switchSource(it) }
                }
                "toggle_noise_suppression" -> toggleNoiseSuppression()
                "stop_stream" -> stopStreaming()
                "pause_stream" -> pauseStreaming()
                "resume_stream" -> resumeStreaming()
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse server message: $message")
        }
    }
    
    /**
     * Handle encoded audio frame
     */
    private fun onAudioFrameEncoded(frame: AudioEncoder.EncodedAudioFrame) {
        if (!isStreaming.get() || isPaused.get()) return
        
        totalFramesEncoded++
        
        try {
            val packet = mapOf(
                "type" to "audio",
                "stream_id" to config.streamId,
                "timestamp" to frame.presentationTimeUs,
                "source" to currentSource,
                "size" to frame.size,
                "data" to android.util.Base64.encodeToString(frame.data, android.util.Base64.NO_WRAP)
            )
            
            webSocket?.send(Gson().toJson(packet))
            totalBytesSent += frame.size
            
            currentStreamState?.totalFramesEncoded = totalFramesEncoded
            currentStreamState?.totalBytesSent = totalBytesSent
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame", e)
        }
    }
    
    /**
     * Switch audio source
     */
    private fun switchSource(source: String) {
        if (!isStreaming.get()) return
        
        Log.i(TAG, "Switching source from $currentSource to $source")
        
        // Stop current recording
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        disableNoiseSuppression()
        
        currentSource = source
        
        // Initialize new source
        val success = when (source) {
            SOURCE_MICROPHONE -> initMicrophoneSource()
            SOURCE_DEVICE_AUDIO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Device audio switch requires new MediaProjection
                    Log.w(TAG, "Device audio switch requires new MediaProjection")
                    initMicrophoneSource() // Fallback to microphone
                } else {
                    initMicrophoneSource()
                }
            }
            else -> initMicrophoneSource()
        }
        
        if (success) {
            startRecording()
            currentStreamState?.currentSource = currentSource
            updateNotification("Audio Streaming - $currentSource")
            Log.i(TAG, "Switched to source: $currentSource")
        } else {
            Log.e(TAG, "Failed to switch source")
            stopStreaming()
        }
    }
    
    /**
     * Toggle noise suppression
     */
    private fun toggleNoiseSuppression() {
        noiseSuppressionEnabled = !noiseSuppressionEnabled
        
        if (noiseSuppressionEnabled) {
            enableNoiseSuppression()
        } else {
            disableNoiseSuppression()
        }
        
        currentStreamState?.noiseSuppressionEnabled = noiseSuppressionEnabled
        Log.i(TAG, "Noise suppression: ${if (noiseSuppressionEnabled) "ON" else "OFF"}")
    }
    
    /**
     * Pause streaming
     */
    private fun pauseStreaming() {
        if (!isStreaming.get() || isPaused.get()) return
        
        isPaused.set(true)
        currentStreamState?.isPaused = true
        
        audioRecord?.stop()
        
        updateNotification("Audio Streaming Paused")
        Log.i(TAG, "Audio streaming paused")
    }
    
    /**
     * Resume streaming
     */
    private fun resumeStreaming() {
        if (!isStreaming.get() || !isPaused.get()) return
        
        isPaused.set(false)
        currentStreamState?.isPaused = false
        
        startRecording()
        
        updateNotification("Audio Streaming Active")
        Log.i(TAG, "Audio streaming resumed")
    }
    
    /**
     * Stop streaming
     */
    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return
        
        isPaused.set(false)
        
        // Stop recording
        streamingJob?.cancel()
        streamingJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        // Disable noise suppression
        disableNoiseSuppression()
        
        // Stop encoder
        audioEncoder?.stop()
        
        // Close WebSocket
        webSocket?.close(1000, "Stream stopped")
        webSocket = null
        
        // Release MediaProjection
        mediaProjection?.stop()
        mediaProjection = null
        
        // Update state
        currentStreamState?.isActive = false
        
        // Stop foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        Log.i(TAG, "Audio streaming stopped")
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        audioEncoder?.release()
        audioEncoder = null
        
        audioRecord?.release()
        audioRecord = null
        
        disableNoiseSuppression()
        
        mediaProjection?.stop()
        mediaProjection = null
    }
    
    /**
     * Update stream state
     */
    private fun updateStreamState(error: String? = null) {
        currentStreamState?.lastError = error
        
        if (error != null) {
            try {
                val errorPacket = mapOf(
                    "type" to "error",
                    "stream_id" to config.streamId,
                    "error" to error
                )
                webSocket?.send(Gson().toJson(errorPacket))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send error notification", e)
            }
        }
    }
    
    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio streaming service"
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
            .setContentTitle("Audio Stream Active")
            .setContentText("Streaming audio content")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
            .setContentTitle("Audio Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
        Log.i(TAG, "AudioStreamService destroyed")
    }
}
