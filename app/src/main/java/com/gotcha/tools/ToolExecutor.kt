package com.gotcha.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Single dispatch point for all tool side effects. Validates args, checks
 * preconditions (executors return permission errors instead of crashing),
 * and records every execution in the [ActionLog].
 */
class ToolExecutor(context: Context) {

    private val TAG = "Gotcha"
    private val appContext = context.applicationContext
    private val phoneTool = PhoneTool(appContext)
    private val systemTool = SystemTool(appContext)
    private val storageTool = StorageTool()
    private val fileTool = FileTool(appContext)
    private val terminalTool = TerminalTool()
    private val wallpaperTool = WallpaperTool(appContext)
    private val contactsTool = ContactsTool(appContext)
    private val smsTool = SmsTool(appContext)
    private val calendarTool = CalendarTool(appContext)
    private val alarmTool = AlarmTool(appContext)
    private val deviceTool = DeviceTool(appContext)
    private val locationTool = LocationTool(appContext)
    private val appsTool = AppsTool(appContext)
    private val clipboardTool = ClipboardTool(appContext)
    private val mediaCaptureTool = MediaCaptureTool(appContext)
    // Tier 3 tools
    private val webSearchTool = WebSearchTool()
    private val webFetchTool = WebFetchTool()
    private val questionTool = QuestionTool()
    private val todoTool = TodoTool()
    private val editTool = EditTool(appContext)
    private val globTool = GlobTool(appContext)
    private val grepTool = GrepTool(appContext)
    private val accessibilityTool = AccessibilityTool(appContext)
    private val notificationTool = NotificationTool(appContext)
    private val overlayTool = OverlayTool(appContext)
    private val deviceAdminTool = DeviceAdminTool(appContext)
    private val vpnTool = VpnTool(appContext)
    // Tier 4 tools
    private val rootTool = RootTool()
    private val actionLog = ActionLog(appContext)

    /**
     * Execute [name] with [args] on behalf of the given [agent].
     * Returns an error without running the tool if the agent mode disallows it.
     */
    suspend fun execute(name: String, args: JsonObject, agent: AgentMode = AgentMode.OPERATOR): ToolResult {
        if (!ToolRegistry.contains(name)) {
            return ToolResult.error("Unknown tool '$name'. Only the fixed tool catalog is available.")
        }
        if (!ToolRegistry.isAllowedForAgent(name, agent)) {
            return ToolResult.error(
                "This action is not available in ${agent.name} mode. " +
                "Switch to Operator mode if you need to perform this action."
            )
        }
        val result = try {
            withContext(Dispatchers.IO) { dispatch(name, args) }
        } catch (e: Exception) {
            ToolResult.error("Tool '$name' failed: ${e.message}")
        }
        Log.d(TAG, "execute: $name -> success=${result.success}, msg=${result.message.take(80)}, perm=${result.needsPermission}")
        actionLog.record(name, args.toString(), result)
        return result
    }

    /**
     * Execute an uninstall that was already confirmed by the user.
     * Bypasses the destructive-action confirmation flow.
     */
    suspend fun executeUninstall(packageName: String): ToolResult {
        Log.d(TAG, "executeUninstall: $packageName")
        val result = withContext(Dispatchers.IO) { appsTool.doUninstall(packageName) }
        actionLog.record("uninstall_app", packageName, result)
        return result
    }

    private suspend fun dispatch(name: String, args: JsonObject): ToolResult {
        return when (name) {
        "dial_number" -> phoneTool.dialNumber(args.requireString("number") ?: return missing("number"))
        "get_storage_info" -> storageTool.getStorageInfo()
        "get_battery_info" -> systemTool.getBatteryInfo()
        "edit" -> editTool.edit(
            path = args.requireString("path") ?: return missing("path"),
            oldString = args.requireString("oldString") ?: return missing("oldString"),
            newString = args.requireString("newString") ?: return missing("newString"),
            replaceAll = args["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false
        )
        "list_files" -> fileTool.listFiles(
            path = args.requireString("path") ?: return missing("path"),
            recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false,
            sortBy = args.requireString("sort_by"),
            include = args.requireString("include"),
            exclude = args.requireString("exclude"),
            maxDepth = args.requireInt("max_depth")
        )
        "read_file" -> fileTool.readFile(
            path = args.requireString("path") ?: return missing("path"),
            offset = args.requireInt("offset"),
            limit = args.requireInt("limit"),
            encoding = args.requireString("encoding")
        )
        "write_file" -> fileTool.writeFile(
            path = args.requireString("path") ?: return missing("path"),
            content = args.requireString("content") ?: return missing("content"),
            append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false,
            binary = args["binary"]?.jsonPrimitive?.booleanOrNull ?: false
        )
        "open_app" -> systemTool.openApp(args.requireString("package_name") ?: return missing("package_name"))
        "set_brightness" -> systemTool.setBrightness(
            args["percent"]?.jsonPrimitive?.intOrNull ?: return missing("percent")
        )
        "toggle_wifi" -> systemTool.toggleWifi(
            args["enabled"]?.jsonPrimitive?.booleanOrNull ?: return missing("enabled")
        )
        "set_wallpaper" -> wallpaperTool.setWallpaper(args.requireString("url"))
        "run_command" -> terminalTool.runCommand(
            command = args.requireString("command") ?: return missing("command"),
            workingDir = args.requireString("working_dir"),
            timeoutSeconds = args.requireInt("timeout_seconds") ?: 15
        )
        "call_number" -> phoneTool.callNumber(args.requireString("number") ?: return missing("number"))
        "read_call_log" -> phoneTool.readCallLog(args.requireInt("limit") ?: 10)
        "find_contact" -> contactsTool.findContact(args.requireString("name") ?: return missing("name"))
        "add_contact" -> contactsTool.addContact(
            name = args.requireString("name") ?: return missing("name"),
            number = args.requireString("number") ?: return missing("number")
        )
        "send_sms" -> smsTool.sendSms(
            number = args.requireString("number") ?: return missing("number"),
            message = args.requireString("message") ?: return missing("message")
        )
        "read_recent_sms" -> smsTool.readRecentSms(args.requireInt("limit") ?: 10)
        "list_calendar_events" -> calendarTool.listEvents(args.requireInt("days_ahead") ?: 7)
        "create_calendar_event" -> calendarTool.createEvent(
            title = args.requireString("title") ?: return missing("title"),
            start = args.requireString("start") ?: return missing("start"),
            end = args.requireString("end"),
            location = args.requireString("location")
        )
        "set_alarm" -> alarmTool.setAlarm(
            hour = args.requireInt("hour") ?: return missing("hour"),
            minute = args.requireInt("minute") ?: return missing("minute"),
            message = args.requireString("message")
        )
        "set_timer" -> alarmTool.setTimer(
            seconds = args.requireInt("seconds") ?: return missing("seconds"),
            message = args.requireString("message")
        )
        "toggle_torch" -> deviceTool.toggleTorch(
            on = args["on"]?.jsonPrimitive?.booleanOrNull ?: return missing("on"),
            durationSeconds = args.requireInt("duration_seconds")
        )
        "set_volume" -> deviceTool.setVolume(
            stream = args.requireString("stream") ?: return missing("stream"),
            percent = args.requireInt("percent") ?: return missing("percent"),
            showUi = args["show_ui"]?.jsonPrimitive?.booleanOrNull ?: false
        )
        "get_volume" -> deviceTool.getVolume(args.requireString("stream"))
        "set_ringer_mode" -> deviceTool.setRingerMode(args.requireString("mode") ?: return missing("mode"))
        "vibrate" -> deviceTool.vibrate(
            durationMs = args.requireInt("duration_ms") ?: 500,
            intensity = args.requireInt("intensity") ?: 100,
            pattern = args.requireString("pattern")
        )
        "set_dnd" -> deviceTool.setDnd(
            args["enabled"]?.jsonPrimitive?.booleanOrNull ?: return missing("enabled")
        )
        "get_location" -> locationTool.getLocation()
        "list_installed_apps" -> appsTool.listInstalledApps(args.requireString("search"))
        "uninstall_app" -> appsTool.uninstallApp(args.requireString("package_name") ?: return missing("package_name"))
        "get_app_usage" -> appsTool.getAppUsage(args.requireInt("days") ?: 7)
        "get_data_usage" -> appsTool.getDataUsage(args.requireInt("days") ?: 30)
        "get_clipboard" -> clipboardTool.getClipboard()
        "set_clipboard" -> clipboardTool.setClipboard(args.requireString("text") ?: return missing("text"))
        "take_photo" -> mediaCaptureTool.takePhoto(args.requireString("camera"))
        "start_audio_recording" -> mediaCaptureTool.startAudioRecording()
        "stop_audio_recording" -> mediaCaptureTool.stopAudioRecording()
        "question" -> questionTool.ask(
            question = args.requireString("question") ?: return missing("question"),
            options = args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content },
            allowCustom = args["allowCustom"]?.jsonPrimitive?.booleanOrNull ?: true
        )
        "websearch" -> webSearchTool.search(
            query = args.requireString("query") ?: return missing("query"),
            numResults = args.requireInt("numResults") ?: 5
        )
        "webfetch" -> webFetchTool.fetch(
            url = args.requireString("url") ?: return missing("url"),
            format = args.requireString("format")
        )
        "todowrite" -> todoTool.todowrite(
            parseTodoItems(args["items"]) ?: return missing("items")
        )
        "read_image" -> fileTool.readFile(args.requireString("path") ?: return missing("path"))
        "glob" -> globTool.glob(
            path = args.requireString("path") ?: return missing("path"),
            pattern = args.requireString("pattern") ?: return missing("pattern")
        )
        "grep" -> grepTool.grep(
            path = args.requireString("path") ?: return missing("path"),
            pattern = args.requireString("pattern") ?: return missing("pattern"),
            include = args.requireString("include")
        )
        // ---- Tier 3 ----
        "read_screen" -> accessibilityTool.readScreen()
        "tap" -> accessibilityTool.tap(
            text = args.requireString("text"),
            x = args.requireInt("x"),
            y = args.requireInt("y")
        )
        "swipe" -> accessibilityTool.swipe(
            direction = args.requireString("direction"),
            x1 = args.requireInt("x1"), y1 = args.requireInt("y1"),
            x2 = args.requireInt("x2"), y2 = args.requireInt("y2")
        )
        "input_text" -> accessibilityTool.inputText(args.requireString("text") ?: return missing("text"))
        "global_action" -> accessibilityTool.globalAction(args.requireString("action") ?: return missing("action"))
        "read_notifications" -> notificationTool.readNotifications(args.requireInt("limit") ?: 15)
        "dismiss_notifications" -> notificationTool.dismissNotifications(args.requireString("key"))
        "media_control" -> notificationTool.mediaControl(args.requireString("action") ?: return missing("action"))
        "show_overlay" -> overlayTool.showOverlay(
            text = args.requireString("text") ?: return missing("text"),
            durationMs = args.requireInt("duration_ms") ?: 4000
        )
        "hide_overlay" -> overlayTool.hideOverlay()
        "lock_screen" -> deviceAdminTool.lockScreen()
        "disable_camera" -> deviceAdminTool.disableCamera(
            args["disabled"]?.jsonPrimitive?.booleanOrNull ?: return missing("disabled")
        )
        "set_password_policy" -> deviceAdminTool.setPasswordPolicy(
            args.requireInt("min_length") ?: return missing("min_length")
        )
        "set_firewall" -> vpnTool.setFirewall(
            args["enabled"]?.jsonPrimitive?.booleanOrNull ?: return missing("enabled")
        )
        "get_firewall_status" -> vpnTool.getFirewallStatus()
        // ---- Tier 4 ----
        "check_root" -> rootTool.checkRoot()
        "run_root_command" -> rootTool.runRootCommand(args.requireString("command") ?: return missing("command"))
        "write_secure_settings" -> rootTool.writeSecureSetting(
            namespace = args.requireString("namespace") ?: return missing("namespace"),
            key = args.requireString("key") ?: return missing("key"),
            value = args.requireString("value") ?: return missing("value")
        )
        else -> ToolResult.error("Tool '$name' has no executor.")
        }
    }

    private fun JsonObject.requireString(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    /** Parse an integer argument; tolerates numbers sent as JSON strings by the LLM. */
    private fun JsonObject.requireInt(key: String): Int? =
        this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

    private fun missing(param: String) =
        ToolResult.error("Missing or invalid required parameter '$param'.")

    private fun parseTodoItems(element: JsonElement?): List<TodoItem>? {
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val statusStr = obj["status"]?.jsonPrimitive?.content ?: "pending"
            val status = parseTodoStatus(statusStr)
            val priority = obj["priority"]?.jsonPrimitive?.content
            TodoItem(content = content, status = status, priority = priority)
        }.toList().ifEmpty { null }
    }

    private fun parseTodoStatus(s: String): TodoStatus = when (s.lowercase().trim()) {
        "in_progress", "in progress", "inprogress" -> TodoStatus.IN_PROGRESS
        "completed", "done", "complete" -> TodoStatus.COMPLETED
        "cancelled", "canceled", "cancelled" -> TodoStatus.CANCELLED
        else -> TodoStatus.PENDING
    }
}
