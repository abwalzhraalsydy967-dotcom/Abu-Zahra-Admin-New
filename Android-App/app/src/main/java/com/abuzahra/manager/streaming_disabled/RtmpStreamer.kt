package com.abuzahra.manager.streaming

import android.content.Context
import android.util.Log
import com.abuzahra.manager.Config
import com.abuzahra.manager.util.DeviceUtils
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * RtmpStreamer - RTMP streaming support
 * Handles RTMP handshake, connection, and streaming to RTMP servers
 * 
 * This is a lightweight RTMP client implementation that supports basic streaming
 * without requiring external libraries. For production use with advanced features,
 * consider using a dedicated RTMP library like librtmp or Red5Pro.
 */
class RtmpStreamer(
    private val context: Context,
    private val config: StreamConfig.Configuration
) {
    companion object {
        private const val TAG = "RtmpStreamer"
        
        // RTMP Constants
        private const val RTMP_PORT = 1935
        private const val RTMP_VERSION = 3
        private const val HANDSHAKE_SIZE = 1536
        private const val CHUNK_SIZE = 4096
        private const val DEFAULT_CHUNK_STREAM_ID = 2
        
        // RTMP Message Types
        private const val MSG_TYPE_CHUNK_SIZE = 1
        private const val MSG_TYPE_ABORT = 2
        private const val MSG_TYPE_ACK = 3
        private const val MSG_TYPE_USER_CONTROL = 4
        private const val MSG_TYPE_WINDOW_ACK_SIZE = 5
        private const val MSG_TYPE_SET_PEER_BW = 6
        private const val MSG_TYPE_AUDIO = 8
        private const val MSG_TYPE_VIDEO = 9
        private const val MSG_TYPE_DATA = 18
        private const val MSG_TYPE_INVOKE = 20
        private const val MSG_TYPE_COMMAND = 22
        
        // RTMP Control Messages
        private const val CONTROL_STREAM_BEGIN = 0
        private const val CONTROL_STREAM_EOF = 1
        private const val CONTROL_STREAM_DRY = 2
        private const val CONTROL_SET_BUFFER = 3
        private const val CONTROL_STREAM_IS_RECORDED = 4
        private const val CONTROL_PING_REQUEST = 6
        private const val CONTROL_PING_RESPONSE = 7
        
        // Video Frame Types
        private const val VIDEO_FRAME_KEY = 1
        private const val VIDEO_FRAME_INTER = 2
        private const val VIDEO_FRAME_DISPOSABLE = 3
        private const val VIDEO_FRAME_GENERATED = 4
        private const val VIDEO_FRAME_COMMAND = 5
        
        // Video Codec IDs
        private const val CODEC_H264 = 7
        private const val CODEC_H265 = 12
        
        // Audio Codec IDs
        private const val CODEC_AAC = 10
        
        // Sound formats
        private const val SOUND_FORMAT_AAC = 10
        private const val SOUND_RATE_44K = 3
        private const val SOUND_SIZE_16BIT = 1
        private const val SOUND_TYPE_STEREO = 1
        
        // AAC Packet Types
        private const val AAC_PACKET_SEQUENCE_HEADER = 0
        private const val AAC_PACKET_RAW = 1
        
        // H264 NAL Types
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
        private const val NAL_TYPE_IDR = 5
    }
    
    // Socket connection
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    
    // Connection state
    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    
    // Stream info
    private var streamId: Int = 1
    private var transactionId: Int = 0
    private var chunkSize: Int = CHUNK_SIZE
    
    // Timestamps
    private val streamTimestamp = AtomicLong(0)
    private val audioTimestamp = AtomicLong(0)
    private val videoTimestamp = AtomicLong(0)
    
    // Buffer queue
    private val sendQueue = ConcurrentLinkedQueue<RtmpPacket>()
    private var sendJob: Job? = null
    
    // Coroutine scope
    private val streamerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Statistics
    private var bytesSent = 0L
    private var videoFramesSent = 0L
    private var audioFramesSent = 0L
    private var startTime = 0L
    
    // Callbacks
    private var onConnectionStateChange: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    
    /**
     * RTMP packet data class
     */
    data class RtmpPacket(
        val type: Int,
        val timestamp: Long,
        val streamId: Int,
        val data: ByteArray
    )
    
    /**
     * Set connection state callback
     */
    fun onConnectionStateChange(callback: (Boolean) -> Unit) {
        onConnectionStateChange = callback
    }
    
    /**
     * Set error callback
     */
    fun onError(callback: (String) -> Unit) {
        onError = callback
    }
    
    /**
     * Connect to RTMP server
     */
    fun connect(rtmpUrl: String): Boolean {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected")
            return true
        }
        
        // Parse RTMP URL
        val (host, port, appName, streamKey) = parseRtmpUrl(rtmpUrl) ?: run {
            onError?.invoke("Invalid RTMP URL")
            return false
        }
        
        try {
            // Connect socket
            socket = Socket()
            socket?.connect(InetSocketAddress(host, port), StreamConfig.RTMP_CONNECT_TIMEOUT_MS.toInt())
            socket?.tcpNoDelay = true
            socket?.soTimeout = 5000
            
            outputStream = DataOutputStream(socket?.getOutputStream())
            
            // Perform handshake
            if (!performHandshake()) {
                disconnect()
                return false
            }
            
            // Connect to application
            if (!connectToApp(appName)) {
                disconnect()
                return false
            }
            
            // Create stream
            if (!createStream()) {
                disconnect()
                return false
            }
            
            // Publish stream
            if (!publishStream(streamKey)) {
                disconnect()
                return false
            }
            
            isConnected.set(true)
            onConnectionStateChange?.invoke(true)
            
            // Start send loop
            startSendLoop()
            
            startTime = System.currentTimeMillis()
            Log.i(TAG, "Connected to RTMP server: $rtmpUrl")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to RTMP server", e)
            onError?.invoke("Connection failed: ${e.message}")
            disconnect()
            return false
        }
    }
    
    /**
     * Parse RTMP URL into components
     */
    private fun parseRtmpUrl(url: String): Tuple4<String, Int, String, String>? {
        try {
            // Parse URL: rtmp://host[:port]/app/stream_key
            val normalizedUrl = url.removePrefix("rtmp://").removePrefix("rtmps://")
            
            val parts = normalizedUrl.split("/")
            if (parts.size < 3) return null
            
            val hostPort = parts[0].split(":")
            val host = hostPort[0]
            val port = if (hostPort.size > 1) hostPort[1].toInt() else RTMP_PORT
            
            val app = parts[1]
            val streamKey = parts.subList(2, parts.size).joinToString("/")
            
            return Tuple4(host, port, app, streamKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RTMP URL", e)
            return null
        }
    }
    
    /**
     * Perform RTMP handshake
     */
    private fun performHandshake(): Boolean {
        try {
            val inputStream = socket?.getInputStream() ?: return false
            
            // Send C0 + C1
            val c0c1 = ByteArray(1 + HANDSHAKE_SIZE)
            c0c1[0] = RTMP_VERSION.toByte()  // C0
            ByteBuffer.wrap(c0c1, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt((System.currentTimeMillis() / 1000).toInt())
            // Fill rest with random data
            for (i in 5 until c0c1.size) {
                c0c1[i] = (Math.random() * 256).toInt().toByte()
            }
            outputStream?.write(c0c1)
            outputStream?.flush()
            
            // Read S0 + S1 + S2
            val s0 = ByteArray(1)
            inputStream.read(s0)
            if (s0[0].toInt() != RTMP_VERSION) {
                Log.e(TAG, "Server RTMP version mismatch")
                return false
            }
            
            val s1 = ByteArray(HANDSHAKE_SIZE)
            inputStream.read(s1)
            
            // Send C2 (echo of S1)
            outputStream?.write(s1)
            outputStream?.flush()
            
            // Read S2 (echo of C1)
            val s2 = ByteArray(HANDSHAKE_SIZE)
            inputStream.read(s2)
            
            Log.i(TAG, "RTMP handshake completed")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)
            return false
        }
    }
    
    /**
     * Connect to RTMP application
     */
    private fun connectToApp(appName: String): Boolean {
        try {
            // Send window ack size
            sendWindowAckSize(2500000)
            
            // Send set peer bandwidth
            sendSetPeerBandwidth(2500000)
            
            // Send connect command
            transactionId++
            val connectCommand = buildConnectCommand(appName)
            sendMessage(MSG_TYPE_INVOKE, 0, connectCommand)
            
            // Wait for response (simplified - in production would parse response)
            Thread.sleep(100)
            
            Log.i(TAG, "Connected to app: $appName")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to app", e)
            return false
        }
    }
    
    /**
     * Build connect command
     */
    private fun buildConnectCommand(appName: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // AMF0 format
        // String "connect"
        dos.writeByte(2) // AMF0 String marker
        writeAmfString(dos, "connect")
        
        // Number (transaction ID)
        dos.writeByte(0) // AMF0 Number marker
        dos.writeInt(transactionId)
        
        // Object (command object)
        dos.writeByte(3) // AMF0 Object marker
        writeAmfString(dos, "app")
        dos.writeByte(2)
        writeAmfString(dos, appName)
        writeAmfString(dos, "type")
        dos.writeByte(2)
        writeAmfString(dos, "nonprivate")
        writeAmfString(dos, "flashVer")
        dos.writeByte(2)
        writeAmfString(dos, "FMLE/3.0")
        writeAmfString(dos, "tcUrl")
        dos.writeByte(2)
        writeAmfString(dos, "rtmp://${config.serverUrl}/$appName")
        writeAmfString(dos, "fpad")
        dos.writeByte(1) // AMF0 Boolean marker
        dos.writeBoolean(false)
        writeAmfString(dos, "capabilities")
        dos.writeByte(0)
        dos.writeInt(239)
        writeAmfString(dos, "audioCodecs")
        dos.writeByte(0)
        dos.writeInt(3191)
        writeAmfString(dos, "videoCodecs")
        dos.writeByte(0)
        dos.writeInt(252)
        writeAmfString(dos, "videoFunction")
        dos.writeByte(0)
        dos.writeInt(1)
        dos.writeByte(0) // Object end marker
        dos.writeByte(0)
        dos.writeByte(9)
        
        return baos.toByteArray()
    }
    
    /**
     * Create RTMP stream
     */
    private fun createStream(): Boolean {
        try {
            transactionId++
            
            // Send createStream command
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            
            dos.writeByte(2)
            writeAmfString(dos, "createStream")
            dos.writeByte(0)
            dos.writeInt(transactionId)
            dos.writeByte(5) // AMF0 Null marker
            
            sendMessage(MSG_TYPE_INVOKE, 0, baos.toByteArray())
            
            // Wait for response
            Thread.sleep(100)
            
            Log.i(TAG, "Stream created with ID: $streamId")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create stream", e)
            return false
        }
    }
    
    /**
     * Publish stream
     */
    private fun publishStream(streamKey: String): Boolean {
        try {
            transactionId++
            
            // Send publish command
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            
            dos.writeByte(2)
            writeAmfString(dos, "publish")
            dos.writeByte(0)
            dos.writeInt(transactionId)
            dos.writeByte(5) // Null
            dos.writeByte(2)
            writeAmfString(dos, streamKey)
            dos.writeByte(2)
            writeAmfString(dos, "live")
            
            sendMessage(MSG_TYPE_INVOKE, streamId, baos.toByteArray())
            
            // Send stream begin
            sendControlMessage(CONTROL_STREAM_BEGIN, streamId)
            
            isStreaming.set(true)
            Log.i(TAG, "Publishing stream: $streamKey")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish stream", e)
            return false
        }
    }
    
    /**
     * Send video frame
     */
    fun sendVideoFrame(data: ByteArray, timestamp: Long, isKeyFrame: Boolean) {
        if (!isStreaming.get()) return
        
        val timestampDelta = timestamp - videoTimestamp.get()
        videoTimestamp.set(timestamp)
        
        // Build FLV video tag
        val flvTag = buildVideoTag(data, isKeyFrame)
        
        val packet = RtmpPacket(
            type = MSG_TYPE_VIDEO,
            timestamp = timestampDelta.toInt().toLong(),
            streamId = streamId,
            data = flvTag
        )
        
        sendQueue.offer(packet)
        videoFramesSent++
    }
    
    /**
     * Build FLV video tag
     */
    private fun buildVideoTag(naluData: ByteArray, isKeyFrame: Boolean): ByteArray {
        val baos = ByteArrayOutputStream()
        
        // Video tag header
        val frameType = if (isKeyFrame) VIDEO_FRAME_KEY else VIDEO_FRAME_INTER
        val codecId = if (config.videoCodec == StreamConfig.VideoCodec.H265) CODEC_H265 else CODEC_H264
        
        // First byte: frame type (4 bits) + codec id (4 bits)
        baos.write((frameType shl 4) or codecId)
        
        // AVC/HEVC packet type (1 = NALU)
        baos.write(1)
        
        // Composition time (3 bytes, signed, 0 for real-time)
        baos.write(0)
        baos.write(0)
        baos.write(0)
        
        // NALU data
        baos.write(naluData)
        
        return baos.toByteArray()
    }
    
    /**
     * Send audio frame
     */
    fun sendAudioFrame(data: ByteArray, timestamp: Long) {
        if (!isStreaming.get()) return
        
        val timestampDelta = timestamp - audioTimestamp.get()
        audioTimestamp.set(timestamp)
        
        // Build FLV audio tag
        val flvTag = buildAudioTag(data)
        
        val packet = RtmpPacket(
            type = MSG_TYPE_AUDIO,
            timestamp = timestampDelta.toInt().toLong(),
            streamId = streamId,
            data = flvTag
        )
        
        sendQueue.offer(packet)
        audioFramesSent++
    }
    
    /**
     * Build FLV audio tag
     */
    private fun buildAudioTag(aacData: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        
        // Audio tag header
        // First byte: sound format (4 bits) + sound rate (2 bits) + sound size (1 bit) + sound type (1 bit)
        val soundFormat = SOUND_FORMAT_AAC
        val soundRate = SOUND_RATE_44K
        val soundSize = SOUND_SIZE_16BIT
        val soundType = if (config.audioEnabled && config.audioBitrate > 64000) SOUND_TYPE_STEREO else 0
        
        baos.write((soundFormat shl 4) or (soundRate shl 2) or (soundSize shl 1) or soundType)
        
        // AAC packet type (1 = raw)
        baos.write(AAC_PACKET_RAW)
        
        // AAC data
        baos.write(aacData)
        
        return baos.toByteArray()
    }
    
    /**
     * Send sequence header (SPS/PPS for video, AudioSpecificConfig for audio)
     */
    fun sendSequenceHeader(sps: ByteArray?, pps: ByteArray?, audioConfig: ByteArray?) {
        if (!isStreaming.get()) return
        
        // Send video sequence header
        if (sps != null && pps != null) {
            val baos = ByteArrayOutputStream()
            
            // Video tag header
            baos.write((VIDEO_FRAME_KEY shl 4) or CODEC_H264)
            baos.write(0) // Sequence header
            
            // Composition time
            baos.write(0)
            baos.write(0)
            baos.write(0)
            
            // AVCDecoderConfigurationRecord
            baos.write(1) // configurationVersion
            baos.write(sps[1]) // AVCProfileIndication
            baos.write(sps[2]) // profile_compatibility
            baos.write(sps[3]) // AVCLevelIndication
            baos.write(0xFF) // lengthSizeMinusOne (3 bytes NALU length)
            baos.write(0xE1) // numOfSequenceParameterSets (1 SPS)
            
            // SPS
            baos.write((sps.size shr 8) and 0xFF)
            baos.write(sps.size and 0xFF)
            baos.write(sps)
            
            // PPS
            baos.write(1) // numOfPictureParameterSets
            baos.write((pps.size shr 8) and 0xFF)
            baos.write(pps.size and 0xFF)
            baos.write(pps)
            
            val packet = RtmpPacket(
                type = MSG_TYPE_VIDEO,
                timestamp = 0,
                streamId = streamId,
                data = baos.toByteArray()
            )
            
            sendQueue.offer(packet)
        }
        
        // Send audio sequence header
        if (audioConfig != null) {
            val baos = ByteArrayOutputStream()
            
            // Audio tag header
            val soundFormat = SOUND_FORMAT_AAC
            val soundRate = SOUND_RATE_44K
            val soundSize = SOUND_SIZE_16BIT
            val soundType = if (config.audioEnabled) SOUND_TYPE_STEREO else 0
            
            baos.write((soundFormat shl 4) or (soundRate shl 2) or (soundSize shl 1) or soundType)
            baos.write(AAC_PACKET_SEQUENCE_HEADER)
            baos.write(audioConfig)
            
            val packet = RtmpPacket(
                type = MSG_TYPE_AUDIO,
                timestamp = 0,
                streamId = streamId,
                data = baos.toByteArray()
            )
            
            sendQueue.offer(packet)
        }
    }
    
    /**
     * Start send loop
     */
    private fun startSendLoop() {
        sendJob = streamerScope.launch {
            while (isConnected.get()) {
                try {
                    val packet = sendQueue.poll()
                    if (packet != null) {
                        sendMessage(packet.type, packet.streamId, packet.data)
                        bytesSent += packet.data.size
                    } else {
                        delay(1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending packet", e)
                    onError?.invoke("Send error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Send RTMP message
     */
    private fun sendMessage(type: Int, streamId: Int, data: ByteArray) {
        try {
            // Chunk header (type 0)
            outputStream?.writeByte(0) // Chunk basic header (csid = 0)
            outputStream?.writeByte(0) // Extended csid
            
            // Chunk message header
            outputStream?.writeInt(0) // timestamp delta (0 for now)
            outputStream?.writeInt(data.size)
            outputStream?.writeByte(type)
            outputStream?.writeInt(streamId)
            
            // Data
            outputStream?.write(data)
            outputStream?.flush()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }
    
    /**
     * Send window acknowledgement size
     */
    private fun sendWindowAckSize(size: Int) {
        val data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array()
        sendMessage(MSG_TYPE_WINDOW_ACK_SIZE, 0, data)
    }
    
    /**
     * Send set peer bandwidth
     */
    private fun sendSetPeerBandwidth(size: Int) {
        val data = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).putInt(size).put(2).array()
        sendMessage(MSG_TYPE_SET_PEER_BW, 0, data)
    }
    
    /**
     * Send control message
     */
    private fun sendControlMessage(controlType: Int, streamId: Int) {
        val data = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(controlType.toShort())
            .putInt(streamId)
            .array()
        sendMessage(MSG_TYPE_USER_CONTROL, 0, data)
    }
    
    /**
     * Write AMF string
     */
    private fun writeAmfString(dos: DataOutputStream, str: String) {
        dos.writeShort(str.length)
        dos.write(str.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Disconnect from RTMP server
     */
    fun disconnect() {
        if (!isConnected.get()) return
        
        isStreaming.set(false)
        isConnected.set(false)
        
        try {
            // Send deleteStream command
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeByte(2)
            writeAmfString(dos, "deleteStream")
            dos.writeByte(0)
            dos.writeInt(++transactionId)
            dos.writeByte(5)
            dos.writeInt(streamId)
            sendMessage(MSG_TYPE_INVOKE, 0, baos.toByteArray())
            
            // Close socket
            outputStream?.close()
            socket?.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
        
        sendJob?.cancel()
        sendQueue.clear()
        
        onConnectionStateChange?.invoke(false)
        Log.i(TAG, "Disconnected from RTMP server")
    }
    
    /**
     * Get statistics
     */
    fun getStatistics(): RtmpStatistics {
        return RtmpStatistics(
            isConnected = isConnected.get(),
            isStreaming = isStreaming.get(),
            bytesSent = bytesSent,
            videoFramesSent = videoFramesSent,
            audioFramesSent = audioFramesSent,
            duration = if (startTime > 0) System.currentTimeMillis() - startTime else 0
        )
    }
    
    /**
     * RTMP statistics data class
     */
    data class RtmpStatistics(
        val isConnected: Boolean,
        val isStreaming: Boolean,
        val bytesSent: Long,
        val videoFramesSent: Long,
        val audioFramesSent: Long,
        val duration: Long
    )
    
    /**
     * Cleanup resources
     */
    fun release() {
        disconnect()
        streamerScope.cancel()
        Log.i(TAG, "RtmpStreamer released")
    }
}
