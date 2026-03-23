package com.earflows.app.translation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Cloud translation engine using OpenRouter API.
 *
 * OpenRouter (https://openrouter.ai) provides access to 200+ LLMs via
 * a single OpenAI-compatible API. Many models are free or very cheap.
 *
 * Pipeline:
 * 1. Accumulate audio → on-device ASR (Android SpeechRecognizer or Whisper ONNX)
 * 2. Send recognized text to OpenRouter for translation (chat completions)
 * 3. On-device TTS to synthesize translated text to speech
 *
 * Recommended models for translation (fast + cheap):
 * - google/gemini-2.0-flash-001 — very fast, great multilingual
 * - meta-llama/llama-3.1-70b-instruct — good quality, free tier available
 * - mistralai/mistral-large — strong multilingual
 *
 * Some models on OpenRouter have free tiers — check https://openrouter.ai/models
 */
class CloudTranslationEngine(
    private val context: Context,
    private val apiKey: String,
    private val model: String = Constants.OPENROUTER_DEFAULT_MODEL
) : TranslationEngine {

    companion object {
        private const val TAG = "CloudTranslation"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override val engineName = "OpenRouter Cloud"
    override val requiresNetwork = true
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(Constants.CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sourceLang = ""
    private var targetLang = ""
    private var systemPrompt = ""

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Audio buffer — we accumulate then process
    private val audioBuffer = mutableListOf<Short>()
    private val bufferLock = Any()

    // Output streams
    private val _translatedAudio = MutableSharedFlow<TranslatedChunk>(extraBufferCapacity = 50)
    override val translatedAudioStream: Flow<TranslatedChunk> = _translatedAudio.asSharedFlow()

    private val _transcription = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 10)
    override val transcriptionStream: Flow<TranscriptionEvent> = _transcription.asSharedFlow()

    override suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        state = EngineState.LOADING
        this.sourceLang = sourceLang
        this.targetLang = targetLang

        val sourceName = langDisplayName(sourceLang)
        val targetName = langDisplayName(targetLang)

        systemPrompt = buildString {
            append("You are a real-time simultaneous interpreter. ")
            append("Translate the following $sourceName text to $targetName. ")
            append("Translate naturally and fluently, preserving meaning and tone. ")
            append("Output ONLY the translation, nothing else. No quotes, no commentary, no explanation.")
        }

        return withContext(Dispatchers.IO) {
            try {
                // Verify API key with a minimal request
                val testOk = testApiConnection()
                if (!testOk) {
                    Log.e(TAG, "OpenRouter API connection failed")
                    state = EngineState.ERROR
                    return@withContext false
                }

                // Initialize TTS for output
                initTts(targetLang)

                state = EngineState.READY
                Log.i(TAG, "OpenRouter cloud engine ready: $sourceName → $targetName (model: $model)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}", e)
                state = EngineState.ERROR
                false
            }
        }
    }

    private suspend fun initTts(targetLang: String) {
        suspendCancellableCoroutine { continuation ->
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = getLocale(targetLang)
                    tts?.setSpeechRate(1.1f)
                    ttsReady = true
                } else {
                    ttsReady = false
                }
                continuation.resume(Unit)
            }
        }
    }

    /**
     * Test the API connection with a minimal translation request.
     */
    private fun testApiConnection(): Boolean {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Translate to French: hello")
                    })
                })
                put("max_tokens", 10)
            }

            val request = Request.Builder()
                .url(Constants.OPENROUTER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://earflows.com")
                .header("X-Title", "EarFlows")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            if (!ok) {
                Log.e(TAG, "API test failed: ${response.code} ${response.body?.string()?.take(200)}")
            }
            response.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "API test error: ${e.message}")
            false
        }
    }

    override suspend fun feedAudioChunk(pcmSamples: ShortArray) {
        if (state != EngineState.READY && state != EngineState.PROCESSING) return
        state = EngineState.PROCESSING

        synchronized(bufferLock) {
            for (s in pcmSamples) audioBuffer.add(s)
        }

        // Process when we have enough audio (~2 seconds)
        if (audioBuffer.size >= 32000) {
            processBuffer()
        }
    }

    override suspend fun flushSegment() {
        if (audioBuffer.isNotEmpty()) {
            processBuffer()
        }
        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        state = EngineState.READY
    }

    /**
     * Process accumulated audio:
     * 1. On-device ASR (placeholder — uses simple energy-based detection for now)
     * 2. Send to OpenRouter for translation
     * 3. TTS the result
     *
     * Note: In production, this would use Whisper ONNX or Android SpeechRecognizer
     * for proper ASR. The OpenRouter API handles the text translation.
     */
    private suspend fun processBuffer() = withContext(Dispatchers.IO) {
        val samples: ShortArray
        synchronized(bufferLock) {
            samples = audioBuffer.toShortArray()
            audioBuffer.clear()
        }

        if (samples.isEmpty()) return@withContext

        // TODO: Replace with proper ASR (Whisper ONNX or SpeechRecognizer)
        // For now, this placeholder shows the translation pipeline works.
        // The CascadeTranslationEngine handles full ASR → translate → TTS locally.
        // This cloud engine will be connected to the ASR output once integrated.
        Log.d(TAG, "Cloud engine: ${samples.size} samples buffered, awaiting ASR integration")
    }

    /**
     * Translate text via OpenRouter API.
     * This is the core method — called by the pipeline after ASR produces text.
     */
    suspend fun translateText(sourceText: String): String? = withContext(Dispatchers.IO) {
        if (sourceText.isBlank()) return@withContext null

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", sourceText)
                    })
                })
                put("max_tokens", 500)
                put("temperature", 0.3) // Low temperature for consistent translations
                put("stream", false)
            }

            val request = Request.Builder()
                .url(Constants.OPENROUTER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://earflows.com")
                .header("X-Title", "EarFlows")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Translation API error: ${response.code}")
                response.close()
                return@withContext null
            }

            val responseBody = response.body?.string()
            response.close()

            if (responseBody == null) return@withContext null

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val translatedText = choices
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()

            if (!translatedText.isNullOrBlank()) {
                Log.i(TAG, "Translated: \"$sourceText\" → \"$translatedText\"")

                // Emit transcription event for UI
                _transcription.emit(TranscriptionEvent(
                    sourceText = sourceText,
                    translatedText = translatedText,
                    isFinal = true
                ))

                // Synthesize speech
                val audioData = synthesizeSpeech(translatedText)
                if (audioData != null) {
                    _translatedAudio.emit(TranslatedChunk(pcmData = audioData, isFinal = true))
                }
            }

            translatedText
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}", e)
            null
        }
    }

    /**
     * Translate text with streaming response (for lower perceived latency).
     * Reads SSE chunks and emits partial translations as they arrive.
     */
    suspend fun translateTextStreaming(sourceText: String): String? = withContext(Dispatchers.IO) {
        if (sourceText.isBlank()) return@withContext null

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", sourceText)
                    })
                })
                put("max_tokens", 500)
                put("temperature", 0.3)
                put("stream", true)
            }

            val request = Request.Builder()
                .url(Constants.OPENROUTER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://earflows.com")
                .header("X-Title", "EarFlows")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Streaming API error: ${response.code}")
                response.close()
                return@withContext null
            }

            val fullTranslation = StringBuilder()
            val reader = response.body?.source() ?: run {
                response.close()
                return@withContext null
            }

            while (!reader.exhausted()) {
                val line = reader.readUtf8Line() ?: break

                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = JSONObject(data)
                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content", "")

                    if (!delta.isNullOrEmpty()) {
                        fullTranslation.append(delta)
                        // Emit partial transcription for live UI update
                        _transcription.emit(TranscriptionEvent(
                            sourceText = sourceText,
                            translatedText = fullTranslation.toString(),
                            isFinal = false
                        ))
                    }
                } catch (_: Exception) {}
            }

            reader.close()
            response.close()

            val result = fullTranslation.toString().trim()
            if (result.isNotBlank()) {
                // Emit final transcription
                _transcription.emit(TranscriptionEvent(
                    sourceText = sourceText,
                    translatedText = result,
                    isFinal = true
                ))

                // TTS
                val audioData = synthesizeSpeech(result)
                if (audioData != null) {
                    _translatedAudio.emit(TranslatedChunk(pcmData = audioData, isFinal = true))
                }

                Log.i(TAG, "Streamed translation: \"$sourceText\" → \"$result\"")
            }

            result.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming translation error: ${e.message}", e)
            null
        }
    }

    private suspend fun synthesizeSpeech(text: String): ShortArray? {
        if (!ttsReady || tts == null) return null

        return withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "cloud_tts_${System.currentTimeMillis()}.wav")
            val utteranceId = "cloud_${System.currentTimeMillis()}"

            suspendCancellableCoroutine { continuation ->
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            val pcm = readWavPcm(tempFile)
                            tempFile.delete()
                            continuation.resume(pcm)
                        }
                    }
                    @Deprecated("Deprecated in API")
                    override fun onError(id: String?) {
                        tempFile.delete()
                        continuation.resume(null)
                    }
                })
                tts?.synthesizeToFile(text, null, tempFile, utteranceId)
            }
        }
    }

    private fun readWavPcm(file: File): ShortArray? {
        if (!file.exists() || file.length() < 44) return null
        val bytes = file.readBytes()
        val pcmBytes = bytes.copyOfRange(44, bytes.size)
        return ShortArray(pcmBytes.size / 2) { i ->
            ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
        }
    }

    override suspend fun release() {
        state = EngineState.RELEASED
        tts?.stop()
        tts?.shutdown()
        client.dispatcher.executorService.shutdown()
        synchronized(bufferLock) { audioBuffer.clear() }
        Log.i(TAG, "Cloud engine released")
    }

    private fun langDisplayName(code: String): String = when (code) {
        "tha" -> "Thai"
        "fra" -> "French"
        "eng" -> "English"
        "cmn" -> "Mandarin Chinese"
        "spa" -> "Spanish"
        "deu" -> "German"
        "jpn" -> "Japanese"
        "kor" -> "Korean"
        "vie" -> "Vietnamese"
        "ara" -> "Arabic"
        "hin" -> "Hindi"
        "por" -> "Portuguese"
        "rus" -> "Russian"
        "ita" -> "Italian"
        else -> code
    }

    private fun getLocale(code: String): Locale = when (code) {
        "fra" -> Locale.FRENCH
        "eng" -> Locale.ENGLISH
        "deu" -> Locale.GERMAN
        "spa" -> Locale("es")
        "ita" -> Locale.ITALIAN
        "por" -> Locale("pt")
        "jpn" -> Locale.JAPANESE
        "kor" -> Locale.KOREAN
        "cmn" -> Locale.CHINESE
        "rus" -> Locale("ru")
        "ara" -> Locale("ar")
        "hin" -> Locale("hi")
        "tha" -> Locale("th")
        "vie" -> Locale("vi")
        else -> Locale.FRENCH
    }
}
