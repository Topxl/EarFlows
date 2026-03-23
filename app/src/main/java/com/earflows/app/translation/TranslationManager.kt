package com.earflows.app.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.earflows.app.data.PreferencesManager
import com.earflows.app.model.ModelDownloadManager
import com.earflows.app.model.SeamlessModelLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge

/**
 * Orchestrates translation engines with automatic fallback chain:
 *
 * 1. Cloud (OpenAI Realtime) — if selected + API key + internet
 * 2. SeamlessStreaming (local) — if ONNX model available
 * 3. Cascade Whisper+NLLB+TTS (local) — fallback if Seamless not available
 *
 * Auto-fallback: cloud → local on network failure.
 * Exposes a unified translatedAudioStream regardless of active engine.
 */
class TranslationManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    companion object {
        private const val TAG = "TranslationManager"
    }

    private var localEngine: TranslationEngine? = null
    private var cloudEngine: CloudTranslationEngine? = null
    private var activeEngine: TranslationEngine? = null

    // Mic source override — set by service
    var forceBtMic = false

    private val _activeEngineName = MutableStateFlow("Initializing...")
    val activeEngineName = _activeEngineName.asStateFlow()

    private val _isCloudActive = MutableStateFlow(false)
    val isCloudActive = _isCloudActive.asStateFlow()

    val modelDownloadManager = ModelDownloadManager(context)

    /** Unified stream of translated audio from whichever engine is active */
    val translatedAudioStream: Flow<TranslatedChunk>
        get() {
            val streams = mutableListOf<Flow<TranslatedChunk>>()
            localEngine?.let { streams.add(it.translatedAudioStream) }
            cloudEngine?.let { streams.add(it.translatedAudioStream) }
            return if (streams.isEmpty()) {
                MutableStateFlow(TranslatedChunk(ShortArray(0)))
            } else {
                streams.merge()
            }
        }

    /** Unified transcription stream */
    val transcriptionStream: Flow<TranscriptionEvent>
        get() {
            val streams = mutableListOf<Flow<TranscriptionEvent>>()
            localEngine?.let { streams.add(it.transcriptionStream) }
            cloudEngine?.let { streams.add(it.transcriptionStream) }
            return if (streams.isEmpty()) {
                MutableStateFlow(TranscriptionEvent())
            } else {
                streams.merge()
            }
        }

    /**
     * Initialize the appropriate engine based on user preferences and connectivity.
     */
    suspend fun initialize(sourceLang: String, targetLang: String): Boolean {
        // Always initialize local engine first (it actually works for translation)
        // Cloud engine currently needs ASR integration — local cascade is functional
        val localOk = initializeLocalEngine(sourceLang, targetLang)

        val useCloud = preferencesManager.useCloud.first()
        val apiKey = preferencesManager.getApiKey()

        if (useCloud && apiKey != null && isNetworkAvailable()) {
            // Prepare cloud engine in background (for text translation via OpenRouter)
            // But keep local as active engine since cloud needs ASR integration
            Log.i(TAG, "Preparing cloud engine (OpenRouter) in background")
            cloudEngine = CloudTranslationEngine(context, apiKey)
            cloudEngine!!.initialize(sourceLang, targetLang)
            // Cloud engine is available for switchToCloud() when ASR is integrated
        }

        return localOk
    }

    /**
     * Initialize local engine with fallback chain:
     * 1. Try SeamlessStreaming (best quality, lowest latency)
     * 2. Fall back to Cascade Whisper+NLLB+TTS (always works if models downloaded)
     */
    private suspend fun initializeLocalEngine(sourceLang: String, targetLang: String): Boolean {
        // Priority 1: Sherpa-ONNX offline S2S
        val sherpaModels = com.earflows.app.model.SherpaModelManager(context)

        // Auto-download if not ready
        val needsDownload = !sherpaModels.isAsrReady() || !sherpaModels.isWhisperReady()
        if (needsDownload) {
            Log.i(TAG, "Sherpa models missing, downloading...")
            _activeEngineName.value = "Downloading models..."
            val dlOk = sherpaModels.downloadAll()
            if (!dlOk) Log.w(TAG, "Sherpa download failed, will use cloud fallback")
        }

        if (sherpaModels.isAsrReady() || sherpaModels.isWhisperReady()) {
            Log.i(TAG, "Trying Sherpa offline S2S engine...")
            val sherpaEngine = SherpaS2SEngine(context, forceBtMic, sherpaModels)
            val ok = sherpaEngine.initialize(sourceLang, targetLang)
            if (ok) {
                localEngine = sherpaEngine
                activeEngine = sherpaEngine
                _isCloudActive.value = false
                _activeEngineName.value = sherpaEngine.engineName
                Log.i(TAG, "Sherpa offline S2S active")
                return true
            }
            Log.w(TAG, "Sherpa init failed, falling back to cloud")
        }

        // Priority 2: RealtimeTranslationEngine (Gemini cloud SSE)
        val apiKey = preferencesManager.getApiKey()
        if (apiKey != null) {
            Log.i(TAG, "Initializing Realtime cloud engine (OpenRouter + SSE)...")
            val realtimeEngine = RealtimeTranslationEngine(context, apiKey)
            val ok = realtimeEngine.initialize(sourceLang, targetLang)
            if (ok) {
                localEngine = realtimeEngine
                activeEngine = realtimeEngine
                _isCloudActive.value = true
                _activeEngineName.value = realtimeEngine.engineName
                Log.i(TAG, "Realtime cloud engine active: ${realtimeEngine.engineName}")
                return true
            }
        }

        Log.e(TAG, "No engine available (no sherpa models, no API key)")
        _activeEngineName.value = "No engine"
        return false
    }

    private suspend fun prepareLocalFallback(sourceLang: String, targetLang: String) {
        // Prepare local engine in background for instant fallback if cloud drops
        if (modelDownloadManager.areModelsReady()) {
            val cascadeEngine = CascadeTranslationEngine(context, modelDownloadManager)
            cascadeEngine.initialize(sourceLang, targetLang)
            localEngine = cascadeEngine
        } else {
            val modelLoader = SeamlessModelLoader(context)
            val seamlessEngine = LocalTranslationEngine(context, modelLoader)
            if (seamlessEngine.initialize(sourceLang, targetLang)) {
                localEngine = seamlessEngine
            }
        }
    }

    /**
     * Feed audio to the active engine. Auto-fallback on failure.
     */
    suspend fun feedAudioChunk(pcmSamples: ShortArray) {
        val engine = activeEngine ?: return

        try {
            engine.feedAudioChunk(pcmSamples)
        } catch (e: Exception) {
            Log.e(TAG, "Engine error during feedAudioChunk: ${e.message}")
            if (engine == cloudEngine) {
                fallbackToLocal()
                localEngine?.feedAudioChunk(pcmSamples)
            }
        }
    }

    suspend fun flushSegment() {
        try {
            activeEngine?.flushSegment()
        } catch (e: Exception) {
            Log.e(TAG, "Engine error during flush: ${e.message}")
            if (activeEngine == cloudEngine) {
                fallbackToLocal()
            }
        }
    }

    /**
     * Switch between cloud and local engines at runtime.
     */
    suspend fun switchToCloud(sourceLang: String, targetLang: String): Boolean {
        val apiKey = preferencesManager.getApiKey() ?: return false
        if (!isNetworkAvailable()) return false

        cloudEngine?.release()
        cloudEngine = CloudTranslationEngine(context, apiKey)
        val ok = cloudEngine!!.initialize(sourceLang, targetLang)

        if (ok) {
            activeEngine = cloudEngine
            _isCloudActive.value = true
            _activeEngineName.value = cloudEngine!!.engineName
            Log.i(TAG, "Switched to cloud engine")
        }
        return ok
    }

    suspend fun switchToLocal(sourceLang: String, targetLang: String): Boolean {
        if (localEngine?.state == EngineState.READY) {
            activeEngine = localEngine
            _isCloudActive.value = false
            _activeEngineName.value = localEngine!!.engineName
            Log.i(TAG, "Switched to local engine")
            return true
        }
        return initializeLocalEngine(sourceLang, targetLang)
    }

    private suspend fun fallbackToLocal() {
        Log.w(TAG, "Falling back to local engine")
        cloudEngine?.release()
        cloudEngine = null

        if (localEngine?.state == EngineState.READY) {
            activeEngine = localEngine
            _isCloudActive.value = false
            _activeEngineName.value = localEngine!!.engineName
        } else {
            _activeEngineName.value = "Fallback failed"
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Raw audio chunks for parallel recording */
    fun getRawAudioStream(): Flow<ByteArray>? {
        return (activeEngine as? RealtimeTranslationEngine)?.rawAudioChunks
            ?: (localEngine as? RealtimeTranslationEngine)?.rawAudioChunks
    }

    /** Expose debug state from RealtimeTranslationEngine for UI visualization */
    fun getRealtimeDebugState(): kotlinx.coroutines.flow.StateFlow<RealtimeTranslationEngine.PipelineDebugState>? {
        return (activeEngine as? RealtimeTranslationEngine)?.debugState
            ?: (localEngine as? RealtimeTranslationEngine)?.debugState
    }

    suspend fun release() {
        localEngine?.release()
        cloudEngine?.release()
        localEngine = null
        cloudEngine = null
        activeEngine = null
        Log.i(TAG, "TranslationManager released")
    }
}
