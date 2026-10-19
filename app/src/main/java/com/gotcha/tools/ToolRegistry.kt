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

    // ---- dynamic tools (server-defined: Home Assistant, user-added MCP servers) ----

    /** One owner's registered tools: its connector id's slice of the dynamic set. */
    private data class OwnerTools(
        val definitions: Map<String, ToolDefinition>,
        val readOnlyNames: Set<String>
    )

    /**
     * Tools whose schemas come from an external server rather than the compile-time
     * catalog, kept per owner: the id of the connector that registered them. A
     * connector registers its slice after connecting and clears it on disconnect,
     * without touching another connector's. [ToolRegistry] does not know their names
     * ahead of time, so the compile-time [definitions] map never sees them.
     *
     * The whole set is held as one immutable snapshot and swapped atomically via
     * [@Volatile], so a reader (per-turn schema assembly) always sees a consistent
     * (definitions, read-only names, owners) triple — never definitions from one
     * registration with read-only names from the previous. Writers serialize on
     * [dynamicLock], because each one rebuilds the snapshot from the current one.
     */
    private class DynamicToolSet(val byOwner: Map<String, OwnerTools>) {
        val definitions: Map<String, ToolDefinition> =
            byOwner.values.fold(emptyMap()) { acc, owner -> acc + owner.definitions }
        val readOnlyNames: Set<String> = byOwner.values.flatMapTo(mutableSetOf()) { it.readOnlyNames }
        val ownerOf: Map<String, String> =
            byOwner.flatMap { (owner, tools) -> tools.definitions.keys.map { it to owner } }.toMap()
    }

    @Volatile
    private var dynamicSet = DynamicToolSet(emptyMap())

    private val dynamicLock = Any()

    /**
     * Replaces [owner]'s dynamic tools with [tools]. Connectors call this after a
     * successful connect (and again on refresh); names in [readOnlyNames] are the
     * only ones of them offered to Monitor.
     *
     * A server picks its own tool names, so a name already taken — by a built-in
     * tool or by another owner — is dropped rather than allowed to shadow the tool
     * the model and the executor already know by that name. Returns the names that
     * were registered.
     */
    fun setDynamicTools(owner: String, tools: Collection<ToolDefinition>, readOnlyNames: Set<String>): Set<String> =
        synchronized(dynamicLock) {
            val current = dynamicSet
            val taken = definitions.keys + current.ownerOf.filterValues { it != owner }.keys
            val kept = tools.filter { it.function.name !in taken }.associateBy { it.function.name }
            val slice = OwnerTools(kept, readOnlyNames.filterTo(mutableSetOf()) { it in kept })
            dynamicSet = DynamicToolSet(
                if (kept.isEmpty()) current.byOwner - owner else current.byOwner + (owner to slice)
            )
            kept.keys
        }

    /** Unregisters [owner]'s dynamic tools (used on connector disconnect). */
    fun clearDynamicTools(owner: String) {
        synchronized(dynamicLock) { dynamicSet = DynamicToolSet(dynamicSet.byOwner - owner) }
    }

    /** Unregisters every owner's dynamic tools. Tests use it to start clean. */
    fun clearAllDynamicTools() {
        synchronized(dynamicLock) { dynamicSet = DynamicToolSet(emptyMap()) }
    }

    /** Names of the currently registered dynamic tools, of every owner. */
    val dynamicTools: Set<String>
        get() = dynamicSet.definitions.keys

    /** The registered dynamic tools, grouped by owner (connector id). */
    val dynamicToolsByOwner: Map<String, Set<String>>
        get() = dynamicSet.byOwner.mapValues { it.value.definitions.keys }

    /** The connector id that registered dynamic tool [name], or null if none did. */
    fun dynamicOwnerOf(name: String): String? = dynamicSet.ownerOf[name]

    // Empty by design: AgentEngine.requestConfirmation() auto-approves, so an
    // entry here would imply a gate that does not exist. send_email has its own
    // confirmation via the CONFIRM_SEND_EMAIL flow in EmailTools.
    val sensitiveTools: Set<String> = emptySet()

    val destructiveTools: Set<String> = setOf(
        "uninstall_app",
        "delete_calendar_event",
        "delete_alarm",
        "delete_timer",
        "delete_scheduled_task"
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
        "todowrite", "list_alarms", "list_timers", "show_alarms", "list_scheduled_tasks",
        "question",
        "sleep",
        "finish_task",
        "websearch", "webfetch",
        "get_clipboard",
        "read_screen", "read_notifications",
        "check_root", "search_skills",
        "get_health_summary", "get_health_records",
        "get_now_playing",
        "about_samosa_ai", "about_gotcha"
    )

    /**
     * Read-only tools offered to Monitor: the base set, the connector read tools
     * from [ConnectorCatalog], and the read-only slice of the currently registered
     * dynamic tools. Computed on access because the dynamic slice changes when a
     * connector connects or disconnects.
     */
    val monitorTools: Set<String>
        get() = baseMonitorTools + ConnectorCatalog.monitorTools + dynamicSet.readOnlyNames

    /** Tools a sub-agent never gets; see [subAgentTools]. */
    private val subAgentExcluded = setOf(
        "task",
        "navigate_app",
        "finish_task",
        "update_user_profile",
        "update_gotcha_settings",
        "schedule_agent_task",
        "edit_scheduled_task",
        "delete_scheduled_task"
    )

    /**
     * Full Operator tool set minus task + navigate_app (sub-agents cannot delegate
     * further), minus finish_task, which ends the *top-level* run — a sub-agent
     * reports back with ask_final_answer instead — minus update_user_profile,
     * whose modify-and-extend directive lives only in the top-level Operator prompt,
     * and minus update_gotcha_settings and the scheduled-task writes, whose approval
     * prompts belong to the run the user is watching, not to a delegated one.
     * Dynamic tools are included while registered; they are hidden by the
     * hidden-tools mechanism when their connector is not active.
     */
    val subAgentTools: Set<String>
        get() = definitions.keys + dynamicSet.definitions.keys - subAgentExcluded

    /** Tools available to the App Navigator sub-agent. */
    val navigatorTools: Set<String> = setOf(
        "tap", "tap_index", "long_press", "long_press_index", "swipe", "input_text", "press_key",
        "global_action", "open_app", "open_setting", "list_installed_apps", "search_skills",
        "sleep", "ask_final_answer"
    )

    /**
     * Everything except ask_final_answer, which is a sub-agent-to-parent control
     * signal: [SubAgentSession] and [AppNavigatorSession] treat it as "stop here",
     * while the top-level loop has no such handling and would silently keep going.
     * The top-level equivalent is finish_task. Dynamic tools are included while
     * registered; they are hidden by the hidden-tools mechanism when their
     * connector is not active.
     */
    private val operatorTools: Set<String>
        get() = definitions.keys + dynamicSet.definitions.keys - setOf("ask_final_answer")

    /**
     * Tools that hand the whole job to a sub-agent and return only a text report.
     * The engine counts consecutive rounds made up solely of these to catch
     * re-delegation loops.
     */
    val delegationTools: Set<String> = setOf("task", "navigate_app")

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
        "open_setting" to ToolDefinition(
            function = FunctionDefinition(
                "open_setting",
                "Jump straight to an Android settings screen instead of searching in Settings. " +
                    "One of: wifi, internet, mobile_data, nfc, bluetooth, location, airplane_mode, " +
                    "battery_saver, display, sound, date_time, language, " +
                    "input_method, storage, accessibility, default_apps, cast.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("setting") {
                            put("type", "string")
                            put("description", "Which settings screen to open")
                        }
                    }
                    putJsonArray("required") { add("setting") }
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

    /** The compile-time catalog only; dynamic tools are served by [toolsForAgent]. */
    fun allDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun definition(name: String): ToolDefinition? =
        definitions[name] ?: dynamicSet.definitions[name]

    fun contains(name: String): Boolean = name in definitions || name in dynamicSet.definitions

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
     * whose only possible reply is "not connected". Dynamic tools (Home Assistant
     * MCP) join the compile-time catalog while registered; the connector-hiding
     * logic in [ConnectorRegistry.hiddenToolNames] adds them to [hiddenTools]
     * whenever their owning connector is not active.
     */
    fun toolsForAgent(
        agent: AgentMode,
        hiddenTools: Set<String> = emptySet()
    ): List<ToolDefinition> {
        val dynamic = dynamicSet
        val allowed = when (agent) {
            AgentMode.MONITOR -> baseMonitorTools + ConnectorCatalog.monitorTools + dynamic.readOnlyNames
            AgentMode.OPERATOR -> definitions.keys + dynamic.definitions.keys - setOf("ask_final_answer")
        }
        return (definitions + dynamic.definitions)
            .filterKeys { it in allowed && it !in hiddenTools }
            .values.toList()
    }

    fun toolsForSubAgent(hiddenTools: Set<String> = emptySet()): List<ToolDefinition> {
        val dynamic = dynamicSet
        val allowed = definitions.keys + dynamic.definitions.keys - subAgentExcluded
        return (definitions + dynamic.definitions)
            .filterKeys { it in allowed && it !in hiddenTools }
            .values.toList()
    }

    fun toolsForNavigator(): List<ToolDefinition> =
        navigatorTools.mapNotNull { navigatorDefinitions[it] }
}
