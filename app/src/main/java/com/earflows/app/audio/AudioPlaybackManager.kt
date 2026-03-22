package com.earflows.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays translated audio PCM data via AudioTrack.
 * Routes to Bluetooth earbuds when available.
 * Supports split-channel mode: original left + translation right.
 */
class AudioPlaybackManager {

    companion object {
        private const val TAG = "AudioPlayback"
        // Output at 24kHz for better quality (Seamless outputs 16kHz, we upsample)
        private const val OUTPUT_SAMPLE_RATE = 16_000
    }

    private var audioTrack: AudioTrack? = null
    private var isSplitChannel = false
    var volume: Float = 1.0f

    fun initialize(splitChannel: Boolean = false): Boolean {
        isSplitChannel = splitChannel

        val channelConfig = if (splitChannel) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid AudioTrack buffer size: $minBufferSize")
            return false
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(OUTPUT_SAMPLE_RATE)
            .setChannelMask(channelConfig)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            audioTrack?.release()
            audioTrack = null
            return false
        }

        audioTrack?.play()
        Log.i(TAG, "AudioTrack initialized: ${OUTPUT_SAMPLE_RATE}Hz, split=$splitChannel")
        return true
    }

    /**
     * Route audio output to a specific device (e.g. Bluetooth).
     */
    fun setOutputDevice(device: AudioDeviceInfo?) {
        audioTrack?.setPreferredDevice(device)
        Log.i(TAG, "Output routed to: ${device?.productName ?: "default"}")
    }

    /**
     * Write translated audio PCM (mono 16-bit) to AudioTrack.
     * If split-channel mode, the translation goes to the right channel.
     */
    suspend fun playTranslatedAudio(pcmData: ShortArray) = withContext(Dispatchers.IO) {
        val track = audioTrack ?: return@withContext

        val output = if (isSplitChannel) {
            // Interleave: left=silence, right=translation
            val stereo = ShortArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                stereo[i * 2] = 0                // Left channel: silence (original comes from mic passthrough)
                stereo[i * 2 + 1] = pcmData[i]   // Right channel: translation
            }
            stereo
        } else {
            pcmData
        }

        // Apply volume
        if (volume != 1.0f) {
            for (i in output.indices) {
                output[i] = (output[i] * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        track.write(output, 0, output.size)
    }

    /**
     * For split-channel: write original audio to the left channel.
     */
    suspend fun playOriginalAudio(pcmData: ShortArray) = withContext(Dispatchers.IO) {
        if (!isSplitChannel) return@withContext
        val track = audioTrack ?: return@withContext

        val stereo = ShortArray(pcmData.size * 2)
        for (i in pcmData.indices) {
            stereo[i * 2] = pcmData[i]       // Left: original
            stereo[i * 2 + 1] = 0            // Right: silence (translation written separately)
        }
        track.write(stereo, 0, stereo.size)
    }

    fun pause() {
        audioTrack?.pause()
    }

    fun resume() {
        audioTrack?.play()
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack stop error: ${e.message}")
        }
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "AudioTrack released")
    }
}
