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

        fun ok(message: String) = ToolResult(true, message)
        fun error(message: String) = ToolResult(false, message)
        fun permissionNeeded(permission: String, message: String) =
            ToolResult(false, message, needsPermission = permission)
    }
}
