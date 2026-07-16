package com.gotcha.tools

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

    val sensitiveTools: Set<String> = emptySet()

    val destructiveTools: Set<String> = setOf(
        "uninstall_app",
        "delete_calendar_event",
        "delete_alarm",
        "delete_timer"
    )

    val monitorTools: Set<String> = setOf(
        "dial_number", "read_call_log", "find_contact", "read_recent_sms",
        "list_calendar_events",
        "get_storage_info", "get_battery_info", "get_location", "get_volume",
        "get_audio_recording_status",
        "get_app_usage", "get_data_usage",
        "open_app", "list_installed_apps",
        "list_files", "read_file", "grep", "glob",
        "todowrite", "list_alarms", "list_timers",
        "question",
        "sleep",
        "websearch", "webfetch",
        "get_clipboard",
        "read_screen", "read_notifications",
        "check_root"
    )

    /** Full Operator tool set minus task + navigate_app (sub-agents cannot delegate further). */
    val subAgentTools: Set<String> = definitions.keys - setOf("task", "navigate_app")

    /** Tools available to the App Navigator sub-agent. */
    val navigatorTools: Set<String> = setOf(
        "tap", "tap_index", "long_press", "long_press_index", "swipe", "input_text", "press_key",
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
                            put("description", "Optional element index to scroll")
                        }
                    }
                    putJsonArray("required") { add("direction") }
                }
            )
        ),
        "input_text" to ToolDefinition(
            function = FunctionDefinition(
                "input_text",
                "Type text into a field. Provide 'index' to target it directly.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Text to type")
                        }
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "Element index")
                        }
                    }
                    putJsonArray("required") { add("text") }
                }
            )
        ),
        "press_key" to ToolDefinition(
            function = FunctionDefinition(
                "press_key",
                "Press a system key: back, home, enter.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("key") {
                            put("type", "string")
                            put("description", "back, home, enter")
                        }
                    }
                    putJsonArray("required") { add("key") }
                }
            )
        ),
        "sleep" to ToolDefinition(
            function = FunctionDefinition(
                "sleep",
                "Wait for a short time (max 3s).",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("duration_seconds") {
                            put("type", "integer")
                            put("description", "Seconds (1-3)")
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
        )
    )

    fun allDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun definition(name: String): ToolDefinition? = definitions[name]

    fun contains(name: String): Boolean = name in definitions

    fun isSensitive(name: String): Boolean = name in sensitiveTools

    fun isDestructive(name: String): Boolean = name in destructiveTools

    fun isAllowedForAgent(name: String, agent: AgentMode): Boolean = when (agent) {
        AgentMode.MONITOR -> name in monitorTools
        AgentMode.OPERATOR -> name in operatorTools
    }

    fun isAllowedForSubAgent(name: String): Boolean = name in subAgentTools

    fun toolsForAgent(agent: AgentMode): List<ToolDefinition> = when (agent) {
        AgentMode.MONITOR -> definitions.filterKeys { it in monitorTools }.values.toList()
        AgentMode.OPERATOR -> definitions.values.toList()
    }

    fun toolsForSubAgent(): List<ToolDefinition> =
        definitions.filterKeys { it in subAgentTools }.values.toList()

    fun toolsForNavigator(): List<ToolDefinition> =
        navigatorTools.mapNotNull { navigatorDefinitions[it] }
}
