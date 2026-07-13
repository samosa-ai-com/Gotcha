package com.gotcha.tools

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
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
        val service = GotchaAccessibilityService.instance
        if (service == null) return if (isEnabled()) serviceNotRunning() else notEnabled()
        val lines = service.dumpScreenText()
        return if (lines.isEmpty()) {
            ToolResult.ok("No readable text is on screen right now.")
        } else {
            ToolResult.ok("On-screen text (${lines.size} items):\n" + lines.joinToString("\n") { "- $it" })
        }
    }

    /** Read screen text AND flag for full-resolution screenshot capture. */
    fun readScreenRaw(): ToolResult {
        val service = GotchaAccessibilityService.instance
        if (service == null) return if (isEnabled()) serviceNotRunning() else notEnabled()
        val lines = service.dumpScreenText()
        val text = if (lines.isEmpty()) "No readable text is on screen right now."
        else lines.joinToString("\n") { "- $it" }
        return ToolResult.ok("read_screen_raw:$text")
    }

    /** Tap either an on-screen element matching [text], or absolute coordinates. */
    fun tap(text: String?, x: Int?, y: Int?): ToolResult {
        val service = GotchaAccessibilityService.instance ?: return if (isEnabled()) serviceNotRunning() else notEnabled()
        return when {
            !text.isNullOrBlank() ->
                if (service.tapByText(text)) ToolResult.ok("Tapped an element matching \"$text\".")
                else ToolResult.error("Found no clickable element matching \"$text\" on screen.")
            x != null && y != null ->
                if (service.tapAt(x.toFloat(), y.toFloat())) ToolResult.ok("Tapped at ($x, $y).")
                else ToolResult.error("Could not dispatch the tap gesture.")
            else -> ToolResult.error("Provide either 'text' to match, or both 'x' and 'y' coordinates.")
        }
    }

    /** Swipe in a named direction, or between explicit coordinates. */
    fun swipe(direction: String?, x1: Int?, y1: Int?, x2: Int?, y2: Int?, normalized: Boolean = false, distance: Int? = null, index: Int? = null): ToolResult {
        val service = GotchaAccessibilityService.instance ?: return if (isEnabled()) serviceNotRunning() else notEnabled()
        if (index != null) {
            val element = ScreenPerception.resolveElementByIndex(index)
                ?: return ToolResult.error("No UI element with index $index found on screen.")
            val parts = element.bounds.split(",").map { it.trim().toIntOrNull() }
            if (parts.size == 4 && parts.none { it == null }) {
                val cx = (parts[0]!! + parts[2]!!) / 2f
                val top = parts[1]!!.toFloat()
                val bottom = parts[3]!!.toFloat()
                val left = parts[0]!!.toFloat()
                val right = parts[2]!!.toFloat()
                val h = bottom - top
                val w = right - left
                val (sx, sy, ex, ey) = when (direction?.lowercase()?.trim()) {
                    "up" -> listOf(cx, top + h * 0.8f, cx, top + h * 0.2f)
                    "down" -> listOf(cx, top + h * 0.2f, cx, top + h * 0.8f)
                    "left" -> listOf(left + w * 0.8f, (top + bottom) / 2f, left + w * 0.2f, (top + bottom) / 2f)
                    "right" -> listOf(left + w * 0.2f, (top + bottom) / 2f, left + w * 0.8f, (top + bottom) / 2f)
                    else -> return ToolResult.error("Provide a direction (up/down/left/right) when using index.")
                }
                return if (service.swipe(sx, sy, ex, ey, 500)) ToolResult.ok("Swiped $direction on element $index.")
                else ToolResult.error("Could not dispatch the swipe gesture on element $index.")
            }
        }
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            val (sx, sy, ex, ey) = if (normalized) {
                val (w, h) = ScreenPerception.getScreenDimensions()
                val s = ScreenPerception.normalizeToPixel(x1, y1, w, h)
                val e = ScreenPerception.normalizeToPixel(x2, y2, w, h)
                listOf(s.first.toFloat(), s.second.toFloat(), e.first.toFloat(), e.second.toFloat())
            } else {
                listOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
            }
            return if (service.swipe(sx, sy, ex, ey))
                ToolResult.ok("Swiped from (${sx.toInt()}, ${sy.toInt()}) to (${ex.toInt()}, ${ey.toInt()}).")
            else ToolResult.error("Could not dispatch the swipe gesture.")
        }
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val scrollPx = distance?.toFloat()?.takeIf { it > 0f } ?: (h * 0.6f)
        val (sx, sy, ex, ey) = when (direction?.lowercase()?.trim()) {
            "up" -> listOf(w / 2, h * 0.8f, w / 2, h * 0.8f - scrollPx)
            "down" -> listOf(w / 2, h * 0.2f, w / 2, h * 0.2f + scrollPx)
            "left" -> listOf(w * 0.8f, h / 2, w * 0.8f - scrollPx, h / 2)
            "right" -> listOf(w * 0.2f, h / 2, w * 0.2f + scrollPx, h / 2)
            else -> return ToolResult.error(
                "Provide a direction (up/down/left/right) or explicit x1,y1,x2,y2 coordinates."
            )
        }
        return if (service.swipe(sx, sy, ex, ey, 500)) ToolResult.ok("Swiped $direction.")
        else ToolResult.error("Could not dispatch the swipe gesture.")
    }

    /** Type text into the currently focused input field or target an element directly by index. */
    fun inputText(text: String, index: Int? = null): ToolResult {
        val service = GotchaAccessibilityService.instance ?: return if (isEnabled()) serviceNotRunning() else notEnabled()
        if (index != null) {
            val element = ScreenPerception.resolveElementByIndex(index)
                ?: return ToolResult.error("No UI element with index $index found on screen.")
            if (service.typeTextIntoNodeByBounds(element.bounds, text)) {
                return ToolResult.ok("Typed \"$text\" into element $index.")
            }
            // Fall through if direct setting fails, maybe it wasn't editable via ACTION_SET_TEXT
        }
        return if (service.typeText(text)) ToolResult.ok("Typed \"$text\" into the focused field.")
        else ToolResult.error("No editable field is focused. Try passing the index of the text field.")
    }

    /** Perform a device-wide navigation gesture via the accessibility service (back/home/recents/...). */
    fun globalAction(action: String): ToolResult {
        val service = GotchaAccessibilityService.instance ?: return if (isEnabled()) serviceNotRunning() else notEnabled()
        return if (service.performGlobal(action)) ToolResult.ok("Performed global action: $action.")
        else ToolResult.error(
            "Unknown or unsupported global action '$action'. " +
                "Use back, home, recents, notifications, quick_settings, or lock_screen."
        )
    }

    /** Tap a UI element by its index from the numbered elements list. */
    fun tapByIndex(index: Int): ToolResult {
        val service = GotchaAccessibilityService.instance ?: return if (isEnabled()) serviceNotRunning() else notEnabled()
        val element = ScreenPerception.resolveElementByIndex(index)
            ?: return ToolResult.error("No UI element with index $index found on screen.")
        val parts = element.bounds.split(",").map { it.trim().toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) {
            return ToolResult.error("Invalid bounds for element $index.")
        }
        val cx = (parts[0]!! + parts[2]!!) / 2f
        val cy = (parts[1]!! + parts[3]!!) / 2f
        return if (service.tapAt(cx, cy))
            ToolResult.ok("Tapped element $index: \"${element.text.take(50)}\" at (${cx.toInt()}, ${cy.toInt()}).")
        else ToolResult.error("Could not dispatch the tap gesture for element $index.")
    }

    /**
     * Press a system key or perform a common navigation action.
     * For "enter", clicks the currently focused input element.
     */
    fun pressKey(key: String): ToolResult {
        val k = key.lowercase().trim()
        val globalKeys = setOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen")
        if (k in globalKeys) return globalAction(k)
        if (k == "enter") {
            val service = GotchaAccessibilityService.instance ?: return if (isEnabled()) serviceNotRunning() else notEnabled()
            try {
                val root = service.rootInActiveWindow
                val result = try {
                    val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null) {
                        focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else false
                } finally { try { root?.recycle() } catch (_: Exception) { } }
                if (result) return ToolResult.ok("Pressed Enter.")
            } catch (_: Exception) { }
            return ToolResult.error("Could not press Enter. No focused element found.")
        }
        return ToolResult.error(
            "Unknown key '$key'. Valid: ${(globalKeys + "enter").joinToString(", ")}."
        )
    }

    private fun requireService(): GotchaAccessibilityService? =
        GotchaAccessibilityService.instance ?: run {
            if (isEnabled()) null else null // both cases return null, but error differs
        }

    private fun notEnabled() = ToolResult.permissionNeeded(
        ToolResult.ACCESSIBILITY_ACCESS,
        "This action needs the Gotcha accessibility service. I have opened Accessibility " +
            "settings — please enable Gotcha there and ask again."
    )

    private fun serviceNotRunning() = ToolResult.error(
        "The Gotcha accessibility service is enabled but not running. " +
            "This can happen after an app restart. Please toggle it off and on in " +
            "Settings → Accessibility, or force-stop and reopen the app."
    )

    /** True when this app's service is listed in the system's enabled-accessibility-services setting. */
    private fun isEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val pkg = context.packageName
        val cls = GotchaAccessibilityService::class.java.name
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val entry = splitter.next()
            // Check long form (com.gotcha/com.gotcha.service.GotchaAccessibilityService)
            if (entry.equals("$pkg/$cls", ignoreCase = true)) return true
            // Check short form (com.gotcha/.service.GotchaAccessibilityService)
            if (entry.equals("$pkg/.${cls.substringAfter(pkg)}", ignoreCase = true)) return true
            if (entry.startsWith("$pkg/", ignoreCase = true) && entry.endsWith(cls.substringAfterLast('.'), ignoreCase = true)) return true
        }
        return false
    }
}
