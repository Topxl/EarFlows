package com.earflows.app.translation

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import com.earflows.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Definitive real-time translation engine.
 *
 * Architecture (no SpeechRecognizer — full control):
 * 1. AudioRecord captures from PHONE MIC (not Bluetooth) at 16kHz
 * 2. Energy-based speech detection (simple RMS threshold)
 * 3. When speech detected, accumulate audio
 * 4. On silence after speech, encode to WAV → base64
 * 5. Send to OpenRouter (Gemini Flash) for transcription + translation in ONE call
 * 6. TTS speaks the translation
 *
 * This bypasses SpeechRecognizer entirely — we control the mic, the VAD, and the API.
 */
class RealtimeTranslationEngine(
    private val context: Context,
    private val apiKey: String?,
    private val model: String = Constants.OPENROUTER_DEFAULT_MODEL
) : TranslationEngine {

    companion object {
        private const val TAG = "RealtimeEngine"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 30              // 30ms frames for energy detection
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000  // 480 samples

        // VAD parameters — tuned for ambient Thai conversation
        private const val SILENCE_FRAMES = 25        // ~750ms of silence = end of utterance
        private const val MIN_SPEECH_FRAMES = 20     // ~600ms minimum speech
        private const val MAX_SPEECH_SECONDS = 4     // Max 4s per segment — translate often

        // Adaptive noise floor — computed at runtime
        private const val NOISE_FLOOR_MULTIPLIER = 2.5  // Speech must be 2.5x above noise floor
        private const val NOISE_FLOOR_WINDOW = 100      // Frames to compute baseline noise (~3s)
    }

    override val engineName = "EarFlows Realtime"
    override val requiresNetwork = true
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Echo cancellation: mute capture while TTS is speaking to avoid feedback loop
    @Volatile private var isTtsSpeaking = false

    private var sourceLang = ""
    private var targetLang = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Output streams
    private val _translatedAudio = MutableSharedFlow<TranslatedChunk>(extraBufferCapacity = 20)
    override val translatedAudioStream: Flow<TranslatedChunk> = _translatedAudio.asSharedFlow()

    private val _transcription = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 10)
    override val transcriptionStream: Flow<TranscriptionEvent> = _transcription.asSharedFlow()

    // Raw audio stream for parallel recording (exposed to service)
    private val _rawAudioChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 20)
    val rawAudioChunks: Flow<ByteArray> = _rawAudioChunks.asSharedFlow()

    // === DEBUG STATE ===
    data class PipelineDebugState(
        val asrStatus: String = "OFF",
        val asrRmsDb: Float = 0f,
        val lastSourceText: String = "",
        val lastTranslatedText: String = "",
        val translateLatencyMs: Long = 0,
        val firstTokenLatencyMs: Long = 0,  // Time to first SSE token
        val ttsStatus: String = "OFF",
        val totalTranslations: Int = 0,
        val totalErrors: Int = 0,
        val lastError: String = "",
        // Metrics
        val totalSpeechSegments: Int = 0,
        val totalApiCalls: Int = 0,
        val avgLatencyMs: Long = 0,
        val micSource: String = "---",
        val isCapturing: Boolean = false
    )

    private val _debugState = MutableStateFlow(PipelineDebugState())
    val debugState = _debugState.asStateFlow()

    private var totalLatency = 0L
    private var latencyCount = 0

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        state = EngineState.LOADING
        this.sourceLang = sourceLang
        this.targetLang = targetLang

        try {
            // 1. Force audio routing to phone mic (not Bluetooth)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // MODE_NORMAL ensures we use the phone's main mic, not BT SCO
            audioManager.mode = AudioManager.MODE_NORMAL

            // 2. Initialize AudioRecord — phone mic, 16kHz mono
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Try CAMCORDER first (always uses phone's built-in mic, ignores BT)
            // Fallback to UNPROCESSED, then MIC
            val audioSources = listOf(
                MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER (phone mic)",
                MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED (raw phone mic)",
                MediaRecorder.AudioSource.MIC to "MIC (default)"
            )
            var selectedSource = "none"
            for ((source, name) in audioSources) {
                try {
                    audioRecord = AudioRecord(
                        source, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf * 2, FRAME_SAMPLES * 4 * 2)
                    )
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        selectedSource = name
                        Log.i(TAG, "Mic source: $name")
                        break
                    } else {
                        audioRecord?.release()
                        audioRecord = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Source $name failed: ${e.message}")
                }
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                _debugState.value = _debugState.value.copy(lastError = "Mic init failed")
                state = EngineState.ERROR
                return false
            }

            _debugState.value = _debugState.value.copy(micSource = selectedSource)
            Log.i(TAG, "AudioRecord initialized: $selectedSource, 16kHz")

            // 3. Initialize TTS
            initTts(targetLang)

            // 4. Verify API key
            if (apiKey.isNullOrBlank()) {
                Log.e(TAG, "No API key")
                _debugState.value = _debugState.value.copy(lastError = "No OpenRouter API key")
                state = EngineState.ERROR
                return false
            }

            state = EngineState.READY
            _debugState.value = _debugState.value.copy(asrStatus = "READY")
            Log.i(TAG, "Engine ready: $sourceLang → $targetLang")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            _debugState.value = _debugState.value.copy(lastError = "Init: ${e.message}")
            state = EngineState.ERROR
            return false
        }
    }

    private suspend fun initTts(targetLang: String) {
        suspendCancellableCoroutine { cont ->
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = getLocale(targetLang)
                    tts?.setSpeechRate(1.1f)
                    ttsReady = true

                    // Echo cancellation: track when TTS starts/stops speaking
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isTtsSpeaking = true
                            _debugState.value = _debugState.value.copy(ttsStatus = "SPEAKING")
                        }
                        override fun onDone(utteranceId: String?) {
                            isTtsSpeaking = false
                            _debugState.value = _debugState.value.copy(ttsStatus = "READY")
                        }
                        @Deprecated("Deprecated") override fun onError(utteranceId: String?) {
                            isTtsSpeaking = false
                        }
                    })

                    _debugState.value = _debugState.value.copy(ttsStatus = "READY")
                    Log.i(TAG, "TTS ready (with echo cancellation)")
                } else {
                    _debugState.value = _debugState.value.copy(ttsStatus = "ERROR")
                }
                cont.resume(Unit)
            }
        }
    }

    /**
     * Start the capture + detection + translation loop.
     * This is the main pipeline — runs until stopped.
     */
    private fun startPipeline() {
        if (captureJob?.isActive == true) return

        captureJob = scope.launch {
            val recorder = audioRecord ?: return@launch
            recorder.startRecording()
            _debugState.value = _debugState.value.copy(isCapturing = true, asrStatus = "CALIBRATING")
            Log.i(TAG, "Pipeline started — calibrating noise floor...")

            val frame = ShortArray(FRAME_SAMPLES)
            val speechBuffer = mutableListOf<Short>()
            var speechFrameCount = 0
            var silenceFrameCount = 0
            var isSpeaking = false

            // Adaptive noise floor
            val noiseHistory = ArrayDeque<Double>(NOISE_FLOOR_WINDOW)
            var noiseFloor = 400.0  // Initial estimate, updated every frame
            var frameCount = 0L

            while (isActive) {
                val read = recorder.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) {
                    delay(10)
                    continue
                }

                // Emit raw audio for parallel recording (always, even during TTS)
                val rawBytes = ByteArray(read * 2)
                for (i in 0 until read) {
                    rawBytes[i * 2] = (frame[i].toInt() and 0xFF).toByte()
                    rawBytes[i * 2 + 1] = (frame[i].toInt() shr 8 and 0xFF).toByte()
                }
                _rawAudioChunks.tryEmit(rawBytes)

                // Echo cancellation: skip TRANSLATION while TTS is speaking
                // This prevents the mic from picking up our own translation output
                if (isTtsSpeaking) {
                    continue
                }

                frameCount++

                // Compute RMS energy
                var sum = 0.0
                for (i in 0 until read) { val s = frame[i].toDouble(); sum += s * s }
                val rms = sqrt(sum / read)
                val rmsDb = (20 * Math.log10(rms + 1)).toFloat()

                // Update noise floor (running minimum of recent frames)
                if (!isSpeaking) {
                    noiseHistory.addLast(rms)
                    if (noiseHistory.size > NOISE_FLOOR_WINDOW) noiseHistory.removeFirst()
                    if (noiseHistory.size >= 10) {
                        // Noise floor = 30th percentile of recent silence frames
                        val sorted = noiseHistory.sorted()
                        noiseFloor = sorted[(sorted.size * 0.3).toInt()]
                    }
                }

                val speechThreshold = noiseFloor * NOISE_FLOOR_MULTIPLIER
                val isSpeechFrame = rms > speechThreshold

                // Update debug
                _debugState.value = _debugState.value.copy(
                    asrRmsDb = rmsDb,
                    asrStatus = when {
                        frameCount < 30 -> "CALIBRATING"
                        isSpeaking -> "SPEECH"
                        else -> "LISTENING (noise=${noiseFloor.toInt()}, thresh=${speechThreshold.toInt()})"
                    }
                )

                if (isSpeechFrame) {
                    silenceFrameCount = 0
                    speechFrameCount++

                    if (!isSpeaking) {
                        isSpeaking = true
                        speechBuffer.clear()
                        Log.d(TAG, "Speech started (rms=${rms.toInt()}, threshold=${speechThreshold.toInt()}, noise=${noiseFloor.toInt()})")
                    }

                    // Add frame to buffer
                    for (i in 0 until read) speechBuffer.add(frame[i])

                    // Max segment — process in parallel, keep listening
                    if (speechBuffer.size > SAMPLE_RATE * MAX_SPEECH_SECONDS) {
                        Log.i(TAG, "Max segment (${speechBuffer.size / SAMPLE_RATE}s), sending to API...")
                        val segment = speechBuffer.toShortArray()
                        speechBuffer.clear()
                        speechFrameCount = 0
                        scope.launch { processSegment(segment) }
                    }
                } else {
                    if (isSpeaking) {
                        for (i in 0 until read) speechBuffer.add(frame[i])
                        silenceFrameCount++

                        if (silenceFrameCount >= SILENCE_FRAMES) {
                            if (speechFrameCount >= MIN_SPEECH_FRAMES) {
                                _debugState.value = _debugState.value.copy(
                                    asrStatus = "PROCESSING",
                                    totalSpeechSegments = _debugState.value.totalSpeechSegments + 1
                                )
                                Log.i(TAG, "Speech ended (${speechBuffer.size / SAMPLE_RATE}s, ${speechFrameCount} frames)")
                                val segment = speechBuffer.toShortArray()
                                // Process in parallel — don't block capture
                                scope.launch { processSegment(segment) }
                            } else {
                                Log.d(TAG, "Speech too short (${speechFrameCount} frames), ignoring")
                            }

                            speechBuffer.clear()
                            speechFrameCount = 0
                            silenceFrameCount = 0
                            isSpeaking = false
                            _debugState.value = _debugState.value.copy(asrStatus = "LISTENING")
                        }
                    }
                }
            }

            recorder.stop()
            _debugState.value = _debugState.value.copy(isCapturing = false, asrStatus = "OFF")
            Log.i(TAG, "Pipeline stopped")
        }
    }

    /**
     * SSE STREAMING: Send audio, read tokens as they arrive, TTS each sentence chunk.
     *
     * Flow:
     * 1. POST with "stream": true → server sends SSE events
     * 2. Each SSE "data:" line contains a delta token
     * 3. We accumulate tokens into a sentence buffer
     * 4. On each sentence boundary (. ! ? , ;) → TTS.speak(QUEUE_ADD)
     * 5. First token latency measured separately for debug
     *
     * This gives ~1s faster perceived latency than waiting for full response.
     */
    private suspend fun processSegment(samples: ShortArray) = withContext(Dispatchers.IO) {
        if (apiKey == null) return@withContext

        val startTime = System.currentTimeMillis()
        _debugState.value = _debugState.value.copy(totalApiCalls = _debugState.value.totalApiCalls + 1)

        try {
            val wavBytes = encodeWav(samples)
            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            val srcName = langDisplayName(sourceLang)
            val tgtName = langDisplayName(targetLang)

            val body = JSONObject().apply {
                put("model", model)
                put("stream", true)  // ← SSE streaming enabled
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You translate $srcName audio to $tgtName. Reply ONLY in $tgtName. If you cannot understand the audio, reply with an empty string.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Translate to $tgtName: สวัสดีครับ")
                    })
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", if (targetLang == "fra") "Bonjour." else "Hello.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "input_audio")
                                put("input_audio", JSONObject().apply {
                                    put("data", base64Audio)
                                    put("format", "wav")
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "→ $tgtName:")
                            })
                        })
                    })
                })
                put("max_tokens", 200)
                put("temperature", 0.1)
            }

            val request = Request.Builder()
                .url(Constants.OPENROUTER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://earflows.com")
                .header("X-Title", "EarFlows")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(200) ?: ""
                Log.e(TAG, "SSE HTTP ${response.code}: $errBody")
                _debugState.value = _debugState.value.copy(
                    lastError = "HTTP ${response.code}",
                    totalErrors = _debugState.value.totalErrors + 1
                )
                response.close()
                return@withContext
            }

            // === READ SSE STREAM ===
            val source = response.body?.source()
            if (source == null) {
                response.close()
                return@withContext
            }

            val fullText = StringBuilder()
            val sentenceBuffer = StringBuilder()  // Accumulate until sentence boundary
            var firstTokenTime = 0L
            var sentenceCount = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue

                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = JSONObject(data)

                    // Check for error in stream
                    val error = chunk.optJSONObject("error")
                    if (error != null) {
                        val errMsg = error.optString("message", "stream error")
                        Log.e(TAG, "SSE error: $errMsg")
                        _debugState.value = _debugState.value.copy(lastError = errMsg.take(80))
                        break
                    }

                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content", "")
                        ?: continue

                    if (delta.isEmpty()) continue

                    // First token timing
                    if (firstTokenTime == 0L) {
                        firstTokenTime = System.currentTimeMillis() - startTime
                        Log.i(TAG, "First token in ${firstTokenTime}ms")
                        _debugState.value = _debugState.value.copy(
                            firstTokenLatencyMs = firstTokenTime,
                            ttsStatus = "STREAMING"
                        )
                    }

                    fullText.append(delta)
                    sentenceBuffer.append(delta)

                    // Update UI live with partial text
                    _debugState.value = _debugState.value.copy(
                        lastTranslatedText = fullText.toString()
                    )

                    // TTS on sentence boundaries: . ! ? , ; or newline
                    // This makes TTS start speaking BEFORE the full response arrives
                    val lastChar = sentenceBuffer.lastOrNull()
                    if (lastChar != null && lastChar in ".!?,;\n" && sentenceBuffer.length > 3) {
                        val sentence = sentenceBuffer.toString().trim()
                        sentenceBuffer.clear()

                        if (sentence.isNotBlank() && !isJunkText(sentence)) {
                            sentenceCount++
                            // QUEUE_ADD = append after current speech (don't interrupt)
                            val queueMode = if (sentenceCount == 1)
                                TextToSpeech.QUEUE_FLUSH  // First sentence: interrupt any previous
                            else
                                TextToSpeech.QUEUE_ADD    // Next sentences: queue up

                            tts?.speak(sentence, queueMode, null, "sse_${System.currentTimeMillis()}")
                            Log.d(TAG, "TTS[$sentenceCount]: \"$sentence\"")
                        }
                    }

                } catch (_: Exception) { /* skip malformed SSE line */ }
            }

            source.close()
            response.close()

            // Speak any remaining text in the buffer
            val remaining = sentenceBuffer.toString().trim()
            if (remaining.isNotBlank() && !isJunkText(remaining)) {
                val queueMode = if (sentenceCount == 0)
                    TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(remaining, queueMode, null, "sse_final_${System.currentTimeMillis()}")
                Log.d(TAG, "TTS[final]: \"$remaining\"")
            }

            val totalMs = System.currentTimeMillis() - startTime
            val finalText = fullText.toString().trim()

            if (finalText.isNotBlank() && !isJunkText(finalText)) {
                totalLatency += totalMs
                latencyCount++

                // Check if model returned Thai → retranslate
                val hasThaiChars = finalText.any { it.code in 0x0E00..0x0E7F }
                if (hasThaiChars && sourceLang == "tha") {
                    Log.w(TAG, "SSE returned Thai, retranslating as text...")
                    _debugState.value = _debugState.value.copy(lastSourceText = finalText)
                    val retranslated = retranslateText(finalText)
                    if (retranslated != null) {
                        tts?.speak(retranslated, TextToSpeech.QUEUE_FLUSH, null, "retrans")
                        _debugState.value = _debugState.value.copy(lastTranslatedText = retranslated)
                    }
                }

                Log.i(TAG, "SSE complete in ${totalMs}ms (first token: ${firstTokenTime}ms) → \"${finalText.take(80)}\"")
                _debugState.value = _debugState.value.copy(
                    lastTranslatedText = finalText,
                    translateLatencyMs = totalMs,
                    avgLatencyMs = totalLatency / latencyCount,
                    totalTranslations = _debugState.value.totalTranslations + 1,
                    ttsStatus = "DONE"
                )

                _transcription.emit(TranscriptionEvent(translatedText = finalText, isFinal = true))
            }

        } catch (e: Exception) {
            Log.e(TAG, "SSE error: ${e.message}", e)
            _debugState.value = _debugState.value.copy(
                lastError = "${e.javaClass.simpleName}: ${e.message}",
                totalErrors = _debugState.value.totalErrors + 1
            )
        }
    }

    /** Check if text is junk (empty, dots, non-letter) */
    private fun isJunkText(text: String): Boolean {
        return text.isBlank()
                || text == "..." || text == "…"
                || text.length < 2
                || text.all { !it.isLetter() }
    }

    /** Fallback: translate text when audio API returned transcription instead of translation */
    private fun retranslateText(sourceText: String): String? {
        if (apiKey == null) return null
        try {
            val srcName = langDisplayName(sourceLang)
            val tgtName = langDisplayName(targetLang)
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Translate $srcName to $tgtName. Output ONLY the $tgtName translation.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", sourceText)
                    })
                })
                put("max_tokens", 300)
                put("temperature", 0.1)
            }
            val request = Request.Builder()
                .url(Constants.OPENROUTER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://earflows.com")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            val response = httpClient.newCall(request).execute()
            val result = response.body?.string()
            response.close()
            return JSONObject(result ?: "{}").optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content", "")?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Retranslate failed: ${e.message}")
            return null
        }
    }

    /** Encode ShortArray PCM to WAV byte array */
    private fun encodeWav(samples: ShortArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) byteBuffer.putShort(s)
        val pcmBytes = byteBuffer.array()

        val out = ByteArrayOutputStream()
        val dataSize = pcmBytes.size
        val totalSize = dataSize + 36

        // WAV header
        out.write("RIFF".toByteArray())
        out.write(intToLE(totalSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLE(16))          // chunk size
        out.write(shortToLE(1))         // PCM
        out.write(shortToLE(1))         // mono
        out.write(intToLE(SAMPLE_RATE)) // sample rate
        out.write(intToLE(SAMPLE_RATE * 2)) // byte rate
        out.write(shortToLE(2))         // block align
        out.write(shortToLE(16))        // bits per sample
        out.write("data".toByteArray())
        out.write(intToLE(dataSize))
        out.write(pcmBytes)

        return out.toByteArray()
    }

    private fun intToLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()
    )
    private fun shortToLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()
    )

    // === TranslationEngine interface ===

    override suspend fun feedAudioChunk(pcmSamples: ShortArray) {
        if (state == EngineState.READY || state == EngineState.PROCESSING) {
            state = EngineState.PROCESSING
            startPipeline()
        }
    }

    override suspend fun flushSegment() { /* Pipeline handles its own segmentation */ }

    override suspend fun release() {
        state = EngineState.RELEASED
        captureJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        tts?.stop()
        tts?.shutdown()
        httpClient.dispatcher.executorService.shutdown()
        _debugState.value = _debugState.value.copy(isCapturing = false, asrStatus = "OFF")
        Log.i(TAG, "Engine released")
    }

    // === Helpers ===

    private fun langDisplayName(code: String) = when (code) {
        "tha" -> "Thai"; "fra" -> "French"; "eng" -> "English"
        "cmn" -> "Chinese"; "spa" -> "Spanish"; "deu" -> "German"
        "jpn" -> "Japanese"; "kor" -> "Korean"; "vie" -> "Vietnamese"
        else -> code
    }

    private fun getLocale(code: String) = when (code) {
        "fra" -> Locale.FRENCH; "eng" -> Locale.ENGLISH; "deu" -> Locale.GERMAN
        "spa" -> Locale("es"); "ita" -> Locale.ITALIAN; "jpn" -> Locale.JAPANESE
        "kor" -> Locale.KOREAN; "cmn" -> Locale.CHINESE; "tha" -> Locale("th")
        else -> Locale.FRENCH
    }
}
