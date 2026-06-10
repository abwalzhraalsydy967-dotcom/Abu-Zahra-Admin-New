package com.abuzahra.manager.streaming

data class EncodedAudioFrame(val data: ByteArray, val presentationTimeUs: Long, val flags: Int = 0)

class AdaptiveBitrateController {
    fun updateThroughput(bytes: Long, durationMs: Long) {}
    fun getTargetBitrate(): Int = 2_500_000
    fun reset() {}
    fun start() {}
    fun stop() {}
}
