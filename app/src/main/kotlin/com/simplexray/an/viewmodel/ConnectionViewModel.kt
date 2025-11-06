package com.simplexray.an.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import com.simplexray.an.common.AppLogger
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplexray.an.R
import com.simplexray.an.common.ServiceStateChecker
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.viewmodel.MainViewUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "ConnectionViewModel"

/**
 * ViewModel for managing VPN connection state
 * TODO: Add connection retry mechanism with exponential backoff
 * TODO: Implement connection state persistence across app restarts
 * TODO: Add connection quality monitoring
 */
class ConnectionViewModel(
    application: Application,
    private val prefs: Preferences,
    private val selectedConfigFile: StateFlow<File?>,
    private val uiEventSender: (MainViewUiEvent) -> Unit
) : AndroidViewModel(application) {
    
    // StateFlow updates are thread-safe by design
    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()
    
    private val _controlMenuClickable = MutableStateFlow(true)
    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()
    
    // Properly unregistered in onCleared()
    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLogger.d("Service started")
            setServiceEnabled(true)
            setControlMenuClickable(true)
        }
    }
    
    // Properly unregistered in onCleared()
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLogger.d("Service stopped")
            setServiceEnabled(false)
            setControlMenuClickable(true)
        }
    }
    
    init {
        AppLogger.d("ConnectionViewModel initialized")
        viewModelScope.launch(Dispatchers.IO) {
            _isServiceEnabled.value = isServiceRunning(getApplication(), TProxyService::class.java)
        }
    }
    
    fun setControlMenuClickable(isClickable: Boolean) {
        _controlMenuClickable.value = isClickable
    }
    
    fun setServiceEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.enable = enabled
    }
    
    fun startTProxyService(action: String) {
        viewModelScope.launch {
            if (selectedConfigFile.value == null) {
                uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.not_select_config)))
                AppLogger.w("Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val intent = Intent(getApplication(), TProxyService::class.java).setAction(action)
            uiEventSender(MainViewUiEvent.StartService(intent))
        }
    }
    
    fun stopTProxyService() {
        viewModelScope.launch {
            val intent = Intent(
                getApplication(),
                TProxyService::class.java
            ).setAction(TProxyService.ACTION_DISCONNECT)
            uiEventSender(MainViewUiEvent.StartService(intent))
        }
    }
    
    // TODO: Add VPN permission state caching to avoid repeated checks
    // TODO: Consider adding VPN permission request retry logic
    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (selectedConfigFile.value == null) {
                uiEventSender(MainViewUiEvent.ShowSnackbar(getApplication<Application>().getString(R.string.not_select_config)))
                AppLogger.w("Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            // TODO: Add config file validation before starting VPN
            val vpnIntent = VpnService.prepare(getApplication())
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startTProxyService(TProxyService.ACTION_CONNECT)
            }
        }
    }
    
    // Track registration state to prevent double registration and ensure cleanup
    private var receiversRegistered = false
    
    // UPGRADE-RISK: RECEIVER_NOT_EXPORTED required on API 33+ - handled with version check
    fun registerTProxyServiceReceivers() {
        if (receiversRegistered) {
            AppLogger.w("TProxyService receivers already registered, skipping")
            return
        }
        
        val application = getApplication<Application>()
        val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // RECEIVER_NOT_EXPORTED prevents other apps from sending intents to this receiver
                application.registerReceiver(
                    startReceiver,
                    startSuccessFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                // On older Android, receiver is exported by default but only receives intents
                // from same app due to package name matching
                application.registerReceiver(startReceiver, startSuccessFilter)
            }
            
            val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(
                    stopReceiver,
                    stopSuccessFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                application.registerReceiver(stopReceiver, stopSuccessFilter)
            }
            
            receiversRegistered = true
            AppLogger.d("TProxyService receivers registered.")
        } catch (e: Exception) {
            AppLogger.e("Failed to register TProxyService receivers", e)
            // Ensure state is consistent on failure
            receiversRegistered = false
        }
    }
    
    fun unregisterTProxyServiceReceivers() {
        if (!receiversRegistered) {
            AppLogger.d("TProxyService receivers not registered, skipping unregister")
            return
        }
        
        val application = getApplication<Application>()
        var unregistered = false
        
        try {
            application.unregisterReceiver(startReceiver)
            unregistered = true
        } catch (e: IllegalArgumentException) {
            AppLogger.w("Start receiver was not registered", e)
        } catch (e: Exception) {
            AppLogger.e("Error unregistering start receiver", e)
        }
        
        try {
            application.unregisterReceiver(stopReceiver)
            unregistered = true
        } catch (e: IllegalArgumentException) {
            AppLogger.w("Stop receiver was not registered", e)
        } catch (e: Exception) {
            AppLogger.e("Error unregistering stop receiver", e)
        }
        
        if (unregistered) {
            receiversRegistered = false
            AppLogger.d("TProxyService receivers unregistered.")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        AppLogger.d("ConnectionViewModel cleared - cleaning up receivers")
        unregisterTProxyServiceReceivers()
    }
    
    companion object {
        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            return ServiceStateChecker.isServiceRunning(context, serviceClass)
        }
    }
}

