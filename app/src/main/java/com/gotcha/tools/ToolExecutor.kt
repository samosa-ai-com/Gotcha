package com.gotcha.tools

import android.content.Context
import android.util.Log
import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.connectors.ConnectorCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
class ToolExecutor(
    context: Context,
    val onTask: (suspend (description: String, prompt: String) -> ToolResult)? = null,
    val onNavigateApp: (suspend (task: String) -> ToolResult)? = null,
    val onUpdateUserProfile: (suspend (update: ProfileUpdate) -> ToolResult)? = null
) {

    private companion object {
        const val TAG = "Gotcha"
    }

    private val appContext = context.applicationContext
    private val phoneTool = PhoneTool(appContext)
    private val settingsRouter = SettingsRouter(appContext)
    private val systemTool = SystemTool(appContext, settingsRouter)
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
    private val companyInfoTool = CompanyInfoTool(appContext)

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

    // Tier 4 tools
    private val rootTool = RootTool()
    private val termuxTool = TermuxTool(appContext)
    private val healthTool = HealthTool(appContext)
    private val actionLog = ActionLog(appContext)

    init {
        com.gotcha.connectors.ConnectorRegistry.init(appContext)
    }

    /**
     * Execute [name] with [args] on behalf of the given [agent].
     * Returns an error without running the tool if the agent mode disallows it.
     */
    suspend fun execute(
        name: String,
        args: JsonObject,
        agent: AgentMode = AgentMode.OPERATOR,
        isSubAgent: Boolean = false,
        /**
         * Connector-owned tools withheld from the model this turn. Callers on the
         * model path pass these so a hallucinated call is refused with an
         * actionable message instead of reaching a router that cannot serve it.
         */
        hiddenTools: Set<String> = emptySet()
    ): ToolResult {
        if (!ToolRegistry.contains(name)) {
            return ToolResult.error("Unknown tool '$name'. Only the fixed tool catalog is available.")
        }
        if (name in hiddenTools) {
            return ToolResult.error(unavailableMessage(name))
        }
        if (!isSubAgent && !ToolRegistry.isAllowedForAgent(name, agent)) {
            return ToolResult.error(
                "This action is not available in ${agent.name} mode. " +
                    "Switch to Operator mode if you need to perform this action."
            )
        }
        if (isSubAgent && !ToolRegistry.isAllowedForSubAgent(name)) {
            return ToolResult.error(
                "Tool '$name' is not available to sub-agents (no recursive delegation)."
            )
        }
        val result = try {
            withContext(Dispatchers.IO) { dispatch(name, args, hiddenTools) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Tool '$name' failed: ${e.message}")
        }
        // Do not log result.message — tool payloads can contain sensitive user data
        // (SMS bodies, clipboard text, file contents, location, etc.).
        Log.d(
            TAG,
            "execute: $name -> success=${result.success}, msgLen=${result.message.length}, perm=${result.needsPermission}"
        )
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

    suspend fun executeDeleteAlarm(id: Long): ToolResult {
        return withContext(Dispatchers.IO) { alarmTool.doDeleteAlarm(id) }
    }

    suspend fun executeDeleteTimer(id: Long): ToolResult {
        return withContext(Dispatchers.IO) { alarmTool.doDeleteTimer(id) }
    }

    suspend fun executeDeleteCalendarEvent(eventId: Long): ToolResult {
        return withContext(Dispatchers.IO) { calendarTool.doDeleteEvent(eventId) }
    }

    /** Execute an email send the user already confirmed (payload from CONFIRM_SEND_EMAIL:). */
    suspend fun executeSendEmail(argsBase64: String): ToolResult {
        val email = com.gotcha.connectors.ConnectorRegistry.email()
            ?: return ToolResult.error("Email connectors are not initialized.")
        val result = withContext(Dispatchers.IO) { email.executeSendConfirmed(argsBase64) }
        actionLog.record("send_email", "(confirmed send)", result)
        return result
    }

    /** Human-readable description of a pending send, for the confirmation dialog. */
    fun describeSendEmail(argsBase64: String): String =
        com.gotcha.connectors.ConnectorRegistry.email()?.describeSend(argsBase64)
            ?: "Send an email (details unavailable)."

    // Single when-dispatch over the entire fixed tool catalog; size and branch count are
    // inherent to the design.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun dispatch(name: String, args: JsonObject, hidden: Set<String>): ToolResult {
        com.gotcha.connectors.ConnectorRegistry.toolHandler(name)?.let { return it.invoke(name, args) }
        return when (name) {
            "dial_number" -> phoneTool.dialNumber(args.requireString("number") ?: return missing("number"))
            "get_storage_info" -> storageTool.getStorageInfo()
            "get_battery_info" -> systemTool.getBatteryInfo()
            "about_samosa_ai" -> companyInfoTool.aboutSamosaAi()
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
            "open_setting" -> settingsRouter.open(
                key = args.requireString("setting") ?: return missing("setting"),
                confirmed = args["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "set_wallpaper" -> wallpaperTool.setWallpaper(args.requireString("url"))
            "run_command" -> terminalTool.runCommand(
                command = args.requireString("command") ?: return missing("command"),
                workingDir = args.requireString("working_dir"),
                timeoutSeconds = args.requireInt("timeout_seconds") ?: 15
            )
            "call_number" -> phoneTool.callNumber(
                args.requireString("number") ?: return missing("number"),
                speakerphone = args["speakerphone"]?.jsonPrimitive?.booleanOrNull,
                simSlot = args.requireString("sim_slot")
            )
            "read_call_log" -> phoneTool.readCallLog(
                limit = args.requireInt("limit") ?: 10,
                callTypeFilter = args.requireString("type"),
                contact = args.requireString("contact"),
                fromDate = args.requireString("from_date"),
                toDate = args.requireString("to_date")
            )
            "find_contact" -> contactsTool.findContact(
                name = args.requireString("name"),
                number = args.requireString("number")
            )
            "add_contact" -> contactsTool.addContact(
                name = args.requireString("name") ?: return missing("name"),
                number = args.requireString("number") ?: return missing("number"),
                phoneType = args.requireString("phone_type"),
                email = args.requireString("email"),
                organization = args.requireString("organization")
            )
            "send_sms" -> smsTool.sendSms(
                number = args.requireString("number") ?: return missing("number"),
                message = args.requireString("message") ?: return missing("message"),
                deliveryReport = args["delivery_report"]?.jsonPrimitive?.booleanOrNull,
                sendAt = args.requireString("send_at")
            )
            "read_recent_sms" -> smsTool.readRecentSms(
                limit = args.requireInt("limit") ?: 10,
                fromAddress = args.requireString("from_address"),
                fromDate = args.requireString("from_date"),
                toDate = args.requireString("to_date"),
                unreadOnly = args["unread_only"]?.jsonPrimitive?.booleanOrNull,
                search = args.requireString("search"),
                includeSent = args["include_sent"]?.jsonPrimitive?.booleanOrNull
            )
            // list_calendar_events / create_calendar_event / check_availability are owned by
            // ConnectorRegistry's CalendarTools router, which delegates the default
            // source="device" back to this same CalendarTool.
            "edit_calendar_event" -> calendarTool.editEvent(
                eventId = args.requireInt("event_id")?.toLong() ?: return missing("event_id"),
                title = args.requireString("title"),
                start = args.requireString("start"),
                end = args.requireString("end"),
                location = args.requireString("location"),
                description = args.requireString("description"),
                allDay = args["all_day"]?.jsonPrimitive?.booleanOrNull,
                reminderMinutes = args.requireInt("reminder_minutes"),
                attendees = args.requireStringList("attendees"),
                recurrence = args.requireString("recurrence"),
                recurrenceCount = args.requireInt("recurrence_count"),
                recurrenceUntil = args.requireString("recurrence_until")
            )
            "delete_calendar_event" -> calendarTool.deleteEvent(
                eventId = args.requireInt("event_id")?.toLong() ?: return missing("event_id")
            )
            "set_alarm" -> alarmTool.setAlarm(
                hour = args.requireInt("hour") ?: return missing("hour"),
                minute = args.requireInt("minute") ?: return missing("minute"),
                message = args.requireString("message"),
                days = args["days"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content },
                vibrate = args["vibrate"]?.jsonPrimitive?.booleanOrNull
            )
            "set_timer" -> alarmTool.setTimer(
                seconds = args.requireInt("seconds") ?: 0,
                message = args.requireString("message"),
                hours = args.requireInt("hours"),
                minutes = args.requireInt("minutes"),
                system = args["system"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "list_alarms" -> alarmTool.listAlarms()
            "list_timers" -> alarmTool.listTimers()
            "show_alarms" -> alarmTool.showAlarms()
            "snooze_alarm" -> alarmTool.snoozeAlarm(minutes = args.requireInt("minutes"))
            "dismiss_timer" -> alarmTool.dismissTimer()
            "edit_alarm" -> alarmTool.editAlarm(
                id = args.requireInt("alarm_id")?.toLong() ?: return missing("alarm_id"),
                hour = args.requireInt("hour"),
                minute = args.requireInt("minute"),
                message = args.requireString("message"),
                days = args["days"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content },
                vibrate = args["vibrate"]?.jsonPrimitive?.booleanOrNull
            )
            "delete_alarm" -> alarmTool.deleteAlarm(
                id = args.requireInt("alarm_id")?.toLong() ?: return missing("alarm_id")
            )
            "delete_timer" -> alarmTool.deleteTimer(
                id = args.requireInt("timer_id")?.toLong() ?: return missing("timer_id")
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
            "get_location" -> locationTool.getLocation(
                fresh = args["fresh"]?.jsonPrimitive?.booleanOrNull
            )
            "list_installed_apps" -> appsTool.listInstalledApps(args.requireString("search"))
            "uninstall_app" -> appsTool.uninstallApp(
                args.requireString("package_name") ?: return missing("package_name")
            )
            "get_app_usage" -> appsTool.getAppUsage(args.requireInt("days") ?: 7)
            "get_data_usage" -> appsTool.getDataUsage(args.requireInt("days") ?: 30)
            "get_clipboard" -> clipboardTool.getClipboard()
            "set_clipboard" -> clipboardTool.setClipboard(args.requireString("text") ?: return missing("text"))
            "take_photo" -> mediaCaptureTool.takePhoto(args.requireString("camera"))
            "start_audio_recording" -> mediaCaptureTool.startAudioRecording(
                source = args.requireString("source"),
                maxDurationSeconds = args.requireInt("max_duration_seconds"),
                outputPath = args.requireString("output_path"),
                quality = args.requireString("quality")
            )
            "stop_audio_recording" -> mediaCaptureTool.stopAudioRecording()
            "get_audio_recording_status" -> mediaCaptureTool.getAudioRecordingStatus()
            "pause_audio_recording" -> mediaCaptureTool.pauseAudioRecording()
            "resume_audio_recording" -> mediaCaptureTool.resumeAudioRecording()
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
            "ask_final_answer" -> {
                ToolResult.ok(args.requireString("answer") ?: "(no answer)")
            }
            // Marker, not a side effect: AgentEngine recognises the prefix, speaks
            // the summary and ends the run. Reaching here from anywhere else is
            // harmless — it just reads back as text.
            "finish_task" -> {
                ToolResult.ok("FINISH_TASK:" + (args.requireString("summary") ?: return missing("summary")))
            }
            "task" -> {
                val handler = onTask
                if (handler == null) {
                    ToolResult.error("Task delegation is not configured.")
                } else {
                    val description = args.requireString("description") ?: return missing("description")
                    val prompt = args.requireString("prompt") ?: return missing("prompt")
                    handler(description, prompt)
                }
            }
            "update_user_profile" -> {
                val handler = onUpdateUserProfile
                if (handler == null) {
                    ToolResult.error("Profile updates are not configured.")
                } else {
                    handler(
                        ProfileUpdate(
                            occupation = args.requireString("occupation"),
                            background = args.requireString("background"),
                            replyStyle = args.requireString("reply_style")
                        )
                    )
                }
            }
            "sleep" -> {
                val secs = args.requireInt("duration_seconds")?.coerceIn(1, 86400)
                    ?: return missing("duration_seconds")
                for (remaining in secs downTo 1) {
                    Log.d(TAG, "sleep: ${remaining}s remaining")
                    delay(1000L)
                }
                ToolResult.ok("Slept for $secs seconds.")
            }
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
            "read_screen_raw" -> accessibilityTool.readScreenRaw()
            "tap" -> {
                var x = args.requireInt("x")
                var y = args.requireInt("y")
                val normalized = args.requireBoolean("normalized") ?: true
                if (normalized && x != null && y != null) {
                    val (w, h) = ScreenPerception.getScreenDimensions()
                    val (nx, ny) = ScreenPerception.normalizeToPixel(x, y, w, h)
                    x = nx
                    y = ny
                }
                accessibilityTool.tap(text = args.requireString("text"), x = x, y = y)
            }
            "swipe" -> accessibilityTool.swipe(
                direction = args.requireString("direction"),
                x1 = args.requireInt("x1"),
                y1 = args.requireInt("y1"),
                x2 = args.requireInt("x2"),
                y2 = args.requireInt("y2"),
                normalized = args.requireBoolean("normalized") ?: true,
                index = args.requireInt("index")
            )
            "tap_index" -> {
                val index = args.requireInt("index") ?: return missing("index")
                accessibilityTool.tapByIndex(index)
            }
            "long_press" -> {
                var x = args.requireInt("x")
                var y = args.requireInt("y")
                val normalized = args.requireBoolean("normalized") ?: true
                if (normalized && x != null && y != null) {
                    val (w, h) = ScreenPerception.getScreenDimensions()
                    val pixelCoords = ScreenPerception.normalizeToPixel(x, y, w, h)
                    x = pixelCoords.first
                    y = pixelCoords.second
                }
                accessibilityTool.longPress(
                    text = args.requireString("text"),
                    x = x,
                    y = y
                )
            }
            "long_press_index" -> {
                val index = args.requireInt("index") ?: return missing("index")
                accessibilityTool.longPressByIndex(index)
            }
            "press_key" -> {
                val key = args.requireString("key") ?: return missing("key")
                accessibilityTool.pressKey(key)
            }
            "navigate_app" -> {
                val handler = onNavigateApp
                if (handler == null) {
                    ToolResult.error("App navigation is not configured.")
                } else {
                    val task = args.requireString("task") ?: return missing("task")
                    handler(task)
                }
            }
            "input_text" -> accessibilityTool.inputText(
                text = args.requireString("text") ?: return missing("text"),
                index = args.requireInt("index")
            )
            "global_action" -> accessibilityTool.globalAction(args.requireString("action") ?: return missing("action"))
            "read_notifications" -> notificationTool.readNotifications(
                limit = args.requireInt("limit") ?: 15,
                app = args.requireString("app")
            )
            "dismiss_notifications" -> notificationTool.dismissNotifications(args.requireString("key"))
            "get_health_summary" -> healthTool.getSummary(args.requireInt("days"))
            "get_health_records" -> healthTool.getRecords(
                type = args.requireString("type") ?: return missing("type"),
                daysArg = args.requireInt("days")
            )
            "media_control" -> notificationTool.mediaControl(
                action = args.requireString("action") ?: return missing("action"),
                app = args.requireString("app"),
                positionSeconds = args.requireInt("position_seconds")
            )
            "get_now_playing" -> notificationTool.getNowPlaying()
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

            // ---- Tier 4 ----
            "check_root" -> rootTool.checkRoot().also {
                // The real probe beats the binary-path guess that gates the other
                // root tools, so let it correct the cache either way.
                DeviceCapabilities.setRootAvailable(it.message.contains("Root IS available"))
            }
            "run_root_command" -> rootTool.runRootCommand(args.requireString("command") ?: return missing("command"))
            "run_termux_command" -> termuxTool.runCommand(
                command = args.requireString("command") ?: return missing("command"),
                workingDir = args.requireString("working_dir"),
                timeoutSeconds = args.requireInt("timeout_seconds"),
                stdin = args.requireString("stdin")
            )
            "write_secure_settings" -> rootTool.writeSecureSetting(
                namespace = args.requireString("namespace") ?: return missing("namespace"),
                key = args.requireString("key") ?: return missing("key"),
                value = args.requireString("value") ?: return missing("value")
            )
            "search_skills" -> {
                val query = args.requireString("query") ?: return missing("query")
                // Same gating as the auto-injected skills: never hand back advice
                // for tools the model cannot currently call.
                val results = SkillRegistry.searchSkills(query, hidden)
                if (results.isEmpty()) {
                    ToolResult.ok("No skills found matching '$query'.")
                } else {
                    val instructions = results.joinToString("\n\n") { "Skill [${it.id}]:\n${it.instructions}" }
                    ToolResult.ok("Found ${results.size} skills matching '$query':\n\n$instructions")
                }
            }
            else -> ToolResult.error("Tool '$name' has no executor.")
        }
    }

    /** Names what would make [name] work, so the model can steer the user there. */
    private fun unavailableMessage(name: String): String {
        CapabilityCatalog.ownerOf(name)?.let { capability ->
            return "Tool '$name' is unavailable: it needs ${capability.label}, which is not " +
                "available on this device right now. Tell the user what to enable; do not retry."
        }
        // Dynamic tools (e.g. Home Assistant MCP) are registered at runtime, so the
        // compile-time catalog cannot know them; name the owning connector explicitly.
        if (name in ToolRegistry.dynamicTools) {
            return "Tool '$name' is unavailable: it needs ${ConnectorCatalog.HOME_ASSISTANT.displayName}, which is not " +
                "connected or is switched off. Tell the user to set it up in the drawer " +
                "menu ▸ Connectors; do not retry."
        }
        val owners = com.gotcha.connectors.ConnectorCatalog.ownersOf(name)
            .joinToString(" or ") { it.displayName }
        val suffix = if (owners.isBlank()) {
            // Defensive fallback: today every hidden tool is owned by either a connector
            // or a capability, so this branch is unreachable. Keep a generic message in
            // case a future catalog change leaves a hidden tool with no owner.
            "It is currently unavailable."
        } else {
            "It needs $owners, which is not connected or is switched off."
        }
        return "Tool '$name' is unavailable. $suffix " +
            "Tell the user to set it up in the drawer menu ▸ Connectors; do not retry."
    }

    private fun JsonObject.requireString(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    /** Parse an integer argument; tolerates numbers sent as JSON strings by the LLM. */
    private fun JsonObject.requireInt(key: String): Int? =
        this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

    private fun JsonObject.requireBoolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.requireStringList(key: String): List<String>? =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content }

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
