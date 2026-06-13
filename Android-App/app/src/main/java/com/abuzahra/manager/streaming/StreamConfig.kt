package com.abuzahra.manager.streaming

import android.content.Context
import android.os.Build
import android.util.Size
import android.util.SparseIntArray
import com.abuzahra.manager.Config

/**
 * StreamConfig - Configuration for streaming
 * Contains bitrate, resolution, FPS, codec settings
 */
object StreamConfig {
    
    private const val TAG = "StreamConfig"
    
    // Video Codec Types
    enum class VideoCodec(val mimeType: String) {
        H264("video/avc"),
        H265("video/hevc")
    }
    
    // Audio Codec Types
    enum class AudioCodec(val mimeType: String) {
        AAC("audio/mp4a-latm"),
        OPUS("audio/opus")
    }
    
    // Quality Presets
    enum class Quality(val width: Int, val height: Int, val videoBitrate: Int, val audioBitrate: Int, val fps: Int) {
        LOW(640, 480, 800_000, 64_000, 15),          // 480p
        MEDIUM(1280, 720, 2_500_000, 128_000, 30),   // 720p
        HIGH(1920, 1080, 5_000_000, 192_000, 30),    // 1080p
        ULTRA(2560, 1440, 10_000_000, 256_000, 60)   // 1440p
    }
    
    // Stream Types
    enum class StreamType {
        SCREEN,
        CAMERA_FRONT,
        CAMERA_BACK,
        AUDIO_MIC,
        AUDIO_DEVICE
    }
    
    // Resolution mappings
    val RESOLUTION_480P = Size(640, 480)
    val RESOLUTION_720P = Size(1280, 720)
    val RESOLUTION_1080P = Size(1920, 1080)
    
    // FPS Options
    val FPS_OPTIONS = intArrayOf(15, 24, 30, 60)
    
    // Bitrate ranges (bps)
    const val MIN_VIDEO_BITRATE = 500_000        // 500 Kbps
    const val MAX_VIDEO_BITRATE = 20_000_000     // 20 Mbps
    const val DEFAULT_VIDEO_BITRATE = 2_500_000  // 2.5 Mbps
    
    const val MIN_AUDIO_BITRATE = 32_000         // 32 Kbps
    const val MAX_AUDIO_BITRATE = 320_000        // 320 Kbps
    const val DEFAULT_AUDIO_BITRATE = 128_000    // 128 Kbps
    
    // Keyframe intervals
    const val KEYFRAME_INTERVAL_SECONDS = 2
    
    // Audio settings
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_CHANNEL_COUNT = 2
    const val AUDIO_BITRATE = 128_000
    
    // Buffer settings
    const val VIDEO_BUFFER_SIZE = 1024 * 1024     // 1 MB
    const val AUDIO_BUFFER_SIZE = 64 * 1024       // 64 KB
    
    // Network settings
    const val WEBSOCKET_RECONNECT_DELAY_MS = 3000L
    const val WEBSOCKET_PING_INTERVAL_MS = 30000L
    const val RTMP_CONNECT_TIMEOUT_MS = 10000L
    
    // Recording settings
    const val MAX_RECORDING_DURATION_MS = 3 * 60 * 60 * 1000L  // 3 hours
    const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024 * 1024    // 2 GB
    
    // Adaptive bitrate thresholds
    const val NETWORK_QUALITY_EXCELLENT = 10_000_000   // 10 Mbps
    const val NETWORK_QUALITY_GOOD = 5_000_000         // 5 Mbps
    const val NETWORK_QUALITY_FAIR = 2_000_000         // 2 Mbps
    const val NETWORK_QUALITY_POOR = 500_000           // 500 Kbps
    
    // Color formats for video encoding
    val COLOR_FORMATS = SparseIntArray().apply {
        // These are common color formats supported by MediaCodec
        put(19, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
        put(20, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar)
        put(21, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        put(39, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar)
        put(2130708433, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }
    
    /**
     * Stream configuration data class
     */
    data class Configuration(
        val streamId: String = generateStreamId(),
        val streamType: StreamType = StreamType.SCREEN,
        val videoEnabled: Boolean = true,
        val audioEnabled: Boolean = true,
        val videoCodec: VideoCodec = VideoCodec.H264,
        val audioCodec: AudioCodec = AudioCodec.AAC,
        val quality: Quality = Quality.MEDIUM,
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val videoBitrate: Int = DEFAULT_VIDEO_BITRATE,
        val audioBitrate: Int = DEFAULT_AUDIO_BITRATE,
        val keyframeInterval: Int = KEYFRAME_INTERVAL_SECONDS,
        val serverUrl: String = "",
        val streamKey: String = "",
        val enableAdaptiveBitrate: Boolean = true,
        val enableNoiseSuppression: Boolean = true,
        val enableEchoCancellation: Boolean = true
    ) {
        companion object {
            fun generateStreamId(): String {
                return "stream_${System.currentTimeMillis()}_${(0..9999).random().toString().padStart(4, '0')}"
            }
        }
        
        fun toMap(): Map<String, Any> {
            return mapOf(
                "stream_id" to streamId,
                "stream_type" to streamType.name,
                "video_enabled" to videoEnabled,
                "audio_enabled" to audioEnabled,
                "video_codec" to videoCodec.name,
                "audio_codec" to audioCodec.name,
                "quality" to quality.name,
                "width" to width,
                "height" to height,
                "fps" to fps,
                "video_bitrate" to videoBitrate,
                "audio_bitrate" to audioBitrate,
                "keyframe_interval" to keyframeInterval,
                "server_url" to serverUrl,
                "stream_key" to streamKey,
                "adaptive_bitrate" to enableAdaptiveBitrate,
                "noise_suppression" to enableNoiseSuppression,
                "echo_cancellation" to enableEchoCancellation
            )
        }
    }
    
    /**
     * Stream state for persistence and recovery
     */
    data class StreamState(
        val streamId: String,
        val streamType: StreamType,
        val startTime: Long,
        var isActive: Boolean,
        var paused: Boolean = false,
        var totalBytesSent: Long = 0,
        var totalFramesEncoded: Long = 0,
        var currentBitrate: Int = 0,
        var lastError: String? = null,
        var reconnectionAttempts: Int = 0
    ) {
        fun toMap(): Map<String, Any?> {
            return mapOf(
                "stream_id" to streamId,
                "stream_type" to streamType.name,
                "start_time" to startTime,
                "is_active" to isActive,
                "paused" to paused,
                "total_bytes_sent" to totalBytesSent,
                "total_frames_encoded" to totalFramesEncoded,
                "current_bitrate" to currentBitrate,
                "last_error" to lastError,
                "reconnection_attempts" to reconnectionAttempts
            )
        }
        
        val duration: Long
            get() = System.currentTimeMillis() - startTime
        
        val averageBitrate: Long
            get() = if (duration > 0) (totalBytesSent * 8 * 1000 / duration) else 0
    }
    
    /**
     * Get default configuration for a stream type
     */
    fun getDefaultConfig(streamType: StreamType, context: Context? = null): Configuration {
        return when (streamType) {
            StreamType.SCREEN -> Configuration(
                streamType = StreamType.SCREEN,
                quality = Quality.MEDIUM,
                fps = 30,
                videoBitrate = 2_500_000
            )
            StreamType.CAMERA_FRONT, StreamType.CAMERA_BACK -> Configuration(
                streamType = streamType,
                quality = Quality.MEDIUM,
                fps = 30,
                videoBitrate = 2_000_000
            )
            StreamType.AUDIO_MIC -> Configuration(
                streamType = StreamType.AUDIO_MIC,
                videoEnabled = false,
                audioEnabled = true,
                audioBitrate = 128_000
            )
            StreamType.AUDIO_DEVICE -> Configuration(
                streamType = StreamType.AUDIO_DEVICE,
                videoEnabled = false,
                audioEnabled = true,
                audioBitrate = 192_000
            )
        }
    }
    
    /**
     * Get streaming server WebSocket URL
     */
    fun getWebSocketUrl(context: Context?): String {
        val baseUrl = Config.getBaseUrl()
        return "${baseUrl.replace("https://", "wss://").replace("http://", "ws://")}/ws/stream"
    }
    
    /**
     * Calculate appropriate bitrate for given quality and network conditions
     */
    fun calculateAdaptiveBitrate(quality: Quality, networkBandwidth: Long): Int {
        val targetBitrate = quality.videoBitrate
        
        return when {
            networkBandwidth >= NETWORK_QUALITY_EXCELLENT -> targetBitrate
            networkBandwidth >= NETWORK_QUALITY_GOOD -> (targetBitrate * 0.8).toInt()
            networkBandwidth >= NETWORK_QUALITY_FAIR -> (targetBitrate * 0.5).toInt()
            networkBandwidth >= NETWORK_QUALITY_POOR -> (targetBitrate * 0.3).toInt()
            else -> MIN_VIDEO_BITRATE
        }.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
    }
    
    /**
     * Get supported video codec
     */
    fun getSupportedVideoCodec(preferH265: Boolean = false): VideoCodec {
        // Check device support for H.265/HEVC
        if (preferH265 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val codecCount = android.media.MediaCodecList.getCodecCount()
            for (i in 0 until codecCount) {
                val info = android.media.MediaCodecList.getCodecInfoAt(i)
                if (info.isEncoder) {
                    val types = info.supportedTypes
                    if (types.any { it.equals("video/hevc", ignoreCase = true) }) {
                        return VideoCodec.H265
                    }
                }
            }
        }
        return VideoCodec.H264
    }
    
    /**
     * Check if device supports specific resolution and FPS
     */
    fun isResolutionSupported(width: Int, height: Int, fps: Int): Boolean {
        // Most devices support up to 1080p
        if (width <= 1920 && height <= 1080 && fps <= 30) {
            return true
        }
        // For higher resolutions, we assume support on modern devices
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
    
    /**
     * Get optimal resolution based on device capabilities
     */
    fun getOptimalResolution(context: Context?, requestedQuality: Quality): Size {
        val screenMetrics = context?.resources?.displayMetrics
        val screenWidth = screenMetrics?.widthPixels ?: requestedQuality.width
        val screenHeight = screenMetrics?.heightPixels ?: requestedQuality.height
        
        // Don't exceed screen resolution
        val width = minOf(requestedQuality.width, screenWidth)
        val height = minOf(requestedQuality.height, screenHeight)
        
        return Size(width, height)
    }
    
    /**
     * Validate configuration
     */
    fun validateConfig(config: Configuration): List<String> {
        val errors = mutableListOf<String>()
        
        if (config.width < 320 || config.width > 4096) {
            errors.add("Invalid width: ${config.width}")
        }
        if (config.height < 240 || config.height > 4096) {
            errors.add("Invalid height: ${config.height}")
        }
        if (config.fps !in 1..120) {
            errors.add("Invalid FPS: ${config.fps}")
        }
        if (config.videoBitrate < MIN_VIDEO_BITRATE || config.videoBitrate > MAX_VIDEO_BITRATE) {
            errors.add("Invalid video bitrate: ${config.videoBitrate}")
        }
        if (config.audioBitrate < MIN_AUDIO_BITRATE || config.audioBitrate > MAX_AUDIO_BITRATE) {
            errors.add("Invalid audio bitrate: ${config.audioBitrate}")
        }
        // Note: serverUrl is auto-filled by StreamExecutor from Config.getBaseUrl()
        // We no longer require it in validation to avoid false errors
        
        return errors
    }
    
    /**
     * Preset configurations for common use cases
     */
    object Presets {
        fun screenShareLow() = Configuration(
            streamType = StreamType.SCREEN,
            quality = Quality.LOW,
            fps = 15,
            videoBitrate = 1_000_000,
            serverUrl = Config.getBaseUrl()
        )
        
        fun screenShareMedium() = Configuration(
            streamType = StreamType.SCREEN,
            quality = Quality.MEDIUM,
            fps = 30,
            videoBitrate = 2_500_000,
            serverUrl = Config.getBaseUrl()
        )
        
        fun screenShareHigh() = Configuration(
            streamType = StreamType.SCREEN,
            quality = Quality.HIGH,
            fps = 30,
            videoBitrate = 5_000_000,
            serverUrl = Config.getBaseUrl()
        )
        
        fun cameraStream() = Configuration(
            streamType = StreamType.CAMERA_BACK,
            quality = Quality.MEDIUM,
            fps = 30,
            videoBitrate = 2_000_000,
            serverUrl = Config.getBaseUrl()
        )
        
        fun audioOnlyMic() = Configuration(
            streamType = StreamType.AUDIO_MIC,
            videoEnabled = false,
            audioEnabled = true,
            audioBitrate = 128_000,
            serverUrl = Config.getBaseUrl()
        )
        
        fun audioOnlyDevice() = Configuration(
            streamType = StreamType.AUDIO_DEVICE,
            videoEnabled = false,
            audioEnabled = true,
            audioBitrate = 192_000,
            serverUrl = Config.getBaseUrl()
        )
    }
}
