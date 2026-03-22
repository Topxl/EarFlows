package com.earflows.app.translation

import android.util.Base64
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Cloud-based translation engine using OpenAI Realtime API.
 *
 * Uses WebSocket streaming for ultra-low latency:
 * - Sends raw audio chunks as base64 PCM
 * - Receives translated audio chunks in real-time
 * - Supports simultaneous interpretation via the realtime model
 *
 * Protocol: OpenAI Realtime API (WebSocket)
 * - Send: input_audio_buffer.append (base64 PCM 16kHz mono 16-bit)
 * - Receive: response.audio.delta (base64 PCM 24kHz mono 16-bit)
 */
class CloudTranslationEngine(
    private val apiKey: String
) : TranslationEngine {

    companion object {
        private const val TAG = "CloudTranslation"
    }

    override val engineName = "OpenAI Realtime (Cloud)"
    override val requiresNetwork = true
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no timeout
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sourceLang = ""
    private var targetLang = ""

    // Output streams
    private val _translatedAudio = MutableSharedFlow<TranslatedChunk>(extraBufferCapacity = 50)
    override val translatedAudioStream: Flow<TranslatedChunk> = _translatedAudio.asSharedFlow()

    private val _transcription = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 10)
    override val transcriptionStream: Flow<TranscriptionEvent> = _transcription.asSharedFlow()

    override suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        state = EngineState.LOADING
        this.sourceLang = sourceLang
        this.targetLang = targetLang

        return try {
            connectWebSocket()
            true
        } catch (e: Exception) {
            state = EngineState.ERROR
            Log.e(TAG, "Failed to connect: ${e.message}", e)
            false
        }
    }

    private fun connectWebSocket() {
        // Map ISO 639-3 to display names for the prompt
        val sourceName = langDisplayName(sourceLang)
        val targetName = langDisplayName(targetLang)

        val request = Request.Builder()
            .url("${Constants.OPENAI_REALTIME_WS_URL}?model=gpt-4o-realtime-preview")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                state = EngineState.READY

                // Configure session for translation
                val sessionConfig = JSONObject().apply {
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("modalities", org.json.JSONArray(listOf("audio", "text")))
                        put("instructions", buildString {
                            append("You are a real-time simultaneous interpreter. ")
                            append("Listen to $sourceName speech and translate it to $targetName. ")
                            append("Translate naturally and fluently, preserving meaning and tone. ")
                            append("Begin translating immediately as you hear speech — do not wait for complete sentences. ")
                            append("Output only the translation, no commentary.")
                        })
                        put("input_audio_format", "pcm16")
                        put("output_audio_format", "pcm16")
                        put("input_audio_transcription", JSONObject().apply {
                            put("model", "whisper-1")
                        })
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 500)
                        })
                    })
                }
                webSocket.send(sessionConfig.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                state = EngineState.ERROR
                // Auto-reconnect
                scope.launch {
                    delay(Constants.CLOUD_RECONNECT_DELAY_MS)
                    if (state == EngineState.ERROR) {
                        Log.i(TAG, "Attempting reconnect...")
                        connectWebSocket()
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                if (state != EngineState.RELEASED) {
                    state = EngineState.ERROR
                }
            }
        })
    }

    override suspend fun feedAudioChunk(pcmSamples: ShortArray) {
        if (state != EngineState.READY && state != EngineState.PROCESSING) return
        state = EngineState.PROCESSING

        // Convert ShortArray to byte array (little-endian 16-bit PCM)
        val byteBuffer = ByteBuffer.allocate(pcmSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmSamples) byteBuffer.putShort(sample)
        val base64Audio = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)

        // Send audio to OpenAI Realtime API
        val message = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        webSocket?.send(message.toString())
    }

    override suspend fun flushSegment() {
        // Commit the audio buffer — signals end of user turn
        val commit = JSONObject().apply {
            put("type", "input_audio_buffer.commit")
        }
        webSocket?.send(commit.toString())
        state = EngineState.READY
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "response.audio.delta" -> {
                    // Received translated audio chunk
                    val base64Audio = json.optString("delta", "")
                    if (base64Audio.isNotEmpty()) {
                        val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                        val shorts = bytesToShorts(audioBytes)
                        scope.launch {
                            _translatedAudio.emit(TranslatedChunk(pcmData = shorts))
                        }
                    }
                }

                "response.audio.done" -> {
                    scope.launch {
                        _translatedAudio.emit(TranslatedChunk(pcmData = ShortArray(0), isFinal = true))
                    }
                }

                "response.audio_transcript.delta" -> {
                    val transcript = json.optString("delta", "")
                    if (transcript.isNotEmpty()) {
                        scope.launch {
                            _transcription.emit(TranscriptionEvent(translatedText = transcript))
                        }
                    }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val sourceText = json.optString("transcript", "")
                    scope.launch {
                        _transcription.emit(TranscriptionEvent(sourceText = sourceText, isFinal = true))
                    }
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    Log.e(TAG, "API error: ${error?.optString("message")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}")
        }
    }

    override suspend fun release() {
        state = EngineState.RELEASED
        webSocket?.close(1000, "Release")
        webSocket = null
        client.dispatcher.executorService.shutdown()
        Log.i(TAG, "Cloud engine released")
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) {
            shorts[i] = buffer.short
        }
        return shorts
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
}
