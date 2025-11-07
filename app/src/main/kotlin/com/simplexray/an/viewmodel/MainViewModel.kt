package com.simplexray.an.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import com.simplexray.an.common.AppLogger
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.common.CoreStatsClient
import com.simplexray.an.common.ROUTE_APP_LIST
import com.xray.app.stats.command.SysStatsResponse
import com.simplexray.an.common.ROUTE_CONFIG_EDIT
import com.simplexray.an.common.ServiceStateChecker
import com.simplexray.an.common.ThemeMode
import com.simplexray.an.common.error.AppError
import com.simplexray.an.common.error.ErrorHandler
import com.simplexray.an.common.error.runCatchingWithError
import com.simplexray.an.common.error.runSuspendCatchingWithError
import com.simplexray.an.common.error.toAppError
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.service.XrayProcessManager
import com.simplexray.an.update.UpdateManager
import com.simplexray.an.update.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MainViewModel"

sealed class MainViewUiEvent {
    data class ShowSnackbar(val message: String) : MainViewUiEvent()
    data class ShareLauncher(val intent: Intent) : MainViewUiEvent()
    data class StartService(val intent: Intent) : MainViewUiEvent()
    data object RefreshConfigList : MainViewUiEvent()
    data class Navigate(val route: String) : MainViewUiEvent()
}

class MainViewModel(application: Application) :
    AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val activityScope: CoroutineScope = viewModelScope
    private var coreStatsClient: CoreStatsClient? = null
    private var currentStatsApiPort: Int = 0 // Track current port to detect changes
    private val coreStatsClientMutex = Mutex()

    private val fileManager: FileManager = FileManager(application, prefs)

    // TODO: Replace callback with StateFlow or Compose state for better reactivity
    var reloadView: (() -> Unit)? = null

    lateinit var appListViewModel: AppListViewModel
    
    // Specialized ViewModels
    lateinit var configViewModel: ConfigViewModel
    lateinit var connectionViewModel: ConnectionViewModel
    lateinit var downloadViewModel: DownloadViewModel
    lateinit var updateViewModel: UpdateViewModel
    lateinit var settingsViewModel: SettingsViewModel
    lateinit var backupRestoreViewModel: BackupRestoreViewModel
    lateinit var fileOperationsViewModel: FileOperationsViewModel
    
    // Keep for backward compatibility
    lateinit var configEditViewModel: ConfigEditViewModel

    // Delegate to SettingsViewModel for backward compatibility
    val settingsState: StateFlow<SettingsState>
        get() = if (::settingsViewModel.isInitialized) {
            settingsViewModel.settingsState
        } else {
            MutableStateFlow(
                SettingsState(
                    socksPort = InputFieldState(prefs.socksPort.toString()),
                    dnsIpv4 = InputFieldState(prefs.dnsIpv4),
                    dnsIpv6 = InputFieldState(prefs.dnsIpv6),
                    switches = SwitchStates(
                        ipv6Enabled = prefs.ipv6,
                        useTemplateEnabled = prefs.useTemplate,
                        httpProxyEnabled = prefs.httpProxyEnabled,
                        bypassLanEnabled = prefs.bypassLan,
                        disableVpn = prefs.disableVpn,
                        themeMode = prefs.theme
                    ),
                    info = InfoStates(
                        appVersion = BuildConfig.VERSION_NAME,
                        kernelVersion = "N/A",
                        geoipSummary = "",
                        geositeSummary = "",
                        geoipUrl = prefs.geoipUrl,
                        geositeUrl = prefs.geositeUrl
                    ),
                    files = FileStates(
                        isGeoipCustom = prefs.customGeoipImported,
                        isGeositeCustom = prefs.customGeositeImported
                    ),
                    connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
                    connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString())
                )
            ).asStateFlow()
        }

    private val _coreStatsState = MutableStateFlow(CoreStatsState())
    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()
    
    private val _statsErrorState = MutableStateFlow<String?>(null)
    val statsErrorState: StateFlow<String?> = _statsErrorState.asStateFlow()
    
    private val _isStatsRefreshing = MutableStateFlow(false)
    val isStatsRefreshing: StateFlow<Boolean> = _isStatsRefreshing.asStateFlow()
    
    // Stats caching
    private var cachedStats: CoreStatsState? = null
    private var lastStatsUpdateTime = 0L
    private val statsCacheValidityMs = 2000L // 2 seconds cache
    
    // Configurable update interval (default 1 second)
    private var statsUpdateIntervalMs = 1000L

    private val _controlMenuClickable = MutableStateFlow(true)
    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    // BUG: Channel.BUFFERED may cause memory issues if events accumulate
    // PERF: Consider using Channel.UNLIMITED or RENDEZVOUS based on use case
    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()

    private val _geoipDownloadProgress = MutableStateFlow<String?>(null)
    val geoipDownloadProgress: StateFlow<String?> = _geoipDownloadProgress.asStateFlow()
    private var geoipDownloadJob: Job? = null

    private val _geositeDownloadProgress = MutableStateFlow<String?>(null)
    val geositeDownloadProgress: StateFlow<String?> = _geositeDownloadProgress.asStateFlow()
    private var geositeDownloadJob: Job? = null

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _newVersionAvailable = MutableStateFlow<String?>(null)
    val newVersionAvailable: StateFlow<String?> = _newVersionAvailable.asStateFlow()
    private val updateManager = UpdateManager(application)
    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    private var currentDownloadId: Long? = null
    
    // Download completion state - holds APK URI and file path when download completes
    data class DownloadCompletion(val uri: Uri, val filePath: String?)
    private val _downloadCompletion = MutableStateFlow<DownloadCompletion?>(null)
    val downloadCompletion: StateFlow<DownloadCompletion?> = _downloadCompletion.asStateFlow()

    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLogger.d("Service started")
            setServiceEnabled(true)
            setControlMenuClickable(true)
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLogger.d("Service stopped")
            setServiceEnabled(false)
            setControlMenuClickable(true)
            _coreStatsState.value = CoreStatsState()
            coreStatsClient?.close()
            coreStatsClient = null
        }
    }

    init {
        AppLogger.d("MainViewModel initialized.")
        
        // Initialize specialized ViewModels
        val uiEventSender: (MainViewUiEvent) -> Unit = { event ->
            _uiEvent.trySend(event)
        }
        
        // Initialize service enabled state first
        viewModelScope.launch(Dispatchers.IO) {
            _isServiceEnabled.value = isServiceRunning(application, TProxyService::class.java)
        }
        
        // Initialize ConfigViewModel first (needs service enabled state)
        configViewModel = ConfigViewModel(
            application,
            prefs,
            _isServiceEnabled.asStateFlow(),
            uiEventSender
        )
        
        // Initialize ConnectionViewModel (needs config file state)
        connectionViewModel = ConnectionViewModel(
            application,
            prefs,
            configViewModel.selectedConfigFile,
            uiEventSender
        )
        
        // Sync service enabled state between ConnectionViewModel and MainViewModel
        // Also clear core stats when service stops
        var previousEnabled = _isServiceEnabled.value
        viewModelScope.launch {
            connectionViewModel.isServiceEnabled.collect { enabled ->
                _isServiceEnabled.value = enabled
                // Clear core stats when service stops
                if (previousEnabled && !enabled) {
                    _coreStatsState.value = CoreStatsState()
                    coreStatsClientMutex.withLock {
                        coreStatsClient?.close()
                        coreStatsClient = null
                    }
                }
                previousEnabled = enabled
            }
        }
        
        // Initialize DownloadViewModel (needs service enabled state)
        downloadViewModel = DownloadViewModel(
            application,
            prefs,
            connectionViewModel.isServiceEnabled,
            uiEventSender
        )
        
        // Initialize UpdateViewModel (needs service enabled state)
        updateViewModel = UpdateViewModel(
            application,
            prefs,
            connectionViewModel.isServiceEnabled,
            uiEventSender
        )
        
        // Initialize SettingsViewModel
        settingsViewModel = SettingsViewModel(
            application,
            prefs,
            fileManager,
            uiEventSender
        )
        
        // Initialize BackupRestoreViewModel
        backupRestoreViewModel = BackupRestoreViewModel(
            application,
            prefs,
            fileManager,
            uiEventSender,
            onRestoreSuccess = {
                settingsViewModel.updateSettingsState()
                refreshConfigFileList()
            }
        )
        
        // Initialize FileOperationsViewModel
        fileOperationsViewModel = FileOperationsViewModel(
            application,
            prefs,
            fileManager,
            _isServiceEnabled.asStateFlow(),
            _selectedConfigFile.asStateFlow(),
            uiEventSender,
            onConfigListChanged = { refreshConfigFileList() }
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            refreshConfigFileList()
        }
    }

    // Delegate to SettingsViewModel
    private fun updateSettingsState() {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.updateSettingsState()
        }
    }

    // Delegate to ConnectionViewModel
    fun setControlMenuClickable(isClickable: Boolean) = 
        connectionViewModel.setControlMenuClickable(isClickable)
    fun setServiceEnabled(enabled: Boolean) {
        // Update both ConnectionViewModel and MainViewModel state
        connectionViewModel.setServiceEnabled(enabled)
        // Also update local state if needed (it should sync via flow)
        // The state will be synced through the flow collector in init
    }

    // Delegate to BackupRestoreViewModel
    fun clearCompressedBackupData() {
        if (::backupRestoreViewModel.isInitialized) {
            backupRestoreViewModel.clearCompressedBackupData()
        }
    }

    fun performBackup(createFileLauncher: ActivityResultLauncher<String>) {
        if (::backupRestoreViewModel.isInitialized) {
            backupRestoreViewModel.performBackup(createFileLauncher)
        }
    }

    suspend fun handleBackupFileCreationResult(uri: Uri) {
        if (::backupRestoreViewModel.isInitialized) {
            backupRestoreViewModel.handleBackupFileCreationResult(uri)
        }
    }

    suspend fun startRestoreTask(uri: Uri) {
        if (::backupRestoreViewModel.isInitialized) {
            backupRestoreViewModel.startRestoreTask(uri)
        }
    }

    // Delegate to FileOperationsViewModel
    suspend fun createConfigFile(): String? {
        return if (::fileOperationsViewModel.isInitialized) {
            fileOperationsViewModel.createConfigFile()
        } else {
            null
        }
    }

    /**
     * Get the current stats update interval in milliseconds.
     * 
     * @return Update interval in milliseconds (default: 1000ms)
     * @see setStatsUpdateInterval
     */
    fun getStatsUpdateInterval(): Long = statsUpdateIntervalMs
    
    /**
     * Set the stats update interval.
     * 
     * The minimum interval is 500ms to prevent excessive API calls.
     * Lower intervals may impact performance and battery life.
     * 
     * @param intervalMs Desired update interval in milliseconds (minimum: 500ms)
     * @see getStatsUpdateInterval
     */
    fun setStatsUpdateInterval(intervalMs: Long) {
        statsUpdateIntervalMs = maxOf(500L, intervalMs)
    }
    
    /**
     * Manually refresh core stats, bypassing the cache.
     * 
     * This method forces an immediate update of core statistics from the Xray API,
     * regardless of cache validity. Use this for pull-to-refresh functionality.
     * 
     * @see updateCoreStats
     */
    suspend fun refreshCoreStats() {
        _isStatsRefreshing.value = true
        _statsErrorState.value = null
        try {
            updateCoreStats(forceRefresh = true)
        } catch (e: Exception) {
            _statsErrorState.value = e.message ?: "Unknown error"
            AppLogger.e("Failed to refresh core stats", e)
        } finally {
            _isStatsRefreshing.value = false
        }
    }
    
    /**
     * Update core stats with caching and error handling.
     * 
     * This method fetches statistics from the Xray stats API with the following features:
     * - **Caching**: Results are cached for 2 seconds to reduce API calls
     * - **Error handling**: Errors are captured and exposed via `statsErrorState`
     * - **Fallback**: Uses cached stats if API call fails
     * - **Lifecycle-aware**: Returns cached stats if service is not enabled
     * 
     * @param forceRefresh If true, bypasses cache and forces a fresh API call
     * 
     * @see refreshCoreStats
     * @see statsErrorState
     * @see coreStatsState
     */
    suspend fun updateCoreStats(forceRefresh: Boolean = false) {
        if (!_isServiceEnabled.value) {
            // Return cached stats if available
            cachedStats?.let { _coreStatsState.value = it }
            return
        }

        // Check cache validity
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedStats != null && (now - lastStatsUpdateTime) < statsCacheValidityMs) {
            _coreStatsState.value = cachedStats!!
            return
        }

        _statsErrorState.value = null
        
        // Validate API port before attempting connection
        val apiPort = prefs.apiPort.takeIf { it > 0 } ?: XrayProcessManager.statsPort
        if (apiPort <= 0) {
            // API port not available yet, use cached stats if available
            cachedStats?.let { _coreStatsState.value = it }
            _statsErrorState.value = "Stats API port not available (Xray may not be running)"
            return
        }
        
        try {
            // Use Mutex instead of synchronized for suspend functions
            // Recreate client if port changed or client is null
            coreStatsClientMutex.withLock {
                if (coreStatsClient == null || currentStatsApiPort != apiPort) {
                    // Close old client if exists
                    coreStatsClient?.close()
                    // Create new client with current port
                    coreStatsClient = CoreStatsClient.create("127.0.0.1", apiPort)
                    currentStatsApiPort = apiPort
                }
            }

            // Single attempt to get stats (retry mechanism removed)
            var stats: SysStatsResponse? = null
            var traffic: TrafficState? = null
            var lastError: String? = null
            
            AppLogger.d("Attempting stats API connection (port: $apiPort)")
            
            stats = coreStatsClientMutex.withLock { 
                runCatching { 
                    coreStatsClient?.getSystemStats() 
                }.onFailure { e ->
                    lastError = e.message
                    AppLogger.w("Failed to get system stats: ${e.javaClass.simpleName}: ${e.message}", e)
                }.getOrNull()
            }
            
            traffic = coreStatsClientMutex.withLock { 
                runCatching { 
                    coreStatsClient?.getTraffic() 
                }.onFailure { e ->
                    if (lastError == null) lastError = e.message
                    AppLogger.w("Failed to get traffic stats: ${e.javaClass.simpleName}: ${e.message}", e)
                }.getOrNull()
            }

            if (stats == null && traffic == null) {
                // Connection failed, try to close and reset
                coreStatsClientMutex.withLock {
                    coreStatsClient?.close()
                    coreStatsClient = null
                    currentStatsApiPort = 0 // Reset port tracking
                }
                // Use cached stats if available
                cachedStats?.let { _coreStatsState.value = it }
                val errorMsg = if (lastError != null) {
                    "Failed to connect to stats API (port: $apiPort). Error: $lastError"
                } else {
                    "Failed to connect to stats API (port: $apiPort). Xray may not be ready yet."
                }
                _statsErrorState.value = errorMsg
                return
            }

            val newStats = CoreStatsState(
                uplink = traffic?.uplink ?: 0,
                downlink = traffic?.downlink ?: 0,
                numGoroutine = stats?.numGoroutine ?: 0,
                numGC = stats?.numGC ?: 0,
                alloc = stats?.alloc ?: 0,
                totalAlloc = stats?.totalAlloc ?: 0,
                sys = stats?.sys ?: 0,
                mallocs = stats?.mallocs ?: 0,
                frees = stats?.frees ?: 0,
                liveObjects = stats?.liveObjects ?: 0,
                pauseTotalNs = stats?.pauseTotalNs ?: 0,
                uptime = stats?.uptime ?: 0
            )
            
            // Update cache
            cachedStats = newStats
            lastStatsUpdateTime = now
            _coreStatsState.value = newStats
            AppLogger.d("Core stats updated")
        } catch (e: Exception) {
            AppLogger.e("Error updating core stats: ${e.javaClass.simpleName}: ${e.message}", e)
            // More detailed error message
            val errorMsg = when {
                e.message?.contains("UNAVAILABLE", ignoreCase = true) == true -> 
                    "Stats API unavailable (port: $apiPort). Xray may not be ready yet."
                e.message?.contains("DEADLINE_EXCEEDED", ignoreCase = true) == true -> 
                    "Stats API timeout (port: $apiPort). Xray may be slow to respond."
                e.message?.contains("Connection refused", ignoreCase = true) == true -> 
                    "Stats API connection refused (port: $apiPort). Check if Xray is running."
                else -> "Failed to connect to stats API (port: $apiPort). ${e.message ?: "Unknown error"}"
            }
            _statsErrorState.value = errorMsg
            // Use cached stats if available
            cachedStats?.let { _coreStatsState.value = it }
        }
    }

    // Delegate to FileOperationsViewModel
    suspend fun importConfigFromClipboard(): String? {
        return if (::fileOperationsViewModel.isInitialized) {
            fileOperationsViewModel.importConfigFromClipboard()
        } else {
            null
        }
    }

    suspend fun handleSharedContent(content: String) {
        if (::fileOperationsViewModel.isInitialized) {
            fileOperationsViewModel.handleSharedContent(content)
        }
    }

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        if (::fileOperationsViewModel.isInitialized) {
            fileOperationsViewModel.deleteConfigFile(file, callback)
        } else {
            callback()
        }
    }

    fun extractAssetsIfNeeded() {
        fileManager.extractAssetsIfNeeded()
    }

    // Delegate to SettingsViewModel
    fun updateSocksPort(portString: String): Boolean {
        return if (::settingsViewModel.isInitialized) {
            settingsViewModel.updateSocksPort(portString)
        } else {
            false
        }
    }

    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        return if (::settingsViewModel.isInitialized) {
            settingsViewModel.updateDnsIpv4(ipv4Addr)
        } else {
            false
        }
    }

    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        return if (::settingsViewModel.isInitialized) {
            settingsViewModel.updateDnsIpv6(ipv6Addr)
        } else {
            false
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setIpv6Enabled(enabled)
        }
    }

    fun setUseTemplateEnabled(enabled: Boolean) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setUseTemplateEnabled(enabled)
        }
    }

    fun setHttpProxyEnabled(enabled: Boolean) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setHttpProxyEnabled(enabled)
        }
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setBypassLanEnabled(enabled)
        }
    }

    fun setDisableVpnEnabled(enabled: Boolean) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setDisableVpnEnabled(enabled)
        }
    }

    fun setEnablePerformanceMode(enabled: Boolean) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setEnablePerformanceMode(enabled)
        }
    }

    fun setTheme(mode: ThemeMode) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.setTheme(mode)
            reloadView?.invoke()
        }
    }

    fun importRuleFile(uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = fileManager.importRuleFile(uri, fileName)
            if (success) {
                when (fileName) {
                    "geoip.dat", "geosite.dat" -> {
                        // Update settings state after file import
                        if (::settingsViewModel.isInitialized) {
                            settingsViewModel.updateSettingsState()
                        }
                    }
                }
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        "$fileName ${getApplication<Application>().getString(R.string.import_success)}"
                    )
                )
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.import_failed)))
            }
        }
    }

    fun showExportFailedSnackbar() {
        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.export_failed)))
    }

    fun startTProxyService(action: String) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.not_select_config)))
                AppLogger.w( "Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val intent = Intent(application, TProxyService::class.java).setAction(action)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun editConfig(filePath: String) {
        viewModelScope.launch {
            configEditViewModel = ConfigEditViewModel(application, filePath, prefs)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_CONFIG_EDIT))
        }
    }

    fun shareIntent(chooserIntent: Intent, packageManager: PackageManager) {
        viewModelScope.launch {
            if (chooserIntent.resolveActivity(packageManager) != null) {
                _uiEvent.trySend(MainViewUiEvent.ShareLauncher(chooserIntent))
                AppLogger.d("Export intent resolved and started.")
            } else {
                AppLogger.w( "No activity found to handle export intent.")
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        getApplication<Application>().getString(R.string.no_app_for_export)
                    )
                )
            }
        }
    }

    fun stopTProxyService() {
        viewModelScope.launch {
            val intent = Intent(
                application,
                TProxyService::class.java
            ).setAction(TProxyService.ACTION_DISCONNECT)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.not_select_config)))
                AppLogger.w( "Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val vpnIntent = VpnService.prepare(application)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startTProxyService(TProxyService.ACTION_CONNECT)
            }
        }
    }

    fun navigateToAppList() {
        viewModelScope.launch {
            appListViewModel = AppListViewModel(application)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST))
        }
    }

    fun navigateToPerformance() {
        _uiEvent.trySend(MainViewUiEvent.Navigate(com.simplexray.an.common.ROUTE_PERFORMANCE))
    }
    
    fun navigateToAdvancedPerformanceSettings() {
        _uiEvent.trySend(MainViewUiEvent.Navigate(com.simplexray.an.common.ROUTE_ADVANCED_PERFORMANCE_SETTINGS))
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        prefs.configFilesOrder = currentList.map { it.name }
    }

    fun refreshConfigFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            val actualFiles =
                filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                    ?: emptyList()
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

    // Delegate to SettingsViewModel
    fun updateConnectivityTestTarget(target: String) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.updateConnectivityTestTarget(target)
        }
    }

    fun updateConnectivityTestTimeout(timeout: String) {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.updateConnectivityTestTimeout(timeout)
        }
    }

    fun testConnectivity() {
        if (::settingsViewModel.isInitialized) {
            settingsViewModel.testConnectivity(
                _isServiceEnabled.value,
                prefs.socksAddress,
                prefs.socksPort
            )
        }
    }

    fun registerTProxyServiceReceivers() {
        val application = getApplication<Application>()
        val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(
                startReceiver,
                startSuccessFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            getApplication<Application>().registerReceiver(startReceiver, startSuccessFilter)
        }

        val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(
                stopReceiver,
                stopSuccessFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            getApplication<Application>().registerReceiver(stopReceiver, stopSuccessFilter)
        }
        AppLogger.d("TProxyService receivers registered.")
    }

    fun unregisterTProxyServiceReceivers() {
        val application = getApplication<Application>()
        try {
            getApplication<Application>().unregisterReceiver(startReceiver)
        } catch (e: IllegalArgumentException) {
            AppLogger.w( "Start receiver was not registered", e)
        }
        try {
            getApplication<Application>().unregisterReceiver(stopReceiver)
        } catch (e: IllegalArgumentException) {
            AppLogger.w( "Stop receiver was not registered", e)
        }
        AppLogger.d("TProxyService receivers unregistered.")
        // BUG: BroadcastReceiver lifecycle management - ensure unregisterTProxyServiceReceivers() is called in onCleared()
        // BUG: If ViewModel is destroyed without calling unregisterTProxyServiceReceivers(), receivers may leak memory
        // FIXME: Receivers are unregistered in onCleared() but may not be called if process is killed
    }

    fun restoreDefaultGeoip(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeoip()
            if (::settingsViewModel.isInitialized) {
                settingsViewModel.updateSettingsState()
            }
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.rule_file_restore_geoip_success)))
            withContext(Dispatchers.Main) {
                AppLogger.d("Restored default geoip.dat.")
                callback()
            }
        }
    }

    fun restoreDefaultGeosite(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeosite()
            if (::settingsViewModel.isInitialized) {
                settingsViewModel.updateSettingsState()
            }
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.rule_file_restore_geosite_success)))
            withContext(Dispatchers.Main) {
                AppLogger.d("Restored default geosite.dat.")
                callback()
            }
        }
    }

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            if (fileName == "geoip.dat") {
                geoipDownloadJob?.cancel()
            } else {
                geositeDownloadJob?.cancel()
            }
            AppLogger.d("Download cancellation requested for $fileName")
        }
    }

    fun downloadRuleFile(url: String, fileName: String) {
        val currentJob = if (fileName == "geoip.dat") geoipDownloadJob else geositeDownloadJob
        if (currentJob?.isActive == true) {
            AppLogger.w( "Download already in progress for $fileName")
            return
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val progressFlow = if (fileName == "geoip.dat") {
                prefs.geoipUrl = url
                _geoipDownloadProgress
            } else {
                prefs.geositeUrl = url
                _geositeDownloadProgress
            }

            val client = OkHttpClient.Builder().apply {
                if (_isServiceEnabled.value) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
                }
            }.build()

            try {
                progressFlow.value = getApplication<Application>().getString(R.string.connecting)

                val request = Request.Builder().url(url).build()
                val call = client.newCall(request)
                val response = call.await()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download file: ${response.code}")
                }

                val body = response.body ?: throw IOException("Response body is null")
                val totalBytes = body.contentLength()
                var bytesRead = 0L
                var lastProgress = -1

                body.byteStream().use { inputStream ->
                    val success = fileManager.saveRuleFile(inputStream, fileName) { read ->
                        ensureActive()
                        bytesRead += read
                        if (totalBytes > 0) {
                            val progress = (bytesRead * 100 / totalBytes).toInt()
                            if (progress != lastProgress) {
                                progressFlow.value =
                                    getApplication<Application>().getString(R.string.downloading, progress)
                                lastProgress = progress
                            }
                        } else {
                            if (lastProgress == -1) {
                                progressFlow.value =
                                    getApplication<Application>().getString(R.string.downloading_no_size)
                                lastProgress = 0
                            }
                        }
                    }
                    if (success) {
                        when (fileName) {
                            "geoip.dat", "geosite.dat" -> {
                                // Update settings state after download
                                if (::settingsViewModel.isInitialized) {
                                    settingsViewModel.updateSettingsState()
                                }
                            }
                        }
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.download_success)))
                    } else {
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.download_failed)))
                    }
                }
            } catch (e: CancellationException) {
                AppLogger.d("Download cancelled for $fileName")
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.download_cancelled)))
            } catch (e: Exception) {
                AppLogger.e( "Failed to download rule file", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.download_failed) + ": " + e.message))
            } finally {
                progressFlow.value = null
                updateSettingsState()
            }
        }

        if (fileName == "geoip.dat") {
            geoipDownloadJob = job
        } else {
            geositeDownloadJob = job
        }

        job.invokeOnCompletion {
            if (fileName == "geoip.dat") {
                geoipDownloadJob = null
            } else {
                geositeDownloadJob = null
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(Result.success(response))
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWith(Result.failure(e))
            }
        })
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingForUpdates.value = true
            val client = OkHttpClient.Builder().apply {
                if (_isServiceEnabled.value) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
                }
            }.build()

            val request = Request.Builder()
                .url(BuildConfig.REPOSITORY_URL + "/releases/latest")
                .head()
                .build()

            try {
                val response = client.newCall(request).await()
                val location = response.request.url.toString()
                val latestTag = location.substringAfterLast("/tag/v")
                AppLogger.d("Latest version tag: $latestTag")
                val updateAvailable = compareVersions(latestTag) > 0
                if (updateAvailable) {
                    _newVersionAvailable.value = latestTag
                } else {
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            getApplication<Application>().getString(R.string.no_new_version_available)
                        )
                    )
                }
            } catch (e: CancellationException) {
                // Re-throw cancellation to properly handle coroutine cancellation
                throw e
            } catch (e: Exception) {
                AppLogger.e( "Failed to check for updates", e)
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        getApplication<Application>().getString(R.string.failed_to_check_for_updates) + ": " + e.message
                    )
                )
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    fun downloadNewVersion(versionTag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isDownloadingUpdate.value = true
                _downloadProgress.value = 0

                // Detect device ABI and construct appropriate APK URL
                val deviceAbi = detectDeviceAbi()
                if (deviceAbi == null) {
                    throw IllegalStateException("No supported ABI found for this device")
                }

                // Construct APK download URL for detected ABI variant
                val apkUrl = BuildConfig.REPOSITORY_URL +
                    "/releases/download/v$versionTag/simplexray-$deviceAbi.apk"
                
                AppLogger.d("Detected ABI: $deviceAbi, Starting APK download from: $apkUrl")
                
                // Start download
                val downloadId = updateManager.downloadApk(versionTag, apkUrl)
                currentDownloadId = downloadId

                // Monitor download progress
                updateManager.observeDownloadProgress(downloadId).collect { progress ->
                    when (progress) {
                        is DownloadProgress.Downloading -> {
                            _downloadProgress.value = progress.progress
                            AppLogger.d("Download progress: ${progress.progress}%")
                        }
                        is DownloadProgress.Completed -> {
                            _downloadProgress.value = 100
                            _isDownloadingUpdate.value = false
                            AppLogger.d("Download completed, waiting for user to install")
                            
                            // Store completion info instead of installing immediately
                            withContext(Dispatchers.Main) {
                                _downloadCompletion.value = DownloadCompletion(progress.uri, progress.filePath)
                            }
                        }
                        is DownloadProgress.Failed -> {
                            _isDownloadingUpdate.value = false
                            _downloadProgress.value = 0
                            AppLogger.e( "Download failed: ${progress.error}")
                            
                            withContext(Dispatchers.Main) {
                                _uiEvent.trySend(
                                    MainViewUiEvent.ShowSnackbar(
                                        "Download failed: ${progress.error}"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Re-throw cancellation to properly handle coroutine cancellation
                _isDownloadingUpdate.value = false
                _downloadProgress.value = 0
                _downloadCompletion.value = null
                throw e
            } catch (e: Exception) {
                _isDownloadingUpdate.value = false
                _downloadProgress.value = 0
                _downloadCompletion.value = null
                AppLogger.e( "Error downloading update", e)
                
                withContext(Dispatchers.Main) {
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(
                            "Error downloading update: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        currentDownloadId?.let { id ->
            updateManager.cancelDownload(id)
            _isDownloadingUpdate.value = false
            _downloadProgress.value = 0
            _newVersionAvailable.value = null
            _downloadCompletion.value = null
        }
    }
    
    /**
     * Installs the downloaded APK
     */
    fun installDownloadedApk() {
        _downloadCompletion.value?.let { completion ->
            updateManager.installApk(completion.uri, completion.filePath)
            _downloadCompletion.value = null
            _newVersionAvailable.value = null
        }
    }
    
    /**
     * Clears download completion state without installing
     */
    fun clearDownloadCompletion() {
        _downloadCompletion.value = null
        _newVersionAvailable.value = null
    }
    fun clearNewVersionAvailable() {        _newVersionAvailable.value = null    }

    /**
     * Detects the appropriate ABI variant for the current device
     * @return The ABI string (e.g., "arm64-v8a", "x86_64") or null if no match found
     */
    private fun detectDeviceAbi(): String? {
        val supportedAbis = BuildConfig.SUPPORTED_ABIS
        val deviceAbis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            arrayOf(Build.CPU_ABI ?: "", Build.CPU_ABI2 ?: "")
        }

        // Find first matching ABI from device's supported ABIs
        for (deviceAbi in deviceAbis) {
            if (deviceAbi in supportedAbis) {
                AppLogger.d("Detected device ABI: $deviceAbi")
                return deviceAbi
            }
        }

        // Fallback to first supported ABI if no match
        AppLogger.w( "No matching ABI found, using fallback: ${supportedAbis.firstOrNull()}")
        return supportedAbis.firstOrNull()
    }

    private fun compareVersions(version1: String): Int {
        val parts1 = version1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 =
            BuildConfig.VERSION_NAME.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }
        return 0
    }

    override fun onCleared() {
        super.onCleared()
        AppLogger.d("MainViewModel cleared - cleaning up resources")
        
        // CRITICAL: Unregister receivers first to prevent memory leak
        // This must be done early to ensure cleanup even if exceptions occur
        try {
            unregisterTProxyServiceReceivers()
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered, ignore
            AppLogger.d("Receivers already unregistered")
        } catch (e: Exception) {
            AppLogger.w("Error unregistering receivers", e)
        }
        
        geoipDownloadJob?.cancel()
        geositeDownloadJob?.cancel()
        activityScope.coroutineContext.cancelChildren()
        
        // Close core stats client with proper error handling
        try {
            kotlinx.coroutines.runBlocking {
            coreStatsClientMutex.withLock {
                coreStatsClient?.close()
                coreStatsClient = null
            }
            }
        } catch (e: Exception) {
            AppLogger.w("Error closing core stats client", e)
        }
    }

    companion object {

        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            // Use modern ServiceStateChecker utility instead of deprecated APIs
            return ServiceStateChecker.isServiceRunning(context, serviceClass)
        }
        
        /**
         * Refresh service state by checking if TProxyService is actually running.
         * This should be called when app resumes to ensure UI reflects actual state.
         */
        fun refreshServiceState() {
            // This will be called from MainActivity.onResume
            // The actual state update will happen via broadcast receivers
            // But we can also do a direct check here
        }
    }
}

class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

