package com.abuzahra.manager.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoEncoder - H264/H265 video encoding using MediaCodec
 * Supports hardware accelerated encoding with configurable settings
 */
class VideoEncoder(
    private val config: StreamConfig.Configuration
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val TIMEOUT_US = 10000L
        private const val I_FRAME_INTERVAL = 2 // seconds
    }
    
    // MediaCodec encoder
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    // Encoder state
    private val isRunning = AtomicBoolean(false)
    private var encodingThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Statistics
    private var framesEncoded = 0L
    private var bytesEncoded = 0L
    private var startTime = 0L
    private var lastFrameTime = 0L
    
    // Callback for encoded data
    private var encodedDataCallback: ((EncodedFrame) -> Unit)? = null
    
    // SPS/PPS for H264 (needed for streaming)
    private var sps: ByteBuffer? = null
    private var pps: ByteBuffer? = null
    private var vps: ByteBuffer? = null // For H265
    
    /**
     * Encoded frame data class
     */
    data class EncodedFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean,
        val isConfigFrame: Boolean = false,
        val codec: StreamConfig.VideoCodec
    ) {
        val size: Int
            get() = data.size
    }
    
    /**
     * Encoder state listener
     */
    interface EncoderListener {
        fun onEncoderReady(surface: Surface)
        fun onEncoderError(error: String)
        fun onEncodingStarted()
        fun onEncodingStopped()
    }
    
    private var listener: EncoderListener? = null
    
    /**
     * Set callback for encoded data
     */
    fun setEncodedDataCallback(callback: (EncodedFrame) -> Unit) {
        encodedDataCallback = callback
    }
    
    /**
     * Set encoder listener
     */
    fun setListener(listener: EncoderListener) {
        this.listener = listener
    }
    
    /**
     * Initialize and configure the encoder
     */
    fun init(): Boolean {
        if (encoder != null) {
            Log.w(TAG, "Encoder already initialized")
            return true
        }
        
        try {
            val mimeType = config.videoCodec.mimeType
            
            // Create encoder by codec type
            encoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val codecName = findBestEncoder(mimeType)
                if (codecName != null) {
                    Log.i(TAG, "Using encoder: $codecName")
                    MediaCodec.createByCodecName(codecName)
                } else {
                    Log.w(TAG, "Falling back to default encoder for $mimeType")
                    MediaCodec.createEncoderByType(mimeType)
                }
            } else {
                MediaCodec.createEncoderByType(mimeType)
            }
            
            // Configure encoder
            if (!configureEncoder()) {
                release()
                return false
            }
            
            Log.i(TAG, "VideoEncoder initialized: ${config.width}x${config.height} @ ${config.fps}fps, ${config.videoBitrate}bps")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            listener?.onEncoderError("Failed to initialize encoder: ${e.message}")
            return false
        }
    }
    
    /**
     * Find the best hardware encoder for the given MIME type
     */
    private fun findBestEncoder(mimeType: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null
        }
        
        val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos.filter { 
            it.isEncoder && it.supportedTypes.contains(mimeType) 
        }
        
        // Prefer hardware encoders
        val hardwareEncoder = codecs.firstOrNull { 
            it.name.contains("OMX.", ignoreCase = true) || 
            it.name.contains("c2.android", ignoreCase = true) ||
            it.name.contains("qcom", ignoreCase = true)
        }
        
        return hardwareEncoder?.name
    }
    
    /**
     * Configure the encoder with the given settings
     */
    private fun configureEncoder(): Boolean {
        val codec = encoder ?: return false
        
        try {
            val format = MediaFormat.createVideoFormat(config.videoCodec.mimeType, config.width, config.height)
            
            // Set color format
            val colorFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            } else {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            }
            
            // Video format parameters
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeInterval)
            
            // Quality parameters
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            
            // Profile and level for H264
            when (config.videoCodec) {
                StreamConfig.VideoCodec.H264 -> {
                    format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
                StreamConfig.VideoCodec.H265 -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31)
                    }
                }
            }
            
            // Configure encoder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                listener?.onEncoderReady(inputSurface!!)
            }
            
            Log.i(TAG, "Encoder configured with format: $format")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure encoder", e)
            listener?.onEncoderError("Configuration failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Start encoding
     */
    fun start(): Boolean {
        if (encoder == null) {
            Log.e(TAG, "Encoder not initialized")
            return false
        }
        
        if (isRunning.get()) {
            Log.w(TAG, "Encoder already running")
            return true
        }
        
        try {
            encoder?.start()
            isRunning.set(true)
            startTime = System.currentTimeMillis()
            
            // Start encoding thread
            encodingThread = Thread {
                drainEncoder()
            }.apply {
                name = "VideoEncoder-${config.streamId}"
                start()
            }
            
            listener?.onEncodingStarted()
            Log.i(TAG, "VideoEncoder started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            listener?.onEncoderError("Start failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop encoding
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        isRunning.set(false)
        
        try {
            // Signal end of stream
            encoder?.signalEndOfInputStream()
            
            // Wait for encoding thread to finish (encoder can block up to TIMEOUT_US=10s on dequeueOutputBuffer)
            encodingThread?.join(15000)
            encodingThread = null
            
            encoder?.stop()
            Log.i(TAG, "VideoEncoder stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
        
        listener?.onEncodingStopped()
    }
    
    /**
     * Release encoder resources
     */
    fun release() {
        stop()
        
        try {
            inputSurface?.release()
            inputSurface = null
            
            encoder?.release()
            encoder = null
            
            sps = null
            pps = null
            vps = null
            
            Log.i(TAG, "VideoEncoder released")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
    
    /**
     * Request keyframe (IDR frame)
     */
    fun requestKeyframe() {
        if (!isRunning.get() || encoder == null) {
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                encoder?.setParameters(params)
                Log.d(TAG, "Keyframe requested")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request keyframe", e)
        }
    }
    
    /**
     * Update bitrate dynamically
     */
    fun updateBitrate(newBitrate: Int) {
        if (!isRunning.get() || encoder == null) {
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                encoder?.setParameters(params)
                Log.d(TAG, "Bitrate updated to $newBitrate")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update bitrate", e)
        }
    }
    
    /**
     * Drain encoded data from the encoder
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isRunning.get()) {
            try {
                val outputBufferId = encoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                
                when (outputBufferId) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        handleFormatChange()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                        Thread.sleep(1)
                    }
                    in 0..Int.MAX_VALUE -> {
                        handleEncodedData(outputBufferId!!, bufferInfo)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error draining encoder", e)
                break
            }
        }
    }
    
    /**
     * Handle format change from encoder
     */
    private fun handleFormatChange() {
        val format = encoder?.outputFormat ?: return
        Log.i(TAG, "Encoder format changed: $format")
        
        // Extract codec specific data (SPS/PPS for H264, VPS/SPS/PPS for H265)
        when (config.videoCodec) {
            StreamConfig.VideoCodec.H264 -> {
                format.getByteBuffer("csd-0")?.let { spsBuffer ->
                    sps = ByteBuffer.allocate(spsBuffer.remaining()).apply {
                        put(spsBuffer)
                        flip()
                    }
                }
                format.getByteBuffer("csd-1")?.let { ppsBuffer ->
                    pps = ByteBuffer.allocate(ppsBuffer.remaining()).apply {
                        put(ppsBuffer)
                        flip()
                    }
                }
            }
            StreamConfig.VideoCodec.H265 -> {
                format.getByteBuffer("csd-0")?.let { vpsBuffer ->
                    vps = ByteBuffer.allocate(vpsBuffer.remaining()).apply {
                        put(vpsBuffer)
                        flip()
                    }
                }
                format.getByteBuffer("csd-1")?.let { spsBuffer ->
                    sps = ByteBuffer.allocate(spsBuffer.remaining()).apply {
                        put(spsBuffer)
                        flip()
                    }
                }
                format.getByteBuffer("csd-2")?.let { ppsBuffer ->
                    pps = ByteBuffer.allocate(ppsBuffer.remaining()).apply {
                        put(ppsBuffer)
                        flip()
                    }
                }
            }
        }
    }
    
    /**
     * Handle encoded data from encoder
     */
    private fun handleEncodedData(index: Int, info: MediaCodec.BufferInfo) {
        val codec = encoder ?: return
        
        try {
            val outputBuffer = codec.getOutputBuffer(index)
            if (outputBuffer == null) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            
            // Check if this is a config frame
            val isConfigFrame = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            
            // Check if this is a keyframe
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            
            // For config frames, store codec specific data
            if (isConfigFrame) {
                when (config.videoCodec) {
                    StreamConfig.VideoCodec.H264 -> {
                        if (sps == null || pps == null) {
                            // Parse SPS/PPS from the config frame
                            parseH264Config(outputBuffer, info.size)
                        }
                    }
                    StreamConfig.VideoCodec.H265 -> {
                        if (vps == null || sps == null || pps == null) {
                            parseH265Config(outputBuffer, info.size)
                        }
                    }
                }
            }
            
            // Extract frame data
            val data = ByteArray(info.size)
            outputBuffer.position(info.offset)
            outputBuffer.get(data)
            
            // Create encoded frame
            val frame = EncodedFrame(
                data = data,
                presentationTimeUs = info.presentationTimeUs,
                isKeyFrame = isKeyFrame,
                isConfigFrame = isConfigFrame,
                codec = config.videoCodec
            )
            
            // Update statistics
            framesEncoded++
            bytesEncoded += data.size
            lastFrameTime = System.currentTimeMillis()
            
            // Callback with encoded frame
            encodedDataCallback?.invoke(frame)
            
            // Release buffer
            codec.releaseOutputBuffer(index, false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling encoded data", e)
        }
    }
    
    /**
     * Parse H264 config data (SPS/PPS)
     */
    private fun parseH264Config(buffer: ByteBuffer, size: Int) {
        // Simple parsing - look for NAL start codes
        val data = ByteArray(size)
        buffer.position(0)
        buffer.get(data)
        
        var i = 0
        while (i < size - 4) {
            // Look for NAL start code (0x00 0x00 0x00 0x01)
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() && 
                data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()) {
                
                val nalType = data[i + 4].toInt() and 0x1F
                
                // Find next start code or end
                var end = size
                for (j in i + 4 until size - 4) {
                    if (data[j] == 0x00.toByte() && data[j + 1] == 0x00.toByte() && 
                        data[j + 2] == 0x00.toByte() && data[j + 3] == 0x01.toByte()) {
                        end = j
                        break
                    }
                }
                
                when (nalType) {
                    7 -> { // SPS
                        sps = ByteBuffer.allocate(end - i).apply {
                            put(data, i, end - i)
                            flip()
                        }
                    }
                    8 -> { // PPS
                        pps = ByteBuffer.allocate(end - i).apply {
                            put(data, i, end - i)
                            flip()
                        }
                    }
                }
            }
            i++
        }
    }
    
    /**
     * Parse H265 config data (VPS/SPS/PPS)
     */
    private fun parseH265Config(buffer: ByteBuffer, size: Int) {
        val data = ByteArray(size)
        buffer.position(0)
        buffer.get(data)
        
        var i = 0
        while (i < size - 4) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte() && 
                data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()) {
                
                val nalType = (data[i + 4].toInt() shr 1) and 0x3F
                
                var end = size
                for (j in i + 4 until size - 4) {
                    if (data[j] == 0x00.toByte() && data[j + 1] == 0x00.toByte() && 
                        data[j + 2] == 0x00.toByte() && data[j + 3] == 0x01.toByte()) {
                        end = j
                        break
                    }
                }
                
                when (nalType) {
                    32 -> { // VPS
                        vps = ByteBuffer.allocate(end - i).apply {
                            put(data, i, end - i)
                            flip()
                        }
                    }
                    33 -> { // SPS
                        sps = ByteBuffer.allocate(end - i).apply {
                            put(data, i, end - i)
                            flip()
                        }
                    }
                    34 -> { // PPS
                        pps = ByteBuffer.allocate(end - i).apply {
                            put(data, i, end - i)
                            flip()
                        }
                    }
                }
            }
            i++
        }
    }
    
    /**
     * Get the input surface for encoding
     */
    fun getInputSurface(): Surface? = inputSurface
    
    /**
     * Get codec specific data (for streaming initialization)
     */
    fun getCodecSpecificData(): Map<String, ByteArray?> {
        return mapOf(
            "sps" to sps?.let { ByteArray(it.remaining()).also { arr -> it.get(arr); it.flip() } },
            "pps" to pps?.let { ByteArray(it.remaining()).also { arr -> it.get(arr); it.flip() } },
            "vps" to vps?.let { ByteArray(it.remaining()).also { arr -> it.get(arr); it.flip() } }
        )
    }
    
    /**
     * Check if encoder is running
     */
    fun isRunning(): Boolean = isRunning.get()
    
    /**
     * Get encoder statistics
     */
    fun getStatistics(): EncoderStatistics {
        val duration = if (startTime > 0) System.currentTimeMillis() - startTime else 0
        val avgBitrate = if (duration > 0) (bytesEncoded * 8 * 1000 / duration) else 0
        val avgFps = if (duration > 0) (framesEncoded * 1000 / duration) else 0
        
        return EncoderStatistics(
            framesEncoded = framesEncoded,
            bytesEncoded = bytesEncoded,
            duration = duration,
            averageBitrate = avgBitrate,
            averageFps = avgFps,
            lastFrameTime = lastFrameTime
        )
    }
    
    /**
     * Encoder statistics data class
     */
    data class EncoderStatistics(
        val framesEncoded: Long,
        val bytesEncoded: Long,
        val duration: Long,
        val averageBitrate: Long,
        val averageFps: Long,
        val lastFrameTime: Long
    )
}
