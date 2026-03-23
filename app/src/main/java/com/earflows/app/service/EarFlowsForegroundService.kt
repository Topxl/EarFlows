package com.earflows.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.earflows.app.MainActivity
import com.earflows.app.R
import com.earflows.app.audio.AudioCaptureManager
import com.earflows.app.audio.AudioPlaybackManager
import com.earflows.app.audio.AudioRecordingManager
import com.earflows.app.audio.BluetoothAudioManager
import com.earflows.app.data.PreferencesManager
import com.earflows.app.translation.EngineState
import com.earflows.app.translation.TranslationManager
import com.earflows.app.util.Constants
import com.earflows.app.vad.SileroVadDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * EarFlows Foreground Service
 *
 * The main orchestrator running as an Android Foreground Service (type: microphone).
 * Coordinates all subsystems:
 *
 * [Microphone] → AudioCaptureManager → chunks →
 *     ├── SileroVadDetector (filter non-speech)
 *     ├── TranslationManager (local/cloud) → translated audio →
 *     │       AudioPlaybackManager → [Bluetooth earbuds]
 *     └── AudioRecordingManager → [WAV files on storage]
 *
 * Lifecycle:
 * - Started via startForegroundService() from MainActivity
 * - Runs until explicitly stopped or user taps "Stop" in notification
 * - Survives activity destruction (foreground service guarantee)
 * - Shows persistent notification with pause/stop actions
 */
class EarFlowsForegroundService : Service() {

    companion object {
        private const val TAG = "EarFlowsService"
    }

    // --- Subsystems ---
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var vadDetector: SileroVadDetector
    private lateinit var translationManager: TranslationManager
    private lateinit var playbackManager: AudioPlaybackManager
    private lateinit var recordingManager: AudioRecordingManager
    private lateinit var bluetoothManager: BluetoothAudioManager
    private lateinit var preferencesManager: PreferencesManager

    // --- Coroutines ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var translationOutputJob: Job? = null

    // --- Wake lock ---
    private var wakeLock: PowerManager.WakeLock? = null

    // --- State exposed to UI ---
    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState = _serviceState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _vadActive = MutableStateFlow(false)

    // Conversation mode
    private lateinit var conversationModeManager: com.earflows.app.audio.ConversationModeManager
    private val _isReplyMode = MutableStateFlow(false)
    val isReplyMode = _isReplyMode.asStateFlow()

    // Mic source override (independent of reply mode)
    private val _useBtMic = MutableStateFlow(false)
    val useBtMic = _useBtMic.asStateFlow()
    val vadActive = _vadActive.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs = _latencyMs.asStateFlow()

    // Expose translation manager for UI to observe engine name, cloud status
    val translationManagerRef: TranslationManager?
        get() = if (::translationManager.isInitialized) translationManager else null

    // --- Binder for Activity binding ---
    inner class LocalBinder : Binder() {
        fun getService(): EarFlowsForegroundService = this@EarFlowsForegroundService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ========================================================================
    // SERVICE LIFECYCLE
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        preferencesManager = PreferencesManager(applicationContext)
        audioCapture = AudioCaptureManager()
        vadDetector = SileroVadDetector(applicationContext)
        translationManager = TranslationManager(applicationContext, preferencesManager)
        playbackManager = AudioPlaybackManager()
        recordingManager = AudioRecordingManager(applicationContext)
        bluetoothManager = BluetoothAudioManager(applicationContext)
        conversationModeManager = com.earflows.app.audio.ConversationModeManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}, state=${_serviceState.value}")

        when (intent?.action) {
            Constants.SERVICE_STOP_ACTION -> {
                stopEarFlows()
                return START_NOT_STICKY
            }
            Constants.SERVICE_PAUSE_ACTION -> {
                pauseEarFlows()
                return START_STICKY
            }
            Constants.SERVICE_RESUME_ACTION -> {
                resumeEarFlows()
                return START_STICKY
            }
        }

        // Normal start
        if (_serviceState.value == ServiceState.STOPPED) {
            Log.i(TAG, "Starting foreground + pipeline...")
            startForegroundWithNotification()
            serviceScope.launch { startEarFlows() }
        } else {
            Log.i(TAG, "Already running, ignoring start (state=${_serviceState.value})")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopEarFlows()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ========================================================================
    // CORE PIPELINE
    // ========================================================================

    private suspend fun startEarFlows() {
        _serviceState.value = ServiceState.STARTING
        Log.i(TAG, "Starting EarFlows pipeline...")

        // 1. Acquire partial wake lock (keep CPU alive for background processing)
        acquireWakeLock()

        // 2. AudioRecord + VAD are NOT used — SpeechRecognizer handles mic directly
        // This avoids mic conflict and is faster (no intermediate buffering)
        Log.i(TAG, "Using SpeechRecognizer mode (no AudioRecord)")

        // 3. Initialize translation engine
        val sourceLang = preferencesManager.sourceLang.first()
        val targetLang = preferencesManager.targetLang.first()
        val splitChannel = preferencesManager.splitChannel.first()
        val volume = preferencesManager.outputVolume.first()

        val translationOk = translationManager.initialize(sourceLang, targetLang)
        if (!translationOk) {
            Log.w(TAG, "Translation engine init failed — service will run but not translate")
            // Don't abort: recording still works without translation
        }

        // 5. Initialize audio playback
        playbackManager.initialize(splitChannel)
        playbackManager.volume = volume

        // 6. Setup Bluetooth routing
        bluetoothManager.initialize { isConnected ->
            if (isConnected) {
                playbackManager.setOutputDevice(bluetoothManager.getBluetoothOutputDevice())
            } else {
                playbackManager.setOutputDevice(null) // Fall back to speaker
            }
        }
        // Route to BT immediately if available
        bluetoothManager.getBluetoothOutputDevice()?.let {
            playbackManager.setOutputDevice(it)
        }

        // 7. Start recording
        recordingManager.startSession(sourceLang, targetLang)

        // 8. Start the audio pipeline
        _serviceState.value = ServiceState.RUNNING
        updateNotification()

        startAudioPipeline()
    }

    /**
     * Core audio processing pipeline.
     * Captures audio → VAD → Translation → Playback, all streaming.
     */
    private fun startAudioPipeline() {
        // RealtimeTranslationEngine uses Android SpeechRecognizer which has its own mic access.
        // We must NOT use AudioRecord simultaneously — it would conflict.
        // Instead, just trigger feedAudioChunk once to start the SpeechRecognizer.

        Log.i(TAG, "Starting realtime pipeline")

        // Trigger the realtime engine to start
        captureJob = serviceScope.launch(Dispatchers.IO) {
            translationManager.feedAudioChunk(ShortArray(0))
            _vadActive.value = true
        }

        // Job: Record raw audio in parallel (WAV segments)
        serviceScope.launch(Dispatchers.IO) {
            translationManager.getRawAudioStream()?.collect { bytes ->
                if (!_isPaused.value) {
                    recordingManager.writeChunk(bytes)
                }
            }
        }

        // Job: Collect translated audio for recording/playback
        translationOutputJob = serviceScope.launch(Dispatchers.IO) {
            translationManager.translatedAudioStream.collect { translatedChunk ->
                if (!_isPaused.value && translatedChunk.pcmData.isNotEmpty()) {
                    playbackManager.playTranslatedAudio(translatedChunk.pcmData)
                }
            }
        }
    }

    // ========================================================================
    // PAUSE / RESUME / STOP
    // ========================================================================

    private fun pauseEarFlows() {
        _isPaused.value = true
        playbackManager.pause()
        updateNotification()
        Log.i(TAG, "EarFlows paused")
    }

    private fun resumeEarFlows() {
        _isPaused.value = false
        playbackManager.resume()
        updateNotification()
        Log.i(TAG, "EarFlows resumed")
    }

    private fun stopEarFlows() {
        Log.i(TAG, "Stopping EarFlows...")
        _serviceState.value = ServiceState.STOPPING

        // Cancel pipeline jobs
        captureJob?.cancel()
        translationOutputJob?.cancel()

        // Release subsystems
        audioCapture.release()
        vadDetector.release()
        serviceScope.launch {
            val sourceLang = preferencesManager.sourceLang.first()
            val targetLang = preferencesManager.targetLang.first()
            recordingManager.stopSession(sourceLang, targetLang)
            translationManager.release()
        }
        playbackManager.release()
        bluetoothManager.release()

        // Release wake lock
        releaseWakeLock()

        _serviceState.value = ServiceState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ========================================================================
    // RUNTIME SWITCHING (cloud ↔ local)
    // ========================================================================

    fun switchEngine(useCloud: Boolean) {
        serviceScope.launch {
            val sourceLang = preferencesManager.sourceLang.first()
            val targetLang = preferencesManager.targetLang.first()

            // Cloud engine doesn't have integrated ASR yet — force local for now
            // TODO: Enable cloud switching once ASR → OpenRouter pipeline is integrated
            if (useCloud) {
                Log.i(TAG, "Cloud mode requested but not yet functional — keeping local engine")
                preferencesManager.setUseCloud(false)
                return@launch
            }

            val ok = translationManager.switchToLocal(sourceLang, targetLang)

            if (ok) {
                preferencesManager.setUseCloud(false)
                updateNotification()
            }
            Log.i(TAG, "Engine switch to local: $ok")
        }
    }

    // ========================================================================
    // NOTIFICATION
    // ========================================================================

    private fun startForegroundWithNotification() {
        val notification = buildNotification(isPaused = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID, notification)
        }
    }

    // ========================================================================
    // ========================================================================
    // MIC SOURCE — independent of reply mode
    // ========================================================================

    /**
     * Switch mic source without changing translation direction.
     * Restarts the engine with the new mic source.
     */
    fun setMicSource(useBt: Boolean) {
        _useBtMic.value = useBt
        translationManager.forceBtMic = useBt
        Log.i(TAG, "Mic source changed: ${if (useBt) "BT earbuds" else "Phone mic"}")

        // Restart engine to pick up new mic source
        serviceScope.launch {
            translationManager.release()
            kotlinx.coroutines.delay(200)

            val sourceLang = preferencesManager.sourceLang.first()
            val targetLang = preferencesManager.targetLang.first()
            val src = if (_isReplyMode.value) targetLang else sourceLang
            val tgt = if (_isReplyMode.value) sourceLang else targetLang
            translationManager.initialize(src, tgt)
            translationManager.feedAudioChunk(ShortArray(0))
            Log.i(TAG, "Engine restarted with mic: ${if (useBt) "BT" else "phone"}")
        }
    }

    // ========================================================================
    // CONVERSATION MODE — switch between ambient and reply
    // ========================================================================

    /**
     * Toggle reply mode:
     * - OFF (ambient): phone mic → Thai ASR → French TTS → BT earbuds
     * - ON (reply): BT mic → French ASR → Thai TTS → phone speaker
     */
    fun setReplyMode(enabled: Boolean) {
        _isReplyMode.value = enabled
        val audioMgr = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

        serviceScope.launch {
            val sourceLang = preferencesManager.sourceLang.first()
            val targetLang = preferencesManager.targetLang.first()

            // 1. Stop current engine
            translationManager.release()
            Log.i(TAG, "Engine stopped for mode switch")

            // 2. Set audio routing
            if (enabled) {
                // Open a VOICE_COMMUNICATION AudioRecord FIRST — this enables SCO/routing controls
                try {
                    val tempRec = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        16000, android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, 3200
                    )
                    if (tempRec.state == android.media.AudioRecord.STATE_INITIALIZED) {
                        tempRec.startRecording()
                        // NOW the mode change will work
                        audioMgr.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                        audioMgr.isSpeakerphoneOn = true
                        kotlinx.coroutines.delay(300)
                        tempRec.stop()
                        tempRec.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SCO trick failed: ${e.message}")
                }
                Log.i(TAG, "REPLY routing set: mode=${audioMgr.mode}, speaker=${audioMgr.isSpeakerphoneOn}")
            } else {
                audioMgr.isSpeakerphoneOn = false
                audioMgr.mode = android.media.AudioManager.MODE_NORMAL
                Log.i(TAG, "AMBIENT routing set: mode=${audioMgr.mode}, speaker=${audioMgr.isSpeakerphoneOn}")
            }

            kotlinx.coroutines.delay(300)

            // 3. Re-init engine
            val src = if (enabled) targetLang else sourceLang
            val tgt = if (enabled) sourceLang else targetLang
            translationManager.initialize(src, tgt)

            // 4. If reply mode, force TTS output to speaker via setCommunicationDevice
            if (enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val speaker = audioMgr.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioMgr.setCommunicationDevice(speaker)
                    Log.i(TAG, "setCommunicationDevice → SPEAKER: ${speaker.productName}")
                }
            } else if (!enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                audioMgr.clearCommunicationDevice()
                Log.i(TAG, "clearCommunicationDevice → back to BT")
            }

            // 5. Start pipeline
            translationManager.feedAudioChunk(ShortArray(0))
            Log.i(TAG, "Engine restarted: $src → $tgt (reply=$enabled, speaker=${audioMgr.isSpeakerphoneOn})")
        }
    }

    private fun updateNotification() {
        val notification = buildNotification(isPaused = _isPaused.value)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(Constants.NOTIFICATION_ID, notification)
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        // Tap notification → open app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EarFlowsForegroundService::class.java).apply {
                action = Constants.SERVICE_STOP_ACTION
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Pause/Resume action
        val pauseResumeIntent = PendingIntent.getService(
            this, 2,
            Intent(this, EarFlowsForegroundService::class.java).apply {
                action = if (isPaused) Constants.SERVICE_RESUME_ACTION else Constants.SERVICE_PAUSE_ACTION
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isCloud = translationManager.isCloudActive.value
        val contentText = if (isPaused) {
            "En pause"
        } else if (isCloud) {
            getString(R.string.notification_text_cloud)
        } else {
            getString(R.string.notification_text_local)
        }

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Placeholder icon
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) getString(R.string.action_resume) else getString(R.string.action_pause),
                pauseResumeIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.action_stop),
                stopIntent
            )
            .build()
    }

    // ========================================================================
    // WAKE LOCK
    // ========================================================================

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EarFlows::TranslationService"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // Max 4 hours to prevent battery drain if forgotten
        }
        Log.i(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        Log.i(TAG, "Wake lock released")
    }
}

enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}
