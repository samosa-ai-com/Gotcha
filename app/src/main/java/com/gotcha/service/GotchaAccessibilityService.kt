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
import com.gotcha.util.GotchaLog
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
                    GotchaLog.d("GotchaAccessibilityService") { "Copy click detected: id=$viewId" }
                    triggerClipboardBroadcast()
                }
                source.recycle()
            }
            val eventTexts = event.text ?: emptyList()
            for (t in eventTexts) {
                val textStr = t?.toString() ?: ""
                if (isCopyString(textStr)) {
                    GotchaLog.d("GotchaAccessibilityService") { "Copy event text detected" }
                    triggerClipboardBroadcast()
                    break
                }
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val eventTexts = event.text ?: emptyList()
            for (t in eventTexts) {
                val textStr = t?.toString() ?: ""
                if (isClipboardToast(textStr)) {
                    GotchaLog.d("GotchaAccessibilityService") { "Clipboard toast detected" }
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
            GotchaLog.d("GotchaAccessibilityService") { "Sent CLIPBOARD_CHANGED broadcast" }
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

    /**
     * The node tree of the app we are looking *at*, which is not always
     * [rootInActiveWindow].
     *
     * Lens adds a full-screen overlay and only then asks what is on screen. That
     * window is focusable, so it becomes the active one, and
     * `rootInActiveWindow` starts returning our own canvas — a bare custom View
     * with no text anywhere in it. Everything downstream then behaves as though
     * the screen were empty: no Select chip, no contextual actions, no action
     * menu. It reproduced identically on two emulators and a Nothing Phone 3a,
     * and looked for a long time like a bug in the text extraction itself.
     *
     * Callers own the returned node and must recycle it, as before.
     */
    private fun hostRoot(): AccessibilityNodeInfo? {
        val candidates = runCatching { windows }.getOrNull().orEmpty()
        var best: AccessibilityNodeInfo? = null
        var bestLayer = Int.MIN_VALUE
        for (window in candidates) {
            // The window *type* is the discriminator, not the package. Our
            // overlays are added as TYPE_APPLICATION_OVERLAY and surface here as
            // TYPE_SYSTEM, alongside the status bar; the app underneath is the
            // only TYPE_APPLICATION. Filtering by package instead looks right
            // until Lens is used on Gotcha itself, at which point it throws away
            // the host too.
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (window.layer <= bestLayer) {
                root.recycle()
                continue
            }
            best?.recycle()
            best = root
            bestLayer = window.layer
        }
        return best ?: rootInActiveWindow
    }

    /** Recursively collect visible, non-blank text/content-descriptions from the active window. */
    fun dumpScreenText(limit: Int = 200): List<String> {
        val root = hostRoot() ?: return emptyList()
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
        val root = hostRoot() ?: return null
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
        // Only text that actually lives inside the selection: a node must be
        // substantially within the region, not merely touching it. A bare
        // Rect.intersects would pull in the full text of any wide block or
        // container that overlaps the selection by a single pixel, which made
        // the extracted-text card read like the whole screen.
        if (substantiallyInside(bounds, region)) {
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
     * True when [bounds] is mostly inside [region]: the intersection must cover
     * at least [REGION_TEXT_COVERAGE] of the node's own area. This keeps the
     * "Extracted Text" card scoped to the selected area.
     */
    internal fun substantiallyInside(bounds: android.graphics.Rect, region: android.graphics.Rect): Boolean =
        Companion.substantiallyInside(bounds, region, REGION_TEXT_COVERAGE)

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
        val root = hostRoot() ?: return region
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
        val root = hostRoot() ?: return emptyList()
        val nodes = mutableListOf<ScreenTextNode>()

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
            val visible = node.text?.toString()?.trim()
            val described = node.contentDescription?.toString()?.trim()
            val rect = if (!visible.isNullOrBlank() || !described.isNullOrBlank()) findValidBounds(node) else null
            if (rect != null) {
                if (!visible.isNullOrBlank()) nodes.add(ScreenTextNode(visible, rect, derived = false))
                // Both are kept when they disagree, rather than `text ?: description`.
                // A node that *renders* "3 hours ago" while *describing* itself as
                // "Jul 26, 2026" is a formatted-timestamp widget telling us so, and
                // collapsing the two threw that signal away. Anything found only in
                // the description is marked so ranking can weigh it lower.
                if (!described.isNullOrBlank() && !described.equals(visible, ignoreCase = true)) {
                    nodes.add(ScreenTextNode(described, rect, derived = !visible.isNullOrBlank()))
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
        val nodeRanges = mutableListOf<Pair<IntRange, ScreenTextNode>>()
        for (node in nodes) {
            val startIdx = fullTextBuilder.length
            fullTextBuilder.append(node.text).append("\n")
            val endIdx = fullTextBuilder.length
            nodeRanges.add(Pair(startIdx..endIdx, node))
        }

        val fullText = fullTextBuilder.toString()
        val settings = runCatching { com.gotcha.data.SettingsRepository(applicationContext).load() }.getOrNull()
        val prefCurr = settings?.preferredCurrency ?: "USD"
        val prefLang = settings?.preferredLanguage ?: "English"
        val entities = SmartActionDetector.detectAll(
            fullText,
            allowChat = false,
            targetCurrency = prefCurr,
            targetLanguage = prefLang
        )
        val placeable = discardOversizedBounds(locateEntities(entities, nodeRanges))
        val selected = SmartActionDetector.selectForAnnotation(placeable.map { it.entity })
        // Matched by identity, not equality: selection hands back the very objects
        // it was given, and two genuinely distinct detections can compare equal.
        return selected.mapNotNull { candidate ->
            val bounds = placeable.firstOrNull { it.entity === candidate.entity }?.boundsOnScreen
                ?: return@mapNotNull null
            AnnotatedEntity(candidate.entity, bounds, candidate.members)
        }
    }

    /**
     * Map each entity onto the union of the bounds of the nodes its span covers.
     *
     * An entity that turned up *only* in nodes' contentDescriptions is weighted
     * down rather than dropped: that it was never rendered as text is a reason to
     * doubt it, not proof it is wrong.
     */
    private fun locateEntities(
        entities: List<DetectedEntity>,
        nodeRanges: List<Pair<IntRange, ScreenTextNode>>
    ): List<AnnotatedEntity> {
        val located = mutableListOf<AnnotatedEntity>()
        for (entity in entities) {
            val unionRect = android.graphics.Rect()
            var count = 0
            var derivedOnly = true
            for ((range, node) in nodeRanges) {
                if (range.first > entity.span.last || entity.span.first > range.last) continue
                if (count == 0) unionRect.set(node.bounds) else unionRect.union(node.bounds)
                if (!node.derived) derivedOnly = false
                count++
            }
            if (count == 0 || unionRect.width() <= 0 || unionRect.height() <= 0) continue
            // A date nobody can see is a formatted timestamp. Every other type
            // survives on a description alone — a phone number read out to a
            // screen reader is still a phone number — but a node rendering
            // "10 hours ago" while describing itself as a date is the one case
            // where the description exists precisely because the value is not an
            // event. Tense catches these once they are a day old; this catches
            // them while they are still today.
            if (derivedOnly && entity.type == EntityType.CALENDAR) continue
            val weighted = if (derivedOnly) {
                entity.copy(confidence = entity.confidence * SmartActionDetector.DERIVED_TEXT_CONFIDENCE_SCALE)
            } else {
                entity
            }
            located.add(AnnotatedEntity(weighted, unionRect))
        }
        return located
    }

    /**
     * Drop annotations whose bounds are too big to mean anything.
     *
     * `findValidBounds` climbs to a parent when a text node is smaller than 10px,
     * which for a compact timestamp can walk all the way up to the list container
     * — so the "annotation" is a box around the entire feed. Two things give that
     * away without knowing the app: it covers most of the screen, or it wholly
     * contains another annotation's bounds.
     */
    private fun discardOversizedBounds(items: List<AnnotatedEntity>): List<AnnotatedEntity> {
        if (items.size <= 1) return items
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        val maxArea = (screenArea * SmartActionDetector.MAX_ANNOTATION_SCREEN_FRACTION).toLong()

        return items.filter { item ->
            val bounds = item.boundsOnScreen
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (screenArea > 0 && area > maxArea) return@filter false
            items.none { other ->
                other !== item &&
                    bounds.contains(other.boundsOnScreen) &&
                    bounds != other.boundsOnScreen
            }
        }
    }

    /** One piece of text on screen, and whether it came from a node's description rather than its label. */
    private data class ScreenTextNode(
        val text: String,
        val bounds: android.graphics.Rect,
        val derived: Boolean
    )

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
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
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

        /**
         * Fraction of a node's own area that must fall inside the Lens selection
         * for its text to be included (see [substantiallyInside]). Mirrors the
         * snap-to-element coverage so the extracted text matches the selection.
         */
        private const val REGION_TEXT_COVERAGE = 0.5f

        /**
         * Pure geometric helper exposed for tests. True when [bounds] is mostly
         * inside [region]: the intersection must cover at least [coverage] of
         * the node's own area. Kept on the companion so unit tests can pin the
         * threshold without instantiating the service.
         */
        internal fun substantiallyInside(
            bounds: android.graphics.Rect,
            region: android.graphics.Rect,
            coverage: Float = REGION_TEXT_COVERAGE
        ): Boolean {
            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
            val inter = android.graphics.Rect(bounds)
            if (!inter.intersect(region)) return false
            val nodeArea = bounds.width().toLong() * bounds.height().toLong()
            val interArea = inter.width().toLong() * inter.height().toLong()
            return interArea >= nodeArea * coverage
        }
    }

    /** Register a clipboard listener to cache clipboard content. */
    internal fun initClipboardListener() {
        GotchaLog.d("GotchaAccessibilityService") { "initClipboardListener: using event-based detection" }
    }
}
