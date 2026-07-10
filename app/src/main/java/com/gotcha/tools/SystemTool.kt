package com.gotcha.tools

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings

class SystemTool(private val context: Context) {

    fun toggleDarkMode(enabled: Boolean): ToolResult {
        return try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                uiModeManager.setApplicationNightMode(
                    if (enabled) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                )
                ToolResult.ok("${if (enabled) "Dark" else "Light"} mode enabled for the app.")
            } else {
                // System-wide night mode below Android 12 needs privileged access;
                // fall back to reporting the limitation honestly.
                ToolResult.error(
                    "Changing the theme programmatically requires Android 12+. " +
                        "On this device the user can toggle dark mode in Settings > Display."
                )
            }
        } catch (e: Exception) {
            ToolResult.error("Could not change theme: ${e.message}")
        }
    }

    fun setBrightness(percent: Int): ToolResult {
        if (percent !in 0..100) {
            return ToolResult.error("Brightness must be between 0 and 100 (got $percent).")
        }
        if (!Settings.System.canWrite(context)) {
            return ToolResult.permissionNeeded(
                ToolResult.WRITE_SETTINGS,
                "Missing the 'Modify system settings' special access. " +
                    "I have opened the settings page — please enable it for Gotcha and ask again."
            )
        }
        return try {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (percent * 255) / 100
            )
            ToolResult.ok("Screen brightness set to $percent%.")
        } catch (e: Exception) {
            ToolResult.error("Could not set brightness: ${e.message}")
        }
    }

    fun getBatteryInfo(): ToolResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            ToolResult.ok("Battery at $percent%, ${if (charging) "charging" else "not charging"}.")
        } catch (e: Exception) {
            ToolResult.error("Could not read battery info: ${e.message}")
        }
    }

    fun toggleWifi(): ToolResult {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ToolResult.ok("Opened the Wi-Fi settings panel so the user can toggle Wi-Fi.")
        } catch (e: Exception) {
            ToolResult.error("Could not open Wi-Fi settings: ${e.message}")
        }
    }

    fun openApp(packageName: String): ToolResult {
        return try {
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ToolResult.ok("Launched $packageName.")
            }
            // Fuzzy fallback: match against installed app labels.
            val query = packageName.lowercase()
            val match = pm.getInstalledApplications(0).firstOrNull {
                pm.getApplicationLabel(it).toString().lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
            if (match != null) {
                val intent = pm.getLaunchIntentForPackage(match.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return ToolResult.ok(
                        "Launched ${pm.getApplicationLabel(match)} (${match.packageName})."
                    )
                }
            }
            ToolResult.error("No launchable app found matching '$packageName'.")
        } catch (e: Exception) {
            ToolResult.error("Could not open app: ${e.message}")
        }
    }
}
