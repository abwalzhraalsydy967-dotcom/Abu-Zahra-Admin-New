package com.abuzahra.manager.streaming

import android.util.Log

/**
 * AudioEncoder - Stub implementation for audio encoding
 * Provides the API surface expected by streaming services
 */
class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrate: Int = 128000
) {
    companion object {
        private const val TAG = "AudioEncoder"
    }

    private var callback: ((EncodedAudioFrame) -> Unit)? = null

    data class EncodedAudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean
    )

    fun setEncodedDataCallback(cb: (EncodedAudioFrame) -> Unit) {
        callback = cb
    }

    fun init(): Boolean {
        Log.d(TAG, "AudioEncoder init (stub) - sampleRate=$sampleRate, channels=$channelCount, bitrate=$bitrate")
        return true
    }

    fun start() {
        Log.d(TAG, "AudioEncoder start (stub)")
    }

    fun stop() {
        Log.d(TAG, "AudioEncoder stop (stub)")
        callback = null
    }

    fun release() {
        Log.d(TAG, "AudioEncoder release (stub)")
        callback = null
    }

    fun encode(buffer: ByteArray, presentationTimeUs: Long) {
        callback?.invoke(EncodedAudioFrame(buffer, presentationTimeUs, false))
    }

    fun getInputSurface(): android.view.Surface? = null

    fun queueAudioData(buf: ByteArray, ts: Long) {
        callback?.invoke(EncodedAudioFrame(buf, ts, false))
    }
}