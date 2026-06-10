package com.abuzahra.manager.streaming

class AudioEncoder(sampleRate: Int = 44100, channelCount: Int = 2, bitrate: Int = 128000) {
    private var callback: ((ByteArray) -> Unit)? = null
    fun setEncodedDataCallback(cb: (ByteArray) -> Unit) { callback = cb }
    fun encode(buffer: ByteArray, presentationTimeUs: Long) { callback?.invoke(buffer) }
    fun getInputSurface() = null
    fun release() {}
    fun stop() {}
    fun queueAudioData(buf: ByteArray, ts: Long) {}
}
