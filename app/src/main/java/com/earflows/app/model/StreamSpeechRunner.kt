package com.earflows.app.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.earflows.app.util.Constants
import java.io.File
import java.nio.FloatBuffer

/**
 * StreamSpeech ONNX Model Runner for on-device simultaneous S2S translation.
 *
 * StreamSpeech architecture (split into 4 ONNX models for mobile):
 *
 * 1. ENCODER (speech_encoder.onnx ~150MB quantized)
 *    - Input: raw audio float32 [1, T] (16kHz)
 *    - Output: speech features [1, T/320, 1024]
 *    - Based on HuBERT / wav2vec2 frontend
 *
 * 2. POLICY (monotonic_policy.onnx ~30MB)
 *    - Input: encoder features [1, T', 1024]
 *    - Output: read/write decisions (when to emit translation)
 *    - Implements Wait-k or MMA (Monotonic Multihead Attention)
 *    - This is what makes it SIMULTANEOUS — decides to translate before input ends
 *
 * 3. DECODER (unit_decoder.onnx ~200MB quantized)
 *    - Input: encoder features + policy decisions
 *    - Output: discrete speech units [1, T''] (codebook indices)
 *    - Based on mBART decoder adapted for speech units
 *
 * 4. VOCODER (vocoder.onnx ~50MB)
 *    - Input: speech units [1, T'']
 *    - Output: waveform float32 [1, T_out] (16kHz)
 *    - HiFi-GAN or similar neural vocoder
 *
 * Total quantized: ~430MB — fits easily in Fold 6 (12GB RAM)
 *
 * Streaming protocol:
 * - Feed 500ms audio chunks continuously
 * - Encoder processes incrementally (append to hidden states)
 * - Policy decides when enough context → triggers decoder
 * - Decoder emits speech units → vocoder generates audio
 * - Output audio played immediately
 */
class StreamSpeechRunner(private val context: Context) {

    companion object {
        private const val TAG = "StreamSpeech"
        private const val SAMPLE_RATE = 16000
        private const val HOP_LENGTH = 320         // Encoder downsampling factor
        private const val HIDDEN_DIM = 1024         // Encoder output dimension

        // Model filenames
        private const val ENCODER_FILE = "streamspeech_encoder_q8.onnx"
        private const val POLICY_FILE = "streamspeech_policy_q8.onnx"
        private const val DECODER_FILE = "streamspeech_decoder_q8.onnx"
        private const val VOCODER_FILE = "streamspeech_vocoder_q8.onnx"
    }

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var policySession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    // Streaming state
    private val encoderBuffer = mutableListOf<Float>()  // Accumulated encoder features
    private var decoderState: FloatArray? = null          // Decoder hidden state (autoregressive)
    private var writePosition = 0                         // How many frames have been decoded

    // Language IDs
    private var srcLangId = 0L
    private var tgtLangId = 0L

    private var isLoaded = false

    /**
     * Load all 4 model components.
     * Returns false if models not found (user needs to download/export them).
     */
    fun loadModel(sourceLang: String, targetLang: String): Boolean {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try { addNnapi(); Log.i(TAG, "NNAPI enabled") } catch (_: Exception) {}
            }

            val modelDir = File(context.filesDir, Constants.MODEL_DIR)

            val encFile = File(modelDir, ENCODER_FILE)
            val polFile = File(modelDir, POLICY_FILE)
            val decFile = File(modelDir, DECODER_FILE)
            val vocFile = File(modelDir, VOCODER_FILE)

            if (!encFile.exists() || !decFile.exists() || !vocFile.exists()) {
                Log.w(TAG, "StreamSpeech models not found in ${modelDir.absolutePath}")
                Log.w(TAG, "Required: $ENCODER_FILE, $POLICY_FILE, $DECODER_FILE, $VOCODER_FILE")
                Log.w(TAG, "Run: python tools/export_streamspeech.py to generate them")
                return false
            }

            encoderSession = ortEnv!!.createSession(encFile.absolutePath, opts)
            Log.i(TAG, "Encoder loaded: ${encFile.length() / (1024 * 1024)}MB")

            if (polFile.exists()) {
                policySession = ortEnv!!.createSession(polFile.absolutePath, opts)
                Log.i(TAG, "Policy loaded: ${polFile.length() / (1024 * 1024)}MB")
            }

            decoderSession = ortEnv!!.createSession(decFile.absolutePath, opts)
            Log.i(TAG, "Decoder loaded: ${decFile.length() / (1024 * 1024)}MB")

            vocoderSession = ortEnv!!.createSession(vocFile.absolutePath, opts)
            Log.i(TAG, "Vocoder loaded: ${vocFile.length() / (1024 * 1024)}MB")

            srcLangId = langToId(sourceLang)
            tgtLangId = langToId(targetLang)

            isLoaded = true
            Log.i(TAG, "StreamSpeech loaded: $sourceLang($srcLangId) → $targetLang($tgtLangId)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load StreamSpeech: ${e.message}", e)
            return false
        }
    }

    /**
     * Process a 500ms audio chunk through the S2S pipeline.
     * Returns translated audio PCM (may be empty if policy decides to wait).
     *
     * This is the core streaming loop:
     * 1. Encode new audio → append features
     * 2. Run policy → should we emit translation?
     * 3. If yes: decode → vocoder → return audio
     * 4. If no: return null (waiting for more input)
     */
    fun processChunk(inputPcm: ShortArray): ShortArray? {
        if (!isLoaded) return null
        val env = ortEnv ?: return null

        try {
            // Convert to float normalized
            val floatInput = FloatArray(inputPcm.size) { inputPcm[it].toFloat() / 32768f }

            // === STEP 1: ENCODE ===
            val newFeatures = runEncoder(env, floatInput) ?: return null

            // Append to accumulated features
            synchronized(encoderBuffer) {
                for (f in newFeatures) encoderBuffer.add(f)
            }

            val totalFrames = encoderBuffer.size / HIDDEN_DIM

            // === STEP 2: POLICY (should we translate?) ===
            val shouldWrite = if (policySession != null) {
                runPolicy(env, totalFrames)
            } else {
                // No policy model — use simple heuristic:
                // Emit every ~1 second of accumulated audio (50 frames at 320 hop)
                totalFrames - writePosition >= 50
            }

            if (!shouldWrite) return null  // Wait for more input

            // === STEP 3: DECODE (features → speech units) ===
            val features = synchronized(encoderBuffer) { encoderBuffer.toFloatArray() }
            val units = runDecoder(env, features, totalFrames) ?: return null

            writePosition = totalFrames

            // === STEP 4: VOCODER (units → waveform) ===
            val waveform = runVocoder(env, units) ?: return null

            return waveform

        } catch (e: Exception) {
            Log.e(TAG, "processChunk error: ${e.message}")
            return null
        }
    }

    /**
     * Flush remaining buffered audio (end of speech).
     */
    fun flush(): ShortArray? {
        if (!isLoaded || encoderBuffer.isEmpty()) return null

        try {
            val env = ortEnv ?: return null
            val features = synchronized(encoderBuffer) { encoderBuffer.toFloatArray() }
            val totalFrames = features.size / HIDDEN_DIM

            if (totalFrames <= writePosition) return null

            val units = runDecoder(env, features, totalFrames) ?: return null
            val waveform = runVocoder(env, units)

            // Reset state for next utterance
            resetState()

            return waveform
        } catch (e: Exception) {
            Log.e(TAG, "flush error: ${e.message}")
            resetState()
            return null
        }
    }

    // ========================================================================
    // MODEL INFERENCE
    // ========================================================================

    private fun runEncoder(env: OrtEnvironment, audio: FloatArray): FloatArray? {
        val session = encoderSession ?: return null

        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())
        )

        return try {
            val results = session.run(mapOf("audio" to inputTensor))
            val output = results[0].value

            // Output shape: [1, T/320, 1024]
            val features = when (output) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr3d = output as Array<Array<FloatArray>>
                    arr3d[0].flatMap { it.toList() }.toFloatArray()
                }
                else -> null
            }

            results.close()
            features
        } catch (e: Exception) {
            Log.e(TAG, "Encoder error: ${e.message}")
            null
        } finally {
            inputTensor.close()
        }
    }

    private fun runPolicy(env: OrtEnvironment, totalFrames: Int): Boolean {
        val session = policySession ?: return totalFrames - writePosition >= 50

        // Policy input: number of available frames and current write position
        val readPos = OnnxTensor.createTensor(env, longArrayOf(totalFrames.toLong()))
        val writePos = OnnxTensor.createTensor(env, longArrayOf(writePosition.toLong()))

        return try {
            val results = session.run(mapOf("read_pos" to readPos, "write_pos" to writePos))
            val decision = (results[0].value as LongArray)[0]
            results.close()
            decision > 0  // 1 = write (translate), 0 = read (wait)
        } catch (e: Exception) {
            Log.e(TAG, "Policy error: ${e.message}")
            totalFrames - writePosition >= 50
        } finally {
            readPos.close()
            writePos.close()
        }
    }

    private fun runDecoder(env: OrtEnvironment, features: FloatArray, numFrames: Int): LongArray? {
        val session = decoderSession ?: return null

        val featureTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features), longArrayOf(1, numFrames.toLong(), HIDDEN_DIM.toLong())
        )
        val srcLang = OnnxTensor.createTensor(env, longArrayOf(srcLangId))
        val tgtLang = OnnxTensor.createTensor(env, longArrayOf(tgtLangId))

        return try {
            val inputs = mutableMapOf(
                "encoder_out" to featureTensor,
                "src_lang" to srcLang,
                "tgt_lang" to tgtLang
            )

            val results = session.run(inputs)
            val units = results[0].value as LongArray

            results.close()
            units
        } catch (e: Exception) {
            Log.e(TAG, "Decoder error: ${e.message}")
            null
        } finally {
            featureTensor.close()
            srcLang.close()
            tgtLang.close()
        }
    }

    private fun runVocoder(env: OrtEnvironment, units: LongArray): ShortArray? {
        val session = vocoderSession ?: return null

        val unitTensor = OnnxTensor.createTensor(
            env, java.nio.LongBuffer.wrap(units), longArrayOf(1, units.size.toLong())
        )

        return try {
            val results = session.run(mapOf("units" to unitTensor))
            val waveform = results[0].value

            val pcm = when (waveform) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = (waveform as Array<FloatArray>)[0]
                    ShortArray(arr.size) { (arr[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                }
                is FloatArray -> {
                    ShortArray(waveform.size) { (waveform[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                }
                else -> null
            }

            results.close()
            pcm
        } catch (e: Exception) {
            Log.e(TAG, "Vocoder error: ${e.message}")
            null
        } finally {
            unitTensor.close()
        }
    }

    // ========================================================================
    // STATE MANAGEMENT
    // ========================================================================

    fun resetState() {
        synchronized(encoderBuffer) { encoderBuffer.clear() }
        decoderState = null
        writePosition = 0
    }

    fun release() {
        resetState()
        encoderSession?.close()
        policySession?.close()
        decoderSession?.close()
        vocoderSession?.close()
        ortEnv?.close()
        isLoaded = false
        Log.i(TAG, "StreamSpeech released")
    }

    // ========================================================================
    // LANGUAGE MAPPING
    // ========================================================================

    private fun langToId(code: String): Long = when (code) {
        "eng" -> 0L; "fra" -> 1L; "deu" -> 2L; "spa" -> 3L
        "ita" -> 4L; "por" -> 5L; "rus" -> 6L; "cmn" -> 7L
        "jpn" -> 8L; "kor" -> 9L; "ara" -> 10L; "hin" -> 11L
        "tha" -> 12L; "vie" -> 13L; "ind" -> 14L
        else -> { Log.w(TAG, "Unknown lang: $code"); 0L }
    }
}
