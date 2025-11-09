package com.simplexray.an.viewmodel

import android.app.Application
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import com.simplexray.an.R
import com.simplexray.an.common.AppLogger
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "BackupRestoreViewModel"

/**
 * ViewModel for managing backup and restore operations.
 * Handles compressing app data for backup and restoring from backup files.
 */
class BackupRestoreViewModel(
    application: Application,
    private val prefs: Preferences,
    private val fileManager: FileManager,
    private val uiEventSender: (MainViewUiEvent) -> Unit,
    private val onRestoreSuccess: () -> Unit // Callback to refresh state after restore
) : AndroidViewModel(application) {
    
    private var compressedBackupData: ByteArray? = null
    
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
            if (compressedBackupData != null) {
                val dataToWrite: ByteArray = compressedBackupData as ByteArray
                compressedBackupData = null
                try {
                    val outputStream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    if (outputStream != null) {
                        outputStream.use { os ->
                            os.write(dataToWrite)
                            AppLogger.d("Backup successful to: $uri")
                            uiEventSender(MainViewUiEvent.ShowSnackbar(
                                getApplication<Application>().getString(R.string.backup_success)
                            ))
                        }
                    } else {
                        AppLogger.e("Failed to open output stream for backup URI: $uri")
                        uiEventSender(MainViewUiEvent.ShowSnackbar(
                            getApplication<Application>().getString(R.string.backup_failed)
                        ))
                    }
                } catch (e: IOException) {
                    AppLogger.e("Error writing backup data to URI: $uri", e)
                    uiEventSender(MainViewUiEvent.ShowSnackbar(
                        getApplication<Application>().getString(R.string.backup_failed)
                    ))
                }
            } else {
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.backup_failed)
                ))
                AppLogger.e("Compressed backup data is null in launcher callback.")
            }
        }
    }
    
    suspend fun startRestoreTask(uri: Uri) {
        withContext(Dispatchers.IO) {
            val success = fileManager.decompressAndRestore(uri)
            if (success) {
                AppLogger.d("Restore successful.")
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.restore_success)
                ))
                onRestoreSuccess()
            } else {
                uiEventSender(MainViewUiEvent.ShowSnackbar(
                    getApplication<Application>().getString(R.string.restore_failed)
                ))
            }
        }
    }
}








