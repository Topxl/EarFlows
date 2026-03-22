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

    private var localEngine: TranslationEngine? = null  // SeamlessStreaming or Cascade
    private var cloudEngine: CloudTranslationEngine? = null
    private var activeEngine: TranslationEngine? = null

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
        val useCloud = preferencesManager.useCloud.first()
        val apiKey = preferencesManager.getApiKey()

        if (useCloud && apiKey != null && isNetworkAvailable()) {
            // Try cloud first
            Log.i(TAG, "Attempting cloud engine initialization")
            cloudEngine = CloudTranslationEngine(apiKey)
            val cloudOk = cloudEngine!!.initialize(sourceLang, targetLang)

            if (cloudOk) {
                activeEngine = cloudEngine
                _isCloudActive.value = true
                _activeEngineName.value = cloudEngine!!.engineName
                Log.i(TAG, "Cloud engine active")

                // Also prepare local engine as fallback (in background)
                prepareLocalFallback(sourceLang, targetLang)
                return true
            } else {
                Log.w(TAG, "Cloud init failed, falling back to local")
                cloudEngine?.release()
                cloudEngine = null
            }
        }

        // Use local engine
        return initializeLocalEngine(sourceLang, targetLang)
    }

    /**
     * Initialize local engine with fallback chain:
     * 1. Try SeamlessStreaming (best quality, lowest latency)
     * 2. Fall back to Cascade Whisper+NLLB+TTS (always works if models downloaded)
     */
    private suspend fun initializeLocalEngine(sourceLang: String, targetLang: String): Boolean {
        // Try SeamlessStreaming first
        Log.i(TAG, "Trying SeamlessStreaming local engine...")
        val modelLoader = SeamlessModelLoader(context)
        val seamlessEngine = LocalTranslationEngine(context, modelLoader)
        val seamlessOk = seamlessEngine.initialize(sourceLang, targetLang)

        if (seamlessOk) {
            localEngine = seamlessEngine
            activeEngine = seamlessEngine
            _isCloudActive.value = false
            _activeEngineName.value = seamlessEngine.engineName
            Log.i(TAG, "SeamlessStreaming local engine active")
            return true
        }

        // Fallback to Cascade pipeline
        Log.i(TAG, "SeamlessStreaming not available, trying Cascade engine (Whisper+NLLB+TTS)...")
        seamlessEngine.release()

        if (!modelDownloadManager.areModelsReady()) {
            Log.w(TAG, "Cascade models not downloaded yet. Need: ${modelDownloadManager.getMissingDownloadSizeMb()}MB")
            _activeEngineName.value = "Models needed (${modelDownloadManager.getMissingDownloadSizeMb()}MB)"
            return false
        }

        val cascadeEngine = CascadeTranslationEngine(context, modelDownloadManager)
        val cascadeOk = cascadeEngine.initialize(sourceLang, targetLang)

        if (cascadeOk) {
            localEngine = cascadeEngine
            activeEngine = cascadeEngine
            _isCloudActive.value = false
            _activeEngineName.value = cascadeEngine.engineName
            Log.i(TAG, "Cascade engine active (Whisper+NLLB+TTS)")
            return true
        }

        Log.e(TAG, "All local engines failed to initialize")
        _activeEngineName.value = "No engine available"
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
        cloudEngine = CloudTranslationEngine(apiKey)
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

    suspend fun release() {
        localEngine?.release()
        cloudEngine?.release()
        localEngine = null
        cloudEngine = null
        activeEngine = null
        Log.i(TAG, "TranslationManager released")
    }
}
