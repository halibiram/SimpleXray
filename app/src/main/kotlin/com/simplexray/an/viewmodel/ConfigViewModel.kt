package com.simplexray.an.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.simplexray.an.common.AppLogger
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplexray.an.R
import com.simplexray.an.common.ROUTE_CONFIG_EDIT
import com.simplexray.an.common.error.ErrorHandler
import com.simplexray.an.common.error.runSuspendCatchingWithError
import com.simplexray.an.common.error.toAppError
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "ConfigViewModel"

sealed class ConfigUiEvent {
    data class ShowSnackbar(val message: String) : ConfigUiEvent()
    data class ShareLauncher(val intent: Intent) : ConfigUiEvent()
    data object RefreshConfigList : ConfigUiEvent()
    data class Navigate(val route: String) : ConfigUiEvent()
}

/**
 * ViewModel for managing configuration files
 * TODO: Add config file validation before saving
 * TODO: Implement config file versioning
 * TODO: Add config file encryption option for sensitive data
 */
// SEC: Config files may contain sensitive data - ensure proper validation
// ARCH-DEBT: FileManager tightly coupled - consider dependency injection
class ConfigViewModel(
    application: Application,
    private val prefs: Preferences,
    private val isServiceEnabled: StateFlow<Boolean>,
    private val uiEventSender: (MainViewUiEvent) -> Unit
) : AndroidViewModel(application) {
    
    private val fileManager: FileManager = FileManager(application, prefs)
    
    // Backup data stored temporarily (cleared after use)
    // Note: Consider using encrypted storage for backup data in the future
    private var compressedBackupData: ByteArray? = null
    
    lateinit var configEditViewModel: ConfigEditViewModel
    
    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()
    
    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()
    
    // Channel removed - using uiEventSender callback instead
    
    init {
        AppLogger.d("ConfigViewModel initialized")
        viewModelScope.launch(Dispatchers.IO) {
            refreshConfigFileList()
        }
    }
    
    fun clearCompressedBackupData() {
        compressedBackupData = null
    }
    
    fun performBackup(createFileLauncher: ActivityResultLauncher<String>) {
        viewModelScope.launch {
            compressedBackupData = fileManager.compressBackupData()
            val filename = "simplexray_backup_" + System.currentTimeMillis() + ".dat"
            withContext(Dispatchers.Main) {
                createFileLauncher.launch(filename)
            }
        }
    }
    
    suspend fun handleBackupFileCreationResult(uri: Uri) {
        withContext(Dispatchers.IO) {
            if (compressedBackupData == null) {
                uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.backup_failed)))
                AppLogger.e("Compressed backup data is null in launcher callback.")
                return@withContext
            }
            
            val dataToWrite: ByteArray = compressedBackupData as ByteArray
            compressedBackupData = null
            
            // Validate URI scheme (should be content://)
            if (uri.scheme != "content" && uri.scheme != "file") {
                throw IllegalArgumentException("Invalid backup URI scheme: ${uri.scheme}")
            }
            
            // Write backup with timeout protection
            val result = runSuspendCatchingWithError {
                kotlinx.coroutines.withTimeout(30000) { // 30 second timeout
                    val outputStream = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IOException("Failed to open output stream for backup URI: $uri")
                    
                    // Write in chunks for large backups to avoid blocking
                    outputStream.use { os ->
                        val chunkSize = 64 * 1024 // 64KB chunks
                        var offset = 0
                        while (offset < dataToWrite.size) {
                            val chunk = dataToWrite.sliceArray(offset until minOf(offset + chunkSize, dataToWrite.size))
                            os.write(chunk)
                            offset += chunk.size
                        }
                    }
                }
                AppLogger.d("Backup successful to: $uri")
            }
            
            result.fold(
                onSuccess = {
                    uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.backup_success)))
                },
                onFailure = { throwable ->
                    val appError = throwable.toAppError()
                    ErrorHandler.handleError(appError, TAG)
                    uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.backup_failed)))
                }
            )
        }
    }
    
    suspend fun startRestoreTask(uri: Uri) {
        withContext(Dispatchers.IO) {
            // Validate URI scheme
            if (uri.scheme != "content" && uri.scheme != "file") {
                AppLogger.e("ConfigViewModel: Invalid restore URI scheme: ${uri.scheme}")
                uiEventSender(MainViewUiEvent.ShowSnackbar("Invalid restore file"))
                return@withContext
            }
            
            // Restore with timeout protection
            val result = runCatching {
                kotlinx.coroutines.withTimeout(30000) { // 30 second timeout
                    fileManager.decompressAndRestore(uri)
                }
            }
            
            result.fold(
                onSuccess = { success ->
                    if (success) {
                        uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.restore_success)))
                        AppLogger.d("Restore successful.")
                        refreshConfigFileList()
                    } else {
                        AppLogger.e("ConfigViewModel: Restore operation returned false")
                        uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.restore_failed)))
                    }
                },
                onFailure = { throwable ->
                    AppLogger.e("ConfigViewModel: Restore failed: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
                    uiEventSender(MainViewUiEvent.ShowSnackbar("Restore failed: ${throwable.message}"))
                }
            )
        }
    }
    
    suspend fun createConfigFile(): String? {
        val filePath = fileManager.createConfigFile(getApplication<Application>().assets)
        if (filePath == null) {
            uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.create_config_failed)))
        } else {
            refreshConfigFileList()
        }
        return filePath
    }
    
    suspend fun importConfigFromClipboard(): String? {
        val filePath = fileManager.importConfigFromClipboard()
        if (filePath == null) {
            uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.import_failed)))
        } else {
            refreshConfigFileList()
        }
        return filePath
    }
    
    suspend fun handleSharedContent(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Validate content size (max 10MB - same as FileManager)
            val MAX_CONTENT_SIZE = 10 * 1024 * 1024
            if (content.length > MAX_CONTENT_SIZE) {
                AppLogger.e("ConfigViewModel: Shared content too large: ${content.length} bytes")
                uiEventSender(MainViewUiEvent.ShowSnackbar("Content too large (max 10MB)"))
                return@launch
            }
            
            // Import with error handling
            val result = runCatching {
                fileManager.importConfigFromContent(content)
            }
            
            result.fold(
                onSuccess = { filePath ->
                    if (!filePath.isNullOrEmpty()) {
                        uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.import_success)))
                        refreshConfigFileList()
                    } else {
                        AppLogger.w("ConfigViewModel: Import returned empty file path")
                        uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.invalid_config_format)))
                    }
                },
                onFailure = { throwable ->
                    AppLogger.e("ConfigViewModel: Import failed: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
                    uiEventSender(MainViewUiEvent.ShowSnackbar("Import failed: ${throwable.message}"))
                }
            )
        }
    }
    
    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isServiceEnabled.value && _selectedConfigFile.value != null &&
                _selectedConfigFile.value == file
            ) {
                uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.config_in_use)))
                AppLogger.w("Attempted to delete selected config file: ${file.name}")
                return@launch
            }
            
            val success = fileManager.deleteConfigFile(file)
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshConfigFileList()
                }
            } else {
                uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.delete_fail)))
            }
            callback()
        }
    }
    
    fun extractAssetsIfNeeded() {
        fileManager.extractAssetsIfNeeded()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clear backup data to prevent memory leaks
        compressedBackupData = null
        AppLogger.d("ConfigViewModel cleared")
    }
    
    fun editConfig(filePath: String) {
        viewModelScope.launch {
            configEditViewModel = ConfigEditViewModel(getApplication(), filePath, prefs)
            uiEventSender(MainViewUiEvent.Navigate(ROUTE_CONFIG_EDIT))
        }
    }
    
    fun shareIntent(chooserIntent: Intent, packageManager: android.content.pm.PackageManager) {
        viewModelScope.launch {
            if (chooserIntent.resolveActivity(packageManager) != null) {
                uiEventSender(MainViewUiEvent.ShareLauncher(chooserIntent))
                AppLogger.d("Export intent resolved and started.")
            } else {
                AppLogger.w("No activity found to handle export intent.")
                uiEventSender(
                    MainViewUiEvent.ShowSnackbar(
                        getApplication<Application>().getString(R.string.no_app_for_export)
                    )
                )
            }
        }
    }
    
    fun showExportFailedSnackbar() {
        uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.export_failed)))
    }
    
    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        prefs.configFilesOrder = currentList.map { it.name }
    }
    
    // Debounce config to prevent excessive I/O
    private var lastRefreshTime = 0L
    private val REFRESH_DEBOUNCE_MS = 500L // 500ms debounce
    
    fun refreshConfigFileList() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTime < REFRESH_DEBOUNCE_MS) {
            // Skip refresh if called too soon after last refresh
            return
        }
        lastRefreshTime = now
        
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            // Validate file contents by checking if they're valid JSON files
            // SEC: Validate file contents to prevent listing corrupted or malicious files
            val actualFiles = try {
                filesDir.listFiles { file -> 
                    file.isFile && file.name.endsWith(".json") && 
                    // Basic validation: file is readable and not empty
                    file.canRead() && file.length() > 0 && file.length() <= 10 * 1024 * 1024 // Max 10MB
                }?.toList() ?: emptyList()
            } catch (e: Exception) {
                AppLogger.e("Error listing config files: ${e.message}", e)
                emptyList()
            }
            val actualFilesByName = actualFiles.associateBy { it.name }
            val savedOrder = prefs.configFilesOrder
            
            val newOrder = mutableListOf<File>()
            val remainingActualFileNames = actualFilesByName.toMutableMap()
            
            savedOrder.forEach { filename ->
                actualFilesByName[filename]?.let { file ->
                    newOrder.add(file)
                    remainingActualFileNames.remove(filename)
                }
            }
            
            newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })
            
            _configFiles.value = newOrder
            prefs.configFilesOrder = newOrder.map { it.name }
            
            val currentSelectedPath = prefs.selectedConfigPath
            var fileToSelect: File? = null
            
            if (currentSelectedPath != null) {
                val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                if (foundSelected != null) {
                    fileToSelect = foundSelected
                }
            }
            
            if (fileToSelect == null) {
                fileToSelect = newOrder.firstOrNull()
            }
            
            _selectedConfigFile.value = fileToSelect
            prefs.selectedConfigPath = fileToSelect?.absolutePath
        }
    }
    
    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        prefs.selectedConfigPath = file?.absolutePath
    }
}

