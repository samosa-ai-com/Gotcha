package com.gotcha.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gotcha.ui.ScreenCropOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Drives "Lens" mode: the user draws a free-form shape over the screen; its
 * bounding rectangle is cropped from a fresh accessibility screenshot. The crop
 * can then be:
 *  - opened in the Screen Companion panel as an attached image, so the user can
 *    ask their own question about it (NOT auto-explained),
 *  - copied to the clipboard,
 *  - saved to the public Downloads folder, or
 *  - acted on contextually (convert currency / add to calendar / dial / navigate)
 *    when structured data is detected inside the selection.
 *
 * Bitmap lifecycle: the controller owns exactly one [pendingCrop] at a time and is
 * the ONLY place that recycles it. Menu actions read it, then recycle happens once
 * the action's (possibly async) work has finished — this avoids the earlier
 * "can't compress a recycled bitmap" race.
 *
 * All window mutations are posted to the main thread.
 */
@Suppress("TooManyFunctions")
class ScreenLensController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Open the companion panel with the crop attached; the user asks their own question. */
    private val onAskAboutCrop: (base64Jpeg: String) -> Unit,
    /** Route a detected structured action (native intent or LLM prompt) to the host. */
    private val onContextualAction: (prompt: String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dragSlop = android.view.ViewConfiguration.get(appContext).scaledTouchSlop

    private var cropOverlay: View? = null
    private var actionMenu: View? = null

    /** The current cropped bitmap; owned and recycled only by this controller. */
    private var pendingCrop: Bitmap? = null

    /** Begin a Lens capture: add the full-screen crop overlay. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError("Lens mode requires Android 11+")
            return
        }
        mainHandler.post {
            removeActionMenu()
            removeCropOverlay()
            recyclePendingCrop()
            val overlay = ScreenCropOverlayView(
                appContext,
                onSelection = { rect -> onRegionSelected(rect) },
                onCancel = { cancel() }
            )
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            try {
                windowManager.addView(overlay, params)
                cropOverlay = overlay
            } catch (_: Exception) {
                cropOverlay = null
                onError("Couldn't start Lens mode")
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            removeCropOverlay()
            removeActionMenu()
            recyclePendingCrop()
        }
    }

    private fun onRegionSelected(rectInView: Rect) {
        val overlay = cropOverlay as? ScreenCropOverlayView
        val viewW = overlay?.width ?: 0
        val viewH = overlay?.height ?: 0

        // 1) Enforce a minimum size: a thin line/scribble becomes a usable band.
        val minPx = (MIN_SELECTION_DP * appContext.resources.displayMetrics.density).toInt()
        val padded = expandToMinimum(rectInView, minPx, viewW, viewH)

        scope.launch {
            val service = GotchaAccessibilityService.instance
            if (service == null) {
                withContext(Dispatchers.Main) { onError("Accessibility service not available") }
                return@launch
            }
            // 2) Snap to UI elements the selection substantially overlaps, so drawing
            //    around ~most of a control captures the whole control.
            val snapped = service.snapRegionToElements(padded).also {
                it.intersect(0, 0, viewW.coerceAtLeast(1), viewH.coerceAtLeast(1))
            }
            val finalRect = if (snapped.width() >= minPx && snapped.height() >= minPx) snapped else padded

            // Freeze the selection on the overlay so it stays visible (with the live
            // particle animation) while the action menu is up.
            withContext(Dispatchers.Main) { overlay?.freezeSelection(finalRect) }

            // Detect structured data inside the selection via accessibility text.
            val regionText = service.dumpTextInRegion(finalRect)
            val contextualActions = regionText?.let { SmartActionDetector.detectContextual(it) } ?: emptyList()

            // Hide the overlay's chrome only for the capture instant so it isn't
            // baked into the screenshot, then restore it behind the menu.
            withContext(Dispatchers.Main) { overlay?.setCaptureMode(true) }
            kotlinx.coroutines.delay(80)
            var full = service.takeScreenshotBitmap()
            if (full == null) {
                kotlinx.coroutines.delay(400)
                full = service.takeScreenshotBitmap()
            }
            withContext(Dispatchers.Main) { overlay?.setCaptureMode(false) }
            if (full == null) {
                withContext(Dispatchers.Main) {
                    removeCropOverlay()
                    onError("Screenshot failed — try again")
                }
                return@launch
            }
            val cropped = cropToSelection(full, finalRect, viewW, viewH)
            full.recycle()
            if (cropped == null) {
                withContext(Dispatchers.Main) {
                    removeCropOverlay()
                    onError("Selection was empty")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                pendingCrop = cropped
                showActionMenu(contextualActions, regionText)
            }
        }
    }

    /** Grow [rect] so each side is at least [minPx], clamped to the view bounds. */
    private fun expandToMinimum(rect: Rect, minPx: Int, viewW: Int, viewH: Int): Rect {
        val out = Rect(rect)
        if (out.width() < minPx) {
            val cx = out.centerX()
            out.left = cx - minPx / 2
            out.right = cx + minPx / 2
        }
        if (out.height() < minPx) {
            val cy = out.centerY()
            out.top = cy - minPx / 2
            out.bottom = cy + minPx / 2
        }
        if (viewW > 0 && viewH > 0) out.intersect(0, 0, viewW, viewH)
        return out
    }

    /**
     * Map the selection from view coordinates onto the (possibly higher-res)
     * screenshot bitmap and crop it, clamping to the bitmap bounds.
     */
    private fun cropToSelection(full: Bitmap, rect: Rect, viewW: Int, viewH: Int): Bitmap? {
        val scaleX = if (viewW > 0) full.width.toFloat() / viewW else 1f
        val scaleY = if (viewH > 0) full.height.toFloat() / viewH else 1f
        val left = (rect.left * scaleX).toInt().coerceIn(0, full.width - 1)
        val top = (rect.top * scaleY).toInt().coerceIn(0, full.height - 1)
        val right = (rect.right * scaleX).toInt().coerceIn(left + 1, full.width)
        val bottom = (rect.bottom * scaleY).toInt().coerceIn(top + 1, full.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return try {
            Bitmap.createBitmap(full, left, top, w, h)
        } catch (_: Exception) {
            null
        }
    }

    private fun showActionMenu(contextualActions: List<SmartAction>, regionText: String?) {
        removeActionMenu()
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F21E1E1E"))
                setStroke(dp(1), Color.parseColor("#66568CD8"))
            }
        }
        // Draggable handle: grab this bar to move the menu over the selection.
        container.addView(dragHandle("⠿  Lens selection  ·  drag to move"))

        // Contextual actions detected inside the selection come first.
        contextualActions.forEach { action ->
            container.addView(
                menuButton(action.label, highlight = true) {
                    finishMenu(recycle = true)
                    onContextualAction(action.prompt)
                }
            )
        }

        // "Copy text" when the selection contains readable text.
        val selectedText = regionText?.trim()
        if (!selectedText.isNullOrBlank()) {
            container.addView(
                menuButton("📝  Copy text") {
                    copyTextToClipboard(selectedText)
                    finishMenu(recycle = true)
                }
            )
        }

        container.addView(
            menuButton("🔍  Ask about this") {
                val base64 = pendingCrop?.let { encodeJpeg(it) }
                finishMenu(recycle = true)
                if (base64 != null) {
                    onAskAboutCrop(base64)
                } else {
                    onError("Couldn't encode selection")
                }
            }
        )
        container.addView(
            menuButton("📋  Copy Image") {
                pendingCrop?.let { copyImageToClipboard(it) }
                finishMenu(recycle = true)
            }
        )
        container.addView(
            menuButton("💾  Save to Downloads") {
                val bmp = pendingCrop
                // Hand ownership to the async saver, which recycles when done.
                pendingCrop = null
                removeActionMenu()
                removeCropOverlay()
                if (bmp != null) saveToDownloads(bmp) else onError("Nothing to save")
            }
        )
        container.addView(
            menuButton("✕  Cancel") {
                finishMenu(recycle = true)
            }
        )

        val params = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start centred; the user can drag it anywhere via the handle.
            val metrics = appContext.resources.displayMetrics
            x = (metrics.widthPixels - dp(280)) / 2
            y = (metrics.heightPixels / 3)
        }
        attachDragToMenu(container, params)
        try {
            windowManager.addView(container, params)
            actionMenu = container
        } catch (_: Exception) {
            actionMenu = null
        }
    }

    /** Make the menu window draggable by touching its handle row. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragToMenu(container: View, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        container.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    false // let child buttons still receive the tap
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dragSlop || kotlin.math.abs(dy) > dragSlop) {
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Exception) {}
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun dragHandle(label: String): TextView = TextView(appContext).apply {
        text = label
        setTextColor(Color.parseColor("#9FB6E0"))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(10))
    }

    /**
     * Dismiss the menu AND the (now-persisted) selection overlay, and optionally
     * recycle the crop the menu was acting on.
     */
    private fun finishMenu(recycle: Boolean) {
        removeActionMenu()
        removeCropOverlay()
        if (recycle) recyclePendingCrop()
    }

    private fun copyTextToClipboard(text: String) {
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lens text", text))
            toast("Text copied")
        } catch (e: Exception) {
            onError("Copy failed: ${e.message}")
        }
    }

    private fun copyImageToClipboard(bitmap: Bitmap) {
        try {
            val uri = writeToCache(bitmap) ?: run {
                onError("Couldn't copy image")
                return
            }
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = android.content.ClipData.newUri(
                appContext.contentResolver,
                "Cropped Image",
                uri
            )
            clipboard.setPrimaryClip(clip)
            toast("Image copied")
        } catch (e: Exception) {
            onError("Copy failed: ${e.message}")
        }
    }

    /** Write to a FileProvider-shared cache file so a content:// URI can be clipped. */
    private fun writeToCache(bitmap: Bitmap): android.net.Uri? {
        return try {
            val dir = File(appContext.cacheDir, "lens").apply { mkdirs() }
            val file = File(dir, "lens_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            androidx.core.content.FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Saves [bitmap] on IO, then recycles it — takes ownership from the caller. */
    private fun saveToDownloads(bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            try {
                val filename = "Gotcha_Crop_${System.currentTimeMillis()}.png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                        put(
                            android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Gotcha"
                        )
                    }
                    val uri = appContext.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                    if (uri != null) {
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        withContext(Dispatchers.Main) { toast("Saved to Downloads/Gotcha") }
                    } else {
                        withContext(Dispatchers.Main) { onError("Save failed") }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val directory = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "Gotcha"
                    )
                    if (!directory.exists()) directory.mkdirs()
                    val file = File(directory, filename)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    withContext(Dispatchers.Main) { toast("Saved: ${file.absolutePath}") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError("Save failed: ${e.message}") }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): String? {
        return try {
            val (w, h) = if (bitmap.width > 1024 || bitmap.height > 1024) {
                val ratio = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                (bitmap.width * ratio).toInt().coerceAtLeast(1) to
                    (bitmap.height * ratio).toInt().coerceAtLeast(1)
            } else {
                bitmap.width to bitmap.height
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val output = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
            if (scaled != bitmap) scaled.recycle()
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun recyclePendingCrop() {
        pendingCrop?.let { if (!it.isRecycled) it.recycle() }
        pendingCrop = null
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun menuButton(label: String, highlight: Boolean = false, onClick: () -> Unit): TextView =
        TextView(appContext).apply {
            text = label
            setTextColor(if (highlight) Color.parseColor("#E081C0") else Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(if (highlight) "#332C6BE0" else "#33FFFFFF"))
                if (highlight) setStroke(dp(1), Color.parseColor("#66E081C0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            setOnClickListener { onClick() }
        }

    private fun removeCropOverlay() {
        cropOverlay?.let { safeRemove(it) }
        cropOverlay = null
    }

    private fun removeActionMenu() {
        actionMenu?.let { safeRemove(it) }
        actionMenu = null
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        /** Minimum side length of a Lens selection, so a thin line still captures a band. */
        const val MIN_SELECTION_DP = 44
    }
}
