package com.earflows.app.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * Loads and manages the SeamlessStreaming ONNX model for on-device inference.
 *
 * Model architecture (SeamlessStreaming / SeamlessM4T v2):
 * - Speech Encoder: Conformer-based, encodes audio to hidden representations
 * - Text Decoder with EMMA (Efficient Monotonic Multihead Attention):
 *   enables simultaneous translation (emits tokens while still receiving input)
 * - Unit Vocoder (HiFi-GAN): converts discrete speech units to waveform
 *
 * For mobile deployment:
 * - Model is quantized to INT8 (ONNX quantization) reducing size ~4x
 * - ONNX Runtime Mobile with NNAPI/GPU delegate for hardware acceleration
 * - Model file expected at: app/assets/models/ or downloaded to internal storage
 *
 * IMPORTANT: The actual ONNX model file needs to be exported from
 * facebook/seamless-streaming using the scripts in tools/export_onnx.py
 * This class provides the runtime loading and inference wrapper.
 */
class SeamlessModelLoader(private val context: Context) {

    companion object {
        private const val TAG = "SeamlessModel"

        // Model components (split for mobile efficiency)
        private const val ENCODER_MODEL = "seamless_encoder_q8.onnx"
        private const val DECODER_MODEL = "seamless_decoder_q8.onnx"
        private const val VOCODER_MODEL = "seamless_vocoder_q8.onnx"

        // Single combined model (alternative)
        private const val COMBINED_MODEL = Constants.SEAMLESS_MODEL_FILENAME
    }

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    // Single combined session (if using combined model)
    private var combinedSession: OrtSession? = null

    // Language pair configuration
    private var sourceLangId = 0L
    private var targetLangId = 0L

    // Streaming state: encoder hidden states, decoder cache
    private var encoderStates: OnnxTensor? = null
    private var decoderCache: MutableMap<String, OnnxTensor> = mutableMapOf()

    private var isLoaded = false

    /**
     * Load the ONNX model(s) into memory.
     * Tries combined model first, then split models.
     * Returns false if no model files found.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                // Optimize for mobile
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                // Try to use NNAPI (Android Neural Networks API) for hardware acceleration
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI delegate enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI not available, using CPU: ${e.message}")
                }
            }

            // Strategy 1: Try combined model from internal storage
            val modelDir = File(context.filesDir, Constants.MODEL_DIR)
            val combinedFile = File(modelDir, COMBINED_MODEL)

            if (combinedFile.exists()) {
                combinedSession = ortEnv!!.createSession(
                    combinedFile.absolutePath,
                    sessionOptions
                )
                isLoaded = true
                Log.i(TAG, "Combined model loaded from: ${combinedFile.absolutePath}")
                return@withContext true
            }

            // Strategy 2: Try split models from internal storage
            val encoderFile = File(modelDir, ENCODER_MODEL)
            val decoderFile = File(modelDir, DECODER_MODEL)
            val vocoderFile = File(modelDir, VOCODER_MODEL)

            if (encoderFile.exists() && decoderFile.exists() && vocoderFile.exists()) {
                encoderSession = ortEnv!!.createSession(encoderFile.absolutePath, sessionOptions)
                decoderSession = ortEnv!!.createSession(decoderFile.absolutePath, sessionOptions)
                vocoderSession = ortEnv!!.createSession(vocoderFile.absolutePath, sessionOptions)
                isLoaded = true
                Log.i(TAG, "Split models loaded from: ${modelDir.absolutePath}")
                return@withContext true
            }

            // Strategy 3: Try assets (for bundled small/test models)
            try {
                val assetModels = context.assets.list(Constants.MODEL_DIR) ?: emptyArray()
                if (COMBINED_MODEL in assetModels) {
                    val bytes = context.assets.open("${Constants.MODEL_DIR}/$COMBINED_MODEL").readBytes()
                    combinedSession = ortEnv!!.createSession(bytes, sessionOptions)
                    isLoaded = true
                    Log.i(TAG, "Combined model loaded from assets")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.d(TAG, "No model in assets: ${e.message}")
            }

            Log.w(TAG, "No SeamlessStreaming model found. Place model files in ${modelDir.absolutePath}")
            Log.w(TAG, "Expected: $COMBINED_MODEL or ($ENCODER_MODEL + $DECODER_MODEL + $VOCODER_MODEL)")
            isLoaded = false
            false

        } catch (e: Exception) {
            Log.e(TAG, "Model loading failed: ${e.message}", e)
            isLoaded = false
            false
        }
    }

    /**
     * Set language pair for translation.
     * Maps ISO 639-3 codes to model-internal language IDs.
     */
    fun setLanguagePair(sourceLang: String, targetLang: String) {
        sourceLangId = langCodeToId(sourceLang)
        targetLangId = langCodeToId(targetLang)
        Log.i(TAG, "Language pair: $sourceLang ($sourceLangId) → $targetLang ($targetLangId)")
    }

    /**
     * Run streaming inference: process new audio while maintaining state.
     *
     * This is the core simultaneous translation loop:
     * 1. Encode new audio frames → append to encoder states
     * 2. Run EMMA monotonic attention decoder → get new target tokens (if policy allows)
     * 3. Run vocoder on new tokens → get audio waveform
     *
     * @param audioSamples Full accumulated audio buffer
     * @param offsetSamples Number of samples already processed (only process new ones)
     * @return Translated audio PCM, or null if nothing to emit yet
     */
    suspend fun runStreamingInference(
        audioSamples: ShortArray,
        offsetSamples: Int
    ): ShortArray? = withContext(Dispatchers.Default) {
        if (!isLoaded) return@withContext null

        try {
            val env = ortEnv ?: return@withContext null

            // Convert to float and normalize
            val newSamples = if (offsetSamples < audioSamples.size) {
                audioSamples.copyOfRange(offsetSamples, audioSamples.size)
            } else {
                return@withContext null
            }

            val floatSamples = FloatArray(newSamples.size) { newSamples[it].toFloat() / 32768f }

            // --- Combined model path ---
            combinedSession?.let { session ->
                return@withContext runCombinedInference(env, session, floatSamples, isFinal = false)
            }

            // --- Split model path ---
            if (encoderSession != null && decoderSession != null && vocoderSession != null) {
                return@withContext runSplitInference(env, floatSamples, isFinal = false)
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Streaming inference error: ${e.message}", e)
            null
        }
    }

    /**
     * Run final inference on remaining audio (end of speech segment).
     */
    suspend fun runInference(audioSamples: ShortArray, isFinal: Boolean = true): ShortArray? =
        withContext(Dispatchers.Default) {
            if (!isLoaded || audioSamples.isEmpty()) return@withContext null

            val env = ortEnv ?: return@withContext null
            val floatSamples = FloatArray(audioSamples.size) { audioSamples[it].toFloat() / 32768f }

            try {
                combinedSession?.let { session ->
                    return@withContext runCombinedInference(env, session, floatSamples, isFinal)
                }

                if (encoderSession != null) {
                    return@withContext runSplitInference(env, floatSamples, isFinal)
                }

                null
            } catch (e: Exception) {
                Log.e(TAG, "Final inference error: ${e.message}", e)
                null
            }
        }

    /**
     * Run inference using the combined model.
     * Input: audio_features [1, T] float32
     * Input: source_lang [1] int64
     * Input: target_lang [1] int64
     * Output: waveform [1, T'] float32
     */
    private fun runCombinedInference(
        env: OrtEnvironment,
        session: OrtSession,
        audioSamples: FloatArray,
        isFinal: Boolean
    ): ShortArray? {
        val audioTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(audioSamples),
            longArrayOf(1, audioSamples.size.toLong())
        )

        val srcLangTensor = OnnxTensor.createTensor(env, longArrayOf(sourceLangId))
        val tgtLangTensor = OnnxTensor.createTensor(env, longArrayOf(targetLangId))

        val inputs = mutableMapOf<String, OnnxTensor>(
            "audio_input" to audioTensor,
            "source_lang" to srcLangTensor,
            "target_lang" to tgtLangTensor
        )

        return try {
            val results = session.run(inputs)

            // Extract waveform output
            val waveform = results[0].value
            val outputPcm = when (waveform) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val floatArr = (waveform as Array<FloatArray>)[0]
                    floatToShortPcm(floatArr)
                }
                is FloatArray -> floatToShortPcm(waveform)
                else -> null
            }

            results.close()
            outputPcm
        } catch (e: Exception) {
            Log.e(TAG, "Combined inference error: ${e.message}")
            null
        } finally {
            audioTensor.close()
            srcLangTensor.close()
            tgtLangTensor.close()
        }
    }

    /**
     * Run inference using split encoder → decoder → vocoder pipeline.
     */
    private fun runSplitInference(
        env: OrtEnvironment,
        audioSamples: FloatArray,
        isFinal: Boolean
    ): ShortArray? {
        // Step 1: Encode audio
        val audioTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(audioSamples),
            longArrayOf(1, audioSamples.size.toLong())
        )

        val encoderInputs = mapOf("audio_input" to audioTensor)
        val encoderResults = encoderSession!!.run(encoderInputs)
        val encodedFeatures = encoderResults[0] as OnnxTensor

        // Step 2: Decode (with EMMA monotonic attention for streaming)
        val srcLangTensor = OnnxTensor.createTensor(env, longArrayOf(sourceLangId))
        val tgtLangTensor = OnnxTensor.createTensor(env, longArrayOf(targetLangId))

        val decoderInputs = mutableMapOf(
            "encoder_output" to encodedFeatures,
            "source_lang" to srcLangTensor,
            "target_lang" to tgtLangTensor
        )

        val decoderResults = decoderSession!!.run(decoderInputs)
        val unitTokens = decoderResults[0] as OnnxTensor

        // Step 3: Vocoder (units → waveform)
        val vocoderInputs = mapOf("units" to unitTokens)
        val vocoderResults = vocoderSession!!.run(vocoderInputs)

        val waveform = vocoderResults[0].value
        val outputPcm = when (waveform) {
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                val floatArr = (waveform as Array<FloatArray>)[0]
                floatToShortPcm(floatArr)
            }
            is FloatArray -> floatToShortPcm(waveform)
            else -> null
        }

        // Cleanup
        audioTensor.close()
        encoderResults.close()
        srcLangTensor.close()
        tgtLangTensor.close()
        decoderResults.close()
        vocoderResults.close()

        return outputPcm
    }

    /**
     * Reset streaming state between speech segments.
     */
    fun resetStreamingState() {
        encoderStates?.close()
        encoderStates = null
        decoderCache.values.forEach { it.close() }
        decoderCache.clear()
    }

    fun release() {
        resetStreamingState()
        combinedSession?.close()
        encoderSession?.close()
        decoderSession?.close()
        vocoderSession?.close()
        ortEnv?.close()
        combinedSession = null
        encoderSession = null
        decoderSession = null
        vocoderSession = null
        ortEnv = null
        isLoaded = false
        Log.i(TAG, "Model released")
    }

    /** Convert float32 waveform [-1.0, 1.0] to 16-bit PCM */
    private fun floatToShortPcm(floats: FloatArray): ShortArray {
        return ShortArray(floats.size) { i ->
            (floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    /**
     * Map ISO 639-3 language codes to SeamlessM4T internal IDs.
     * These IDs match the model's language embedding table.
     */
    private fun langCodeToId(code: String): Long = when (code) {
        "eng" -> 0L
        "fra" -> 1L
        "deu" -> 2L
        "spa" -> 3L
        "ita" -> 4L
        "por" -> 5L
        "rus" -> 6L
        "cmn" -> 7L    // Mandarin
        "jpn" -> 8L
        "kor" -> 9L
        "ara" -> 10L
        "hin" -> 11L
        "tha" -> 12L
        "vie" -> 13L
        "ind" -> 14L   // Indonesian
        "tur" -> 15L
        "pol" -> 16L
        "ukr" -> 17L
        "nld" -> 18L   // Dutch
        "swe" -> 19L   // Swedish
        else -> {
            Log.w(TAG, "Unknown language code: $code, defaulting to 0 (eng)")
            0L
        }
    }
}
