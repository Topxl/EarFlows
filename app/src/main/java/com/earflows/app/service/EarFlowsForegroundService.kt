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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            startForegroundWithNotification()
            serviceScope.launch { startEarFlows() }
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

        // 2. Initialize audio capture
        if (!audioCapture.initialize()) {
            Log.e(TAG, "Audio capture init failed")
            _serviceState.value = ServiceState.ERROR
            return
        }

        // 3. Initialize VAD (non-critical — falls back to always-on)
        val vadOk = vadDetector.initialize()
        Log.i(TAG, "VAD initialized: $vadOk (fallback=always-on if false)")

        // 4. Initialize translation engine
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
        // Job 1: Capture audio and process through VAD + translation
        captureJob = serviceScope.launch(Dispatchers.IO) {
            // Launch capture (blocking coroutine that reads mic)
            launch { audioCapture.startCapture() }

            // Process audio chunks as they arrive
            var timestampMs = 0L
            audioCapture.audioChunks.collect { chunk ->
                if (_isPaused.value) return@collect

                timestampMs += (chunk.size * 1000L / Constants.SAMPLE_RATE)

                // VAD: check if speech is present
                val vadResult = vadDetector.processChunk(chunk, timestampMs)
                _vadActive.value = vadResult.isSpeech

                if (vadResult.isSpeech) {
                    // Feed speech to translation engine
                    val feedStart = System.currentTimeMillis()
                    translationManager.feedAudioChunk(chunk)
                    _latencyMs.value = System.currentTimeMillis() - feedStart
                } else if (vadResult.transitioned && !vadResult.isSpeech) {
                    // Speech just ended → flush translation segment
                    translationManager.flushSegment()
                }
            }
        }

        // Job 2: Collect raw audio for recording (parallel, always active)
        serviceScope.launch(Dispatchers.IO) {
            audioCapture.rawBytesChunks.collect { bytes ->
                if (!_isPaused.value) {
                    recordingManager.writeChunk(bytes)
                }
            }
        }

        // Job 3: Collect translated audio and play it
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

            val ok = if (useCloud) {
                translationManager.switchToCloud(sourceLang, targetLang)
            } else {
                translationManager.switchToLocal(sourceLang, targetLang)
            }

            if (ok) {
                preferencesManager.setUseCloud(useCloud)
                updateNotification()
            }
            Log.i(TAG, "Engine switch to ${if (useCloud) "cloud" else "local"}: $ok")
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
