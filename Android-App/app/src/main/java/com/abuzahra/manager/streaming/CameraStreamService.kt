package com.abuzahra.manager.streaming

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.abuzahra.manager.Config
import com.abuzahra.manager.R
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraStreamService - Service for front/back camera streaming using Camera2 API
 * Supports camera switching during stream, multiple quality levels (480p, 720p, 1080p), torch control
 */
class CameraStreamService : Service() {
    
    companion object {
        private const val TAG = "CameraStreamService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "camera_stream_channel"
        
        // Intent actions
        const val ACTION_START_STREAM = "com.abuzahra.manager.START_CAMERA_STREAM"
        const val ACTION_STOP_STREAM = "com.abuzahra.manager.STOP_CAMERA_STREAM"
        const val ACTION_SWITCH_CAMERA = "com.abuzahra.manager.SWITCH_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.abuzahra.manager.TOGGLE_TORCH"
        const val ACTION_UPDATE_QUALITY = "com.abuzahra.manager.UPDATE_QUALITY"
        const val ACTION_PAUSE_STREAM = "com.abuzahra.manager.PAUSE_CAMERA_STREAM"
        const val ACTION_RESUME_STREAM = "com.abuzahra.manager.RESUME_CAMERA_STREAM"
        
        // Intent extras
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_QUALITY = "quality"
        
        // Camera IDs
        const val CAMERA_FRONT = "front"
        const val CAMERA_BACK = "back"
        
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
    
    // Service scope for coroutines
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Camera2 API
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Camera state
    private var currentCameraId: String = ""
    private var frontCameraId: String = ""
    private var backCameraId: String = ""
    private var isTorchOn = false
    private var isStreaming = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)
    
    // Configuration
    private var config: StreamConfig.Configuration = StreamConfig.Presets.cameraStream()
    
    // Encoders
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    
    // WebSocket client
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    
    // Preview size
    private var previewSize: Size? = null
    
    // Statistics
    private var startTime = 0L
    private var totalBytesSent = 0L
    private var totalFramesEncoded = 0L
    
    // SharedPreferences
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
        var currentCamera: String,
        var isTorchOn: Boolean,
        var currentQuality: StreamConfig.Quality,
        var lastError: String? = null
    )
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("camera_stream_prefs", Context.MODE_PRIVATE)
        
        // Initialize camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        // Find available cameras
        findAvailableCameras()
        
        // Initialize HTTP client
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        Log.i(TAG, "CameraStreamService created. Front: $frontCameraId, Back: $backCameraId")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        when (intent?.action) {
            ACTION_START_STREAM -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                configJson?.let { try { config = com.google.gson.Gson().fromJson(it, StreamConfig.Configuration::class.java) } catch(_ : Exception) {} }
                
                val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID) ?: CAMERA_BACK
                startStreaming(cameraId)
            }
            ACTION_STOP_STREAM -> stopStreaming()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_TOGGLE_TORCH -> toggleTorch()
            ACTION_UPDATE_QUALITY -> {
                val qualityName = intent.getStringExtra(EXTRA_QUALITY)
                qualityName?.let { updateQuality(it) }
            }
            ACTION_PAUSE_STREAM -> pauseStreaming()
            ACTION_RESUME_STREAM -> resumeStreaming()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Find available camera IDs
     */
    @SuppressLint("MissingPermission")
    private fun findAvailableCameras() {
        try {
            val cameraIds = cameraManager?.cameraIdList ?: return
            
            for (id in cameraIds) {
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = id
                    CameraCharacteristics.LENS_FACING_BACK -> backCameraId = id
                }
            }
            
            // Default to back camera
            currentCameraId = if (backCameraId.isNotEmpty()) backCameraId else frontCameraId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find cameras", e)
        }
    }
    
    /**
     * Start camera streaming
     */
    private fun startStreaming(cameraId: String) {
        if (isStreaming.get()) {
            Log.w(TAG, "Already streaming")
            return
        }
        
        // Select camera
        currentCameraId = when (cameraId) {
            CAMERA_FRONT -> frontCameraId
            CAMERA_BACK -> backCameraId
            else -> cameraId
        }
        
        if (currentCameraId.isEmpty()) {
            Log.e(TAG, "No camera available")
            updateStreamState(error = "No camera available")
            return
        }
        
        // Start background thread
        startBackgroundThread()
        
        // Initialize encoders
        if (!initEncoders()) {
            updateStreamState(error = "Failed to initialize encoders")
            cleanup()
            return
        }
        
        // Open camera BEFORE connecting to server
        if (!openCamera()) {
            updateStreamState(error = "Failed to open camera")
            cleanup()
            return
        }
        
        // Set streaming active NOW (before server connection)
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
            currentCamera = if (currentCameraId == frontCameraId) CAMERA_FRONT else CAMERA_BACK,
            isTorchOn = false,
            currentQuality = config.quality
        )
        
        updateNotification("Camera Streaming Active")
        
        // Connect to server ASYNC (non-blocking, retries in background)
        connectToServer()
        
        Log.i(TAG, "Camera streaming started: ${config.streamId}")
    }
    
    /**
     * Initialize encoders
     */
    private fun initEncoders(): Boolean {
        // Initialize video encoder
        videoEncoder = VideoEncoder(config)
        
        if (!videoEncoder!!.init()) {
            Log.e(TAG, "Failed to initialize video encoder")
            return false
        }
        
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
     * Start background thread for camera operations
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    
    /**
     * Stop background thread
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
    
    /**
     * Open camera device
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(): Boolean {
        try {
            // Get supported preview size
            val characteristics = cameraManager?.getCameraCharacteristics(currentCameraId)
            val configMap = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            previewSize = chooseOptimalSize(
                configMap?.getOutputSizes(SurfaceTexture::class.java),
                config.width,
                config.height
            )
            
            // Update config with actual size
            previewSize?.let {
                config = config.copy(width = it.width, height = it.height)
            }
            
            // Open camera
            cameraManager?.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera error: $error")
                    updateStreamState(error = "Camera error: $error")
                }
            }, backgroundHandler)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            return false
        }
    }
    
    /**
     * Choose optimal preview size
     */
    private fun chooseOptimalSize(choices: Array<Size>?, targetWidth: Int, targetHeight: Int): Size? {
        if (choices == null || choices.isEmpty()) return null
        
        // Find sizes close to target
        val targetRatio = targetWidth.toDouble() / targetHeight
        
        return choices
            .filter { it.width >= 320 && it.height >= 240 }
            .minByOrNull { size ->
                val ratio = size.width.toDouble() / size.height
                val ratioDiff = Math.abs(ratio - targetRatio)
                val sizeDiff = Math.abs(size.width - targetWidth) + Math.abs(size.height - targetHeight)
                ratioDiff * 1000 + sizeDiff
            } ?: choices[0]
    }
    
    /**
     * Create camera capture session
     */
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val surface = videoEncoder?.getInputSurface() ?: return
        
        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        startPreview()
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        updateStreamState(error = "Capture session failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }
    
    /**
     * Start camera preview
     */
    private fun startPreview() {
        val session = cameraCaptureSession ?: return
        val surface = videoEncoder?.getInputSurface() ?: return
        
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder?.addTarget(surface)
            
            // Set capture parameters
            builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            
            // Apply torch if needed
            if (isTorchOn) {
                builder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }
            
            session.setRepeatingRequest(
                builder?.build()!!,
                null,
                backgroundHandler
            )
            
            // Start video encoder
            videoEncoder?.start()
            audioEncoder?.start()
            
            Log.i(TAG, "Camera preview started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }
    
    /**
     * Connect to streaming server.
     * Non-blocking - retries automatically on failure.
     */
    private fun connectToServer() {
        if (config.serverUrl.isBlank()) {
            config = config.copy(serverUrl = Config.getBaseUrl())
        }
        val serverUrl = config.serverUrl.ifEmpty {
            StreamConfig.getWebSocketUrl(this)
        }
        
        try {
            val request = Request.Builder()
                .url(serverUrl)
                .build()
            
            webSocket?.close(1000, "Reconnecting")
            webSocket = null
            
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
                    Log.w(TAG, "WebSocket failure: ${t.message}")
                    if (isStreaming.get()) {
                        Handler(mainLooper).postDelayed({
                            if (isStreaming.get()) connectToServer()
                        }, StreamConfig.WEBSOCKET_RECONNECT_DELAY_MS)
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
            if (isStreaming.get()) {
                Handler(mainLooper).postDelayed({
                    if (isStreaming.get()) connectToServer()
                }, StreamConfig.WEBSOCKET_RECONNECT_DELAY_MS)
            }
        }
    }
    
    /**
     * Handle server message
     */
    private fun handleServerMessage(message: String) {
        try {
            val map = Gson().fromJson(message, Map::class.java)
            
            when (map["type"]) {
                "switch_camera" -> switchCamera()
                "toggle_torch" -> toggleTorch()
                "set_quality" -> {
                    val quality = map["quality"] as? String
                    quality?.let { updateQuality(it) }
                }
                "stop_stream" -> stopStreaming()
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
        
        try {
            val packet = mapOf(
                "type" to "video",
                "stream_id" to config.streamId,
                "timestamp" to frame.presentationTimeUs,
                "is_keyframe" to frame.isKeyFrame,
                "codec" to frame.codec.name,
                "size" to frame.size,
                "camera" to currentStreamState?.currentCamera,
                "data" to android.util.Base64.encodeToString(frame.data, android.util.Base64.NO_WRAP)
            )
            
            webSocket?.send(Gson().toJson(packet))
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
        
        try {
            val packet = mapOf(
                "type" to "audio",
                "stream_id" to config.streamId,
                "timestamp" to frame.presentationTimeUs,
                "size" to frame.size,
                "data" to android.util.Base64.encodeToString(frame.data, android.util.Base64.NO_WRAP)
            )
            
            webSocket?.send(Gson().toJson(packet))
            totalBytesSent += frame.size
            
            currentStreamState?.totalBytesSent = totalBytesSent
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame", e)
        }
    }
    
    /**
     * Switch between front and back camera
     */
    private fun switchCamera() {
        if (!isStreaming.get()) return
        
        Log.i(TAG, "Switching camera from $currentCameraId")
        
        // Close current camera
        closeCamera()
        
        // Switch camera ID
        currentCameraId = if (currentCameraId == frontCameraId) backCameraId else frontCameraId
        
        // Reopen camera
        if (!openCamera()) {
            Log.e(TAG, "Failed to switch camera")
            // Try to go back to previous camera
            currentCameraId = if (currentCameraId == frontCameraId) backCameraId else frontCameraId
            openCamera()
            return
        }
        
        // Update state
        currentStreamState?.currentCamera = if (currentCameraId == frontCameraId) CAMERA_FRONT else CAMERA_BACK
        
        // Reset torch state
        isTorchOn = false
        currentStreamState?.isTorchOn = false
        
        Log.i(TAG, "Switched to camera: ${currentStreamState?.currentCamera}")
    }
    
    /**
     * Toggle camera torch
     */
    private fun toggleTorch() {
        if (!isStreaming.get()) return
        
        isTorchOn = !isTorchOn
        
        try {
            val session = cameraCaptureSession ?: return
            val surface = videoEncoder?.getInputSurface() ?: return
            
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder?.addTarget(surface)
            builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            
            if (isTorchOn) {
                builder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                builder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            
            session.setRepeatingRequest(builder?.build()!!, null, backgroundHandler)
            
            currentStreamState?.isTorchOn = isTorchOn
            Log.i(TAG, "Torch ${if (isTorchOn) "ON" else "OFF"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch", e)
        }
    }
    
    /**
     * Update stream quality
     */
    private fun updateQuality(qualityName: String) {
        if (!isStreaming.get()) return
        
        val newQuality = try {
            StreamConfig.Quality.valueOf(qualityName.uppercase())
        } catch (e: Exception) {
            Log.w(TAG, "Invalid quality: $qualityName")
            return
        }
        
        // Update config
        config = config.copy(
            quality = newQuality,
            width = newQuality.width,
            height = newQuality.height,
            videoBitrate = newQuality.videoBitrate
        )
        
        // Restart camera with new resolution
        closeCamera()
        
        // Reinitialize video encoder with new settings
        videoEncoder?.release()
        videoEncoder = VideoEncoder(config)
        videoEncoder?.init()
        videoEncoder?.setEncodedDataCallback { frame -> onVideoFrameEncoded(frame) }
        
        openCamera()
        
        currentStreamState?.currentQuality = newQuality
        Log.i(TAG, "Quality updated to: $newQuality")
    }
    
    /**
     * Pause streaming
     */
    private fun pauseStreaming() {
        if (!isStreaming.get() || isPaused.get()) return
        
        isPaused.set(true)
        currentStreamState?.isPaused = true
        
        // Stop preview but keep camera open
        try {
            cameraCaptureSession?.stopRepeating()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing", e)
        }
        
        updateNotification("Camera Streaming Paused")
        Log.i(TAG, "Camera streaming paused")
    }
    
    /**
     * Resume streaming
     */
    private fun resumeStreaming() {
        if (!isStreaming.get() || !isPaused.get()) return
        
        isPaused.set(false)
        currentStreamState?.isPaused = false
        
        startPreview()
        videoEncoder?.requestKeyframe()
        
        updateNotification("Camera Streaming Active")
        Log.i(TAG, "Camera streaming resumed")
    }
    
    /**
     * Close camera device
     */
    private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
    
    /**
     * Stop streaming
     */
    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return
        
        isPaused.set(false)
        
        webSocket?.close(1000, "Stream stopped")
        webSocket = null
        
        videoEncoder?.stop()
        audioEncoder?.stop()
        
        closeCamera()
        stopBackgroundThread()
        
        currentStreamState?.isActive = false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        Log.i(TAG, "Camera streaming stopped")
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        videoEncoder?.release()
        videoEncoder = null
        
        audioEncoder?.release()
        audioEncoder = null
        
        closeCamera()
        stopBackgroundThread()
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
                "Camera Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background camera streaming service"
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
            .setContentTitle("Camera Stream Active")
            .setContentText("Streaming camera content")
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
            .setContentTitle("Camera Stream")
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
        Log.i(TAG, "CameraStreamService destroyed")
    }
}
