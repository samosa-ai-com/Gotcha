package com.gotcha.tools

import com.gotcha.llm.ToolDefinition

/**
 * Agent mode the user can switch between mid-conversation (PRD §6).
 * Monitor = read-only observer; Operator = full permissions.
 */
enum class AgentMode { MONITOR, OPERATOR }

/**
 * Fixed, code-defined tool catalog. The LLM can never add tools at runtime
 * (PRD §11.2 #1) — only this registry decides what is exposed.
 */
object ToolRegistry {

    private val definitions: Map<String, ToolDefinition> =
        ToolDefinitions.all.associateBy { it.function.name }

    /**
     * Sensitive-action confirmation is removed. Permissions are pre-configured
     * in Settings → Permissions. Only destructive tools (see [destructiveTools])
     * require a dedicated user confirmation dialog.
     */
    val sensitiveTools: Set<String> = emptySet()

    /**
     * Destructive tools that require explicit, separate user confirmation even when
     * sensitive-action confirmation is enabled. Each call presents a dedicated dialog
     * to the user before the tool is actually executed.
     */
    val destructiveTools: Set<String> = setOf(
        "uninstall_app",
        "delete_calendar_event",
        "delete_alarm",
        "delete_timer"
    )

    /**
     * Tools available to Monitor mode — read-only / informational only.
     * The Monitor can inspect, list, read, and query, but cannot create,
     * modify, or delete anything. `dial_number` and `open_app` are allowed
     * because they are intent hand-offs (user presses call / system launches app).
     */
    val monitorTools: Set<String> = setOf(
        // Phone & communications (read-only + intent hand-offs)
        "dial_number", "read_call_log", "find_contact", "read_recent_sms",
        // Calendar (read-only)
        "list_calendar_events",
        // Info & sensors
        "get_storage_info", "get_battery_info", "get_location", "get_volume",
        "get_audio_recording_status",
        "get_app_usage", "get_data_usage",
        // Apps (read-only + intent)
        "open_app", "list_installed_apps",
        // Files & terminal (read-only)
        "list_files", "read_file", "grep", "glob",
        // Task management
        "todowrite", "list_alarms", "list_timers",
        // User interaction
        "question",
        // Web
        "websearch", "webfetch",
        // Clipboard (read-only)
        "get_clipboard",
        // Tier 3: read-only accessibility + notifications
        "read_screen", "read_notifications",
        // Tier 3: VpnService status (read-only)
        "get_firewall_status",
        // Tier 4: root check (read-only)
        "check_root"
    )

    /** Operator has access to every tool in the catalog. */
    private val operatorTools: Set<String> = definitions.keys

    fun allDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun definition(name: String): ToolDefinition? = definitions[name]

    fun contains(name: String): Boolean = name in definitions

    fun isSensitive(name: String): Boolean = name in sensitiveTools

    fun isDestructive(name: String): Boolean = name in destructiveTools

    /** Whether [name] is callable by the given [agent] mode. */
    fun isAllowedForAgent(name: String, agent: AgentMode): Boolean = when (agent) {
        AgentMode.MONITOR -> name in monitorTools
        AgentMode.OPERATOR -> name in operatorTools
    }

    /** Returns the subset of tool definitions visible to the given [agent]. */
    fun toolsForAgent(agent: AgentMode): List<ToolDefinition> = when (agent) {
        AgentMode.MONITOR -> definitions.filterKeys { it in monitorTools }.values.toList()
        AgentMode.OPERATOR -> definitions.values.toList()
    }
}
