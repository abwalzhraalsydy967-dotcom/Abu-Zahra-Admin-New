package com.abuzahra.manager.streaming

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * AdaptiveBitrateController - Adjusts video/audio bitrate based on network conditions
 * Uses throughput measurements to maintain smooth streaming
 */
class AdaptiveBitrateController(
    private val minBitrate: Int = 500_000,
    private val maxBitrate: Int = 5_000_000,
    private val initialBitrate: Int = 2_000_000,
    private val targetBufferMs: Long = 2000
) {
    companion object {
        private const val TAG = "ABRController"
    }

    private var currentBitrate = initialBitrate
    private var throughputSamples = mutableListOf<Long>() // bytes per second
    private val maxSamples = 10
    private var isRunning = false

    // Quality levels
    enum class Quality(val bitrate: Int, val label: String) {
        LOW(500_000, "Low"),
        MEDIUM(1_500_000, "Medium"),
        HIGH(2_500_000, "High"),
        VERY_HIGH(4_000_000, "Very High"),
        MAX(5_000_000, "Max")
    }

    fun updateThroughput(bytes: Long, durationMs: Long) {
        if (!isRunning || durationMs <= 0) return
        val bytesPerSec = (bytes * 1000) / durationMs
        synchronized(throughputSamples) {
            throughputSamples.add(bytesPerSec)
            if (throughputSamples.size > maxSamples) {
                throughputSamples.removeAt(0)
            }
        }
        adjustBitrate()
    }

    fun getTargetBitrate(): Int = currentBitrate

    fun getCurrentQuality(): Quality {
        return when {
            currentBitrate <= 750_000 -> Quality.LOW
            currentBitrate <= 1_200_000 -> Quality.MEDIUM
            currentBitrate <= 2_000_000 -> Quality.HIGH
            currentBitrate <= 3_500_000 -> Quality.VERY_HIGH
            else -> Quality.MAX
        }
    }

    fun setQuality(quality: Quality) {
        currentBitrate = quality.bitrate
        Log.d(TAG, "Quality set to ${quality.label} (${quality.bitrate / 1000}kbps)")
    }

    fun reset() {
        synchronized(throughputSamples) {
            throughputSamples.clear()
        }
        currentBitrate = initialBitrate
    }

    fun start() {
        isRunning = true
        Log.d(TAG, "ABR started - initial bitrate: ${currentBitrate / 1000}kbps")
    }

    fun stop() {
        isRunning = false
        Log.d(TAG, "ABR stopped")
    }

    private fun adjustBitrate() {
        val avgThroughput = getAverageThroughput() ?: return

        // Target 80% of available throughput for stable streaming
        val targetBitrate = (avgThroughput * 8 * 0.8).toInt() // Convert bytes/s to bits/s * safety

        val oldBitrate = currentBitrate
        currentBitrate = when {
            targetBitrate < minBitrate -> minBitrate
            targetBitrate > maxBitrate -> maxBitrate
            else -> targetBitrate
        }

        // Only log significant changes (>10%)
        if (kotlin.math.abs(currentBitrate - oldBitrate) > oldBitrate * 0.1) {
            Log.d(TAG, "Bitrate adjusted: ${oldBitrate / 1000}kbps -> ${currentBitrate / 1000}kbps (throughput: ${avgThroughput / 1024}KB/s)")
        }
    }

    private fun getAverageThroughput(): Long? {
        synchronized(throughputSamples) {
            if (throughputSamples.isEmpty()) return null
            return throughputSamples.sum() / throughputSamples.size
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "current_bitrate" to currentBitrate,
            "current_quality" to getCurrentQuality().label,
            "avg_throughput" to (getAverageThroughput() ?: 0),
            "samples" to throughputSamples.size
        )
    }
}