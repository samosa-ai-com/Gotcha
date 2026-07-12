package com.gotcha.tools

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.gotcha.service.GotchaAccessibilityService

/**
 * Tier 3 — drives arbitrary app UIs through the [GotchaAccessibilityService].
 *
 * Every method first checks that the service is actually enabled and bound; if not
 * it returns [ToolResult.permissionNeeded] with the [ToolResult.ACCESSIBILITY_ACCESS]
 * marker so the UI can deep-link the user to Settings → Accessibility.
 */
class AccessibilityTool(private val context: Context) {

    /** Read the visible on-screen text of whatever app is in the foreground. */
    fun readScreen(): ToolResult {
        val service = requireService() ?: return notEnabled()
        val lines = service.dumpScreenText()
        return if (lines.isEmpty()) {
            ToolResult.ok("No readable text is on screen right now.")
        } else {
            ToolResult.ok("On-screen text (${lines.size} items):\n" + lines.joinToString("\n") { "- $it" })
        }
    }

    /** Tap either an on-screen element matching [text], or absolute coordinates. */
    fun tap(text: String?, x: Int?, y: Int?): ToolResult {
        val service = requireService() ?: return notEnabled()
        return when {
            !text.isNullOrBlank() ->
                if (service.tapByText(text)) {
                    ToolResult.ok("Tapped an element matching \"$text\".")
                } else {
                    ToolResult.error("Found no clickable element matching \"$text\" on screen.")
                }
            x != null && y != null ->
                if (service.tapAt(x.toFloat(), y.toFloat())) {
                    ToolResult.ok("Tapped at ($x, $y).")
                } else {
                    ToolResult.error("Could not dispatch the tap gesture.")
                }
            else -> ToolResult.error("Provide either 'text' to match, or both 'x' and 'y' coordinates.")
        }
    }

    /** Swipe in a named direction, or between explicit coordinates. */
    fun swipe(direction: String?, x1: Int?, y1: Int?, x2: Int?, y2: Int?): ToolResult {
        val service = requireService() ?: return notEnabled()
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return if (service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())) {
                ToolResult.ok("Swiped from ($x1, $y1) to ($x2, $y2).")
            } else {
                ToolResult.error("Could not dispatch the swipe gesture.")
            }
        }
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val (sx, sy, ex, ey) = when (direction?.lowercase()?.trim()) {
            "up" -> listOf(w / 2, h * 0.7f, w / 2, h * 0.3f)
            "down" -> listOf(w / 2, h * 0.3f, w / 2, h * 0.7f)
            "left" -> listOf(w * 0.7f, h / 2, w * 0.3f, h / 2)
            "right" -> listOf(w * 0.3f, h / 2, w * 0.7f, h / 2)
            else -> return ToolResult.error(
                "Provide a direction (up/down/left/right) or explicit x1,y1,x2,y2 coordinates."
            )
        }
        return if (service.swipe(sx, sy, ex, ey)) {
            ToolResult.ok("Swiped $direction.")
        } else {
            ToolResult.error("Could not dispatch the swipe gesture.")
        }
    }

    /** Type text into the currently focused input field. */
    fun inputText(text: String): ToolResult {
        val service = requireService() ?: return notEnabled()
        return if (service.typeText(text)) {
            ToolResult.ok("Typed \"$text\" into the focused field.")
        } else {
            ToolResult.error("No editable field is focused. Tap a text field first, then input text.")
        }
    }

    /** Perform a device-wide navigation gesture (back/home/recents/notifications/quick_settings/lock_screen). */
    fun globalAction(action: String): ToolResult {
        val service = requireService() ?: return notEnabled()
        return if (service.performGlobal(action)) {
            ToolResult.ok("Performed global action: $action.")
        } else {
            ToolResult.error(
                "Unknown or unsupported global action '$action'. " +
                    "Use back, home, recents, notifications, quick_settings, or lock_screen."
            )
        }
    }

    private fun requireService(): GotchaAccessibilityService? =
        if (isEnabled()) GotchaAccessibilityService.instance else null

    private fun notEnabled() = ToolResult.permissionNeeded(
        ToolResult.ACCESSIBILITY_ACCESS,
        "This action needs the Gotcha accessibility service. I have opened Accessibility " +
            "settings — please enable Gotcha there and ask again."
    )

    /** True when this app's service is listed in the system's enabled-accessibility-services setting. */
    private fun isEnabled(): Boolean {
        val expected = "${context.packageName}/${GotchaAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
