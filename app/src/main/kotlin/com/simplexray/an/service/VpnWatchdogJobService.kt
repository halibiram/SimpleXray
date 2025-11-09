package com.simplexray.an.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.simplexray.an.common.AppLogger
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watchdog service to monitor and restart VPN service if it dies.
 *
 * MIUI/HyperOS compatibility:
 * - MIUI may kill VPN service and reject automatic restart
 * - This JobService periodically checks VPN status and restarts if needed
 * - Uses JobScheduler which is more reliable on MIUI than service restart
 */
class VpnWatchdogJobService : JobService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onStartJob(params: JobParameters?): Boolean {
        AppLogger.d("VpnWatchdogJobService: Job started - checking VPN status")

        serviceScope.launch {
            try {
                checkAndRestartVpnIfNeeded()
            } finally {
                // Job completed
                jobFinished(params, false) // false = don't reschedule
            }
        }

        // Return true because we're doing work asynchronously
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        AppLogger.d("VpnWatchdogJobService: Job stopped")
        serviceScope.cancel()
        // Return true to reschedule the job
        return true
    }

    private suspend fun checkAndRestartVpnIfNeeded() {
        val prefs = Preferences(this)

        // Check if VPN should be running
        if (!prefs.vpnServiceWasRunning) {
            AppLogger.d("VpnWatchdogJobService: VPN not expected to be running, skipping")
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
                AppLogger.w("VpnWatchdogJobService: MIUI detected - VPN service is dead, attempting restart")
            } else {
                AppLogger.w("VpnWatchdogJobService: VPN service is dead, attempting restart")
            }

            try {
                // Wait a bit to avoid rapid restart loops
                delay(2000)

                // Start VPN service
                val intent = Intent(this, TProxyService::class.java).apply {
                    action = TProxyService.ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                AppLogger.i("VpnWatchdogJobService: VPN service restart initiated")

                // Send notification to user
                if (isMIUI) {
                    AppLogger.i("VpnWatchdogJobService: MIUI - VPN restarted by watchdog. User may need to check autostart permissions.")
                }
            } catch (e: Exception) {
                AppLogger.e("VpnWatchdogJobService: Failed to restart VPN service: ${e.message}", e)
            }
        } else {
            AppLogger.d("VpnWatchdogJobService: VPN service is running normally")
        }
    }

    companion object {
        private const val JOB_ID = 1001
        private const val CHECK_INTERVAL_MS = 30000L // 30 seconds

        /**
         * Schedule watchdog job to periodically check VPN status.
         * This is the primary mechanism for MIUI compatibility.
         */
        fun scheduleWatchdog(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            if (jobScheduler == null) {
                AppLogger.e("VpnWatchdogJobService: JobScheduler not available")
                return
            }

            val componentName = ComponentName(context, VpnWatchdogJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, componentName).apply {
                // Run periodically
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Minimum interval is 15 minutes for periodic jobs
                    // But we can use setPeriodic with flex interval for more frequent checks
                    setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L) // Every 15 min with 5 min flex
                } else {
                    setPeriodic(15 * 60 * 1000L)
                }

                // Required network condition
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

                // Persist across reboots (critical for MIUI)
                setPersisted(true)

                // Don't require charging
                setRequiresCharging(false)

                // Don't require device idle
                setRequiresDeviceIdle(false)
            }.build()

            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                AppLogger.i("VpnWatchdogJobService: Watchdog job scheduled successfully")

                val manufacturer = Build.MANUFACTURER
                if (manufacturer.equals("Xiaomi", ignoreCase = true) ||
                    manufacturer.equals("Redmi", ignoreCase = true)) {
                    AppLogger.i("VpnWatchdogJobService: MIUI detected - Watchdog will monitor VPN service")
                }
            } else {
                AppLogger.e("VpnWatchdogJobService: Failed to schedule watchdog job")
            }
        }

        /**
         * Cancel watchdog job when VPN is manually stopped.
         */
        fun cancelWatchdog(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            jobScheduler?.cancel(JOB_ID)
            AppLogger.d("VpnWatchdogJobService: Watchdog job cancelled")
        }
    }
}
