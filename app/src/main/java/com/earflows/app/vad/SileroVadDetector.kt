package com.earflows.app.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.earflows.app.util.Constants
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Voice Activity Detection using Silero VAD v5 (ONNX).
 *
 * Model: assets/silero_vad.onnx (~2MB)
 * Inputs:
 *   - input: [1, 512] float32 (audio frame, 512 samples = 32ms at 16kHz)
 *   - state: [2, 1, 128] float32 (LSTM hidden state, unified h+c)
 *   - sr: scalar int64 (sample rate = 16000)
 * Outputs:
 *   - output: [1, 1] float32 (speech probability 0.0-1.0)
 *   - stateN: [2, 1, 128] float32 (updated state for next frame)
 */
class SileroVadDetector(private val context: Context) {

    companion object {
        private const val TAG = "SileroVAD"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val FRAME_SIZE = 512       // 32ms at 16kHz
        private const val SR_NODE_VALUE = 16000L
        private const val STATE_DIM = 128        // v5 uses 128 hidden units
    }

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Silero VAD v5: unified state tensor [2, 1, 128]
    private var state: FloatArray = FloatArray(2 * 1 * STATE_DIM)

    // Speech tracking
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
            Log.i(TAG, "Silero VAD v5 initialized (inputs: ${session!!.inputNames})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Silero VAD: ${e.message}")
            false
        }
    }

    fun processChunk(samples: ShortArray, timestampMs: Long): VadResult {
        if (session == null) {
            return VadResult(probability = 1.0f, isSpeech = true, transitioned = false)
        }

        val floatSamples = FloatArray(samples.size) { samples[it].toFloat() / 32768f }

        var maxProb = 0f
        var frameCount = 0
        var frameStart = 0
        while (frameStart + FRAME_SIZE <= floatSamples.size) {
            val frame = floatSamples.copyOfRange(frameStart, frameStart + FRAME_SIZE)
            val prob = runInference(frame)
            maxProb = maxOf(maxProb, prob)
            frameStart += FRAME_SIZE
            frameCount++
        }

        // Log periodically (every ~3s = every ~5 chunks at 640ms)
        if (timestampMs % 3000 < 700) {
            Log.d(TAG, "VAD: maxProb=${"%.3f".format(maxProb)} frames=$frameCount isSpeech=$isSpeechActive ts=$timestampMs")
        }

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

        // Input: [1, 512]
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(frame),
            longArrayOf(1, frame.size.toLong())
        )

        // State: [2, 1, 128] (unified LSTM state)
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, STATE_DIM.toLong())
        )

        // Sample rate: int64 tensor shape=[1]
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(SR_NODE_VALUE)),
            longArrayOf(1)
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr" to srTensor
        )

        return try {
            val results = sess.run(inputs)

            // Output 0: speech probability [1, 1]
            val outputProb = (results[0].value as Array<FloatArray>)[0][0]

            // Output 1: updated state [2, 1, 128]
            val newState = results[1].value
            if (newState is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val stateArr = newState as Array<Array<FloatArray>>
                var idx = 0
                for (layer in stateArr) {
                    for (batch in layer) {
                        for (v in batch) {
                            state[idx++] = v
                        }
                    }
                }
            }

            results.close()
            outputProb
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference error: ${e.message}")
            0f
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    fun resetState() {
        state = FloatArray(2 * 1 * STATE_DIM)
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
    val transitioned: Boolean
)
