package com.earflows.app.translation

import android.content.Context
import android.util.Log
import com.earflows.app.model.SeamlessModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Local (offline) translation engine using Meta SeamlessStreaming.
 *
 * Architecture:
 * - Uses ONNX Runtime Mobile with a quantized SeamlessStreaming model
 * - Speech-to-speech: audio in → audio out (no intermediate text required)
 * - Simultaneous/streaming: begins emitting translated audio before input ends
 *
 * The model pipeline:
 * 1. Speech Encoder: raw audio → speech representations
 * 2. Monotonic attention decoder: streaming text generation (EMMA policy)
 * 3. Unit-based vocoder (HiFi-GAN): text units → speech audio
 *
 * Fallback strategy: if the full Seamless model can't load (RAM/storage),
 * we fall back to a cascaded pipeline: Whisper tiny → NLLB → TTS
 * (not yet implemented — placeholder for v2).
 */
class LocalTranslationEngine(
    private val context: Context,
    private val modelLoader: SeamlessModelLoader
) : TranslationEngine {

    companion object {
        private const val TAG = "LocalTranslation"
    }

    override val engineName = "SeamlessStreaming (Local)"
    override val requiresNetwork = false
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private var sourceLang = ""
    private var targetLang = ""

    // Accumulate audio for processing (streaming window)
    private val audioBuffer = mutableListOf<Short>()
    private val bufferLock = Any()

    // Output streams
    private val _translatedAudio = MutableSharedFlow<TranslatedChunk>(extraBufferCapacity = 20)
    override val translatedAudioStream: Flow<TranslatedChunk> = _translatedAudio.asSharedFlow()

    private val _transcription = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 10)
    override val transcriptionStream: Flow<TranscriptionEvent> = _transcription.asSharedFlow()

    // Streaming state: how many samples have been "consumed" by the model
    private var processedSampleCount = 0
    private val streamingWindowSamples = 16_000 * 2  // 2 seconds of audio context

    override suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        state = EngineState.LOADING
        this.sourceLang = sourceLang
        this.targetLang = targetLang

        return withContext(Dispatchers.IO) {
            try {
                val loaded = modelLoader.loadModel()
                if (loaded) {
                    modelLoader.setLanguagePair(sourceLang, targetLang)
                    state = EngineState.READY
                    Log.i(TAG, "Local engine ready: $sourceLang → $targetLang")
                    true
                } else {
                    state = EngineState.ERROR
                    Log.e(TAG, "Failed to load SeamlessStreaming model")
                    false
                }
            } catch (e: Exception) {
                state = EngineState.ERROR
                Log.e(TAG, "Initialization error: ${e.message}", e)
                false
            }
        }
    }

    override suspend fun feedAudioChunk(pcmSamples: ShortArray) {
        if (state != EngineState.READY && state != EngineState.PROCESSING) return
        state = EngineState.PROCESSING

        synchronized(bufferLock) {
            for (s in pcmSamples) audioBuffer.add(s)
        }

        // Process in streaming fashion: run model on accumulated audio
        // SeamlessStreaming uses monotonic attention — it can emit partial translations
        // as audio accumulates without waiting for end of sentence
        processStreamingWindow()
    }

    override suspend fun flushSegment() {
        if (state != EngineState.PROCESSING) return

        // Force process remaining audio
        withContext(Dispatchers.Default) {
            val remainingSamples: ShortArray
            synchronized(bufferLock) {
                remainingSamples = audioBuffer.toShortArray()
                audioBuffer.clear()
                processedSampleCount = 0
            }

            if (remainingSamples.isNotEmpty()) {
                val result = modelLoader.runInference(remainingSamples, isFinal = true)
                if (result != null) {
                    _translatedAudio.emit(TranslatedChunk(pcmData = result, isFinal = true))
                }
            }

            // Reset model streaming state for next segment
            modelLoader.resetStreamingState()
        }

        state = EngineState.READY
    }

    /**
     * Simultaneous streaming: process audio as it arrives, emit partial translations.
     * SeamlessStreaming's EMMA monotonic attention decides when to emit.
     */
    private suspend fun processStreamingWindow() = withContext(Dispatchers.Default) {
        val currentBuffer: ShortArray
        synchronized(bufferLock) {
            if (audioBuffer.size - processedSampleCount < 8000) return@withContext // Need at least 500ms new audio
            currentBuffer = audioBuffer.toShortArray()
        }

        try {
            // Run inference on the full accumulated buffer
            // The model internally handles the monotonic attention policy
            val translatedPcm = modelLoader.runStreamingInference(
                audioSamples = currentBuffer,
                offsetSamples = processedSampleCount
            )

            if (translatedPcm != null && translatedPcm.isNotEmpty()) {
                _translatedAudio.emit(TranslatedChunk(pcmData = translatedPcm, isFinal = false))
            }

            synchronized(bufferLock) {
                processedSampleCount = currentBuffer.size
                // Trim buffer if it gets too long (keep sliding window)
                if (audioBuffer.size > streamingWindowSamples * 2) {
                    val trimCount = audioBuffer.size - streamingWindowSamples
                    repeat(trimCount) { audioBuffer.removeFirst() }
                    processedSampleCount = (processedSampleCount - trimCount).coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming inference error: ${e.message}", e)
        }
    }

    override suspend fun release() {
        state = EngineState.RELEASED
        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        modelLoader.release()
        Log.i(TAG, "Local engine released")
    }
}
