package com.abuzahra.manager.streaming

class AudioEncoder {
    fun start() {}
    fun stop() {}
    fun encode(buffer: ByteArray, presentationTimeUs: Long): ByteArray = buffer
    fun getInputSurface() = null
    fun release() {}
}
