package com.gotcha.testutil

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants/revokes permissions that `pm grant` cannot reach, via
 * `UiAutomation.executeShellCommand` (adb-shell-equivalent privilege).
 */
object ShellPermissions {

    private const val PACKAGE_NAME = "com.gotcha"

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .close()
    }

    /** SYSTEM_ALERT_WINDOW is an appop, not a runtime permission — `pm grant` does not work. */
    fun grantOverlay() = shell("appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow")

    fun revokeOverlay() = shell("appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW deny")

    /** RECORD_AUDIO grant state drives the ball's mic-gated start path (issue #148). */
    fun revokeMic() = shell("pm revoke $PACKAGE_NAME android.permission.RECORD_AUDIO")

    fun grantMic() = shell("pm grant $PACKAGE_NAME android.permission.RECORD_AUDIO")

    fun enableAccessibilityService() {
        shell(
            "settings put secure enabled_accessibility_services " +
                "$PACKAGE_NAME/com.gotcha.service.GotchaAccessibilityService"
        )
        shell("settings put secure accessibility_enabled 1")
    }

    fun disableAccessibilityService() {
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
    }
}
