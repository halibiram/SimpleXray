package com.simplexray.an.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.simplexray.an.common.AppLogger
import com.simplexray.an.prefs.Preferences

/**
 * AlarmManager-based watchdog receiver for VPN service monitoring.
 *
 * This provides a fallback mechanism when JobScheduler is unavailable or unreliable.
 * MIUI compatibility: AlarmManager alarms can wake device and run even when app is restricted.
 */
class VpnWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        when (intent?.action) {
            ACTION_WATCHDOG_CHECK -> {
                AppLogger.d("VpnWatchdogReceiver: Alarm triggered - checking VPN status")
                checkAndRestartVpn(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLogger.i("VpnWatchdogReceiver: Boot/update detected - scheduling watchdog")
                scheduleWatchdog(context)

                // Also try to restart VPN if it was running
                val prefs = Preferences(context)
                if (prefs.vpnServiceWasRunning) {
                    val manufacturer = Build.MANUFACTURER
                    val isMIUI = manufacturer.equals("Xiaomi", ignoreCase = true) ||
                                 manufacturer.equals("Redmi", ignoreCase = true)

                    if (isMIUI) {
                        AppLogger.i("VpnWatchdogReceiver: MIUI - Attempting to restart VPN after boot")
                    } else {
                        AppLogger.i("VpnWatchdogReceiver: Attempting to restart VPN after boot")
                    }

                    try {
                        val vpnIntent = Intent(context, TProxyService::class.java).apply {
                            action = TProxyService.ACTION_START
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(vpnIntent)
                        } else {
                            context.startService(vpnIntent)
                        }
                    } catch (e: Exception) {
                        AppLogger.e("VpnWatchdogReceiver: Failed to restart VPN after boot: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun checkAndRestartVpn(context: Context) {
        val prefs = Preferences(context)

        // Check if VPN should be running
        if (!prefs.vpnServiceWasRunning) {
            AppLogger.d("VpnWatchdogReceiver: VPN not expected to be running, skipping")
            // Reschedule for next check
            scheduleWatchdog(context)
            return
        }

        // Check if VPN service is actually running
        val isVpnRunning = TProxyService.isRunning()

        if (!isVpnRunning) {
            // VPN should be running but isn't - attempt restart
            val manufacturer = Build.MANUFACTURER
            val isMIUI = manufacturer.equals("Xiaomi", ignoreCase = true) ||
                         manufacturer.equals("Redmi", ignoreCase = true)

            if (isMIUI) {
                AppLogger.w("VpnWatchdogReceiver: MIUI detected - VPN service is dead, attempting restart")
            } else {
                AppLogger.w("VpnWatchdogReceiver: VPN service is dead, attempting restart")
            }

            try {
                // Start VPN service
                val intent = Intent(context, TProxyService::class.java).apply {
                    action = TProxyService.ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                AppLogger.i("VpnWatchdogReceiver: VPN service restart initiated via AlarmManager")
            } catch (e: Exception) {
                AppLogger.e("VpnWatchdogReceiver: Failed to restart VPN service: ${e.message}", e)
            }
        } else {
            AppLogger.d("VpnWatchdogReceiver: VPN service is running normally")
        }

        // Reschedule for next check
        scheduleWatchdog(context)
    }

    companion object {
        private const val ACTION_WATCHDOG_CHECK = "com.simplexray.an.WATCHDOG_CHECK"
        private const val REQUEST_CODE = 1002
        private const val CHECK_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes

        /**
         * Schedule AlarmManager-based watchdog.
         * This is more aggressive than JobScheduler and works better on MIUI.
         */
        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                AppLogger.e("VpnWatchdogReceiver: AlarmManager not available")
                return
            }

            val intent = Intent(context, VpnWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Cancel existing alarm first
            alarmManager.cancel(pendingIntent)

            // Schedule new alarm
            // Use setExactAndAllowWhileIdle for better reliability on MIUI
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // MIUI compatibility: Use setExactAndAllowWhileIdle to run even in Doze mode
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                    pendingIntent
                )
                AppLogger.i("VpnWatchdogReceiver: AlarmManager watchdog scheduled (exact, doze-aware)")
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                    pendingIntent
                )
                AppLogger.i("VpnWatchdogReceiver: AlarmManager watchdog scheduled (exact)")
            }

            val manufacturer = Build.MANUFACTURER
            if (manufacturer.equals("Xiaomi", ignoreCase = true) ||
                manufacturer.equals("Redmi", ignoreCase = true)) {
                AppLogger.i("VpnWatchdogReceiver: MIUI detected - AlarmManager will monitor VPN every 2 minutes")
            }
        }

        /**
         * Cancel AlarmManager watchdog when VPN is manually stopped.
         */
        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) return

            val intent = Intent(context, VpnWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            AppLogger.d("VpnWatchdogReceiver: AlarmManager watchdog cancelled")
        }
    }
}
