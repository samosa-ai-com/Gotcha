package com.gotcha.tools

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.gotcha.service.GotchaDeviceAdminReceiver

/**
 * Tier 3 — device-administration actions via [DevicePolicyManager], guarded by the
 * [GotchaDeviceAdminReceiver] admin component. Every action first checks the component
 * is active; if not it returns the [ToolResult.DEVICE_ADMIN] marker so the UI can launch
 * the activation dialog.
 *
 * Remote wipe is intentionally omitted (irreversible destruction of the user's device).
 */
class DeviceAdminTool(private val context: Context) {

    private val dpm: DevicePolicyManager
        get() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin get() = GotchaDeviceAdminReceiver.componentName(context)

    /** Immediately lock the screen. */
    fun lockScreen(): ToolResult {
        if (!isActive()) return notActive()
        return try {
            dpm.lockNow()
            ToolResult.ok("Locked the screen.")
        } catch (e: Exception) {
            ToolResult.error("Could not lock the screen: ${e.message}")
        }
    }

    /** Enable or disable all device cameras via policy. */
    fun disableCamera(disabled: Boolean): ToolResult {
        if (!isActive()) return notActive()
        return try {
            dpm.setCameraDisabled(admin, disabled)
            ToolResult.ok(if (disabled) "Camera disabled for the whole device." else "Camera re-enabled.")
        } catch (e: Exception) {
            ToolResult.error("Could not change the camera policy: ${e.message}")
        }
    }

    /** Enforce a minimum unlock-password length (0 clears the length requirement). */
    fun setPasswordPolicy(minLength: Int): ToolResult {
        if (!isActive()) return notActive()
        val length = minLength.coerceIn(0, 16)
        return try {
            dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC)
            dpm.setPasswordMinimumLength(admin, length)
            val sufficient = dpm.isActivePasswordSufficient
            ToolResult.ok(
                "Set the minimum password length to $length. " +
                    if (sufficient) {
                        "The current password meets the policy."
                    } else {
                        "The current password does NOT meet the policy — the user will be prompted to update it."
                    }
            )
        } catch (e: Exception) {
            ToolResult.error("Could not set the password policy: ${e.message}")
        }
    }

    private fun isActive(): Boolean = dpm.isAdminActive(admin)

    private fun notActive() = ToolResult.permissionNeeded(
        ToolResult.DEVICE_ADMIN,
        "This needs Gotcha to be an active device administrator. I have opened the activation " +
            "screen — please confirm it there and ask again."
    )
}
