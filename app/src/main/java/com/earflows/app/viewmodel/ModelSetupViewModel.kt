package com.earflows.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.earflows.app.model.DownloadState
import com.earflows.app.model.ModelDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModelSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager(application)

    val downloadState = downloadManager.downloadState
    val downloadProgress = downloadManager.downloadProgress
    val currentModelName = downloadManager.currentModelName

    private val _missingModels = MutableStateFlow(downloadManager.getMissingModels())
    val missingModels = _missingModels.asStateFlow()

    private val _totalSizeMb = MutableStateFlow(downloadManager.getMissingDownloadSizeMb())
    val totalSizeMb = _totalSizeMb.asStateFlow()

    private val _modelsReady = MutableStateFlow(downloadManager.areModelsReady())
    val modelsReady = _modelsReady.asStateFlow()

    fun startDownload() {
        viewModelScope.launch {
            val success = downloadManager.downloadMissingModels()
            if (success) {
                _modelsReady.value = true
            }
            // Refresh missing list
            _missingModels.value = downloadManager.getMissingModels()
            _totalSizeMb.value = downloadManager.getMissingDownloadSizeMb()
        }
    }
}
