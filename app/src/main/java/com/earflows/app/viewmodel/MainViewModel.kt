package com.earflows.app.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.earflows.app.data.PreferencesManager
import com.earflows.app.service.EarFlowsForegroundService
import com.earflows.app.service.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)

    // Service binding
    private var service: EarFlowsForegroundService? = null
    private var isBound = false

    // UI state
    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState = _serviceState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _isVadActive = MutableStateFlow(false)
    val isVadActive = _isVadActive.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs = _latencyMs.asStateFlow()

    private val _engineName = MutableStateFlow("—")
    val engineName = _engineName.asStateFlow()

    // Debug state from realtime engine
    private val _debugState = MutableStateFlow(com.earflows.app.translation.RealtimeTranslationEngine.PipelineDebugState())
    val debugState = _debugState.asStateFlow()

    // Conversation mode
    private val _isReplyMode = MutableStateFlow(false)
    val isReplyMode = _isReplyMode.asStateFlow()

    // Mic source: false = phone mic, true = BT mic (independent of reply mode)
    private val _useBtMic = MutableStateFlow(false)
    val useBtMic = _useBtMic.asStateFlow()

    val useCloud = prefs.useCloud.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sourceLang = prefs.sourceLang.stateIn(viewModelScope, SharingStarted.Eagerly, "tha")
    val targetLang = prefs.targetLang.stateIn(viewModelScope, SharingStarted.Eagerly, "fra")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as EarFlowsForegroundService.LocalBinder
            service = localBinder.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            _serviceState.value = ServiceState.STOPPED
        }
    }

    private fun observeService() {
        val svc = service ?: return
        viewModelScope.launch {
            svc.serviceState.collect { _serviceState.value = it }
        }
        viewModelScope.launch {
            svc.isPaused.collect { _isPaused.value = it }
        }
        viewModelScope.launch {
            svc.vadActive.collect { _isVadActive.value = it }
        }
        viewModelScope.launch {
            svc.latencyMs.collect { _latencyMs.value = it }
        }
        viewModelScope.launch {
            svc.translationManagerRef?.activeEngineName?.collect {
                _engineName.value = it
            }
        }
        // Observe debug state from realtime engine
        viewModelScope.launch {
            svc.translationManagerRef?.getRealtimeDebugState()?.collect {
                _debugState.value = it
            }
        }
        // Observe reply mode
        viewModelScope.launch {
            svc.isReplyMode.collect { _isReplyMode.value = it }
        }
    }

    fun setReplyMode(enabled: Boolean) {
        service?.setReplyMode(enabled)
    }

    fun setUseBtMic(useBt: Boolean) {
        _useBtMic.value = useBt
        service?.setMicSource(useBt)
    }

    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, EarFlowsForegroundService::class.java)
        context.startForegroundService(intent)
        // Bind with AUTO_CREATE after starting foreground service
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun ensureBound() {
        if (!isBound) {
            val context = getApplication<Application>()
            val intent = Intent(context, EarFlowsForegroundService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, EarFlowsForegroundService::class.java).apply {
            action = com.earflows.app.util.Constants.SERVICE_STOP_ACTION
        }
        context.startService(intent)
        if (isBound) {
            context.unbindService(connection)
            isBound = false
        }
        service = null
        _serviceState.value = ServiceState.STOPPED
    }

    fun toggleCloudMode(useCloud: Boolean) {
        viewModelScope.launch {
            prefs.setUseCloud(useCloud)
            service?.switchEngine(useCloud)
        }
    }

    fun bindToExistingService() {
        // Only bind if the service is already running — don't create it prematurely
        // BIND_AUTO_CREATE would start the service + trigger startForeground timeout
        val context = getApplication<Application>()
        val intent = Intent(context, EarFlowsForegroundService::class.java)
        context.bindService(intent, connection, 0) // 0 = don't auto-create
    }

    override fun onCleared() {
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
        super.onCleared()
    }
}
