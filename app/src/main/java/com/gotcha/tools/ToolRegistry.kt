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
     * Tools whose execution mutates device state or is otherwise sensitive;
     * these require user confirmation when the confirmation toggle is on (Phase 7).
     */
    val sensitiveTools: Set<String> = setOf(
        "dial_number", "set_wallpaper",
        "write_file", "edit", "run_command", "set_brightness",
        // Tier 0–2 additions: outbound actions, personal-data reads, and captures.
        "call_number", "read_call_log", "find_contact", "add_contact",
        "send_sms", "read_recent_sms", "create_calendar_event", "list_calendar_events",
        "get_location", "uninstall_app",
        "take_photo", "start_audio_recording", "stop_audio_recording",
        // Tier 3: device-wide / other-app control is always sensitive.
        "read_screen", "tap", "swipe", "input_text", "global_action",
        "read_notifications", "dismiss_notifications", "media_control",
        "show_overlay", "hide_overlay",
        "lock_screen", "disable_camera", "set_password_policy",
        // Tier 3: VpnService — enabling cuts all device connectivity.
        "set_firewall",
        // Tier 4: privileged root execution.
        "run_root_command", "write_secure_settings"
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
        "get_storage_info", "get_battery_info", "get_location",
        "get_app_usage", "get_data_usage",
        // Apps (read-only + intent)
        "open_app", "list_installed_apps",
        // Files & terminal (read-only)
        "list_files", "read_file", "grep", "glob", "read_image",
        // Task management
        "todowrite",
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
