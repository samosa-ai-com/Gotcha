package com.gotcha.tools

enum class Category(val ringColorArgb: Int?, val narration: String?, val isNarratable: Boolean) {
    FOREGROUND(0xFF4A90D9.toInt(), "Looking at the screen", true),
    BACKGROUND(0xFFFF6B35.toInt(), "Making a change", true),
    INFO(null, null, false)
}

object ToolCategories {

    private val foregroundTools = setOf(
        "tap", "tap_index", "long_press", "long_press_index",
        "swipe", "input_text", "press_key",
        "global_action", "open_app", "open_setting",
        "read_screen", "read_screen_raw",
        "navigate_app", "task",
        "dial_number", "compose_email", "show_alarms", "share_podcast"
    )

    private val backgroundTools = setOf(
        "edit", "write_file", "make_directory", "delete_file", "pdf_edit", "media_edit", "media_convert",
        "synthesize_podcast", "synthesize_podcast_dialogue", "transcribe_file",
        "uninstall_app", "delete_calendar_event", "delete_alarm",
        "delete_timer", "edit_alarm",
        "toggle_wifi", "toggle_torch",
        "set_volume", "set_ringer_mode", "set_dnd", "set_brightness",
        "set_wallpaper", "set_clipboard",
        "call_number", "send_sms", "add_contact",
        "create_calendar_event", "edit_calendar_event",
        "set_alarm", "set_timer", "snooze_alarm", "dismiss_timer",
        "take_photo",
        "start_audio_recording", "stop_audio_recording",
        "pause_audio_recording", "resume_audio_recording",
        "run_command", "run_root_command", "run_termux_command", "pull_from_termux",
        "show_overlay", "hide_overlay",
        "lock_screen", "disable_camera", "set_password_policy",
        "dismiss_notifications", "media_control",
        "vibrate", "write_secure_settings",
        "send_email", "mark_email_read",
        "create_task", "complete_task",
        "notion_create_page", "notion_append_to_page"
    )

    /**
     * Tools that bring another app to the front or act on the screen of whatever
     * app is there (issue #98). Gated behind one "may Gotcha control your apps?"
     * ask per request. Narrower than [foregroundTools]: intent handoffs like
     * dial_number open another app but leave the user in charge of it, and
     * `task` is gated through the calls its sub-agent makes, not by itself.
     */
    private val foregroundControlTools = setOf(
        "open_app", "open_setting", "navigate_app",
        "tap", "tap_index", "long_press", "long_press_index",
        "swipe", "input_text", "press_key", "global_action",
        "read_screen", "read_screen_raw"
    )

    fun isForegroundControl(toolName: String): Boolean = toolName in foregroundControlTools

    fun classify(toolName: String): Category = when {
        foregroundTools.contains(toolName) -> Category.FOREGROUND
        backgroundTools.contains(toolName) -> Category.BACKGROUND
        else -> Category.INFO
    }
}
