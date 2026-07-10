package com.gotcha.service

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Tier 3 — Device Admin component. Once the user activates it (via
 * ACTION_ADD_DEVICE_ADMIN), [com.gotcha.tools.DeviceAdminTool] can lock the
 * screen, enforce password policy, and disable the camera through DevicePolicyManager.
 *
 * Remote wipe is deliberately NOT wired up: it would irreversibly destroy the user's
 * real device, which is out of scope for this assistant.
 */
class GotchaDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, GotchaDeviceAdminReceiver::class.java)
    }
}
