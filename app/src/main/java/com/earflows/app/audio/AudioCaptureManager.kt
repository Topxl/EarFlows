package com.earflows.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Captures raw PCM audio from the microphone at 16kHz mono.
 * Emits chunks as ShortArray via a SharedFlow for downstream consumers
 * (VAD → translation pipeline + parallel recording).
 */
class AudioCaptureManager {

    companion object {
        private const val TAG = "AudioCapture"
    }

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false

    // Raw PCM chunks (16-bit signed shorts)
    private val _audioChunks = MutableSharedFlow<ShortArray>(
        replay = 0,
        extraBufferCapacity = 10  // Buffer a few chunks to avoid back-pressure drops
    )
    val audioChunks: Flow<ShortArray> = _audioChunks.asSharedFlow()

    // Also emit raw bytes for the recording pipeline
    private val _rawBytesChunks = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val rawBytesChunks: Flow<ByteArray> = _rawBytesChunks.asSharedFlow()

    val bufferSizeSamples: Int
        get() = Constants.CHUNK_SIZE_BYTES / 2  // 16-bit = 2 bytes per sample

    @SuppressLint("MissingPermission") // Permission checked in service before calling
    fun initialize(): Boolean {
        val minBufferSize = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBufferSize")
            return false
        }

        // Use at least 2x our chunk size or the system minimum, whichever is bigger
        val bufferSize = maxOf(Constants.CHUNK_SIZE_BYTES * 2, minBufferSize)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Constants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        Log.i(TAG, "AudioRecord initialized: ${Constants.SAMPLE_RATE}Hz, buffer=$bufferSize bytes")
        return true
    }

    /**
     * Start capturing in a coroutine. Runs until cancelled or stop() is called.
     * Must be called from a coroutine scope (e.g. service lifecycle scope).
     */
    suspend fun startCapture() = withContext(Dispatchers.IO) {
        val recorder = audioRecord ?: run {
            Log.e(TAG, "AudioRecord not initialized")
            return@withContext
        }

        recorder.startRecording()
        isCapturing = true
        Log.i(TAG, "Audio capture started")

        val chunkSizeSamples = bufferSizeSamples
        val buffer = ShortArray(chunkSizeSamples)

        while (isActive && isCapturing) {
            val readCount = recorder.read(buffer, 0, chunkSizeSamples)
            if (readCount > 0) {
                // Emit short array for VAD + translation
                val chunk = buffer.copyOf(readCount)
                _audioChunks.emit(chunk)

                // Convert to bytes for recording pipeline
                val byteChunk = shortsToBytes(chunk)
                _rawBytesChunks.emit(byteChunk)
            } else if (readCount < 0) {
                Log.e(TAG, "AudioRecord read error: $readCount")
                break
            }
        }

        Log.i(TAG, "Audio capture loop ended")
    }

    fun stop() {
        isCapturing = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop error: ${e.message}")
        }
    }

    fun release() {
        stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "AudioRecord released")
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
