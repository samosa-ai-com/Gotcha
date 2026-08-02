package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings

class SystemTool(
    private val context: Context,
    private val settingsRouter: SettingsRouter = SettingsRouter(context)
) {

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
            // Read before writing so the reply can say what was overwritten — the
            // user never sees this change happen, and it is the only way the model
            // can put the old value back if asked.
            val previous = previousBrightnessLabel(resolver)
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
            ToolResult.ok("Brightness $previous → $percent%.")
        } catch (e: Exception) {
            ToolResult.error("Could not set brightness: ${e.message}")
        }
    }

    /**
     * The brightness the user had, as text. Reports "auto" when adaptive
     * brightness was on: the stored [Settings.System.SCREEN_BRIGHTNESS] is
     * whatever the system last picked, so quoting it as the user's setting would
     * be misleading, and telling the model to restore it would silently leave
     * adaptive brightness off.
     */
    private fun previousBrightnessLabel(resolver: android.content.ContentResolver): String = try {
        val auto = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        if (auto) {
            "auto"
        } else {
            val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (raw < 0) "unknown" else "${(raw * 100) / 255}%"
        }
    } catch (_: Exception) {
        "unknown"
    }

    fun getBatteryInfo(): ToolResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // Temperature, voltage, health, and plugged info come from the sticky battery intent.
            val batteryIntent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val tempRaw = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val healthInt = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1

            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val pluggedLabel = when (plugged) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> null
            }
            val tempC = if (tempRaw > 0) tempRaw / 10f else null
            val healthLabel = when (healthInt) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
                android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> null
            }
            val message = buildString {
                append("Battery at $percent%")
                if (charging) {
                    append(", charging")
                    if (pluggedLabel != null) append(" ($pluggedLabel)")
                } else {
                    append(", not charging")
                }
                if (tempC != null) append(", temp: ${"%.1f".format(tempC)}°C")
                if (voltage > 0) append(", voltage: ${voltage / 1000}V")
                if (healthLabel != null) append(", health: $healthLabel")
            }
            ToolResult.ok(message)
        } catch (e: Exception) {
            ToolResult.error("Could not read battery info: ${e.message}")
        }
    }

    fun toggleWifi(enabled: Boolean): ToolResult {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wasEnabled = wifi?.isWifiEnabled
        return try {
            if (wifi != null && wifi.setWifiEnabled(enabled)) {
                val now = wifi.isWifiEnabled
                return ToolResult.ok(
                    "Wi-Fi turned ${if (enabled) "on" else "off"}" +
                        if (wasEnabled != null) " (was ${if (wasEnabled) "on" else "off"}, now ${if (now) "on" else "off"})." else "."
                )
            }
            openWifiSettings()
        } catch (_: SecurityException) {
            openWifiSettings()
        } catch (e: Exception) {
            ToolResult.error("Could not toggle Wi-Fi: ${e.message}")
        }
    }

    /**
     * The path every device from Android 10 on actually takes: `setWifiEnabled`
     * has been a no-op since API 29, so the panel is the only way. Routed through
     * [SettingsRouter] so there is one place that knows how Wi-Fi is reached.
     */
    private fun openWifiSettings(): ToolResult =
        settingsRouter.open("wifi", confirmed = false)

    fun openApp(packageName: String): ToolResult {
        return try {
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (_: Exception) { packageName }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ToolResult.ok("Launched $label.")
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
            ToolResult.error(
                "No launchable app found matching '$packageName'. You may use list_installed_apps to find the correct name or package name."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not open app: ${e.message}")
        }
    }
}
