package com.gotcha.tools

/**
 * A device-side capability that a group of tools depends on — an accessibility
 * service, a special-access grant, root. The connector analogue is
 * [com.gotcha.connectors.ConnectorSpec]; this is the same idea for things the
 * user grants on the device rather than signs into.
 *
 * One difference matters. A connector that is missing is missing until the user
 * sets it up, and the tools are hidden accordingly. A permission can be granted
 * *on demand*, so hiding the tools must not hide the possibility: the `<env>`
 * block reports each capability's status every turn, which is what lets the
 * agent still say "enable accessibility in Settings and ask me again".
 */
enum class Capability(
    /** Human name used in the tool-unavailable message. */
    val label: String,
    /** Tools that cannot function without it. */
    val tools: Set<String>
) {
    ACCESSIBILITY(
        "the accessibility service",
        setOf(
            "tap", "tap_index", "long_press", "long_press_index", "swipe",
            "input_text", "press_key", "global_action",
            "read_screen", "read_screen_raw",
            "navigate_app"
        )
    ),

    NOTIFICATION_LISTENER(
        "notification access",
        // media_control and get_now_playing read MediaSessions, which Android
        // only hands out to an enabled notification listener.
        setOf("read_notifications", "dismiss_notifications", "media_control", "get_now_playing")
    ),

    DEVICE_ADMIN(
        "device admin",
        setOf("lock_screen", "disable_camera", "set_password_policy")
    ),

    ROOT(
        "root",
        // check_root deliberately stays exposed: it is how the agent finds out,
        // and it is the only tool here that is useful on an unrooted device.
        setOf("run_root_command", "write_secure_settings")
    ),

    TERMUX(
        // Reads as "it needs <label>, which is not available on this device right now."
        "Termux (the Linux terminal app, installed from F-Droid)",
        // Gated on Termux being installed, not on the RUN_COMMAND grant: gating on the grant
        // would hide the only tool that can raise the prompt for it. See DeviceCapabilities.
        //
        // media_convert rides on the same capability because it is ffmpeg run through
        // run_termux_command. Hiding it without Termux is the point: it is the only route to
        // MP3, and a model that could see it on a device that cannot run it would promise a
        // conversion it has no way to perform.
        setOf("run_termux_command", "media_convert")
    ),

    HEALTH_CONNECT(
        "Health Connect",
        setOf("get_health_summary", "get_health_records")
    ),

    OVERLAY(
        "the display-over-other-apps permission",
        setOf("show_overlay", "hide_overlay")
    )
}

/**
 * The static half of capability gating: which tools each capability owns.
 * [DeviceCapabilities] supplies the runtime answer to which ones are available.
 */
object CapabilityCatalog {

    /** Every tool that depends on some device capability. */
    val allGatedTools: Set<String> = Capability.entries.flatMapTo(mutableSetOf()) { it.tools }

    /**
     * Tools to withhold given the capabilities currently available. Unlike
     * connectors there is no union rule — each tool has exactly one owner.
     */
    fun hiddenTools(available: Set<Capability>): Set<String> =
        Capability.entries.filterNot { it in available }.flatMapTo(mutableSetOf()) { it.tools }

    /** The capability [tool] needs, or null if it needs none. */
    fun ownerOf(tool: String): Capability? = Capability.entries.firstOrNull { tool in it.tools }
}
