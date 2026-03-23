package com.earflows.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.earflows.app.data.PreferencesManager
import com.earflows.app.model.ModelDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val modelManager = ModelDownloadManager(application)

    val sourceLang = prefs.sourceLang.stateIn(viewModelScope, SharingStarted.Eagerly, "tha")
    val targetLang = prefs.targetLang.stateIn(viewModelScope, SharingStarted.Eagerly, "fra")
    val splitChannel = prefs.splitChannel.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val recordTranslation = prefs.recordTranslation.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val outputVolume = prefs.outputVolume.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    private val _hasApiKey = MutableStateFlow(prefs.getApiKey() != null)
    val hasApiKey = _hasApiKey.asStateFlow()

    // Model state
    private val _modelsReady = MutableStateFlow(modelManager.areModelsReady())
    val modelsReady = _modelsReady.asStateFlow()

    private val _missingModelCount = MutableStateFlow(modelManager.getMissingModels().size)
    val missingModelCount = _missingModelCount.asStateFlow()

    private val _missingSizeMb = MutableStateFlow(modelManager.getMissingDownloadSizeMb())
    val missingSizeMb = _missingSizeMb.asStateFlow()

    fun refreshModelState() {
        _modelsReady.value = modelManager.areModelsReady()
        _missingModelCount.value = modelManager.getMissingModels().size
        _missingSizeMb.value = modelManager.getMissingDownloadSizeMb()
    }

    fun setSourceLang(lang: String) = viewModelScope.launch { prefs.setSourceLang(lang) }
    fun setTargetLang(lang: String) = viewModelScope.launch { prefs.setTargetLang(lang) }
    fun setSplitChannel(enabled: Boolean) = viewModelScope.launch { prefs.setSplitChannel(enabled) }
    fun setRecordTranslation(enabled: Boolean) = viewModelScope.launch { prefs.setRecordTranslation(enabled) }
    fun setOutputVolume(volume: Float) = viewModelScope.launch { prefs.setOutputVolume(volume) }

    fun setApiKey(key: String) {
        prefs.setApiKey(key)
        _hasApiKey.value = true
    }

    fun clearApiKey() {
        prefs.clearApiKey()
        _hasApiKey.value = false
    }
}
