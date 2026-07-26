package com.gotcha.tools

import com.gotcha.connectors.ConnectorCatalog
import com.gotcha.llm.FunctionDefinition
import com.gotcha.llm.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class AgentMode { MONITOR, OPERATOR }

object ToolRegistry {

    private val definitions: Map<String, ToolDefinition> =
        ToolDefinitions.all.associateBy { it.function.name }

    // Empty by design: AgentEngine.requestConfirmation() auto-approves, so an
    // entry here would imply a gate that does not exist. send_email has its own
    // confirmation via the CONFIRM_SEND_EMAIL flow in EmailTools.
    val sensitiveTools: Set<String> = emptySet()

    val destructiveTools: Set<String> = setOf(
        "uninstall_app",
        "delete_calendar_event",
        "delete_alarm",
        "delete_timer"
    )

    /**
     * Read-only tools that belong to no connector. The connector-owned half of
     * the Monitor set is contributed by [ConnectorCatalog] so the two can never
     * drift — adding a connector read tool in one place used to require
     * remembering to add it here too.
     */
    private val baseMonitorTools: Set<String> = setOf(
        "dial_number", "read_call_log", "find_contact", "read_recent_sms",
        "list_calendar_events",
        "get_storage_info", "get_battery_info", "get_location", "get_volume",
        "get_audio_recording_status",
        "get_app_usage", "get_data_usage",
        "open_app", "list_installed_apps",
        "list_files", "read_file", "grep", "glob",
        "todowrite", "list_alarms", "list_timers", "show_alarms",
        "question",
        "sleep",
        "websearch", "webfetch",
        "get_clipboard",
        "read_screen", "read_notifications",
        "check_root", "search_skills",
        "get_health_summary", "get_health_records",
        "get_now_playing"
    )

    val monitorTools: Set<String> = baseMonitorTools + ConnectorCatalog.monitorTools

    /** Full Operator tool set minus task + navigate_app (sub-agents cannot delegate further). */
    val subAgentTools: Set<String> = definitions.keys - setOf("task", "navigate_app")

    /** Tools available to the App Navigator sub-agent. */
    val navigatorTools: Set<String> = setOf(
        "tap", "tap_index", "long_press", "long_press_index", "swipe", "input_text", "press_key",
        "global_action", "open_app", "list_installed_apps", "search_skills",
        "sleep", "ask_final_answer"
    )

    private val operatorTools: Set<String> = definitions.keys

    /** Trimmed tool definitions for the App Navigator (shorter descriptions = fewer tokens). */
    private val navigatorDefinitions: Map<String, ToolDefinition> = mapOf(
        "tap" to ToolDefinition(
            function = FunctionDefinition(
                "tap",
                "Tap at coordinates. Use normalized=true and x,y in [0,1000] space.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("x") {
                            put("type", "integer")
                            put("description", "X in [0,1000]")
                        }
                        putJsonObject("y") {
                            put("type", "integer")
                            put("description", "Y in [0,1000]")
                        }
                        putJsonObject("normalized") {
                            put("type", "boolean")
                            put("description", "Default true")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Element text to match")
                        }
                    }
                }
            )
        ),
        "tap_index" to ToolDefinition(
            function = FunctionDefinition(
                "tap_index",
                "Tap a UI element by its index from the UI Elements list.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "Element index")
                        }
                    }
                    putJsonArray("required") { add("index") }
                }
            )
        ),
        "long_press" to ToolDefinition(
            function = FunctionDefinition(
                "long_press",
                "Long press at coordinates. Use normalized=true and x,y in [0,1000] space.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("x") {
                            put("type", "integer")
                            put("description", "X in [0,1000]")
                        }
                        putJsonObject("y") {
                            put("type", "integer")
                            put("description", "Y in [0,1000]")
                        }
                        putJsonObject("normalized") {
                            put("type", "boolean")
                            put("description", "Default true")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Element text to match")
                        }
                    }
                }
            )
        ),
        "long_press_index" to ToolDefinition(
            function = FunctionDefinition(
                "long_press_index",
                "Long press a UI element by its index from the UI Elements list.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "Element index")
                        }
                    }
                    putJsonArray("required") { add("index") }
                }
            )
        ),
        "swipe" to ToolDefinition(
            function = FunctionDefinition(
                "swipe",
                "Scroll the screen in a direction. 'down' scrolls to see lower content (upward swipe).",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("direction") {
                            put("type", "string")
                            put("description", "up, down, left, or right")
                        }
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "Optional element index to scroll within")
                        }
                        putJsonObject("distance") {
                            put("type", "integer")
                            put("description", "Optional scroll distance in pixels (smaller = shorter scroll)")
                        }
                    }
                    putJsonArray("required") { add("direction") }
                }
            )
        ),
        "input_text" to ToolDefinition(
            function = FunctionDefinition(
                "input_text",
                "Type text into a field. Provide 'index' to target it directly. After typing, press_key(enter) may be needed to submit.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Text to type")
                        }
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "Element index to target (optional)")
                        }
                    }
                    putJsonArray("required") { add("text") }
                }
            )
        ),
        "press_key" to ToolDefinition(
            function = FunctionDefinition(
                "press_key",
                "Press enter to submit text input, or press back/home for navigation.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("key") {
                            put("type", "string")
                            put("description", "enter, back, or home")
                        }
                    }
                    putJsonArray("required") { add("key") }
                }
            )
        ),
        "sleep" to ToolDefinition(
            function = FunctionDefinition(
                "sleep",
                "Wait for animations or app loading.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("duration_seconds") {
                            put("type", "integer")
                            put("description", "Seconds to wait (1-30)")
                        }
                    }
                    putJsonArray("required") { add("duration_seconds") }
                }
            )
        ),
        "ask_final_answer" to ToolDefinition(
            function = FunctionDefinition(
                "ask_final_answer",
                "Task complete — provide the final answer summary.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("answer") {
                            put("type", "string")
                            put("description", "Final summary")
                        }
                    }
                    putJsonArray("required") { add("answer") }
                }
            )
        ),
        "open_app" to ToolDefinition(
            function = FunctionDefinition(
                "open_app",
                "Launch an app by its name (e.g. 'Settings', 'Google Maps') or package name.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("package_name") {
                            put("type", "string")
                            put("description", "App name or package name (e.g. 'Gmail', 'com.google.android.gm')")
                        }
                    }
                    putJsonArray("required") { add("package_name") }
                }
            )
        ),
        "global_action" to ToolDefinition(
            function = FunctionDefinition(
                "global_action",
                "Perform a system-level action: recents (overview), notifications (shade), " +
                    "quick_settings, lock_screen, back, home.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            put("description", "recents, notifications, quick_settings, lock_screen, back, or home")
                        }
                    }
                    putJsonArray("required") { add("action") }
                }
            )
        ),
        "search_skills" to ToolDefinition(
            function = FunctionDefinition(
                "search_skills",
                "Search for guidance on how to interact with a specific app or perform a system operation.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", "string")
                            put("description", "The app name or operation to search for (e.g. 'Gmail', 'Settings search')")
                        }
                    }
                    putJsonArray("required") { add("query") }
                }
            )
        ),
        "list_installed_apps" to ToolDefinition(
            function = FunctionDefinition(
                "list_installed_apps",
                "List installed apps to find the correct name or package name for open_app.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("search") {
                            put("type", "string")
                            put("description", "Optional search term to filter apps by name")
                        }
                    }
                }
            )
        )
    )

    fun allDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun definition(name: String): ToolDefinition? = definitions[name]

    fun contains(name: String): Boolean = name in definitions

    fun isSensitive(name: String): Boolean = name in sensitiveTools

    fun isDestructive(name: String): Boolean = name in destructiveTools

    fun isAllowedForAgent(
        name: String,
        agent: AgentMode,
        hiddenTools: Set<String> = emptySet()
    ): Boolean {
        if (name in hiddenTools) return false
        return when (agent) {
            AgentMode.MONITOR -> name in monitorTools
            AgentMode.OPERATOR -> name in operatorTools
        }
    }

    fun isAllowedForSubAgent(name: String, hiddenTools: Set<String> = emptySet()): Boolean =
        name !in hiddenTools && name in subAgentTools

    /**
     * Tool schemas to send for [agent], minus [hiddenTools] — connector-owned
     * tools nothing can currently serve. Withholding them is both a token saving
     * and a correctness fix: the model can no longer spend a round calling a tool
     * whose only possible reply is "not connected".
     */
    fun toolsForAgent(
        agent: AgentMode,
        hiddenTools: Set<String> = emptySet()
    ): List<ToolDefinition> {
        val allowed = when (agent) {
            AgentMode.MONITOR -> monitorTools
            AgentMode.OPERATOR -> operatorTools
        }
        return definitions.filterKeys { it in allowed && it !in hiddenTools }.values.toList()
    }

    fun toolsForSubAgent(hiddenTools: Set<String> = emptySet()): List<ToolDefinition> =
        definitions.filterKeys { it in subAgentTools && it !in hiddenTools }.values.toList()

    fun toolsForNavigator(): List<ToolDefinition> =
        navigatorTools.mapNotNull { navigatorDefinitions[it] }
}
