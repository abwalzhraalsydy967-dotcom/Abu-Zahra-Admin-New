package com.abuzahra.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.abuzahra.manager.R
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Base64

/**
 * ScreenCaptureService - Foreground service for screen capture
 * Handles screenshots and screen recording using MediaProjection
 */
class ScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "screen_capture_channel"

        // Action constants
        const val ACTION_CAPTURE_SCREENSHOT = "com.abuzahra.manager.CAPTURE_SCREENSHOT"
        const val ACTION_START_RECORDING = "com.abuzahra.manager.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.abuzahra.manager.STOP_RECORDING"
        const val ACTION_INIT_PROJECTION = "com.abuzahra.manager.INIT_PROJECTION"
        
        // Permission data (persisted from permission request)
        var lastResultCode: Int = 0
        var lastPermissionData: Intent? = null
        
        // Callbacks
        private var screenshotCallback: ((Bitmap?) -> Unit)? = null
        
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
         * Request screenshot
         */
        fun requestScreenshot(callback: (Bitmap?) -> Unit) {
            screenshotCallback = callback
        }
    }
    
    // Service state
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Capture state
    private var isCapturing = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "ScreenCaptureService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        when (intent?.action) {
            ACTION_CAPTURE_SCREENSHOT -> captureScreenshot()
            ACTION_START_RECORDING -> startRecording(intent)
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_INIT_PROJECTION -> initMediaProjection()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Initialize MediaProjection
     */
    private fun initMediaProjection() {
        if (lastResultCode == 0 || lastPermissionData == null) {
            Log.e(TAG, "No permission data available")
            return
        }
        
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(lastResultCode, lastPermissionData!!)
            
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
            
            Log.i(TAG, "MediaProjection initialized: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
        }
    }
    
    /**
     * Capture screenshot
     */
    private fun captureScreenshot() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return
        }
        
        if (mediaProjection == null) {
            initMediaProjection()
        }
        
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not available")
            screenshotCallback?.invoke(null)
            return
        }
        
        isCapturing = true
        
        try {
            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            )
            
            // Create VirtualDisplay
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )
            
            // Wait for image
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    
                    // Cleanup
                    cleanupCapture()
                    isCapturing = false
                    
                    // Save and upload
                    bitmap?.let { saveScreenshot(it) }
                    
                    // Callback
                    screenshotCallback?.invoke(bitmap)
                    screenshotCallback = null
                }
            }, handler)
            
            // Timeout
            handler.postDelayed({
                if (isCapturing) {
                    cleanupCapture()
                    isCapturing = false
                    screenshotCallback?.invoke(null)
                    screenshotCallback = null
                }
            }, 5000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed", e)
            cleanupCapture()
            isCapturing = false
            screenshotCallback?.invoke(null)
            screenshotCallback = null
        }
    }
    
    /**
     * Convert Image to Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to actual screen size
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }
    
    /**
     * Save screenshot to file
     */
    private fun saveScreenshot(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = StorageManager.createTimestampedFile(
                    StorageManager.Dir.SCREENSHOTS,
                    "screenshot",
                    "jpg"
                )
                
                FileOutputStream(file).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                }
                
                Log.i(TAG, "Screenshot saved: ${file.name}")
                
                // Upload to server
                ApiClient.uploadFile(file, "screenshot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot", e)
            }
        }
    }
    
    /**
     * Start screen recording
     */
    private fun startRecording(intent: Intent?) {
        // Recording implementation would go here
        // This is a placeholder for the recording functionality
        Log.i(TAG, "Screen recording started")
    }
    
    /**
     * Stop screen recording
     */
    private fun stopRecording() {
        Log.i(TAG, "Screen recording stopped")
    }
    
    /**
     * Cleanup capture resources
     */
    private fun cleanupCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
    }
    
    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background screen capture service"
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
            .setContentTitle("Screen Capture Active")
            .setContentText("Screen capture service is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        cleanupCapture()
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
        Log.i(TAG, "ScreenCaptureService destroyed")
    }

}
