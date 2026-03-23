package com.earflows.app.translation

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.earflows.app.model.SherpaModelManager
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
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
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Sherpa-ONNX offline Speech-to-Speech engine.
 *
 * Pipeline (100% offline after model download):
 *
 * AudioRecord (CAMCORDER, 16kHz)
 *   → Sherpa Silero VAD (segments speech from silence)
 *     → Sherpa Streaming ASR (zipformer Thai transducer, ~30MB)
 *       → Partial results every ~200ms
 *       → On end of speech: final Thai text
 *         → ML Kit Translate (Thai → French, on-device, ~30MB)
 *           → French text
 *             → Sherpa Piper TTS (French, ~50MB) OR Android TTS
 *               → PCM 22050Hz → resample to 16kHz → AudioTrack
 *
 * Latency breakdown on Snapdragon 8 Gen 3:
 *   VAD: ~5ms, ASR: ~50ms/chunk, Translate: ~20ms, TTS: ~100ms
 *   Total: ~200-500ms E2E (truly simultaneous with streaming ASR)
 */
class SherpaS2SEngine(
    private val context: Context,
    val forceBtMic: Boolean = false,  // true = use BT mic regardless of language
    private val modelManager: SherpaModelManager
) : TranslationEngine {

    companion object {
        private const val TAG = "SherpaS2S"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 100               // 100ms chunks for low latency
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000  // 1600 samples
    }

    override val engineName = "Sherpa Offline S2S"
    override val requiresNetwork = false
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipelineJob: Job? = null

    // Sherpa components
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var sherpaTts: OfflineTts? = null

    // ML Kit translation (offline)
    private var mlTranslator: Translator? = null

    // Fallback Android TTS (if Piper not available)
    private var androidTts: android.speech.tts.TextToSpeech? = null
    private var androidTtsReady = false

    // Audio I/O
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var sourceLang = ""
    private var targetLang = ""

    // Streams
    private val _translatedAudio = MutableSharedFlow<TranslatedChunk>(extraBufferCapacity = 50)
    override val translatedAudioStream: Flow<TranslatedChunk> = _translatedAudio.asSharedFlow()

    private val _transcription = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 10)
    override val transcriptionStream: Flow<TranscriptionEvent> = _transcription.asSharedFlow()

    // Debug state
    data class SherpaDebugState(
        val status: String = "OFF",
        val rmsDb: Float = 0f,
        val isVadSpeech: Boolean = false,
        val lastThaiText: String = "",
        val lastFrenchText: String = "",
        val asrLatencyMs: Long = 0,
        val translateLatencyMs: Long = 0,
        val ttsLatencyMs: Long = 0,
        val totalE2ELatencyMs: Long = 0,
        val chunksProcessed: Long = 0,
        val translationsOk: Int = 0,
        val errors: Int = 0,
        val lastError: String = "",
        val ttsMode: String = "---"  // "Piper" or "Android TTS"
    )

    private val _debugState = MutableStateFlow(SherpaDebugState())
    val debugState = _debugState.asStateFlow()

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        state = EngineState.LOADING
        this.sourceLang = sourceLang
        this.targetLang = targetLang
        _debugState.value = _debugState.value.copy(status = "LOADING")

        return withContext(Dispatchers.IO) {
            try {
                // 1. Check models — need at least one ASR model
                val hasThai = modelManager.isAsrReady()
                val hasWhisper = modelManager.isWhisperReady()
                if (!hasThai && !hasWhisper) {
                    Log.e(TAG, "No ASR models (need Thai or Whisper)")
                    _debugState.value = _debugState.value.copy(lastError = "ASR models missing")
                    state = EngineState.ERROR
                    return@withContext false
                }
                // If source is Thai but Thai model not ready, fall back to Whisper
                if (sourceLang == "tha" && !hasThai && hasWhisper) {
                    Log.w(TAG, "Thai model not ready, using Whisper for Thai")
                }

                // 2. Init ASR — choose model based on source language
                val useThai = sourceLang == "tha"
                val useWhisper = !useThai && modelManager.isWhisperReady()

                if (useThai) {
                    // Thai zipformer transducer (faster, Thai-specific)
                    val asrConfig = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = modelManager.encoderPath(),
                                decoder = modelManager.decoderPath(),
                                joiner = modelManager.joinerPath()
                            ),
                            tokens = modelManager.tokensPath(),
                            numThreads = 4,
                            debug = false
                        ),
                        decodingMethod = "greedy_search"
                    )
                    recognizer = OfflineRecognizer(null, asrConfig)
                    Log.i(TAG, "ASR initialized: Thai zipformer")
                } else if (useWhisper) {
                    // Whisper tiny multilingual (French, English, etc.)
                    val asrConfig = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = modelManager.whisperEncoderPath(),
                                decoder = modelManager.whisperDecoderPath(),
                                language = when (sourceLang) {
                                    "fra" -> "fr"; "eng" -> "en"; "deu" -> "de"
                                    "spa" -> "es"; "ita" -> "it"; "jpn" -> "ja"
                                    else -> "fr"
                                },
                                task = "transcribe"
                            ),
                            tokens = modelManager.whisperTokensPath(),
                            numThreads = 4,
                            debug = false
                        ),
                        decodingMethod = "greedy_search"
                    )
                    recognizer = OfflineRecognizer(null, asrConfig)
                    Log.i(TAG, "ASR initialized: Whisper tiny ($sourceLang)")
                } else {
                    Log.e(TAG, "No ASR model for language: $sourceLang")
                    state = EngineState.ERROR
                    return@withContext false
                }

                // 3. Init Sherpa VAD (Silero)
                val vadAsset = "silero_vad.onnx"
                val vadFile = java.io.File(context.filesDir, "sherpa/$vadAsset")
                if (!vadFile.exists()) {
                    // Copy from assets
                    vadFile.parentFile?.mkdirs()
                    context.assets.open(vadAsset).use { inp ->
                        java.io.FileOutputStream(vadFile).use { out -> inp.copyTo(out) }
                    }
                }

                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = 0.3f,        // Lower = more sensitive (ambient mode)
                        minSpeechDuration = 0.25f,
                        minSilenceDuration = 0.5f,
                        maxSpeechDuration = 10.0f
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 2
                )
                vad = Vad(null, vadConfig)
                Log.i(TAG, "VAD initialized (Silero)")

                // 4. Init TTS (try Piper first, fallback to Android TTS)
                if (modelManager.isTtsReady()) {
                    try {
                        val ttsConfig = OfflineTtsConfig(
                            model = OfflineTtsModelConfig(
                                vits = OfflineTtsVitsModelConfig(
                                    model = modelManager.ttsModelPath(),
                                    dataDir = modelManager.espeakDataPath(),
                                    tokens = ""
                                ),
                                numThreads = 2
                            )
                        )
                        sherpaTts = OfflineTts(null, ttsConfig)
                        _debugState.value = _debugState.value.copy(ttsMode = "Piper (offline)")
                        Log.i(TAG, "TTS initialized (Piper French)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Piper TTS failed: ${e.message}, using Android TTS")
                        initAndroidTts(targetLang)
                    }
                } else {
                    Log.i(TAG, "Piper models not available, using Android TTS")
                    initAndroidTts(targetLang)
                }

                // 5. Init ML Kit Translate (Thai → French)
                val srcLang = toMLKitLang(sourceLang)
                val tgtLang = toMLKitLang(targetLang)
                if (srcLang != null && tgtLang != null) {
                    val opts = TranslatorOptions.Builder()
                        .setSourceLanguage(srcLang)
                        .setTargetLanguage(tgtLang)
                        .build()
                    mlTranslator = Translation.getClient(opts)

                    val ready = suspendCancellableCoroutine { cont ->
                        mlTranslator!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
                            .addOnSuccessListener { cont.resume(true) }
                            .addOnFailureListener { cont.resume(false) }
                    }
                    if (!ready) {
                        Log.w(TAG, "ML Kit model download failed — translation may not work offline")
                    }
                    Log.i(TAG, "ML Kit Translate ready: $sourceLang → $targetLang")
                }

                // 6. Init AudioRecord
                val useBtMicForCapture = forceBtMic || sourceLang != "tha"
                val isReplyMode = useBtMicForCapture
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                // Reply mode: VOICE_COMMUNICATION (BT mic), Ambient: CAMCORDER (phone mic)
                val sources = if (isReplyMode) {
                    listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
                } else {
                    listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
                }
                for (source in sources) {
                    try {
                        audioRecord = AudioRecord(
                            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, CHUNK_SAMPLES * 8)
                        )
                        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            Log.i(TAG, "Mic: ${when(source) { MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER (phone)"; MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMM (BT)"; else -> "MIC" }}")
                            break
                        }
                    } catch (_: Exception) {}
                }

                // 7. Init AudioTrack (output)
                val minOutBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minOutBuf * 2, CHUNK_SAMPLES * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                state = EngineState.READY
                _debugState.value = _debugState.value.copy(status = "READY")
                Log.i(TAG, "SherpaS2S ready: $sourceLang → $targetLang (fully offline)")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Init failed: ${e.message}", e)
                _debugState.value = _debugState.value.copy(lastError = "Init: ${e.message}")
                state = EngineState.ERROR
                false
            }
        }
    }

    private suspend fun initAndroidTts(lang: String) {
        _debugState.value = _debugState.value.copy(ttsMode = "Android TTS")
        suspendCancellableCoroutine { cont ->
            androidTts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    androidTts?.language = getLocale(lang)
                    androidTts?.setSpeechRate(1.1f)
                    androidTtsReady = true
                }
                cont.resume(Unit)
            }
        }
    }

    // ========================================================================
    // STREAMING PIPELINE
    // ========================================================================

    private fun startPipeline() {
        if (pipelineJob?.isActive == true) return

        pipelineJob = scope.launch {
            val recorder = audioRecord ?: return@launch
            recorder.startRecording()
            audioTrack?.play()
            _debugState.value = _debugState.value.copy(status = "LISTENING")
            Log.i(TAG, "Pipeline started")

            val chunk = ShortArray(CHUNK_SAMPLES)
            val floatChunk = FloatArray(CHUNK_SAMPLES)
            var chunksProcessed = 0L

            while (isActive) {
                val read = recorder.read(chunk, 0, CHUNK_SAMPLES)
                if (read <= 0) { delay(5); continue }

                chunksProcessed++

                // Convert to float for sherpa
                for (i in 0 until read) floatChunk[i] = chunk[i].toFloat() / 32768f

                // RMS for debug
                var sum = 0.0
                for (i in 0 until read) { val s = chunk[i].toDouble(); sum += s * s }
                val rmsDb = (20 * kotlin.math.log10(sqrt(sum / read) + 1)).toFloat()
                _debugState.value = _debugState.value.copy(rmsDb = rmsDb, chunksProcessed = chunksProcessed)

                // Feed to VAD
                vad?.acceptWaveform(floatChunk.copyOf(read))

                // Process VAD segments — each is a complete speech utterance
                while (vad != null && !vad!!.empty()) {
                    val segment = vad!!.front()
                    vad!!.pop()

                    val durS = segment.samples.size.toFloat() / SAMPLE_RATE
                    _debugState.value = _debugState.value.copy(isVadSpeech = true, status = "ASR")
                    Log.d(TAG, "VAD segment: ${"%.1f".format(durS)}s")

                    // Offline ASR: process the whole segment at once
                    val e2eStart = System.currentTimeMillis()
                    val offStream = recognizer!!.createStream()
                    offStream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    recognizer!!.decode(offStream)
                    val text = recognizer!!.getResult(offStream).text.trim()
                    offStream.release()
                    val asrMs = System.currentTimeMillis() - e2eStart

                    if (text.isNotBlank()) {
                        Log.i(TAG, "ASR [${asrMs}ms]: \"$text\"")
                        _debugState.value = _debugState.value.copy(
                            lastThaiText = text, asrLatencyMs = asrMs, status = "TRANSLATING"
                        )
                        _transcription.emit(TranscriptionEvent(sourceText = text, isFinal = true))
                        scope.launch { translateAndSpeak(text, e2eStart) }
                    }
                }

                _debugState.value = _debugState.value.copy(
                    isVadSpeech = vad?.isSpeechDetected() ?: false
                )
            }

            try { recorder.stop() } catch (_: Exception) {}
            try { audioTrack?.stop() } catch (_: Exception) {}
            _debugState.value = _debugState.value.copy(status = "OFF")
        }
    }

    /**
     * Translate Thai text to French and speak it. All offline.
     */
    private suspend fun translateAndSpeak(thaiText: String, e2eStart: Long) {
        try {
            // Step 1: ML Kit Translate (Thai → French) — ~20ms on-device
            val translateStart = System.currentTimeMillis()
            val frenchText = translateWithMLKit(thaiText) ?: thaiText
            val translateMs = System.currentTimeMillis() - translateStart

            Log.i(TAG, "Translate [${translateMs}ms]: \"$thaiText\" → \"$frenchText\"")
            _debugState.value = _debugState.value.copy(
                lastFrenchText = frenchText,
                translateLatencyMs = translateMs,
                status = "TTS"
            )
            _transcription.emit(TranscriptionEvent(
                sourceText = thaiText,
                translatedText = frenchText,
                isFinal = true
            ))

            // Step 2: TTS — generate audio
            val ttsStart = System.currentTimeMillis()

            if (sherpaTts != null) {
                // Piper TTS (fully offline, ~100ms)
                val audio = sherpaTts!!.generate(frenchText, sid = 0, speed = 1.1f)
                val ttsMs = System.currentTimeMillis() - ttsStart

                if (audio.samples.isNotEmpty()) {
                    // Piper outputs 22050Hz — resample to 16kHz
                    val resampled = resample(audio.samples, audio.sampleRate, SAMPLE_RATE)
                    val pcm16 = ShortArray(resampled.size) {
                        (resampled[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }

                    // Play on AudioTrack
                    audioTrack?.write(pcm16, 0, pcm16.size)

                    // Emit for recording
                    _translatedAudio.emit(TranslatedChunk(pcmData = pcm16, isFinal = true))

                    val e2eMs = System.currentTimeMillis() - e2eStart
                    _debugState.value = _debugState.value.copy(
                        ttsLatencyMs = ttsMs,
                        totalE2ELatencyMs = e2eMs,
                        translationsOk = _debugState.value.translationsOk + 1,
                        status = "LISTENING"
                    )
                    Log.i(TAG, "E2E [${e2eMs}ms] ASR→Translate→TTS complete")
                }
            } else if (androidTtsReady) {
                // Check if reply mode → must route TTS to phone speaker
                val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val isReply = audioMgr.isSpeakerphoneOn

                if (isReply) {
                    // REPLY MODE: synthesize to file → play via AudioTrack on speaker
                    speakToSpeaker(frenchText)
                } else {
                    // AMBIENT MODE: normal TTS → goes to BT earbuds
                    androidTts?.speak(frenchText, android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                        null, "sherpa_${System.currentTimeMillis()}")
                }

                val ttsMs = System.currentTimeMillis() - ttsStart
                val e2eMs = System.currentTimeMillis() - e2eStart
                _debugState.value = _debugState.value.copy(
                    ttsLatencyMs = ttsMs,
                    totalE2ELatencyMs = e2eMs,
                    translationsOk = _debugState.value.translationsOk + 1,
                    status = "LISTENING"
                )
                Log.i(TAG, "E2E [${e2eMs}ms] ASR→Translate→AndroidTTS complete")
            }

        } catch (e: Exception) {
            Log.e(TAG, "translateAndSpeak error: ${e.message}", e)
            _debugState.value = _debugState.value.copy(
                errors = _debugState.value.errors + 1,
                lastError = e.message ?: "unknown",
                status = "LISTENING"
            )
        }
    }

    private suspend fun translateWithMLKit(text: String): String? {
        val translator = mlTranslator ?: return null
        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    // ========================================================================
    // TranslationEngine interface
    // ========================================================================

    override suspend fun feedAudioChunk(pcmSamples: ShortArray) {
        if (state == EngineState.READY || state == EngineState.PROCESSING) {
            state = EngineState.PROCESSING
            startPipeline()
        }
    }

    override suspend fun flushSegment() {
        // VAD flush — force process remaining audio
        vad?.flush()
    }

    override suspend fun release() {
        state = EngineState.RELEASED
        pipelineJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        recognizer?.release()
        recognizer = null
        vad = null
        sherpaTts = null
        mlTranslator?.close()
        androidTts?.stop()
        androidTts?.shutdown()
        _debugState.value = SherpaDebugState()
        Log.i(TAG, "SherpaS2S released")
    }

    // ========================================================================
    // UTILS
    // ========================================================================

    /**
     * Synthesize text to WAV file, then play via AudioTrack routed to phone speaker.
     * This bypasses the default TTS audio routing (which goes to BT).
     */
    private suspend fun speakToSpeaker(text: String) = withContext(Dispatchers.IO) {
        val tts = androidTts ?: return@withContext
        val tempFile = java.io.File(context.cacheDir, "reply_tts_${System.currentTimeMillis()}.wav")

        try {
            // Synthesize to file
            val done = suspendCancellableCoroutine { cont ->
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { cont.resume(true) }
                    @Deprecated("Deprecated") override fun onError(utteranceId: String?) { cont.resume(false) }
                })
                tts.synthesizeToFile(text, null, tempFile, "reply_${System.currentTimeMillis()}")
            }

            if (!done || !tempFile.exists() || tempFile.length() < 44) return@withContext

            // Read WAV PCM data (skip 44-byte header)
            val bytes = tempFile.readBytes()
            val pcmBytes = bytes.copyOfRange(44, bytes.size)
            val samples = ShortArray(pcmBytes.size / 2) { i ->
                ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
            }

            // Play via AudioTrack with USAGE_VOICE_COMMUNICATION → routes to speaker
            val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speakerDevice = audioMgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

            val speakerTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(22050)  // TTS output rate
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            // Force route to speaker device
            if (speakerDevice != null) {
                speakerTrack.setPreferredDevice(speakerDevice)
                Log.i(TAG, "TTS routed to speaker: ${speakerDevice.productName}")
            }

            speakerTrack.write(samples, 0, samples.size)
            speakerTrack.play()

            // Wait for playback to finish
            val durationMs = (samples.size * 1000L) / 22050
            kotlinx.coroutines.delay(durationMs + 100)

            speakerTrack.stop()
            speakerTrack.release()

        } catch (e: Exception) {
            Log.e(TAG, "speakToSpeaker error: ${e.message}")
        } finally {
            tempFile.delete()
        }
    }

    /** Linear interpolation resampling */
    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate
        val outputLen = (input.size / ratio).toInt()
        val output = FloatArray(outputLen)
        for (i in output.indices) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            output[i] = if (idx + 1 < input.size) {
                input[idx] * (1 - frac) + input[idx + 1] * frac
            } else {
                input[idx.coerceAtMost(input.size - 1)]
            }
        }
        return output
    }

    private fun toMLKitLang(code: String): String? = when (code) {
        "tha" -> TranslateLanguage.THAI
        "fra" -> TranslateLanguage.FRENCH
        "eng" -> TranslateLanguage.ENGLISH
        "cmn" -> TranslateLanguage.CHINESE
        "spa" -> TranslateLanguage.SPANISH
        "deu" -> TranslateLanguage.GERMAN
        "jpn" -> TranslateLanguage.JAPANESE
        "kor" -> TranslateLanguage.KOREAN
        else -> null
    }

    private fun getLocale(code: String) = when (code) {
        "fra" -> java.util.Locale.FRENCH
        "eng" -> java.util.Locale.ENGLISH
        "deu" -> java.util.Locale.GERMAN
        "tha" -> java.util.Locale("th")
        else -> java.util.Locale.FRENCH
    }
}
