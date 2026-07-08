package com.gotcha.tools

import com.gotcha.llm.ToolDefinition

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
        "dial_number", "set_wallpaper", "clear_app_cache",
        "write_file", "run_command", "set_brightness",
        // Tier 0–2 additions: outbound actions, personal-data reads, and captures.
        "call_number", "read_call_log", "find_contact", "add_contact",
        "send_sms", "read_recent_sms", "create_calendar_event", "list_calendar_events",
        "get_location", "uninstall_app",
        "take_photo", "start_audio_recording", "stop_audio_recording",
        // Tier 3: device-wide / other-app control is always sensitive.
        "read_screen", "tap", "swipe", "input_text", "global_action",
        "read_notifications", "dismiss_notifications", "media_control",
        "show_overlay", "hide_overlay",
        "lock_screen", "disable_camera", "set_password_policy"
    )

    fun allDefinitions(): List<ToolDefinition> = definitions.values.toList()

    fun definition(name: String): ToolDefinition? = definitions[name]

    fun contains(name: String): Boolean = name in definitions

    fun isSensitive(name: String): Boolean = name in sensitiveTools
}
