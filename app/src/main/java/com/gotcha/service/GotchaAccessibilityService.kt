package com.gotcha.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tier 3 — AccessibilityService.
 *
 * The single biggest capability jump on the ladder: once the user enables this in
 * Settings → Accessibility, the app can read the on-screen text of any app and
 * perform taps/swipes/typing/global gestures on its behalf.
 *
 * The service exposes itself through a static [instance] so the (stateless)
 * [com.gotcha.tools.AccessibilityTool] can reach the live, bound service. The
 * service is only alive while the user has it enabled; the tool checks [instance]
 * for null and returns a permission hint otherwise.
 */
class GotchaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    // Passive: we drive the UI on demand from tools rather than reacting to events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Capabilities used by AccessibilityTool ----

    /** Recursively collect visible, non-blank text/content-descriptions from the active window. */
    fun dumpScreenText(limit: Int = 200): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        collectText(root, out, limit)
        root.recycle()
        return out
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, limit: Int) {
        if (node == null || out.size >= limit) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        else if (!desc.isNullOrEmpty()) out.add(desc)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, limit)
        }
    }

    /** Tap the first clickable node whose text/description contains [query] (case-insensitive). */
    fun tapByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findClickable(root, query.lowercase())
        val performed = match?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        root.recycle()
        return performed
    }

    private fun findClickable(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val hay = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: ""))
            .lowercase()
        if (hay.contains(query)) {
            var candidate: AccessibilityNodeInfo? = node
            while (candidate != null && !candidate.isClickable) candidate = candidate.parent
            if (candidate != null) return candidate
        }
        for (i in 0 until node.childCount) {
            findClickable(node.getChild(i), query)?.let { return it }
        }
        return null
    }

    /** Type [text] into the currently focused editable field. */
    fun typeText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return ok
    }

    /** Dispatch a tap gesture at absolute screen coordinates (API 24+). */
    fun tapAt(x: Float, y: Float): Boolean = gesture(x, y, x, y, 50)

    /** Dispatch a swipe gesture between two points. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean =
        gesture(x1, y1, x2, y2, durationMs)

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Perform a global action by name; returns false for unknown names. */
    fun performGlobal(action: String): Boolean {
        val code = when (action.lowercase().trim()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else -1
            else -> -1
        }
        return if (code >= 0) performGlobalAction(code) else false
    }

    companion object {
        /** The live service instance while the user has it enabled; null otherwise. */
        @Volatile
        var instance: GotchaAccessibilityService? = null
            private set
    }
}
