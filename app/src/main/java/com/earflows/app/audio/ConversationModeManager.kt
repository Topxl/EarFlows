package com.earflows.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Manages audio routing for bidirectional conversation mode.
 *
 * Two modes:
 *
 * AMBIENT (default):
 *   Input:  Phone mic (CAMCORDER) — captures Thai speakers around you
 *   Output: Bluetooth earbuds (A2DP) — you hear the French translation
 *
 * REPLY (hold button):
 *   Input:  Bluetooth mic (VOICE_COMMUNICATION) — captures YOUR French speech via Buds
 *   Output: Phone speaker (forced) — the Thai person hears the Thai translation
 *
 * Key Android audio routing tricks:
 * - MODE_IN_COMMUNICATION + setSpeakerphoneOn(true) → forces phone speaker even with BT
 * - AudioSource.VOICE_COMMUNICATION → uses BT SCO mic
 * - AudioSource.CAMCORDER → always uses phone's built-in mic
 */
class ConversationModeManager(private val context: Context) {

    companion object {
        private const val TAG = "ConversationMode"
        const val SAMPLE_RATE = 16000
    }

    enum class Mode {
        AMBIENT,  // Phone mic → translate → BT earbuds
        REPLY     // BT mic → translate → Phone speaker
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false

    var currentMode = Mode.AMBIENT
        private set

    /**
     * Switch to AMBIENT mode: phone mic input, BT output.
     */
    fun switchToAmbient() {
        if (currentMode == Mode.AMBIENT) return
        currentMode = Mode.AMBIENT

        // Restore normal audio routing → BT gets audio output
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        // Stop Bluetooth SCO if it was started
        try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
        audioManager.isBluetoothScoOn = false

        Log.i(TAG, "Switched to AMBIENT: phone mic → BT output")
    }

    /**
     * Switch to REPLY mode: BT mic input, phone speaker output.
     */
    fun switchToReply() {
        if (currentMode == Mode.REPLY) return

        // Save current state
        savedMode = audioManager.mode
        savedSpeakerphone = audioManager.isSpeakerphoneOn

        currentMode = Mode.REPLY

        // Start Bluetooth SCO for mic access
        audioManager.isBluetoothScoOn = true
        try { audioManager.startBluetoothSco() } catch (_: Exception) {}

        // Force output to phone speaker (not BT)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        Log.i(TAG, "Switched to REPLY: BT mic → phone speaker output")
    }

    /**
     * Create an AudioRecord configured for the current mode.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun createAudioRecord(): AudioRecord? {
        val source = when (currentMode) {
            Mode.AMBIENT -> MediaRecorder.AudioSource.CAMCORDER       // Phone mic
            Mode.REPLY -> MediaRecorder.AudioSource.VOICE_COMMUNICATION // BT mic
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        return try {
            val record = AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, SAMPLE_RATE * 2)
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord created: ${if (currentMode == Mode.REPLY) "BT mic" else "phone mic"}")
                record
            } else {
                record.release()
                Log.e(TAG, "AudioRecord init failed for mode $currentMode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord error: ${e.message}")
            null
        }
    }

    /**
     * Get the AudioTrack output device for the current mode.
     * AMBIENT → preferred BT device (or null for default/BT)
     * REPLY → phone speaker (forced via AudioManager, AudioTrack follows)
     */
    fun getOutputDevice(): AudioDeviceInfo? {
        return when (currentMode) {
            Mode.AMBIENT -> {
                // Find BT output device
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { d ->
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            }
            Mode.REPLY -> {
                // Find phone speaker
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { d ->
                    d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }
        }
    }

    /**
     * Get source/target language for current mode.
     */
    fun getSourceLang(ambientSource: String, replySource: String): String {
        return when (currentMode) {
            Mode.AMBIENT -> ambientSource  // Thai (what others speak)
            Mode.REPLY -> replySource      // French (what YOU speak)
        }
    }

    fun getTargetLang(ambientTarget: String, replyTarget: String): String {
        return when (currentMode) {
            Mode.AMBIENT -> ambientTarget  // French (translation for you)
            Mode.REPLY -> replyTarget      // Thai (translation for them)
        }
    }

    fun release() {
        if (currentMode == Mode.REPLY) {
            switchToAmbient()
        }
        Log.i(TAG, "Released")
    }
}
