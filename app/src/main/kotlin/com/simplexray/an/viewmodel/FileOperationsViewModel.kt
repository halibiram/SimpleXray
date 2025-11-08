package com.simplexray.an.viewmodel

import android.app.Application
import com.simplexray.an.R
import com.simplexray.an.common.AppLogger
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "FileOperationsViewModel"

/**
 * ViewModel for managing file operations.
 * Handles config file creation, deletion, and import operations.
 */
class FileOperationsViewModel(
    application: Application,
    private val prefs: Preferences,
    private val fileManager: FileManager,
    private val isServiceEnabled: StateFlow<Boolean>,
    private val selectedConfigFile: StateFlow<File?>,
    private val uiEventSender: (MainViewUiEvent) -> Unit,
    private val onConfigListChanged: () -> Unit // Callback to refresh config list
) : AndroidViewModel(application) {
    
    suspend fun createConfigFile(): String? {
        AppLogger.d("FileOperationsViewModel: Creating new config file...")
        val filePath = fileManager.createConfigFile(getApplication<Application>().assets)
        if (filePath == null) {
            AppLogger.e("FileOperationsViewModel: Failed to create config file")
            uiEventSender(MainViewUiEvent.ShowSnackbar(
                getApplication<Application>().getString(R.string.create_config_failed)
            ))
        } else {
            AppLogger.d("FileOperationsViewModel: Config file created successfully: $filePath, refreshing list...")
            kotlinx.coroutines.delay(50)
            onConfigListChanged()
            AppLogger.d("FileOperationsViewModel: Config file list refreshed")
        }
        return filePath
    }
    
    suspend fun importConfigFromClipboard(): String? {
        val filePath = fileManager.importConfigFromClipboard()
        if (filePath == null) {
            uiEventSender(MainViewUiEvent.ShowSnackbar(
                getApplication<Application>().getString(R.string.import_failed)
            ))
        } else {
            onConfigListChanged()
        }
        return filePath
    }
    
    suspend fun handleSharedContent(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!fileManager.importConfigFromContent(content).isNullOrEmpty()) {
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.import_success)
                ))
                onConfigListChanged()
            } else {
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.invalid_config_format)
                ))
            }
        }
    }
    
    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isServiceEnabled.value && selectedConfigFile.value != null &&
                selectedConfigFile.value == file
            ) {
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.config_in_use)
                ))
                AppLogger.w("Attempted to delete selected config file: ${file.name}")
                return@launch
            }
            
            val success = fileManager.deleteConfigFile(file)
            if (success) {
                onConfigListChanged()
            } else {
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.delete_fail)
                ))
            }
            callback()
        }
    }
}






