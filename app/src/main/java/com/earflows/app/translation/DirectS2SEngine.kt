package com.earflows.app.translation

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.earflows.app.model.StreamSpeechRunner
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

/**
 * Direct Speech-to-Speech Translation Engine.
 *
 * NO text intermediate. Audio in → Audio out. True simultaneous streaming.
 *
 * Architecture:
 * 1. AudioRecord captures 16kHz mono PCM from phone mic
 * 2. Chunks of 500ms (8000 samples) fed to S2S model
 * 3. Model outputs translated audio PCM in real-time
 * 4. AudioTrack plays translated audio on Bluetooth earbuds
 *
 * Local model: StreamSpeech (ONNX) — simultaneous S2S with monotonic attention
 * Cloud fallback: OpenAI Realtime API (WebSocket audio streaming)
 *
 * The model processes audio WHILE the speaker is still talking (simultaneous).
 * Latency target: ~1-2s local, ~500-800ms cloud.
 */
class DirectS2SEngine(
    private val context: Context,
    private val apiKey: String? = null
) : TranslationEngine {

    companion object {
        private const val TAG = "DirectS2S"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 500                          // 500ms chunks for streaming
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000  // 8000 samples
        private const val OUTPUT_SAMPLE_RATE = 16000              // Output also 16kHz for simplicity
    }

    override val engineName = "Direct S2S (StreamSpeech)"
    override val requiresNetwork = false
    override var state: EngineState = EngineState.UNINITIALIZED
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipelineJob: Job? = null

    // Audio I/O
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // S2S model runner (local)
    private var modelRunner: StreamSpeechRunner? = null

    // Cloud fallback
    private var cloudWs: OpenAIRealtimeWS? = null
    private var useCloud = false

    private var sourceLang = ""
    private var targetLang = ""

    // Streams (for recording + UI)
    private val _translatedAudio = MutableSharedFlow<TranslatedChunk>(extraBufferCapacity = 50)
    override val translatedAudioStream: Flow<TranslatedChunk> = _translatedAudio.asSharedFlow()

    private val _transcription = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 10)
    override val transcriptionStream: Flow<TranscriptionEvent> = _transcription.asSharedFlow()

    // Debug state
    data class S2SDebugState(
        val mode: String = "OFF",              // LOCAL / CLOUD / OFF
        val inputRmsDb: Float = 0f,
        val outputRmsDb: Float = 0f,
        val latencyMs: Long = 0,
        val chunksProcessed: Long = 0,
        val chunksOutputted: Long = 0,
        val isCapturing: Boolean = false,
        val isOutputting: Boolean = false,
        val modelLoaded: Boolean = false,
        val lastError: String = ""
    )

    private val _debugState = MutableStateFlow(S2SDebugState())
    val debugState = _debugState.asStateFlow()

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        state = EngineState.LOADING
        this.sourceLang = sourceLang
        this.targetLang = targetLang

        try {
            // 1. Init AudioRecord (CAMCORDER = phone mic, not BT)
            val minInBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minInBuf * 2, CHUNK_SAMPLES * 4)
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                _debugState.value = _debugState.value.copy(lastError = "Mic init failed")
                state = EngineState.ERROR
                return false
            }

            // 2. Init AudioTrack (output translated audio)
            val minOutBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
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
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOutBuf * 2, CHUNK_SAMPLES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // 3. Try to load local S2S model
            modelRunner = StreamSpeechRunner(context)
            val modelLoaded = modelRunner!!.loadModel(sourceLang, targetLang)

            if (modelLoaded) {
                useCloud = false
                _debugState.value = _debugState.value.copy(mode = "LOCAL", modelLoaded = true)
                Log.i(TAG, "Local S2S model loaded: $sourceLang → $targetLang")
            } else if (apiKey != null) {
                // Fallback to cloud
                Log.w(TAG, "Local model not available, using cloud fallback")
                cloudWs = OpenAIRealtimeWS(apiKey, sourceLang, targetLang)
                useCloud = true
                _debugState.value = _debugState.value.copy(mode = "CLOUD", modelLoaded = false)
            } else {
                Log.e(TAG, "No local model and no API key for cloud")
                _debugState.value = _debugState.value.copy(lastError = "No model and no API key")
                state = EngineState.ERROR
                return false
            }

            state = EngineState.READY
            Log.i(TAG, "DirectS2S ready: mode=${if (useCloud) "CLOUD" else "LOCAL"}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            _debugState.value = _debugState.value.copy(lastError = "Init: ${e.message}")
            state = EngineState.ERROR
            return false
        }
    }

    // ========================================================================
    // STREAMING PIPELINE
    // ========================================================================

    private fun startPipeline() {
        if (pipelineJob?.isActive == true) return

        pipelineJob = scope.launch {
            val recorder = audioRecord ?: return@launch
            val player = audioTrack ?: return@launch

            recorder.startRecording()
            player.play()

            _debugState.value = _debugState.value.copy(isCapturing = true)
            Log.i(TAG, "S2S pipeline started — streaming...")

            if (useCloud) {
                cloudWs?.connect()
            }

            val inputChunk = ShortArray(CHUNK_SAMPLES)
            var chunksIn = 0L
            var chunksOut = 0L

            while (isActive) {
                // Read 500ms of audio
                val read = recorder.read(inputChunk, 0, CHUNK_SAMPLES)
                if (read <= 0) {
                    delay(10)
                    continue
                }

                chunksIn++
                val inputRms = computeRmsDb(inputChunk, read)
                _debugState.value = _debugState.value.copy(
                    inputRmsDb = inputRms,
                    chunksProcessed = chunksIn
                )

                // Skip silence (basic energy gate)
                if (inputRms < 25f) continue  // ~25dB threshold for speech

                val startMs = System.currentTimeMillis()

                // Process through S2S model
                val outputPcm: ShortArray? = if (useCloud) {
                    cloudWs?.processChunk(inputChunk.copyOf(read))
                } else {
                    modelRunner?.processChunk(inputChunk.copyOf(read))
                }

                if (outputPcm != null && outputPcm.isNotEmpty()) {
                    val latency = System.currentTimeMillis() - startMs
                    chunksOut++

                    // Play translated audio directly on AudioTrack
                    player.write(outputPcm, 0, outputPcm.size)

                    // Emit for recording / UI
                    _translatedAudio.emit(TranslatedChunk(pcmData = outputPcm, isFinal = false))

                    val outputRms = computeRmsDb(outputPcm, outputPcm.size)
                    _debugState.value = _debugState.value.copy(
                        outputRmsDb = outputRms,
                        latencyMs = latency,
                        chunksOutputted = chunksOut,
                        isOutputting = true
                    )
                }
            }

            recorder.stop()
            player.stop()
            cloudWs?.disconnect()
            _debugState.value = _debugState.value.copy(isCapturing = false, isOutputting = false)
            Log.i(TAG, "S2S pipeline stopped. In=$chunksIn Out=$chunksOut")
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
        modelRunner?.flush()
    }

    override suspend fun release() {
        state = EngineState.RELEASED
        pipelineJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        modelRunner?.release()
        cloudWs?.disconnect()
        audioRecord = null
        audioTrack = null
        modelRunner = null
        _debugState.value = _debugState.value.copy(isCapturing = false, isOutputting = false, mode = "OFF")
        Log.i(TAG, "DirectS2S released")
    }

    // ========================================================================
    // UTILS
    // ========================================================================

    private fun computeRmsDb(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / count)
        return (20 * kotlin.math.log10(rms + 1)).toFloat()
    }
}
