package com.gotcha.tools

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Process

class AppsTool(private val context: Context) {

    /** List launchable installed apps (name + package). Uses the manifest <queries> visibility. */
    fun listInstalledApps(): ToolResult {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }
                .distinct()
                .sorted()
            if (apps.isEmpty()) return ToolResult.ok("No launchable apps are visible.")
            ToolResult.ok("Installed apps (${apps.size}):\n" + apps.joinToString("\n") { "- $it" })
        } catch (e: Exception) {
            ToolResult.error("Could not list apps: ${e.message}")
        }
    }

    /** Open the system uninstall dialog for a package (user confirms). */
    fun uninstallApp(packageName: String): ToolResult {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return ToolResult.error("Please provide the package name to uninstall.")
        return try {
            val installed = try {
                context.packageManager.getPackageInfo(pkg, 0); true
            } catch (_: Exception) {
                false
            }
            if (!installed) return ToolResult.error("No app with package '$pkg' is installed.")
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult.ok("Opened the uninstall dialog for $pkg. The user must confirm removal.")
        } catch (e: Exception) {
            ToolResult.error("Could not start uninstall: ${e.message}")
        }
    }

    /** Report per-app screen time over the last [days] days (needs Usage-access special access). */
    fun getAppUsage(days: Int): ToolResult {
        if (!hasUsageAccess()) {
            return ToolResult.permissionNeeded(
                ToolResult.USAGE_ACCESS,
                "App-usage stats need Usage access. I have opened that settings page — please enable it for Gotcha and ask again."
            )
        }
        val window = days.coerceIn(1, 90)
        val end = System.currentTimeMillis()
        val start = end - window * 24L * 60 * 60 * 1000
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            val top = stats
                .filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }
                .take(10)
            if (top.isEmpty()) return ToolResult.ok("No app-usage data in the last $window day(s).")
            val pm = context.packageManager
            val out = top.joinToString("\n") { u ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, 0)).toString()
                } catch (_: Exception) {
                    u.packageName
                }
                "- $label: ${formatDuration(u.totalTimeInForeground)}"
            }
            ToolResult.ok("Top app usage over the last $window day(s):\n$out")
        } catch (e: Exception) {
            ToolResult.error("Could not read app usage: ${e.message}")
        }
    }

    /** Report total mobile + Wi-Fi data used over the last [days] days (needs Usage access). */
    fun getDataUsage(days: Int): ToolResult {
        if (!hasUsageAccess()) {
            return ToolResult.permissionNeeded(
                ToolResult.USAGE_ACCESS,
                "Data-usage stats need Usage access. I have opened that settings page — please enable it for Gotcha and ask again."
            )
        }
        val window = days.coerceIn(1, 90)
        val end = System.currentTimeMillis()
        val start = end - window * 24L * 60 * 60 * 1000
        return try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            @Suppress("DEPRECATION")
            val mobile = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end)
            @Suppress("DEPRECATION")
            val wifi = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, start, end)
            val mobileTotal = mobile.rxBytes + mobile.txBytes
            val wifiTotal = wifi.rxBytes + wifi.txBytes
            ToolResult.ok(
                "Data used over the last $window day(s):\n" +
                    "- Mobile: ${StorageTool.format(mobileTotal)}\n" +
                    "- Wi-Fi: ${StorageTool.format(wifiTotal)}"
            )
        } catch (e: Exception) {
            ToolResult.error("Could not read data usage: ${e.message}")
        }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
