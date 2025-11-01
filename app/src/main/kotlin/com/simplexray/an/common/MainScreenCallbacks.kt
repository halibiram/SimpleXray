package com.simplexray.an.common

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import com.simplexray.an.R
import com.simplexray.an.service.TProxyService
import com.simplexray.an.viewmodel.LogViewModel
import com.simplexray.an.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

data class MainScreenCallbacks(
    val onCreateNewConfigFileAndEdit: () -> Unit,
    val onImportConfigFromClipboard: () -> Unit,
    val onPerformExport: () -> Unit,
    val onPerformBackup: () -> Unit,
    val onPerformRestore: () -> Unit,
    val onDeleteConfigClick: (File, () -> Unit) -> Unit,
    val onSwitchVpnService: () -> Unit
)

@Composable
fun rememberMainScreenCallbacks(
    mainViewModel: MainViewModel,
    logViewModel: LogViewModel,
    launchers: MainScreenLaunchers,
    applicationContext: Context
): MainScreenCallbacks {
    val scope =
        rememberCoroutineScope()
    val onCreateNewConfigFileAndEdit: () -> Unit = {
        scope.launch {
            val filePath = mainViewModel.createConfigFile()
            filePath?.let {
                mainViewModel.editConfig(it)
            }
        }
    }

    val onImportConfigFromClipboard: () -> Unit = {
        scope.launch {
            val filePath = mainViewModel.importConfigFromClipboard()
            filePath?.let {
                mainViewModel.editConfig(it)
            }
        }
    }

    val onPerformExport: () -> Unit = {
        scope.launch {
            val archive = logViewModel.buildExportArchive()
            if (archive != null && archive.exists()) {
                try {
                    val fileUri = FileProvider.getUriForFile(
                        applicationContext,
                        "com.simplexray.an.fileprovider",
                        archive
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(
                        shareIntent,
                        applicationContext.getString(R.string.export)
                    )
                    mainViewModel.shareIntent(chooser, applicationContext.packageManager)
                } catch (e: IllegalArgumentException) {
                    Log.e("MainActivity", "Error getting Uri for export archive", e)
                    mainViewModel.showExportFailedSnackbar()
                }
            } else {
                Log.w("MainActivity", "No logs available to export")
                mainViewModel.showExportFailedSnackbar()
            }
        }
    }

    val onPerformBackup: () -> Unit = {
        scope.launch {
            mainViewModel.performBackup(launchers.createFileLauncher)
        }
    }

    val onPerformRestore: () -> Unit = {
        launchers.openFileLauncher.launch(
            arrayOf(
                "application/octet-stream",
                "*/*"
            )
        )
    }

    val onDeleteConfigClick: (File, () -> Unit) -> Unit = { file, callback ->
        scope.launch {
            mainViewModel.deleteConfigFile(file, callback)
        }
    }

    val onSwitchVpnService: () -> Unit = {
        logViewModel.clearLogs()
        if (mainViewModel.isServiceEnabled.value) {
            mainViewModel.stopTProxyService()
        } else {
            mainViewModel.setControlMenuClickable(false)
            if (mainViewModel.settingsState.value.switches.disableVpn) {
                mainViewModel.startTProxyService(TProxyService.ACTION_START)
            } else {
                mainViewModel.prepareAndStartVpn(launchers.vpnPrepareLauncher)
            }
        }
    }

    return MainScreenCallbacks(
        onCreateNewConfigFileAndEdit = onCreateNewConfigFileAndEdit,
        onImportConfigFromClipboard = onImportConfigFromClipboard,
        onPerformExport = onPerformExport,
        onPerformBackup = onPerformBackup,
        onPerformRestore = onPerformRestore,
        onDeleteConfigClick = onDeleteConfigClick,
        onSwitchVpnService = onSwitchVpnService
    )
}
