package com.gotcha.tools

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log

class AppsTool(private val context: Context) {

    private companion object {
        const val TAG = "Gotcha"
    }

    private data class AppEntry(val label: String, val packageName: String, val isSystemApp: Boolean)

    /** List installed apps with optional search filter. */
    fun listInstalledApps(search: String?): ToolResult {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val allApps = pm.queryIntentActivities(intent, 0).map {
                val isSystem = (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    isSystemApp = isSystem
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

            val userApps = allApps.count { !it.isSystemApp }
            val systemApps = allApps.count { it.isSystemApp }
            val total = allApps.size

            val query = search?.trim()?.lowercase()

            if (query.isNullOrBlank()) {
                // Full listing
                val listing = allApps.take(200).joinToString("\n") {
                    "- ${it.label} (${it.packageName})${if (it.isSystemApp) " [system]" else ""}"
                }
                val truncated = if (allApps.size > 200) "\n…(${allApps.size - 200} more apps — use search to find specific ones)" else ""
                ToolResult.ok("Total: $total apps installed ($userApps user, $systemApps system).\n$listing$truncated")
            } else {
                // Filtered listing
                val matches = allApps.filter {
                    it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                }
                if (matches.isEmpty()) {
                    return ToolResult.ok(
                        "No apps matching '$search' ($total apps installed total). Try a different search term."
                    )
                }
                val matchCount = matches.size
                val listing = matches.take(30).joinToString("\n") {
                    "- ${it.label} (${it.packageName})${if (it.isSystemApp) " [system]" else ""}"
                }
                val truncated = if (matchCount > 30) " (showing 30 of $matchCount)" else ""
                ToolResult.ok(
                    "Found $matchCount app(s) matching '$search'$truncated ($total total, $userApps user, $systemApps system).\n$listing"
                )
            }
        } catch (e: Exception) {
            ToolResult.error(
                "Could not list apps: ${e.message}. You may check that the system is not restricting package visibility."
            )
        }
    }

    /**
     * Uninstall an app. First call resolves the app name/package and returns
     * a confirmation request. The ChatViewModel catches that marker, shows a
     * dialog, and if confirmed calls [doUninstall] to actually execute.
     */
    fun uninstallApp(packageName: String): ToolResult {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return ToolResult.error(
                "Please provide the app name or package name. You may use list_installed_apps to discover the correct name."
            )
        }
        val pm = context.packageManager

        // Try exact package match first
        val info = try {
            pm.getApplicationInfo(pkg, 0).let { it to pm.getApplicationLabel(it).toString() }
        } catch (_: Exception) {
            null
        }

        if (info != null) {
            val label = info.second
            return ToolResult.ok("CONFIRM_UNINSTALL:$label:$pkg")
        }

        // Fuzzy match by app label
        val query = pkg.lowercase()
        val match = try {
            pm.getInstalledApplications(0).firstOrNull { app ->
                val label = try { pm.getApplicationLabel(app).toString().lowercase() } catch (_: Exception) { "" }
                label.contains(query) || app.packageName.lowercase().contains(query)
            }
        } catch (_: Exception) { null }

        if (match != null) {
            val label = try { pm.getApplicationLabel(match).toString() } catch (_: Exception) { match.packageName }
            return ToolResult.ok("CONFIRM_UNINSTALL:$label:${match.packageName}")
        }

        return ToolResult.error(
            "No app found matching '$packageName'. You may use list_installed_apps to find the correct package name or try " +
                "a shorter part of the app name."
        )
    }

    /**
     * Fires the uninstall intent after the user has confirmed the destructive
     * action. The system uninstall dialog opens and the user must tap OK.
     * Detection of completion is unreliable on some OEMs (Xiaomi MIUI);
     * the tool returns immediately and the LLM asks the user to verify
     * the app is gone via list_installed_apps after tapping OK.
     */
    fun doUninstall(packageName: String): ToolResult {
        Log.d(TAG, "doUninstall: $packageName")
        return try {
            val pm = context.packageManager
            val installed = try {
                pm.getPackageInfo(packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
            if (!installed) {
                return ToolResult.error(
                    "No app with package '$packageName' is installed. You may use list_installed_apps to verify the correct name."
                )
            }

            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) { packageName }

            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Log.d(TAG, "doUninstall: dialog opened for $label")
            ToolResult.ok(
                "System dialog opened to uninstall $label. After you tap OK in the dialog, ask the assistant to verify the app is gone."
            )
        } catch (e: Exception) {
            Log.e(TAG, "doUninstall failed: ${e.message}")
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
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
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
