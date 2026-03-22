package com.earflows.app.translation

import kotlinx.coroutines.flow.Flow

/**
 * Core abstraction for speech-to-speech translation engines.
 *
 * Both local (SeamlessStreaming) and cloud (OpenAI Realtime) engines
 * implement this interface, enabling seamless switching and fallback.
 *
 * The streaming contract:
 * - Feed audio chunks via feedAudioChunk()
 * - Collect translated audio via translatedAudioStream
 * - The engine handles buffering, VAD-aware segmentation internally
 */
interface TranslationEngine {

    /** Human-readable engine name for UI */
    val engineName: String

    /** Whether this engine requires internet connectivity */
    val requiresNetwork: Boolean

    /** Current engine state */
    val state: EngineState

    /**
     * Initialize the engine (load model, connect to server, etc.)
     * @param sourceLang ISO 639-3 language code (e.g. "tha")
     * @param targetLang ISO 639-3 language code (e.g. "fra")
     * @return true if initialization succeeded
     */
    suspend fun initialize(sourceLang: String, targetLang: String): Boolean

    /**
     * Feed a chunk of raw PCM audio (16kHz, mono, 16-bit signed).
     * The engine processes it and emits translated audio on translatedAudioStream.
     */
    suspend fun feedAudioChunk(pcmSamples: ShortArray)

    /**
     * Signal end of speech segment (silence detected by VAD).
     * Engine should flush any buffered translation.
     */
    suspend fun flushSegment()

    /**
     * Stream of translated audio PCM chunks (16kHz, mono, 16-bit signed).
     */
    val translatedAudioStream: Flow<TranslatedChunk>

    /**
     * Stream of intermediate text (for UI display, optional).
     */
    val transcriptionStream: Flow<TranscriptionEvent>

    /**
     * Release all resources.
     */
    suspend fun release()
}

/** Translated audio chunk with metadata */
data class TranslatedChunk(
    val pcmData: ShortArray,
    val sampleRate: Int = 16_000,
    val isFinal: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TranslatedChunk) return false
        return pcmData.contentEquals(other.pcmData) && sampleRate == other.sampleRate && isFinal == other.isFinal
    }
    override fun hashCode(): Int = pcmData.contentHashCode()
}

/** Optional text transcription events for UI */
data class TranscriptionEvent(
    val sourceText: String = "",
    val translatedText: String = "",
    val isFinal: Boolean = false
)

enum class EngineState {
    UNINITIALIZED,
    LOADING,
    READY,
    PROCESSING,
    ERROR,
    RELEASED
}
