package com.gotcha.tools

/**
 * Outcome of a tool execution, fed back to the LLM as a role=tool message.
 *
 * @param needsPermission when non-null, the Android permission (or special-access
 *   marker such as [ToolResult.WRITE_SETTINGS]) that must be granted before the
 *   tool can run. The UI layer uses this to trigger a runtime request.
 */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val needsPermission: String? = null
) {
    companion object {
        /** Marker for the WRITE_SETTINGS special app access (not a runtime permission). */
        const val WRITE_SETTINGS = "special:write_settings"

        /** Marker for the "Usage access" special app access (app-usage + data-usage stats). */
        const val USAGE_ACCESS = "special:usage_access"

        /** Marker for the "Do Not Disturb access" (notification-policy) special app access. */
        const val DND_ACCESS = "special:dnd_access"

        // ---- Tier 3: component-based / device-wide special access ----

        /** Marker for enabling the AccessibilityService (Settings → Accessibility). */
        const val ACCESSIBILITY_ACCESS = "special:accessibility_access"

        /** Marker for enabling the NotificationListenerService (Notification access). */
        const val NOTIFICATION_LISTENER_ACCESS = "special:notification_listener_access"

        /** Marker for the "All files access" (MANAGE_EXTERNAL_STORAGE) special access. */
        const val ALL_FILES_ACCESS = "special:all_files_access"

        /** Marker for the "Display over other apps" (SYSTEM_ALERT_WINDOW) special access. */
        const val OVERLAY_ACCESS = "special:overlay_access"

        /** Marker for activating the Device Admin component. */
        const val DEVICE_ADMIN = "special:device_admin"

        /** Marker for the one-time system VPN consent dialog (VpnService). */
        const val VPN_CONSENT = "special:vpn_consent"

        /**
         * Marker for the Health Connect read permissions. These are
         * `android.permission.health.*` grants, but they are requested through
         * Health Connect's own screen rather than the standard runtime dialog.
         */
        const val HEALTH_CONNECT = "special:health_connect"

        fun ok(message: String) = ToolResult(true, message)
        fun error(message: String) = ToolResult(false, message)
        fun permissionNeeded(permission: String, message: String) =
            ToolResult(false, message, needsPermission = permission)
    }
}
