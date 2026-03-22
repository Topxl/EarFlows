package com.earflows.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.earflows.app.util.Constants
import java.nio.FloatBuffer

/**
 * Voice Activity Detection using Silero VAD v5 (ONNX).
 *
 * Model file: assets/silero_vad.onnx (~2MB)
 * Input: 512 samples at 16kHz (32ms frames)
 * Output: speech probability [0.0, 1.0]
 *
 * We process chunks in 512-sample frames and return the max speech probability
 * across all frames in the chunk.
 */
class SileroVadDetector(private val context: Context) {

    companion object {
        private const val TAG = "SileroVAD"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val FRAME_SIZE = 512       // 32ms at 16kHz
        private const val SR_NODE_VALUE = 16000L
    }

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Silero VAD internal state (LSTM hidden states)
    private var h: FloatArray = FloatArray(2 * 1 * 64) // 2 layers, batch 1, 64 hidden
    private var c: FloatArray = FloatArray(2 * 1 * 64)

    // Speech tracking state
    private var speechStartMs: Long = 0
    private var silenceStartMs: Long = 0
    var isSpeechActive: Boolean = false
        private set

    fun initialize(): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            session = ortEnv!!.createSession(modelBytes, sessionOptions)
            resetState()
            Log.i(TAG, "Silero VAD initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Silero VAD: ${e.message}")
            // VAD is non-critical — we can fall back to always-on mode
            false
        }
    }

    /**
     * Process a chunk of audio samples and return the detected speech state.
     * @return SpeechSegment if speech transition detected, null otherwise
     */
    fun processChunk(samples: ShortArray, timestampMs: Long): VadResult {
        if (session == null) {
            // If VAD not available, assume always speech (passthrough)
            return VadResult(probability = 1.0f, isSpeech = true, transitioned = false)
        }

        // Convert shorts to floats normalized [-1.0, 1.0]
        val floatSamples = FloatArray(samples.size) { samples[it].toFloat() / 32768f }

        // Process in FRAME_SIZE frames, take max probability
        var maxProb = 0f
        var frameStart = 0
        while (frameStart + FRAME_SIZE <= floatSamples.size) {
            val frame = floatSamples.copyOfRange(frameStart, frameStart + FRAME_SIZE)
            val prob = runInference(frame)
            maxProb = maxOf(maxProb, prob)
            frameStart += FRAME_SIZE
        }

        // State machine: track speech/silence transitions
        val wasSpeech = isSpeechActive
        val currentIsSpeech = maxProb >= Constants.VAD_THRESHOLD

        if (currentIsSpeech) {
            silenceStartMs = 0
            if (!isSpeechActive) {
                if (speechStartMs == 0L) speechStartMs = timestampMs
                if (timestampMs - speechStartMs >= Constants.VAD_MIN_SPEECH_MS) {
                    isSpeechActive = true
                }
            }
        } else {
            speechStartMs = 0
            if (isSpeechActive) {
                if (silenceStartMs == 0L) silenceStartMs = timestampMs
                if (timestampMs - silenceStartMs >= Constants.VAD_MIN_SILENCE_MS) {
                    isSpeechActive = false
                }
            }
        }

        return VadResult(
            probability = maxProb,
            isSpeech = isSpeechActive,
            transitioned = wasSpeech != isSpeechActive
        )
    }

    private fun runInference(frame: FloatArray): Float {
        val env = ortEnv ?: return 0f
        val sess = session ?: return 0f

        // Input tensor: [1, frame_size]
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(frame),
            longArrayOf(1, frame.size.toLong())
        )

        // Hidden state tensors: [2, 1, 64]
        val hTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(h),
            longArrayOf(2, 1, 64)
        )
        val cTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(c),
            longArrayOf(2, 1, 64)
        )

        // Sample rate tensor
        val srTensor = OnnxTensor.createTensor(env, longArrayOf(SR_NODE_VALUE))

        val inputs = mapOf(
            "input" to inputTensor,
            "h" to hTensor,
            "c" to cTensor,
            "sr" to srTensor
        )

        return try {
            val results = sess.run(inputs)

            // Output: probability, updated h, updated c
            val outputProb = (results[0].value as Array<FloatArray>)[0][0]

            // Update LSTM state
            val newH = results[1].value
            val newC = results[2].value
            if (newH is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val hArr = newH as Array<Array<FloatArray>>
                var idx = 0
                for (layer in hArr) for (batch in layer) for (v in batch) h[idx++] = v
            }
            if (newC is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val cArr = newC as Array<Array<FloatArray>>
                var idx = 0
                for (layer in cArr) for (batch in layer) for (v in batch) c[idx++] = v
            }

            results.close()
            outputProb
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference error: ${e.message}")
            0f
        } finally {
            inputTensor.close()
            hTensor.close()
            cTensor.close()
            srTensor.close()
        }
    }

    fun resetState() {
        h = FloatArray(2 * 1 * 64)
        c = FloatArray(2 * 1 * 64)
        isSpeechActive = false
        speechStartMs = 0
        silenceStartMs = 0
    }

    fun release() {
        session?.close()
        ortEnv?.close()
        session = null
        ortEnv = null
        Log.i(TAG, "Silero VAD released")
    }
}

data class VadResult(
    val probability: Float,
    val isSpeech: Boolean,
    val transitioned: Boolean  // true if just switched from speech↔silence
)
