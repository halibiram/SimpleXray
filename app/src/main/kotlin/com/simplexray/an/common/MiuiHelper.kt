package com.simplexray.an.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper utilities for MIUI/HyperOS compatibility.
 *
 * MIUI has aggressive battery optimization and service killing policies.
 * This class helps navigate users to necessary permission settings.
 */
object MiuiHelper {

    /**
     * Check if device is running MIUI/HyperOS
     */
    fun isMiuiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER
        return manufacturer.equals("Xiaomi", ignoreCase = true) ||
               manufacturer.equals("Redmi", ignoreCase = true) ||
               manufacturer.equals("POCO", ignoreCase = true)
    }

    /**
     * Get MIUI version if available
     */
    fun getMiuiVersion(): String? {
        return try {
            val props = Class.forName("android.os.SystemProperties")
            val get = props.getMethod("get", String::class.java)
            get.invoke(props, "ro.miui.ui.version.name") as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if battery optimization is disabled for the app.
     * MIUI requires this to keep VPN service alive.
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val packageName = context.packageName
            return powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        }
        return true // Not applicable on older versions
    }

    /**
     * Open battery optimization settings for the app.
     * User must disable battery optimization to prevent MIUI from killing VPN service.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLogger.i("MiuiHelper: Opened battery optimization settings")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.e("MiuiHelper: Failed to open battery optimization settings: ${e.message}", e)
            false
        }
    }

    /**
     * Open MIUI autostart permission settings.
     * This is critical for VPN service to restart after being killed.
     */
    fun openMiuiAutoStartSettings(context: Context): Boolean {
        return try {
            // MIUI autostart settings
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.i("MiuiHelper: Opened MIUI autostart settings")
            true
        } catch (e: Exception) {
            // Fallback: try alternative MIUI settings paths
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.securitycenter.MainActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLogger.i("MiuiHelper: Opened MIUI security center (fallback)")
                true
            } catch (e2: Exception) {
                AppLogger.e("MiuiHelper: Failed to open MIUI autostart settings: ${e2.message}", e2)
                false
            }
        }
    }

    /**
     * Open MIUI battery saver settings.
     * User should add app to "No restrictions" list.
     */
    fun openMiuiBatterySaverSettings(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.i("MiuiHelper: Opened MIUI battery saver settings")
            true
        } catch (e: Exception) {
            AppLogger.e("MiuiHelper: Failed to open MIUI battery saver settings: ${e.message}", e)
            false
        }
    }

    /**
     * Get user-friendly message about required MIUI permissions.
     */
    fun getMiuiPermissionsMessage(context: Context): String {
        val appName = context.applicationInfo.loadLabel(context.packageManager)
        val miuiVersion = getMiuiVersion() ?: "HyperOS"

        return """
            |MIUI/HyperOS Compatibility Setup
            |
            |To keep VPN service running on $miuiVersion, please:
            |
            |1. Enable Autostart for $appName
            |   • Settings → Apps → Manage apps → $appName → Autostart
            |
            |2. Disable Battery Optimization for $appName
            |   • Settings → Apps → Manage apps → $appName → Battery saver → No restrictions
            |
            |3. Lock app in Recent Apps
            |   • Open Recent Apps, swipe down on $appName to lock it
            |
            |Without these settings, MIUI may kill the VPN service.
        """.trimMargin()
    }

    /**
     * Log MIUI device information for debugging
     */
    fun logMiuiDeviceInfo() {
        if (!isMiuiDevice()) return

        AppLogger.i("=== MIUI Device Information ===")
        AppLogger.i("Manufacturer: ${Build.MANUFACTURER}")
        AppLogger.i("Model: ${Build.MODEL}")
        AppLogger.i("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        AppLogger.i("MIUI Version: ${getMiuiVersion() ?: "Unknown"}")
        AppLogger.i("==============================")
    }

    /**
     * Check if all required MIUI permissions are granted
     */
    fun checkMiuiPermissions(context: Context): MiuiPermissionStatus {
        if (!isMiuiDevice()) {
            return MiuiPermissionStatus.NOT_MIUI
        }

        val batteryOptDisabled = isBatteryOptimizationDisabled(context)

        // We can't programmatically check autostart permission
        // So we return NEEDS_SETUP if battery optimization is enabled
        return if (batteryOptDisabled) {
            MiuiPermissionStatus.OK
        } else {
            MiuiPermissionStatus.NEEDS_SETUP
        }
    }

    enum class MiuiPermissionStatus {
        NOT_MIUI,           // Not a MIUI device
        OK,                 // All permissions granted
        NEEDS_SETUP         // Needs user to configure MIUI permissions
    }
}
