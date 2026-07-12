package com.gotcha.tools

import com.gotcha.llm.ToolDefinition

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
        "get_firewall_status",
        "check_root"
    )

    /** Full Operator tool set minus task (sub-agents cannot delegate further). */
    val subAgentTools: Set<String> = definitions.keys - "task"

    private val operatorTools: Set<String> = definitions.keys

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
}
