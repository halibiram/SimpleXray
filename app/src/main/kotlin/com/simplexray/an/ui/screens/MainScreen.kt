package com.simplexray.an.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.application
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.simplexray.an.common.AppLogger
import com.simplexray.an.common.NAVIGATION_DEBOUNCE_DELAY
import com.simplexray.an.common.ROUTE_CONFIG
import com.simplexray.an.common.ROUTE_LOG
import com.simplexray.an.common.ROUTE_SETTINGS
import com.simplexray.an.common.ROUTE_STATS
import com.simplexray.an.common.ServiceStateChecker
import com.simplexray.an.common.rememberMainScreenCallbacks
import com.simplexray.an.common.rememberMainScreenLaunchers
import com.simplexray.an.service.TProxyService
import com.simplexray.an.ui.navigation.BottomNavHost
import com.simplexray.an.ui.scaffold.AppScaffold
import com.simplexray.an.viewmodel.LogViewModel
import com.simplexray.an.viewmodel.LogViewModelFactory
import com.simplexray.an.viewmodel.MainViewModel
import com.simplexray.an.viewmodel.MainViewUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    appNavController: NavHostController,
    snackbarHostState: SnackbarHostState
) {
    val bottomNavController = rememberNavController()
    val scope = rememberCoroutineScope()

    val launchers = rememberMainScreenLaunchers(mainViewModel)

    // ViewModel lifecycle managed by Compose
    val logViewModel: LogViewModel = viewModel(
        factory = LogViewModelFactory(mainViewModel.application)
    )

    val callbacks = rememberMainScreenCallbacks(
        mainViewModel = mainViewModel,
        logViewModel = logViewModel,
        launchers = launchers,
        applicationContext = mainViewModel.application
    )

    // Share launcher - result handled by launcher itself
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Result handled by the launched activity
        AppLogger.d("MainScreen: Share activity result: ${it.resultCode}")
    }

    // Receivers properly unregistered in onDispose
    DisposableEffect(mainViewModel) {
        mainViewModel.registerTProxyServiceReceivers()
        onDispose {
            mainViewModel.unregisterTProxyServiceReceivers()
        }
    }
    
    // Check service state when screen becomes visible to ensure UI reflects actual state
    // TODO: Add debouncing for service state checks to prevent excessive checks
    // TODO: Consider using a shared Flow for service state instead of polling
    // PERF: Service state check on every resume may be expensive
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // When screen resumes, check if service is actually running
                // and update UI state if needed (non-blocking)
                scope.launch(Dispatchers.IO) {
                    try {
                        val isActuallyRunning = ServiceStateChecker.isServiceRunning(
                            mainViewModel.application,
                            TProxyService::class.java
                        ) || TProxyService.isRunning()
                        
                        val currentState = mainViewModel.isServiceEnabled.value
                        if (isActuallyRunning != currentState) {
                            AppLogger.d("MainScreen: Service state mismatch detected. Actual: $isActuallyRunning, UI: $currentState. Updating...")
                            // Update the state to match actual service state (StateFlow is thread-safe)
                            mainViewModel.setServiceEnabled(isActuallyRunning)
                        }
                    } catch (e: Exception) {
                        AppLogger.w("MainScreen: Error checking service state: ${e.message}", e)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var lastNavigationTime = 0L

    // LaunchedEffect automatically cancels when key changes or composable leaves composition
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            // Asset extraction (non-blocking via Dispatchers.IO)
            mainViewModel.extractAssetsIfNeeded()
        }

        // Flow collection automatically cancelled when LaunchedEffect is cancelled
        mainViewModel.uiEvent.collectLatest { event ->
            when (event) {
                is MainViewUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(
                        event.message,
                        duration = SnackbarDuration.Short
                    )
                }

                is MainViewUiEvent.ShareLauncher -> {
                    shareLauncher.launch(event.intent)
                }

                is MainViewUiEvent.StartService -> {
                    // Use startForegroundService for API 26+ to avoid IllegalStateException
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        mainViewModel.application.startForegroundService(event.intent)
                    } else {
                        @Suppress("DEPRECATION")
                        mainViewModel.application.startService(event.intent)
                    }
                }

                is MainViewUiEvent.RefreshConfigList -> {
                    mainViewModel.refreshConfigFileList()
                }

                is MainViewUiEvent.Navigate -> {
                    val currentTime = System.currentTimeMillis()
                    // PERF: Navigation debouncing may cause UI lag
                    if (currentTime - lastNavigationTime >= NAVIGATION_DEBOUNCE_DELAY) {
                        lastNavigationTime = currentTime
                        appNavController.navigate(event.route)
                    }
                }
            }
        }
    }

    val logListState = rememberLazyListState()
    val configListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()

    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val mainScreenRoutes = listOf(ROUTE_STATS, ROUTE_CONFIG, ROUTE_LOG, ROUTE_SETTINGS)

    if (currentRoute in mainScreenRoutes) {
        AppScaffold(
            navController = bottomNavController,
            snackbarHostState = snackbarHostState,
            mainViewModel = mainViewModel,
            logViewModel = logViewModel,
            onCreateNewConfigFileAndEdit = callbacks.onCreateNewConfigFileAndEdit,
            onImportConfigFromClipboard = callbacks.onImportConfigFromClipboard,
            onPerformExport = callbacks.onPerformExport,
            onPerformBackup = callbacks.onPerformBackup,
            onPerformRestore = callbacks.onPerformRestore,
            onSwitchVpnService = callbacks.onSwitchVpnService,
            logListState = logListState,
            configListState = configListState,
            settingsScrollState = settingsScrollState
        ) { paddingValues ->
            BottomNavHost(
                navController = bottomNavController,
                paddingValues = paddingValues,
                mainViewModel = mainViewModel,
                onDeleteConfigClick = callbacks.onDeleteConfigClick,
                logViewModel = logViewModel,
                geoipFilePickerLauncher = launchers.geoipFilePickerLauncher,
                geositeFilePickerLauncher = launchers.geositeFilePickerLauncher,
                logListState = logListState,
                configListState = configListState,
                settingsScrollState = settingsScrollState,
                appNavController = appNavController
            )
        }
    } else {
        BottomNavHost(
            navController = bottomNavController,
            paddingValues = androidx.compose.foundation.layout.PaddingValues(),
            mainViewModel = mainViewModel,
            onDeleteConfigClick = callbacks.onDeleteConfigClick,
            logViewModel = logViewModel,
            geoipFilePickerLauncher = launchers.geoipFilePickerLauncher,
            geositeFilePickerLauncher = launchers.geositeFilePickerLauncher,
            logListState = logListState,
            configListState = configListState,
            settingsScrollState = settingsScrollState,
            appNavController = appNavController
        )
    }
}
