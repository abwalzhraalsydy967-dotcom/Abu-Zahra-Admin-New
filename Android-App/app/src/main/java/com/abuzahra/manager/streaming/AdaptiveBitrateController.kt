package com.abuzahra.manager.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * AdaptiveBitrateController - Adaptive bitrate based on network conditions
 * Monitors network quality, bandwidth, and adjusts streaming parameters accordingly
 */
class AdaptiveBitrateController(
    private val context: Context,
    private val config: StreamConfig.Configuration
) {
    companion object {
        private const val TAG = "AdaptiveBitrateController"
        
        // Measurement intervals
        private const val MEASUREMENT_INTERVAL_MS = 1000L
        private const val HISTORY_SIZE = 10
        
        // Network quality thresholds (in Kbps)
        private const val EXCELLENT_THRESHOLD = 10000  // 10 Mbps
        private const val GOOD_THRESHOLD = 5000        // 5 Mbps
        private const val FAIR_THRESHOLD = 2000        // 2 Mbps
        private const val POOR_THRESHOLD = 500         // 500 Kbps
        
        // Bitrate adjustment factors
        private const val INCREASE_FACTOR = 1.1
        private const val DECREASE_FACTOR = 0.85
        private const val MIN_INCREASE_STEP = 100000   // 100 Kbps
        private const val MIN_DECREASE_STEP = 200000   // 200 Kbps
        
        // Buffer thresholds
        private const val LOW_BUFFER_MS = 1000         // 1 second
        private const val HIGH_BUFFER_MS = 5000        // 5 seconds
        private const val CRITICAL_BUFFER_MS = 500     // 0.5 seconds
    }
    
    // Network manager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // Coroutine scope
    private val controllerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Control state
    private val isActive = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    
    // Network state
    private var currentNetworkType = NetworkType.UNKNOWN
    private var currentBandwidth = 0L
    private var currentQuality = NetworkQuality.UNKNOWN
    
    // Bandwidth measurement history
    private val bandwidthHistory = mutableListOf<Long>()
    private val rttHistory = mutableListOf<Long>()
    private val packetLossHistory = mutableListOf<Double>()
    
    // Current streaming stats
    private val bytesSent = AtomicLong(0)
    private val lastBytesSent = AtomicLong(0)
    private val lastMeasurementTime = AtomicLong(0)
    
    // Bitrate control
    private val currentBitrate = AtomicInteger(config.videoBitrate)
    private val targetBitrate = AtomicInteger(config.videoBitrate)
    private val minBitrate = AtomicInteger(StreamConfig.MIN_VIDEO_BITRATE)
    private val maxBitrate = AtomicInteger(StreamConfig.MAX_VIDEO_BITRATE)
    
    // FPS control
    private val currentFps = AtomicInteger(config.fps)
    private val targetFps = AtomicInteger(config.fps)
    
    // Buffer state
    private var bufferHealthMs = 5000L
    private var bufferUnderrunCount = 0
    
    // Channel for bitrate updates
    private val bitrateUpdateChannel = Channel<Int>(Channel.CONFLATED)
    
    // Callbacks
    private var onBitrateChange: ((Int) -> Unit)? = null
    private var onQualityChange: ((NetworkQuality) -> Unit)? = null
    private var onFpsChange: ((Int) -> Unit)? = null
    
    /**
     * Network type enum
     */
    enum class NetworkType {
        WIFI,
        CELLULAR_2G,
        CELLULAR_3G,
        CELLULAR_4G,
        CELLULAR_5G,
        ETHERNET,
        UNKNOWN
    }
    
    /**
     * Network quality enum
     */
    enum class NetworkQuality(val bitrateRange: LongRange) {
        EXCELLENT(EXCELLENT_THRESHOLD * 1000..Long.MAX_VALUE),
        GOOD(GOOD_THRESHOLD * 1000 until EXCELLENT_THRESHOLD * 1000),
        FAIR(FAIR_THRESHOLD * 1000 until GOOD_THRESHOLD * 1000),
        POOR(POOR_THRESHOLD * 1000 until FAIR_THRESHOLD * 1000),
        VERY_POOR(0 until POOR_THRESHOLD * 1000),
        UNKNOWN(0..0)
    }
    
    /**
     * Streaming statistics from encoder
     */
    data class StreamingStats(
        val bytesSent: Long,
        val frameCount: Long,
        val droppedFrames: Long,
        val encodeTime: Long,
        val bufferHealthMs: Long,
        val rttMs: Long? = null,
        val packetLoss: Double? = null
    )
    
    /**
     * Set bitrate change callback
     */
    fun onBitrateChange(callback: (Int) -> Unit) {
        onBitrateChange = callback
    }
    
    /**
     * Set quality change callback
     */
    fun onQualityChange(callback: (NetworkQuality) -> Unit) {
        onQualityChange = callback
    }
    
    /**
     * Set FPS change callback
     */
    fun onFpsChange(callback: (Int) -> Unit) {
        onFpsChange = callback
    }
    
    /**
     * Start adaptive bitrate control
     */
    fun start() {
        if (isActive.getAndSet(true)) {
            Log.w(TAG, "Already active")
            return
        }
        
        // Register network callback
        registerNetworkCallback()
        
        // Start monitoring
        startMonitoring()
        
        // Start bitrate update processor
        startBitrateUpdateProcessor()
        
        Log.i(TAG, "AdaptiveBitrateController started")
    }
    
    /**
     * Stop adaptive bitrate control
     */
    fun stop() {
        if (!isActive.getAndSet(false)) return
        
        // Unregister network callback
        unregisterNetworkCallback()
        
        // Cancel monitoring
        monitoringJob?.cancel()
        monitoringJob = null
        
        Log.i(TAG, "AdaptiveBitrateController stopped")
    }
    
    /**
     * Register network callback
     */
    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    updateNetworkType()
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    currentQuality = NetworkQuality.UNKNOWN
                    onQualityChange?.invoke(currentQuality)
                }
                
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    updateNetworkCapabilities(capabilities)
                }
            }
            
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        }
        
        // Initial network type update
        updateNetworkType()
    }
    
    /**
     * Unregister network callback
     */
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
    }
    
    /**
     * Update network type
     */
    private fun updateNetworkType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            if (capabilities != null) {
                updateNetworkCapabilities(capabilities)
            } else {
                currentNetworkType = NetworkType.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            currentNetworkType = when (networkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    when (telephonyManager.networkType) {
                        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
                        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> NetworkType.CELLULAR_2G
                        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
                        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
                        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA -> NetworkType.CELLULAR_3G
                        TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.CELLULAR_4G
                        TelephonyManager.NETWORK_TYPE_NR -> NetworkType.CELLULAR_5G
                        else -> NetworkType.UNKNOWN
                    }
                }
                ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                else -> NetworkType.UNKNOWN
            }
        }
        
        Log.i(TAG, "Network type: $currentNetworkType")
    }
    
    /**
     * Update network capabilities
     */
    private fun updateNetworkCapabilities(capabilities: NetworkCapabilities) {
        // Determine network type
        currentNetworkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    when {
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMTEL) -> NetworkType.CELLULAR_5G
                        else -> NetworkType.CELLULAR_4G // Default assumption
                    }
                } else {
                    NetworkType.CELLULAR_4G
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
        
        // Get estimated bandwidth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            currentBandwidth = capabilities.linkDownstreamBandwidthKbps * 1000L // Convert to bps
            
            // Update quality based on bandwidth
            updateNetworkQuality(currentBandwidth)
        }
        
        Log.d(TAG, "Network capabilities updated: type=$currentNetworkType, bandwidth=${currentBandwidth / 1000}Kbps")
    }
    
    /**
     * Update network quality based on bandwidth
     */
    private fun updateNetworkQuality(bandwidth: Long) {
        val newQuality = when {
            bandwidth >= EXCELLENT_THRESHOLD * 1000 -> NetworkQuality.EXCELLENT
            bandwidth >= GOOD_THRESHOLD * 1000 -> NetworkQuality.GOOD
            bandwidth >= FAIR_THRESHOLD * 1000 -> NetworkQuality.FAIR
            bandwidth >= POOR_THRESHOLD * 1000 -> NetworkQuality.POOR
            else -> NetworkQuality.VERY_POOR
        }
        
        if (newQuality != currentQuality) {
            currentQuality = newQuality
            onQualityChange?.invoke(currentQuality)
            Log.i(TAG, "Network quality changed to: $currentQuality")
            
            // Adjust max bitrate based on network quality
            adjustMaxBitrateForQuality()
        }
    }
    
    /**
     * Adjust max bitrate based on network quality
     */
    private fun adjustMaxBitrateForQuality() {
        val newMaxBitrate = when (currentQuality) {
            NetworkQuality.EXCELLENT -> StreamConfig.MAX_VIDEO_BITRATE
            NetworkQuality.GOOD -> 8_000_000
            NetworkQuality.FAIR -> 3_000_000
            NetworkQuality.POOR -> 1_000_000
            NetworkQuality.VERY_POOR -> 500_000
            NetworkQuality.UNKNOWN -> config.videoBitrate
        }
        
        maxBitrate.set(newMaxBitrate)
        Log.d(TAG, "Max bitrate adjusted to: ${newMaxBitrate / 1000}Kbps")
    }
    
    /**
     * Start monitoring
     */
    private fun startMonitoring() {
        monitoringJob = controllerScope.launch {
            while (isActive.get()) {
                delay(MEASUREMENT_INTERVAL_MS)
                
                // Measure current throughput
                measureThroughput()
                
                // Update bitrate if needed
                updateBitrate()
                
                // Clean up old history
                cleanupHistory()
            }
        }
    }
    
    /**
     * Measure throughput
     */
    private fun measureThroughput() {
        val currentTime = System.currentTimeMillis()
        val currentBytes = bytesSent.get()
        val lastTime = lastMeasurementTime.getAndSet(currentTime)
        val lastBytes = lastBytesSent.getAndSet(currentBytes)
        
        if (lastTime > 0 && lastBytes > 0) {
            val timeDiff = currentTime - lastTime
            val bytesDiff = currentBytes - lastBytes
            
            if (timeDiff > 0) {
                val throughput = (bytesDiff * 8 * 1000) / timeDiff // bps
                
                bandwidthHistory.add(throughput)
                
                // Update network quality based on measured throughput
                if (bandwidthHistory.size >= 3) {
                    val avgThroughput = bandwidthHistory.takeLast(3).average().toLong()
                    updateNetworkQuality(avgThroughput)
                }
                
                Log.d(TAG, "Throughput: ${throughput / 1000}Kbps, Avg: ${bandwidthHistory.average().toLong() / 1000}Kbps")
            }
        }
    }
    
    /**
     * Update bitrate based on conditions
     */
    private fun updateBitrate() {
        if (bandwidthHistory.isEmpty()) return
        
        val avgBandwidth = bandwidthHistory.average().toLong()
        val currentBit = currentBitrate.get()
        val target = targetBitrate.get()
        
        // Calculate new target bitrate
        val newTarget = when {
            // Very low bandwidth - aggressive decrease
            avgBandwidth < POOR_THRESHOLD * 1000 -> {
                (currentBit * 0.5).toInt().coerceAtLeast(minBitrate.get())
            }
            
            // Low bandwidth - decrease
            avgBandwidth < FAIR_THRESHOLD * 1000 -> {
                (currentBit * DECREASE_FACTOR).toInt().coerceAtLeast(minBitrate.get())
            }
            
            // Good bandwidth - can increase
            avgBandwidth > GOOD_THRESHOLD * 1000 && bufferHealthMs > LOW_BUFFER_MS -> {
                val increase = maxOf(
                    (currentBit * (INCREASE_FACTOR - 1)).toInt(),
                    MIN_INCREASE_STEP
                )
                (currentBit + increase).coerceAtMost(maxBitrate.get())
            }
            
            // Stable
            else -> currentBit
        }
        
        // Check buffer health
        if (bufferHealthMs < CRITICAL_BUFFER_MS) {
            // Critical buffer - decrease bitrate aggressively
            targetBitrate.set((currentBit * 0.5).toInt().coerceAtLeast(minBitrate.get()))
            bufferUnderrunCount++
        } else if (bufferHealthMs < LOW_BUFFER_MS) {
            // Low buffer - decrease bitrate
            targetBitrate.set((currentBit * DECREASE_FACTOR).toInt().coerceAtLeast(minBitrate.get()))
        } else {
            targetBitrate.set(newTarget)
        }
        
        // Adjust FPS if needed
        adjustFps()
        
        // Notify if target changed significantly
        if (abs(targetBitrate.get() - target) > MIN_INCREASE_STEP) {
            bitrateUpdateChannel.trySend(targetBitrate.get())
        }
    }
    
    /**
     * Adjust FPS based on bitrate
     */
    private fun adjustFps() {
        val bitrate = currentBitrate.get()
        val currentFrameRate = currentFps.get()
        
        val newFps = when {
            bitrate < 500_000 -> 15
            bitrate < 1_000_000 -> 20
            bitrate < 2_000_000 -> 24
            bitrate < 4_000_000 -> 30
            else -> config.fps
        }
        
        if (newFps != currentFrameRate) {
            currentFps.set(newFps)
            onFpsChange?.invoke(newFps)
            Log.i(TAG, "FPS adjusted to: $newFps")
        }
    }
    
    /**
     * Start bitrate update processor
     */
    private fun startBitrateUpdateProcessor() {
        controllerScope.launch {
            bitrateUpdateChannel.consumeEach { newBitrate ->
                onBitrateChange?.invoke(newBitrate)
                currentBitrate.set(newBitrate)
                Log.i(TAG, "Bitrate changed to: ${newBitrate / 1000}Kbps")
            }
        }
    }
    
    /**
     * Clean up old history
     */
    private fun cleanupHistory() {
        while (bandwidthHistory.size > HISTORY_SIZE) {
            bandwidthHistory.removeAt(0)
        }
        while (rttHistory.size > HISTORY_SIZE) {
            rttHistory.removeAt(0)
        }
        while (packetLossHistory.size > HISTORY_SIZE) {
            packetLossHistory.removeAt(0)
        }
    }
    
    // ========== Public API ==========
    
    /**
     * Update streaming statistics (call periodically from encoder)
     */
    fun updateStats(stats: StreamingStats) {
        bytesSent.set(stats.bytesSent)
        bufferHealthMs = stats.bufferHealthMs
        
        // Update RTT if available
        stats.rttMs?.let { rtt ->
            rttHistory.add(rtt)
        }
        
        // Update packet loss if available
        stats.packetLoss?.let { loss ->
            packetLossHistory.add(loss)
        }
    }
    
    /**
     * Record bytes sent (for throughput measurement)
     */
    fun recordBytesSent(bytes: Long) {
        bytesSent.addAndGet(bytes)
    }
    
    /**
     * Get current bitrate
     */
    fun getCurrentBitrate(): Int = currentBitrate.get()
    
    /**
     * Get current FPS
     */
    fun getCurrentFps(): Int = currentFps.get()
    
    /**
     * Get network quality
     */
    fun getNetworkQuality(): NetworkQuality = currentQuality
    
    /**
     * Get network type
     */
    fun getNetworkType(): NetworkType = currentNetworkType
    
    /**
     * Get estimated bandwidth
     */
    fun getEstimatedBandwidth(): Long = if (bandwidthHistory.isNotEmpty()) {
        bandwidthHistory.average().toLong()
    } else {
        currentBandwidth
    }
    
    /**
     * Set bitrate limits
     */
    fun setBitrateLimits(min: Int, max: Int) {
        minBitrate.set(min)
        maxBitrate.set(max)
        currentBitrate.set(currentBitrate.get().coerceIn(min, max))
    }
    
    /**
     * Force bitrate (disable adaptive temporarily)
     */
    fun forceBitrate(bitrate: Int) {
        currentBitrate.set(bitrate)
        targetBitrate.set(bitrate)
        onBitrateChange?.invoke(bitrate)
    }
    
    /**
     * Get statistics
     */
    fun getStatistics(): AdaptiveStats {
        return AdaptiveStats(
            isActive = isActive.get(),
            currentBitrate = currentBitrate.get(),
            targetBitrate = targetBitrate.get(),
            currentFps = currentFps.get(),
            networkType = currentNetworkType,
            networkQuality = currentQuality,
            estimatedBandwidth = getEstimatedBandwidth(),
            avgBandwidth = if (bandwidthHistory.isNotEmpty()) bandwidthHistory.average().toLong() else 0,
            avgRtt = if (rttHistory.isNotEmpty()) rttHistory.average().toLong() else 0,
            avgPacketLoss = if (packetLossHistory.isNotEmpty()) packetLossHistory.average() else 0.0,
            bufferHealthMs = bufferHealthMs,
            bufferUnderruns = bufferUnderrunCount
        )
    }
    
    /**
     * Adaptive statistics data class
     */
    data class AdaptiveStats(
        val isActive: Boolean,
        val currentBitrate: Int,
        val targetBitrate: Int,
        val currentFps: Int,
        val networkType: NetworkType,
        val networkQuality: NetworkQuality,
        val estimatedBandwidth: Long,
        val avgBandwidth: Long,
        val avgRtt: Long,
        val avgPacketLoss: Double,
        val bufferHealthMs: Long,
        val bufferUnderruns: Int
    )
    
    /**
     * Cleanup resources
     */
    fun release() {
        stop()
        controllerScope.cancel()
        bitrateUpdateChannel.close()
        Log.i(TAG, "AdaptiveBitrateController released")
    }
}
