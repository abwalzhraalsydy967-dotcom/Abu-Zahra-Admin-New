package com.abuzahra.manager.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioEncoder - AAC audio encoding using MediaCodec
 * Supports hardware accelerated encoding with configurable settings
 */
class AudioEncoder(
    private val sampleRate: Int = StreamConfig.AUDIO_SAMPLE_RATE,
    private val channelCount: Int = StreamConfig.AUDIO_CHANNEL_COUNT,
    private val bitrate: Int = StreamConfig.AUDIO_BITRATE
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val TIMEOUT_US = 10000L
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val AAC_ADTS_HEADER_SIZE = 7
    }
    
    // MediaCodec encoder
    private var encoder: MediaCodec? = null
    
    // Encoder state
    private val isRunning = AtomicBoolean(false)
    private var encodingThread: Thread? = null
    
    // Input buffers for raw audio
    private val inputLock = Object()
    private val inputBuffer = ArrayList<ByteArray>()
    private const val MAX_INPUT_BUFFER_SIZE = 1024 * 1024 // 1 MB
    
    // Statistics
    private var framesEncoded = 0L
    private var bytesEncoded = 0L
    private var startTime = 0L
    private var lastFrameTime = 0L
    
    // Callback for encoded data
    private var encodedDataCallback: ((EncodedAudioFrame) -> Unit)? = null
    
    // Audio specific data (AudioSpecificConfig)
    private var audioSpecificConfig: ByteArray? = null
    
    /**
     * Encoded audio frame data class
     */
    data class EncodedAudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isConfigFrame: Boolean = false
    ) {
        val size: Int
            get() = data.size
    }
    
    /**
     * Encoder state listener
     */
    interface EncoderListener {
        fun onEncoderReady()
        fun onEncoderError(error: String)
        fun onEncodingStarted()
        fun onEncodingStopped()
    }
    
    private var listener: EncoderListener? = null
    
    /**
     * Set callback for encoded data
     */
    fun setEncodedDataCallback(callback: (EncodedAudioFrame) -> Unit) {
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
            // Create encoder
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            
            // Configure encoder
            if (!configureEncoder()) {
                release()
                return false
            }
            
            // Generate AudioSpecificConfig
            audioSpecificConfig = generateAudioSpecificConfig()
            
            Log.i(TAG, "AudioEncoder initialized: ${sampleRate}Hz, $channelCount channels, ${bitrate}bps")
            listener?.onEncoderReady()
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            listener?.onEncoderError("Failed to initialize encoder: ${e.message}")
            return false
        }
    }
    
    /**
     * Configure the encoder with the given settings
     */
    private fun configureEncoder(): Boolean {
        val codec = encoder ?: return false
        
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount)
            
            // Set encoding parameters
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            
            // Set max input size
            val frameSize = (sampleRate * channelCount * 2) / 50 // 20ms frame
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSize * 4)
            
            // Configure encoder
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            Log.i(TAG, "Encoder configured with format: $format")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure encoder", e)
            listener?.onEncoderError("Configuration failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Generate AudioSpecificConfig for AAC
     */
    private fun generateAudioSpecificConfig(): ByteArray {
        // AAC-LC AudioSpecificConfig
        // frequency index mapping
        val freqIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4 // Default to 44100
        }
        
        // AAC-LC profile = 2 (0b10)
        val profile = 2
        
        // Channel configuration
        val channelConfig = channelCount
        
        // Build AudioSpecificConfig (2 bytes for AAC-LC)
        val config = ByteArray(2)
        config[0] = ((profile shl 3) or (freqIndex shr 1)).toByte()
        config[1] = ((freqIndex and 1) shl 7 or (channelConfig shl 3)).toByte()
        
        return config
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
                encodingLoop()
            }.apply {
                name = "AudioEncoder"
                start()
            }
            
            listener?.onEncodingStarted()
            Log.i(TAG, "AudioEncoder started")
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
            synchronized(inputLock) {
                inputLock.notifyAll()
            }
            
            // Wait for encoding thread to finish
            encodingThread?.join(1000)
            encodingThread = null
            
            encoder?.stop()
            Log.i(TAG, "AudioEncoder stopped")
            
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
            encoder?.release()
            encoder = null
            audioSpecificConfig = null
            
            synchronized(inputLock) {
                inputBuffer.clear()
            }
            
            Log.i(TAG, "AudioEncoder released")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
    
    /**
     * Queue raw audio data for encoding
     */
    fun queueAudioData(data: ByteArray, presentationTimeUs: Long) {
        if (!isRunning.get()) {
            return
        }
        
        synchronized(inputLock) {
            if (inputBuffer.sumOf { it.size } + data.size > MAX_INPUT_BUFFER_SIZE) {
                Log.w(TAG, "Input buffer overflow, dropping data")
                return
            }
            
            // Store data with timestamp prefix
            val timestampBytes = ByteBuffer.allocate(8).putLong(presentationTimeUs).array()
            val packet = ByteArray(8 + data.size)
            System.arraycopy(timestampBytes, 0, packet, 0, 8)
            System.arraycopy(data, 0, packet, 8, data.size)
            
            inputBuffer.add(packet)
            inputLock.notify()
        }
    }
    
    /**
     * Main encoding loop
     */
    private fun encodingLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isRunning.get()) {
            try {
                // Feed input
                feedEncoderInput()
                
                // Drain output
                drainEncoderOutput(bufferInfo)
                
                // Small sleep to prevent busy loop
                Thread.sleep(1)
                
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in encoding loop", e)
            }
        }
    }
    
    /**
     * Feed raw audio data to encoder input
     */
    private fun feedEncoderInput() {
        val codec = encoder ?: return
        
        // Get input buffer
        val inputBufferId = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputBufferId < 0) {
            return
        }
        
        val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getInputBuffer(inputBufferId)
        } else {
            @Suppress("DEPRECATION")
            codec.inputBuffers[inputBufferId]
        }
        
        if (inputBuffer == null) {
            return
        }
        
        // Get data from queue
        var data: ByteArray? = null
        var timestamp = 0L
        
        synchronized(inputLock) {
            if (inputBuffer.isNotEmpty()) {
                val packet = inputBuffer.removeAt(0)
                
                // Extract timestamp
                val timestampBytes = ByteArray(8)
                System.arraycopy(packet, 0, timestampBytes, 0, 8)
                timestamp = ByteBuffer.wrap(timestampBytes).long
                
                // Extract audio data
                data = ByteArray(packet.size - 8)
                System.arraycopy(packet, 8, data, 0, data!!.size)
            }
        }
        
        if (data == null) {
            // No data available
            return
        }
        
        // Fill input buffer
        inputBuffer.clear()
        inputBuffer.put(data)
        inputBuffer.flip()
        
        // Queue input buffer
        codec.queueInputBuffer(
            inputBufferId,
            0,
            data!!.size,
            timestamp,
            0
        )
    }
    
    /**
     * Drain encoded output from encoder
     */
    private fun drainEncoderOutput(bufferInfo: MediaCodec.BufferInfo) {
        val codec = encoder ?: return
        
        val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        
        when (outputBufferId) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                handleFormatChange()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // No output available
            }
            in 0..Int.MAX_VALUE -> {
                handleEncodedData(outputBufferId!!, bufferInfo)
            }
        }
    }
    
    /**
     * Handle format change from encoder
     */
    private fun handleFormatChange() {
        val format = encoder?.outputFormat ?: return
        Log.i(TAG, "Encoder format changed: $format")
        
        // Extract AudioSpecificConfig if provided
        format.getByteBuffer("csd-0")?.let { csd ->
            audioSpecificConfig = ByteArray(csd.remaining()).also { arr ->
                csd.get(arr)
                csd.flip()
            }
        }
    }
    
    /**
     * Handle encoded audio data
     */
    private fun handleEncodedData(index: Int, info: MediaCodec.BufferInfo) {
        val codec = encoder ?: return
        
        try {
            val outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getOutputBuffer(index)
            } else {
                @Suppress("DEPRECATION")
                codec.outputBuffers[index]
            }
            
            if (outputBuffer == null) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            
            // Check if this is a config frame
            val isConfigFrame = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            
            // Extract frame data
            val data = ByteArray(info.size)
            outputBuffer.position(info.offset)
            outputBuffer.get(data)
            
            // Create encoded frame
            val frame = EncodedAudioFrame(
                data = data,
                presentationTimeUs = info.presentationTimeUs,
                isConfigFrame = isConfigFrame
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
     * Add ADTS header to AAC frame
     */
    fun addADTSHeader(aacFrame: ByteArray): ByteArray {
        val frameSize = aacFrame.size + AAC_ADTS_HEADER_SIZE
        
        // ADTS header (7 bytes for AAC-LC without CRC)
        val adtsHeader = ByteArray(AAC_ADTS_HEADER_SIZE)
        
        // Syncword (12 bits): 0xFFF
        adtsHeader[0] = 0xFF.toByte()
        adtsHeader[1] = 0xF1.toByte() // Syncword (4 bits) + MPEG-4 (1 bit) + Layer (2 bits) + no CRC (1 bit)
        
        // Profile (2 bits) + Sampling Frequency Index (4 bits) + Private (1 bit) + Channel Config (1 bit)
        val profile = 1 // AAC-LC
        val freqIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4
        }
        val channelConfig = channelCount
        
        adtsHeader[2] = ((profile shl 6) or (freqIndex shl 2) or (channelConfig shr 2)).toByte()
        
        // Channel Config (cont) + Originality (1 bit) + Home (1 bit) + Copyright ID (1 bit) + Copyright ID start (1 bit) + Frame length (13 bits)
        adtsHeader[3] = ((channelConfig and 0x03) shl 6 or ((frameSize shr 11) and 0x03)).toByte()
        adtsHeader[4] = ((frameSize shr 3) and 0xFF).toByte()
        adtsHeader[5] = ((frameSize and 0x07) shl 5 or 0x1F).toByte()
        adtsHeader[6] = 0xFC.toByte()
        
        // Combine header and frame
        val result = ByteArray(AAC_ADTS_HEADER_SIZE + aacFrame.size)
        System.arraycopy(adtsHeader, 0, result, 0, AAC_ADTS_HEADER_SIZE)
        System.arraycopy(aacFrame, 0, result, AAC_ADTS_HEADER_SIZE, aacFrame.size)
        
        return result
    }
    
    /**
     * Get AudioSpecificConfig
     */
    fun getAudioSpecificConfig(): ByteArray? = audioSpecificConfig?.copyOf()
    
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
        
        return EncoderStatistics(
            framesEncoded = framesEncoded,
            bytesEncoded = bytesEncoded,
            duration = duration,
            averageBitrate = avgBitrate,
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
        val lastFrameTime: Long
    )
}
