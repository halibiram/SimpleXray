package com.simplexray.an.service

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class QuickTileService : TileService() {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TProxyService.ACTION_START -> updateTileState(true)
                TProxyService.ACTION_STOP -> updateTileState(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "QuickTileService created.")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "QuickTileService started listening.")

        IntentFilter().apply {
            addAction(TProxyService.ACTION_START)
            addAction(TProxyService.ACTION_STOP)
        }.also { filter ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                    broadcastReceiver,
                    filter
                )
            }
        }

        updateTileState(isVpnServiceRunning(this, TProxyService::class.java))
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "QuickTileService stopped listening.")
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "QuickTileService clicked.")

        qsTile.run {
            if (state == Tile.STATE_INACTIVE) {
                if (VpnService.prepare(this@QuickTileService) != null) {
                    Log.e(TAG, "QuickTileService VPN not ready.")
                    return
                }
                startTProxyService(TProxyService.ACTION_START)
            } else {
                startTProxyService(TProxyService.ACTION_DISCONNECT)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "QuickTileService destroyed.")
    }

    private fun updateTileState(isActive: Boolean) {
        qsTile.apply {
            state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun isVpnServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        // Modern approach: Use ActivityManager.RunningAppProcessInfo instead of deprecated getRunningServices()
        // getRunningServices() is deprecated since API 26 and returns empty list on API 30+

        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

            if (activityManager != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // On older Android versions, use deprecated API
                @Suppress("DEPRECATION")
                activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                    serviceClass.name == service.service.className
                }
            } else {
                // On Android 8.0+ (API 26+), check app's running processes instead
                val appProcesses = activityManager?.runningAppProcesses
                val packageName = context.packageName
                appProcesses?.any { process ->
                    process.processName == packageName &&
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                } ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking service status", e)
            false
        }
    }

    private fun startTProxyService(action: String) {
        Intent(this, TProxyService::class.java).apply {
            this.action = action
        }.also { intent ->
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "QuickTileService"
    }
}