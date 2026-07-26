package com.gotcha.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
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
// One function per accessibility capability (tap, swipe, type, …) by design; size is inherent.
data class ScreenNodeText(val text: String, val bounds: android.graphics.Rect)

@Suppress("TooManyFunctions")
class GotchaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initClipboardListener()
    }

    // Passive: we drive the UI on demand from tools rather than reacting to events,
    // except for broadcasting window state changes to the ScreenCompanionController.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val intent = android.content.Intent("com.gotcha.action.APP_CHANGED").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        // Detect copy actions or clipboard toast alerts
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            if (source != null) {
                val text = source.text?.toString() ?: ""
                val desc = source.contentDescription?.toString() ?: ""
                val viewId = source.viewIdResourceName ?: ""
                if (isCopyString(text) || isCopyString(desc) || viewId.lowercase().contains("copy")) {
                    android.util.Log.d(
                        "GotchaAccessibilityService",
                        "Copy click detected: id=$viewId"
                    )
                    triggerClipboardBroadcast()
                }
                source.recycle()
            }
            val eventTexts = event.text ?: emptyList()
            for (t in eventTexts) {
                val textStr = t?.toString() ?: ""
                if (isCopyString(textStr)) {
                    android.util.Log.d("GotchaAccessibilityService", "Copy event text detected")
                    triggerClipboardBroadcast()
                    break
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val eventTexts = event.text ?: emptyList()
            for (t in eventTexts) {
                val textStr = t?.toString() ?: ""
                if (isClipboardToast(textStr)) {
                    android.util.Log.d(
                        "GotchaAccessibilityService",
                        "Clipboard toast detected"
                    )
                    triggerClipboardBroadcast()
                    break
                }
            }
        }
    }

    private fun isCopyString(s: String): Boolean {
        val lower = s.lowercase().trim()
        return lower == "copy" ||
            lower == "复制" ||
            lower == "剪切" ||
            lower == "copying" ||
            lower == "copier" ||
            lower == "copiar" ||
            lower == "kopieren" ||
            lower == "copia" ||
            lower == "コピー" ||
            lower == "복사"
    }

    private fun isClipboardToast(s: String): Boolean {
        val lower = s.lowercase()
        return lower.contains("copy") ||
            lower.contains("copied") ||
            lower.contains("clipboard") ||
            lower.contains("复制") ||
            lower.contains("剪贴") ||
            lower.contains("剪切板") ||
            lower.contains("copi") ||
            lower.contains("kopier") ||
            lower.contains("clip")
    }

    private fun triggerClipboardBroadcast() {
        try {
            val intent = android.content.Intent("com.gotcha.action.CLIPBOARD_CHANGED").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            android.util.Log.d(
                "GotchaAccessibilityService",
                "Sent CLIPBOARD_CHANGED broadcast"
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "GotchaAccessibilityService",
                "Failed to send broadcast",
                e
            )
        }
    }

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
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (_: Throwable) {
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
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
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

    /**
     * Collect visible text from nodes that intersect [regionInScreen] (screen
     * pixels). Used by Lens mode to detect structured data (currency, dates, etc.)
     * inside the user's selection without OCR. Returns joined text or null.
     */
    fun dumpTextInRegion(regionInScreen: android.graphics.Rect, limit: Int = 60): String? {
        val root = rootInActiveWindow ?: return null
        val out = ArrayList<String>()
        collectTextInRegion(root, regionInScreen, out, limit)
        root.recycle()
        return if (out.isEmpty()) null else out.joinToString(" ")
    }

    private fun collectTextInRegion(
        node: AccessibilityNodeInfo?,
        region: android.graphics.Rect,
        out: MutableList<String>,
        limit: Int
    ) {
        if (node == null || out.size >= limit) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (android.graphics.Rect.intersects(bounds, region)) {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                out.add(text)
            } else if (!desc.isNullOrEmpty()) out.add(desc)
        }
        for (i in 0 until node.childCount) {
            collectTextInRegion(node.getChild(i), region, out, limit)
        }
    }

    /**
     * Grow [region] (screen pixels) to include any UI element it substantially
     * overlaps, so a selection drawn around ~most of a control snaps to the whole
     * control. An element is included when the intersection covers at least
     * [coverage] of EITHER the element's area or the drawn region's area (the
     * latter lets a small scribble inside a big element still snap to it). Returns
     * the unioned rectangle, or the original [region] when nothing qualifies.
     */
    fun snapRegionToElements(
        region: android.graphics.Rect,
        coverage: Float = 0.6f
    ): android.graphics.Rect {
        val root = rootInActiveWindow ?: return region
        val result = android.graphics.Rect(region)
        val regionArea = region.width().toLong() * region.height().toLong()
        accumulateSnap(root, region, regionArea, coverage, result)
        root.recycle()
        return result
    }

    private fun accumulateSnap(
        node: AccessibilityNodeInfo?,
        region: android.graphics.Rect,
        regionArea: Long,
        coverage: Float,
        acc: android.graphics.Rect
    ) {
        if (node == null) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && android.graphics.Rect.intersects(bounds, region)) {
            val inter = android.graphics.Rect(bounds)
            if (inter.intersect(region)) {
                val interArea = inter.width().toLong() * inter.height().toLong()
                val nodeArea = bounds.width().toLong() * bounds.height().toLong()
                val coversNode = nodeArea > 0 && interArea >= coverage * nodeArea
                val coversRegion = regionArea > 0 && interArea >= coverage * regionArea
                // Skip full-screen containers/root so we snap to actual controls,
                // not the window that swallows everything.
                val metrics = resources.displayMetrics
                val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
                val sane = nodeArea in 1 until (screenArea * 8 / 10)
                if ((coversNode || coversRegion) && sane) {
                    acc.union(bounds)
                }
            }
        }
        for (i in 0 until node.childCount) {
            accumulateSnap(node.getChild(i), region, regionArea, coverage, acc)
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

    /**
     * Traverses active screen tree, builds full screen text with character offsets,
     * runs SmartActionDetector.detectAll, and constructs union bounding boxes for every entity.
     */
    fun extractScreenEntitiesWithBounds(): List<AnnotatedEntity> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<Pair<String, android.graphics.Rect>>()

        fun findValidBounds(node: AccessibilityNodeInfo): android.graphics.Rect? {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                val r = android.graphics.Rect()
                curr.getBoundsInScreen(r)
                if (r.width() > 10 && r.height() > 10) {
                    return r
                }
                curr = curr.parent
            }
            return null
        }

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val txt = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (!txt.isNullOrBlank()) {
                val rect = findValidBounds(node)
                if (rect != null) {
                    nodes.add(Pair(txt, rect))
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        walk(root)
        root.recycle()

        if (nodes.isEmpty()) return emptyList()

        val fullTextBuilder = StringBuilder()
        val nodeRanges = mutableListOf<Pair<IntRange, android.graphics.Rect>>()
        for ((txt, rect) in nodes) {
            val startIdx = fullTextBuilder.length
            fullTextBuilder.append(txt).append("\n")
            val endIdx = fullTextBuilder.length
            nodeRanges.add(Pair(startIdx..endIdx, rect))
        }

        val fullText = fullTextBuilder.toString()
        val entities = SmartActionDetector.detectAll(fullText, allowChat = false)
        val annotated = mutableListOf<AnnotatedEntity>()

        for (entity in entities) {
            val unionRect = android.graphics.Rect()
            var count = 0
            for ((range, rect) in nodeRanges) {
                if (range.first <= entity.span.last && entity.span.first <= range.last) {
                    if (count == 0) {
                        unionRect.set(rect)
                    } else {
                        unionRect.union(rect)
                    }
                    count++
                }
            }
            if (count > 0 && unionRect.width() > 0 && unionRect.height() > 0) {
                annotated.add(AnnotatedEntity(entity, unionRect))
            }
        }
        return annotated
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
            } else {
                return false
            }
        } catch (_: Exception) {
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
        android.util.Log.d(
            "GotchaAccessibilityService",
            "initClipboardListener: using event-based detection"
        )
    }
}
