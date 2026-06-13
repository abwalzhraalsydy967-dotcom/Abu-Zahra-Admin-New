package com.abuzahra.manager.streaming

import android.content.Context
import android.os.Build
import android.util.Log
import com.abuzahra.manager.Config
import com.abuzahra.manager.util.DeviceUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTCClient - WebRTC client for real-time streaming
 * Handles signaling, peer connection management, and media streaming
 * 
 * Note: This is a lightweight WebRTC signaling client that works with a WebRTC server.
 * For full WebRTC support, you would typically use the official Google WebRTC library.
 * This implementation uses WebSocket for signaling and can be extended for full WebRTC.
 */
class WebRTCClient(
    private val context: Context,
    private val config: StreamConfig.Configuration
) {
    companion object {
        private const val TAG = "WebRTCClient"
        private const val SIGNALING_TIMEOUT = 30000L
        private const val ICE_CANDIDATE_TIMEOUT = 5000L
    }
    
    // WebSocket connection
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // Connection state
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    // Session info
    private var sessionId: String = ""
    private var peerId: String = ""
    
    // ICE candidates buffer
    private val iceCandidates = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    
    // Remote SDP
    private var remoteSdp: SessionDescription? = null
    private var localSdp: SessionDescription? = null
    
    // Callbacks
    private var onConnectionStateChange: ((ConnectionState) -> Unit)? = null
    private var onIceCandidate: ((IceCandidate) -> Unit)? = null
    private var onRemoteSdp: ((SessionDescription) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onDataReceived: ((ByteArray) -> Unit)? = null
    
    // Statistics
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var packetsSent = 0L
    private var packetsReceived = 0L
    
    // Coroutine scope
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Gson
    private val gson = Gson()
    
    /**
     * Connection state enum
     */
    enum class ConnectionState {
        NEW,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        FAILED,
        CLOSED
    }
    
    /**
     * ICE candidate data class
     */
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int
    )
    
    /**
     * Session description data class
     */
    data class SessionDescription(
        val type: String,  // "offer" or "answer"
        val sdp: String
    )
    
    /**
     * Set connection state callback
     */
    fun onConnectionStateChange(callback: (ConnectionState) -> Unit) {
        onConnectionStateChange = callback
    }
    
    /**
     * Set ICE candidate callback
     */
    fun onIceCandidate(callback: (IceCandidate) -> Unit) {
        onIceCandidate = callback
    }
    
    /**
     * Set remote SDP callback
     */
    fun onRemoteSdp(callback: (SessionDescription) -> Unit) {
        onRemoteSdp = callback
    }
    
    /**
     * Set error callback
     */
    fun onError(callback: (String) -> Unit) {
        onError = callback
    }
    
    /**
     * Set data received callback
     */
    fun onDataReceived(callback: (ByteArray) -> Unit) {
        onDataReceived = callback
    }
    
    /**
     * Connect to signaling server
     */
    fun connect(serverUrl: String? = null): Boolean {
        if (isConnected.get() || isConnecting.get()) {
            Log.w(TAG, "Already connected or connecting")
            return false
        }
        
        isConnecting.set(true)
        notifyConnectionState(ConnectionState.CONNECTING)
        
        val signalingUrl = serverUrl ?: buildSignalingUrl()
        
        try {
            val request = Request.Builder()
                .url(signalingUrl)
                .build()
            
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected")
                    isConnecting.set(false)
                    isConnected.set(true)
                    reconnectAttempts = 0
                    notifyConnectionState(ConnectionState.CONNECTED)
                    
                    // Send join message
                    sendJoinMessage()
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSignalingMessage(text)
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closing: $code - $reason")
                    webSocket.close(1000, null)
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code - $reason")
                    isConnected.set(false)
                    notifyConnectionState(ConnectionState.CLOSED)
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    isConnected.set(false)
                    isConnecting.set(false)
                    notifyConnectionState(ConnectionState.FAILED)
                    onError?.invoke("Connection failed: ${t.message}")
                    
                    // Attempt reconnection
                    attemptReconnection()
                }
            })
            
            Log.i(TAG, "Connecting to signaling server: $signalingUrl")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            isConnecting.set(false)
            onError?.invoke("Connection failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Build signaling URL
     */
    private fun buildSignalingUrl(): String {
        val baseUrl = Config.getBaseUrl()
        val wsProtocol = if (baseUrl.startsWith("https://")) "wss://" else "ws://"
        val host = baseUrl.removePrefix("https://").removePrefix("http://")
        val deviceId = DeviceUtils.getDeviceId(context)
        
        return "$wsProtocol$host/ws/webrtc?device_id=$deviceId&stream_id=${config.streamId}"
    }
    
    /**
     * Send join message to signaling server
     */
    private fun sendJoinMessage() {
        val joinMessage = mapOf(
            "type" to "join",
            "device_id" to DeviceUtils.getDeviceId(context),
            "stream_id" to config.streamId,
            "stream_type" to config.streamType.name,
            "config" to mapOf(
                "video_enabled" to config.videoEnabled,
                "audio_enabled" to config.audioEnabled,
                "video_codec" to config.videoCodec.name,
                "audio_codec" to config.audioCodec.name
            )
        )
        
        sendMessage(joinMessage)
    }
    
    /**
     * Handle signaling message from server
     */
    private fun handleSignalingMessage(message: String) {
        try {
            val json = JsonParser.parseString(message).asJsonObject
            val type = json.get("type")?.asString ?: return
            
            when (type) {
                "joined" -> handleJoinedMessage(json)
                "offer" -> handleOfferMessage(json)
                "answer" -> handleAnswerMessage(json)
                "candidate" -> handleCandidateMessage(json)
                "user_joined" -> handleUserJoinedMessage(json)
                "user_left" -> handleUserLeftMessage(json)
                "error" -> handleErrorMessage(json)
                "config" -> handleConfigMessage(json)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing signaling message", e)
        }
    }
    
    /**
     * Handle joined message
     */
    private fun handleJoinedMessage(json: JsonObject) {
        sessionId = json.get("session_id")?.asString ?: ""
        peerId = json.get("peer_id")?.asString ?: ""
        
        Log.i(TAG, "Joined session: $sessionId, peer: $peerId")
    }
    
    /**
     * Handle offer message (from server/viewer)
     */
    private fun handleOfferMessage(json: JsonObject) {
        val sdp = json.get("sdp")?.asString ?: return
        
        remoteSdp = SessionDescription("offer", sdp)
        onRemoteSdp?.invoke(remoteSdp!!)
        
        Log.i(TAG, "Received offer from server")
        
        // In a full WebRTC implementation, this would trigger answer creation
        // Here we acknowledge receipt
        sendAnswerAck()
    }
    
    /**
     * Handle answer message
     */
    private fun handleAnswerMessage(json: JsonObject) {
        val sdp = json.get("sdp")?.asString ?: return
        
        remoteSdp = SessionDescription("answer", sdp)
        onRemoteSdp?.invoke(remoteSdp!!)
        
        Log.i(TAG, "Received answer from server")
    }
    
    /**
     * Handle ICE candidate message
     */
    private fun handleCandidateMessage(json: JsonObject) {
        val candidate = json.get("candidate")?.asString ?: return
        val sdpMid = json.get("sdpMid")?.asString ?: ""
        val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: 0
        
        val iceCandidate = IceCandidate(candidate, sdpMid, sdpMLineIndex)
        
        // Buffer candidate
        val peerId = json.get("peer_id")?.asString ?: "default"
        iceCandidates.getOrPut(peerId) { mutableListOf() }.add(iceCandidate)
        
        onIceCandidate?.invoke(iceCandidate)
        
        Log.d(TAG, "Received ICE candidate")
    }
    
    /**
     * Handle user joined message
     */
    private fun handleUserJoinedMessage(json: JsonObject) {
        val userId = json.get("user_id")?.asString ?: ""
        Log.i(TAG, "User joined: $userId")
    }
    
    /**
     * Handle user left message
     */
    private fun handleUserLeftMessage(json: JsonObject) {
        val userId = json.get("user_id")?.asString ?: ""
        iceCandidates.remove(userId)
        Log.i(TAG, "User left: $userId")
    }
    
    /**
     * Handle error message
     */
    private fun handleErrorMessage(json: JsonObject) {
        val error = json.get("error")?.asString ?: "Unknown error"
        Log.e(TAG, "Server error: $error")
        onError?.invoke(error)
    }
    
    /**
     * Handle config message
     */
    private fun handleConfigMessage(json: JsonObject) {
        // Handle configuration updates from server
        Log.i(TAG, "Received config update")
    }
    
    /**
     * Send answer acknowledgment
     */
    private fun sendAnswerAck() {
        val ackMessage = mapOf(
            "type" to "answer_ack",
            "session_id" to sessionId
        )
        sendMessage(ackMessage)
    }
    
    /**
     * Send local SDP answer
     */
    fun sendAnswer(sdp: String) {
        localSdp = SessionDescription("answer", sdp)
        
        val answerMessage = mapOf(
            "type" to "answer",
            "session_id" to sessionId,
            "sdp" to sdp
        )
        
        sendMessage(answerMessage)
        Log.i(TAG, "Sent answer")
    }
    
    /**
     * Send local SDP offer
     */
    fun sendOffer(sdp: String) {
        localSdp = SessionDescription("offer", sdp)
        
        val offerMessage = mapOf(
            "type" to "offer",
            "session_id" to sessionId,
            "sdp" to sdp
        )
        
        sendMessage(offerMessage)
        Log.i(TAG, "Sent offer")
    }
    
    /**
     * Send ICE candidate
     */
    fun sendIceCandidate(candidate: IceCandidate) {
        val candidateMessage = mapOf(
            "type" to "candidate",
            "session_id" to sessionId,
            "candidate" to candidate.candidate,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex
        )
        
        sendMessage(candidateMessage)
        Log.d(TAG, "Sent ICE candidate")
    }
    
    /**
     * Send media data (for streaming without full WebRTC)
     */
    fun sendMediaData(data: ByteArray, type: String = "video", timestamp: Long = System.nanoTime() / 1000) {
        if (!isConnected.get()) return
        
        val base64Data = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        
        val mediaMessage = mapOf(
            "type" to "media",
            "media_type" to type,
            "stream_id" to config.streamId,
            "timestamp" to timestamp,
            "size" to data.size,
            "data" to base64Data
        )
        
        sendMessage(mediaMessage)
        
        bytesSent += data.size
        packetsSent++
    }
    
    /**
     * Send message to signaling server
     */
    private fun sendMessage(message: Map<String, Any?>) {
        try {
            val json = gson.toJson(message)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }
    
    /**
     * Attempt reconnection
     */
    private fun attemptReconnection() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnection attempts reached")
            notifyConnectionState(ConnectionState.FAILED)
            return
        }
        
        reconnectAttempts++
        val delay = (reconnectAttempts * 2000).toLong()
        
        Log.i(TAG, "Attempting reconnection in ${delay}ms (attempt $reconnectAttempts)")
        
        clientScope.launch {
            delay(delay)
            connect()
        }
    }
    
    /**
     * Notify connection state change
     */
    private fun notifyConnectionState(state: ConnectionState) {
        onConnectionStateChange?.invoke(state)
    }
    
    /**
     * Disconnect from signaling server
     */
    fun disconnect() {
        if (!isConnected.get()) return
        
        try {
            // Send leave message
            val leaveMessage = mapOf(
                "type" to "leave",
                "session_id" to sessionId
            )
            sendMessage(leaveMessage)
            
            // Close WebSocket
            webSocket?.close(1000, "Client disconnecting")
            webSocket = null
            
            isConnected.set(false)
            notifyConnectionState(ConnectionState.CLOSED)
            
            // Clear buffers
            iceCandidates.clear()
            remoteSdp = null
            localSdp = null
            
            Log.i(TAG, "Disconnected from signaling server")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected.get()
    
    /**
     * Get session ID
     */
    fun getSessionId(): String = sessionId
    
    /**
     * Get statistics
     */
    fun getStatistics(): WebRTCStatistics {
        return WebRTCStatistics(
            isConnected = isConnected.get(),
            sessionId = sessionId,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            packetsSent = packetsSent,
            packetsReceived = packetsReceived,
            iceCandidatesReceived = iceCandidates.values.sumOf { it.size }
        )
    }
    
    /**
     * WebRTC statistics data class
     */
    data class WebRTCStatistics(
        val isConnected: Boolean,
        val sessionId: String,
        val bytesSent: Long,
        val bytesReceived: Long,
        val packetsSent: Long,
        val packetsReceived: Long,
        val iceCandidatesReceived: Int
    )
    
    /**
     * Cleanup resources
     */
    fun release() {
        // Cancel scope FIRST to prevent race conditions between coroutine callbacks and shutdown
        clientScope.cancel()
        disconnect()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        
        Log.i(TAG, "WebRTCClient released")
    }
}
