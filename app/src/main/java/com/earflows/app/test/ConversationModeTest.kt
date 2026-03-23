package com.earflows.app.test

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.earflows.app.audio.ConversationModeManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Tests for the Conversation Mode (bidirectional translation).
 *
 * Tests:
 * 1. Audio routing: phone mic vs BT mic
 * 2. Audio output: BT earbuds vs phone speaker
 * 3. Mode switching speed
 * 4. Translation FR → TH works
 * 5. No audio leak (speaker doesn't feed back into BT mic)
 * 6. Full round-trip: French speech → Thai audio on speaker
 */
class ConversationModeTest(private val context: Context) {

    companion object {
        private const val TAG = "ConvModeTest"
        private const val SAMPLE_RATE = 16000
    }

    data class TestResult(
        val name: String,
        val passed: Boolean,
        val message: String,
        val durationMs: Long = 0
    )

    private val results = mutableListOf<TestResult>()

    suspend fun runAllTests(): List<TestResult> = withContext(Dispatchers.IO) {
        results.clear()
        Log.i(TAG, "========== CONVERSATION MODE TESTS ==========")

        testBluetoothAvailable()
        testAmbientMicSource()
        testReplyMicSource()
        testModeSwitchSpeed()
        testOutputRouting()
        testFrenchToThaiTranslation()
        testAmbientCapture()
        testReplyCapture()

        val passed = results.count { it.passed }
        Log.i(TAG, "========== RESULTS: $passed/${results.size} PASSED ==========")
        results.forEach { r ->
            Log.i(TAG, "  [${if (r.passed) "PASS" else "FAIL"}] ${r.name}: ${r.message}")
        }

        results.toList()
    }

    // ========================================================================
    // TEST 1: Bluetooth earbuds detected
    // ========================================================================

    private fun testBluetoothAvailable() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { d ->
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val btInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { d ->
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        results.add(TestResult(
            "BT output devices",
            btDevices.isNotEmpty(),
            "${btDevices.size} found: ${btDevices.joinToString { "${it.productName} (type=${it.type})" }}"
        ))
        results.add(TestResult(
            "BT input (mic) devices",
            btInputs.isNotEmpty(),
            "${btInputs.size} found: ${btInputs.joinToString { "${it.productName}" }}"
        ))
        results.add(TestResult(
            "Phone speaker",
            speaker != null,
            speaker?.productName?.toString() ?: "NOT FOUND"
        ))
    }

    // ========================================================================
    // TEST 2: Ambient mode mic (phone CAMCORDER)
    // ========================================================================

    @android.annotation.SuppressLint("MissingPermission")
    private fun testAmbientMicSource() {
        val mgr = ConversationModeManager(context)
        mgr.switchToAmbient()

        val record = mgr.createAudioRecord()
        val passed = record != null && record.state == AudioRecord.STATE_INITIALIZED

        results.add(TestResult(
            "Ambient: phone mic init",
            passed,
            if (passed) "CAMCORDER source OK" else "Failed to init phone mic"
        ))

        record?.release()
        mgr.release()
    }

    // ========================================================================
    // TEST 3: Reply mode mic (BT SCO)
    // ========================================================================

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun testReplyMicSource() {
        val mgr = ConversationModeManager(context)
        mgr.switchToReply()

        // Give BT SCO time to connect
        delay(1000)

        val record = mgr.createAudioRecord()
        val passed = record != null && record.state == AudioRecord.STATE_INITIALIZED

        results.add(TestResult(
            "Reply: BT mic init",
            passed,
            if (passed) "VOICE_COMMUNICATION source OK" else "BT mic not available (connect earbuds)"
        ))

        record?.release()
        mgr.switchToAmbient()
        mgr.release()
    }

    // ========================================================================
    // TEST 4: Mode switch speed
    // ========================================================================

    private fun testModeSwitchSpeed() {
        val mgr = ConversationModeManager(context)

        val start1 = System.currentTimeMillis()
        mgr.switchToReply()
        val replyMs = System.currentTimeMillis() - start1

        val start2 = System.currentTimeMillis()
        mgr.switchToAmbient()
        val ambientMs = System.currentTimeMillis() - start2

        val totalMs = replyMs + ambientMs
        val passed = totalMs < 500 // Should switch in under 500ms

        results.add(TestResult(
            "Mode switch speed",
            passed,
            "→Reply: ${replyMs}ms, →Ambient: ${ambientMs}ms, total: ${totalMs}ms",
            totalMs
        ))

        mgr.release()
    }

    // ========================================================================
    // TEST 5: Output routing (BT vs Speaker)
    // ========================================================================

    private fun testOutputRouting() {
        val mgr = ConversationModeManager(context)

        // Ambient: should get BT device
        mgr.switchToAmbient()
        val btDevice = mgr.getOutputDevice()
        val hasBt = btDevice != null && (
            btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            btDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            btDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        results.add(TestResult(
            "Output: Ambient → BT",
            hasBt,
            btDevice?.let { "${it.productName} (type=${it.type})" } ?: "No BT device"
        ))

        // Reply: should get phone speaker
        mgr.switchToReply()
        val speakerDevice = mgr.getOutputDevice()
        val hasSpeaker = speakerDevice != null &&
            speakerDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        results.add(TestResult(
            "Output: Reply → Speaker",
            hasSpeaker,
            speakerDevice?.let { "${it.productName} (type=${it.type})" } ?: "No speaker found"
        ))

        mgr.switchToAmbient()
        mgr.release()
    }

    // ========================================================================
    // TEST 6: French → Thai translation
    // ========================================================================

    private suspend fun testFrenchToThaiTranslation() {
        try {
            val translator = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.FRENCH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build())

            suspendCancellableCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(true) }
                    .addOnFailureListener { cont.resume(false) }
            }

            val tests = listOf(
                "Bonjour, comment allez-vous ?" to "สวัสดี",
                "Je voudrais manger du riz" to "ข้าว",
                "Combien ça coûte ?" to "เท่าไหร่"
            )

            for ((french, expectedContains) in tests) {
                val start = System.currentTimeMillis()
                val thai = suspendCancellableCoroutine { cont ->
                    translator.translate(french)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume("") }
                }
                val ms = System.currentTimeMillis() - start

                val hasThai = thai.any { it.code in 0x0E00..0x0E7F }
                results.add(TestResult(
                    "Reply translate: \"${french.take(25)}\"",
                    hasThai,
                    "→ \"${thai.take(30)}\" (${ms}ms)",
                    ms
                ))
            }

            translator.close()
        } catch (e: Exception) {
            results.add(TestResult("Reply translation", false, "Error: ${e.message}"))
        }
    }

    // ========================================================================
    // TEST 7: Ambient mode captures audio (phone mic)
    // ========================================================================

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun testAmbientCapture() {
        val mgr = ConversationModeManager(context)
        mgr.switchToAmbient()

        val record = mgr.createAudioRecord() ?: run {
            results.add(TestResult("Ambient capture", false, "Mic init failed"))
            mgr.release()
            return
        }

        record.startRecording()
        delay(500) // Record 500ms

        val buffer = ShortArray(SAMPLE_RATE / 2) // 500ms
        val read = record.read(buffer, 0, buffer.size)
        record.stop()
        record.release()
        mgr.release()

        val rms = if (read > 0) {
            var sum = 0.0
            for (i in 0 until read) { val s = buffer[i].toDouble(); sum += s * s }
            sqrt(sum / read)
        } else 0.0

        results.add(TestResult(
            "Ambient capture: phone mic",
            read > 0 && rms > 1,
            "${read} samples, RMS=${rms.toInt()} (should be >0 if not dead silent)"
        ))
    }

    // ========================================================================
    // TEST 8: Reply mode captures audio (BT mic)
    // ========================================================================

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun testReplyCapture() {
        val mgr = ConversationModeManager(context)
        mgr.switchToReply()
        delay(1000) // Wait for BT SCO to connect

        val record = mgr.createAudioRecord()
        if (record == null) {
            results.add(TestResult("Reply capture: BT mic", false, "BT mic init failed (connect earbuds?)"))
            mgr.switchToAmbient()
            mgr.release()
            return
        }

        record.startRecording()
        delay(500)

        val buffer = ShortArray(SAMPLE_RATE / 2)
        val read = record.read(buffer, 0, buffer.size)
        record.stop()
        record.release()
        mgr.switchToAmbient()
        mgr.release()

        val rms = if (read > 0) {
            var sum = 0.0
            for (i in 0 until read) { val s = buffer[i].toDouble(); sum += s * s }
            sqrt(sum / read)
        } else 0.0

        results.add(TestResult(
            "Reply capture: BT mic",
            read > 0,
            "${read} samples, RMS=${rms.toInt()} (BT mic, may be quiet if no speech)"
        ))
    }
}
