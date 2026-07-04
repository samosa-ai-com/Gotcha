package com.gotcha.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Single dispatch point for all tool side effects. Validates args, checks
 * preconditions (executors return permission errors instead of crashing),
 * and records every execution in the [ActionLog].
 */
class ToolExecutor(context: Context) {

    private val appContext = context.applicationContext
    private val phoneTool = PhoneTool(appContext)
    private val systemTool = SystemTool(appContext)
    private val storageTool = StorageTool(appContext)
    private val fileTool = FileTool(appContext)
    private val terminalTool = TerminalTool()
    private val wallpaperTool = WallpaperTool(appContext)
    private val actionLog = ActionLog(appContext)

    suspend fun execute(name: String, args: JsonObject): ToolResult {
        if (!ToolRegistry.contains(name)) {
            return ToolResult.error("Unknown tool '$name'. Only the fixed tool catalog is available.")
        }
        val result = try {
            withContext(Dispatchers.IO) { dispatch(name, args) }
        } catch (e: Exception) {
            ToolResult.error("Tool '$name' failed: ${e.message}")
        }
        actionLog.record(name, args.toString(), result)
        return result
    }

    private suspend fun dispatch(name: String, args: JsonObject): ToolResult {
        return when (name) {
        "dial_number" -> phoneTool.dialNumber(args.requireString("number") ?: return missing("number"))
        "get_storage_info" -> storageTool.getStorageInfo()
        "get_battery_info" -> systemTool.getBatteryInfo()
        "clear_app_cache" -> storageTool.clearAppCache()
        "list_files" -> fileTool.listFiles(args.requireString("path") ?: return missing("path"))
        "read_file" -> fileTool.readFile(args.requireString("path") ?: return missing("path"))
        "write_file" -> fileTool.writeFile(
            path = args.requireString("path") ?: return missing("path"),
            content = args.requireString("content") ?: return missing("content"),
            append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
        )
        "open_app" -> systemTool.openApp(args.requireString("package_name") ?: return missing("package_name"))
        "toggle_dark_mode" -> systemTool.toggleDarkMode(
            args["enabled"]?.jsonPrimitive?.booleanOrNull ?: return missing("enabled")
        )
        "set_brightness" -> systemTool.setBrightness(
            args["percent"]?.jsonPrimitive?.intOrNull ?: return missing("percent")
        )
        "toggle_wifi" -> systemTool.toggleWifi()
        "set_wallpaper" -> wallpaperTool.setWallpaper(args.requireString("url"))
        "run_command" -> terminalTool.runCommand(args.requireString("command") ?: return missing("command"))
        else -> ToolResult.error("Tool '$name' has no executor.")
        }
    }

    private fun JsonObject.requireString(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    private fun missing(param: String) =
        ToolResult.error("Missing or invalid required parameter '$param'.")
}
