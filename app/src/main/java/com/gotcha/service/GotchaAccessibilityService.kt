package com.gotcha.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
        initClipboardListener()
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

    /**
     * Capture a screenshot of the default display via the AccessibilityService screenshot
     * API (API 30+, non-root). Returns a software [Bitmap] or null on failure. The caller
     * is responsible for downscaling/compressing. Used by the assistive-ball "screen share"
     * flow to give the LLM vision context alongside [dumpScreenText].
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun takeScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

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
        if (!text.isNullOrEmpty()) {
            out.add(text)
        } else if (!desc.isNullOrEmpty()) out.add(desc)
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

    fun longPressByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findClickable(root, query.lowercase())
        val performed = match?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
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
        val ok = setTextOnNode(focused, text)
        focused.recycle()
        return ok
    }

    /** Type [text] into a specific node matching the given bounds (left, top, right, bottom). */
    fun typeTextIntoNodeByBounds(boundsStr: String, text: String): Boolean {
        val targetBounds = android.graphics.Rect()
        try {
            val parts = boundsStr.split(",").map { it.trim().toInt() }
            if (parts.size == 4) {
                targetBounds.set(parts[0], parts[1], parts[2], parts[3])
            } else return false
        } catch (e: Exception) {
            return false
        }
        
        val root = rootInActiveWindow ?: return false
        var match: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null || match != null) return
            if (node.isVisibleToUser) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds == targetBounds) {
                    match = AccessibilityNodeInfo.obtain(node)
                    return
                }
            }
            for (i in 0 until node.childCount) {
                search(node.getChild(i))
            }
        }
        
        search(root)
        root.recycle()
        
        val nodeToEdit = match ?: return false
        val ok = setTextOnNode(nodeToEdit, text)
        nodeToEdit.recycle()
        return ok
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Dispatch a tap gesture at absolute screen coordinates (API 24+). */
    fun tapAt(x: Float, y: Float): Boolean = gesture(x, y, x, y, 50)
    
    fun longPressAt(x: Float, y: Float): Boolean = gesture(x, y, x, y, 1000)

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

        /** Last clipboard content, used as fallback when direct read is blocked (API 34+). */
        @Volatile
        var lastClipboardData: ClipData? = null
            internal set
    }

    /** Register a clipboard listener to cache clipboard content. */
    internal fun initClipboardListener() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            try { lastClipboardData = cm.primaryClip } catch (_: Exception) {}
        }
    }
}
