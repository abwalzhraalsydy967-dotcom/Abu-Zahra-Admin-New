package com.abuzahra.manager.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * AudioEncoder - Real AAC audio encoding using MediaCodec
 * Converts raw PCM audio to AAC format for efficient streaming
 */
class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 128000
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val TIMEOUT_US = 10000L
    }

    data class EncodedAudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean
    ) {
        val size: Int get() = data.size
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private var callback: ((EncodedAudioFrame) -> Unit)? = null
    private var encoder: MediaCodec? = null
    private var isEncoding = false

    fun setEncodedDataCallback(cb: (EncodedAudioFrame) -> Unit) {
        callback = cb
    }

    fun init(): Boolean {
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            isEncoding = true
            Log.d(TAG, "AudioEncoder init OK - sampleRate=$sampleRate, channels=$channelCount, bitrate=$bitrate")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "AudioEncoder init failed", e)
            return false
        }
    }

    fun start() {
        if (encoder == null) init()
        isEncoding = true
        Log.d(TAG, "AudioEncoder started")
    }

    fun stop() {
        isEncoding = false
        try {
            encoder?.apply {
                signalEndOfInputStream()
                try {
                    drainOutput(true)
                } catch (_: Exception) {}
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioEncoder stop error", e)
        }
        encoder = null
        callback = null
        Log.d(TAG, "AudioEncoder stopped")
    }

    fun release() {
        stop()
    }

    /**
     * Encode raw PCM 16-bit audio data to AAC
     * @param buffer Raw PCM data (16-bit signed, little-endian)
     * @param presentationTimeUs Presentation timestamp in microseconds
     */
    fun encode(buffer: ByteArray, presentationTimeUs: Long) {
        val enc = encoder ?: return
        if (!isEncoding) return

        try {
            val inputBufferIndex = enc.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer: ByteBuffer? = enc.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(buffer)
                enc.queueInputBuffer(
                    inputBufferIndex, 0, buffer.size,
                    presentationTimeUs, 0
                )
            }
            drainOutput(false)
        } catch (e: Exception) {
            Log.e(TAG, "AudioEncoder encode error", e)
        }
    }

    fun queueAudioData(buf: ByteArray, ts: Long) {
        encode(buf, ts)
    }

    fun getInputSurface(): android.view.Surface? = null

    private fun drainOutput(endOfStream: Boolean) {
        val enc = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) break
                    return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "AudioEncoder output format changed: ${enc.outputFormat}")
                }
                outputIndex >= 0 -> {
                    val outputBuffer: ByteBuffer? = enc.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        // Add ADTS header for AAC raw frames
                        val frameWithAdts = addAdtsHeader(data, bufferInfo.size)

                        callback?.invoke(
                            EncodedAudioFrame(
                                data = frameWithAdts,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                isKeyFrame = true // Audio keyframes
                            )
                        )
                    }
                    enc.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    /**
     * Add ADTS header to raw AAC frame for streaming
     */
    private fun addAdtsHeader(aacFrame: ByteArray, frameLength: Int): ByteArray {
        val adtsHeader = ByteArray(7)
        val profile = 2 // AAC-LC
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
            else -> 4 // default to 44100
        }
        val channelConfig = when (channelCount) {
            1 -> 1
            2 -> 2
            else -> 2
        }

        val fullLength = frameLength + 7
        // ADTS header fields
        adtsHeader[0] = (0xFF).toByte()                              // Sync word
        adtsHeader[1] = (0xF1).toByte()                              // Sync word + MPEG-4 + Layer + CRC absent
        adtsHeader[2] = (((profile - 1) shl 6) or (freqIndex shl 2) or (channelConfig shr 2)).toByte()
        adtsHeader[3] = (((channelConfig and 0x3) shl 6) or (fullLength shr 11)).toByte()
        adtsHeader[4] = ((fullLength shr 3) and 0xFF).toByte()
        adtsHeader[5] = (((fullLength and 0x7) shl 5) or 0x1F).toByte()
        adtsHeader[6] = (0xFC).toByte()                              // Buffer fullness (VBR)

        return adtsHeader + aacFrame
    }
}