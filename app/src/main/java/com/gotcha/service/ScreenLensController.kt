package com.gotcha.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.gotcha.ui.ScreenCropOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class ScreenLensController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAskAboutCrop: (base64Jpeg: String) -> Unit,
    private val onContextualAction: (prompt: String) -> Unit,
    private val onImagePrompt: (base64Jpeg: String, prompt: String) -> Unit,
    private val onOcrToClipboard: (crop: Bitmap) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cropOverlay: View? = null
    private var actionMenu: View? = null
    private var textChips: View? = null
    private var pendingCrop: Bitmap? = null

    /** Begin a Lens capture: add the full-screen crop overlay and auto-annotate UI elements. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError("Lens mode requires Android 11+")
            return
        }
        mainHandler.post {
            removeActionMenu()
            removeCropOverlay()
            recyclePendingCrop()

            val service = GotchaAccessibilityService.instance
            val nodeTexts = service?.extractScreenNodeTexts() ?: emptyList()
            val annotatedEntities = mutableListOf<AnnotatedEntity>()
            for (nodeText in nodeTexts) {
                val entities = SmartActionDetector.detectAll(nodeText.text, allowChat = false)
                for (entity in entities) {
                    annotatedEntities.add(AnnotatedEntity(entity, nodeText.bounds))
                }
            }

            val overlay = ScreenCropOverlayView(
                appContext,
                onSelection = { rect -> onRegionSelected(rect) },
                onCancel = { cancel() },
                onReselectStart = {
                    removeActionMenu()
                    removeTextChips()
                    recyclePendingCrop()
                },
                onAnnotatedEntitySelected = { prompt ->
                    cancel()
                    onContextualAction(prompt)
                }
            )
            overlay.setAnnotatedEntities(annotatedEntities)

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

    @SuppressLint("NewApi")
    private fun onRegionSelected(rectInView: Rect) {
        val overlay = cropOverlay as? ScreenCropOverlayView
        val viewW = overlay?.width ?: 0
        val viewH = overlay?.height ?: 0

        val minPx = (MIN_SELECTION_DP * appContext.resources.displayMetrics.density).toInt()
        val padded = expandToMinimum(rectInView, minPx, viewW, viewH)

        scope.launch {
            val service = GotchaAccessibilityService.instance
            if (service == null) {
                withContext(Dispatchers.Main) { onError("Accessibility service not available") }
                return@launch
            }
            val snapped = service.snapRegionToElements(padded).also {
                it.intersect(0, 0, viewW.coerceAtLeast(1), viewH.coerceAtLeast(1))
            }
            val finalRect = if (snapped.width() >= minPx && snapped.height() >= minPx) snapped else padded

            withContext(Dispatchers.Main) { overlay?.freezeSelection(finalRect) }

            val regionText = service.dumpTextInRegion(finalRect)
            val contextualActions = regionText?.let { SmartActionDetector.detectContextual(it) } ?: emptyList()

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
                showTextChips(finalRect, regionText)
                showActionMenu(contextualActions)
            }
        }
    }

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
        out.intersect(0, 0, viewW, viewH)
        return out
    }

    private fun cropToSelection(src: Bitmap, rectInView: Rect, viewW: Int, viewH: Int): Bitmap? {
        if (src.isRecycled || viewW <= 0 || viewH <= 0) return null
        val scaleX = src.width.toFloat() / viewW.toFloat()
        val scaleY = src.height.toFloat() / viewH.toFloat()
        val cropX = (rectInView.left * scaleX).toInt().coerceIn(0, src.width - 1)
        val cropY = (rectInView.top * scaleY).toInt().coerceIn(0, src.height - 1)
        val cropW = (rectInView.width() * scaleX).toInt().coerceIn(1, src.width - cropX)
        val cropH = (rectInView.height() * scaleY).toInt().coerceIn(1, src.height - cropY)
        return runCatching { Bitmap.createBitmap(src, cropX, cropY, cropW, cropH) }.getOrNull()
    }

    private fun showTextChips(finalRect: Rect, regionText: String?) {
        removeTextChips()
        val overlay = cropOverlay ?: return
        val density = appContext.resources.displayMetrics.density
        val cleanText = regionText?.replace(Regex("\\s+"), " ")?.trim()

        val chipsLayout = android.widget.LinearLayout(appContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(android.graphics.Color.parseColor("#EE101018"))
                setStroke((1 * density).toInt(), android.graphics.Color.parseColor("#44568CD8"))
            }
        }

        val hasText = !cleanText.isNullOrBlank()
        if (hasText) {
            chipsLayout.addView(
                chipButton("📋 Copy text") {
                    val crop = pendingCrop
                    if (crop != null && !crop.isRecycled) {
                        onOcrToClipboard(crop)
                    }
                    cancel()
                }
            )
            chipsLayout.addView(
                chipButton("🌐 Translate") {
                    val crop = pendingCrop
                    if (crop != null && !crop.isRecycled) {
                        val base64 = bitmapToBase64(crop)
                        onImagePrompt(base64, ScreenCompanionController.TRANSLATE_SCREENSHOT_PROMPT)
                        cancel()
                    }
                }
            )
        }

        chipsLayout.addView(
            chipButton("❓ Ask about this") {
                val crop = pendingCrop
                if (crop != null && !crop.isRecycled) {
                    val base64 = bitmapToBase64(crop)
                    onAskAboutCrop(base64)
                    cancel()
                }
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (finalRect.centerX() - (120 * density)).toInt().coerceIn(0, overlay.width - 240)
            y = (finalRect.top - (48 * density)).toInt().coerceAtLeast((16 * density).toInt())
        }

        try {
            windowManager.addView(chipsLayout, params)
            textChips = chipsLayout
        } catch (_: Exception) {
            textChips = null
        }
    }

    private fun chipButton(label: String, onClick: () -> Unit): TextView {
        val density = appContext.resources.displayMetrics.density
        return TextView(appContext).apply {
            text = label
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun showActionMenu(actions: List<SmartAction>) {
        removeActionMenu()
        if (actions.isEmpty()) return

        val density = appContext.resources.displayMetrics.density
        val menuLayout = android.widget.LinearLayout(appContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(android.graphics.Color.parseColor("#F51E293B"))
                setStroke((1 * density).toInt(), android.graphics.Color.parseColor("#334155"))
            }
        }

        for (action in actions) {
            val btn = TextView(appContext).apply {
                text = action.label
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
                setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(android.graphics.Color.parseColor("#334155"))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt()) }
                setOnClickListener {
                    val prompt = action.prompt
                    cancel()
                    onContextualAction(prompt)
                }
            }
            menuLayout.addView(btn)
        }

        val params = WindowManager.LayoutParams(
            (240 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(menuLayout, params)
            actionMenu = menuLayout
        } catch (_: Exception) {
            actionMenu = null
        }
    }

    private fun removeCropOverlay() {
        cropOverlay?.let { safeRemove(it) }
        cropOverlay = null
    }

    private fun removeActionMenu() {
        actionMenu?.let { safeRemove(it) }
        actionMenu = null
    }

    private fun removeTextChips() {
        textChips?.let { safeRemove(it) }
        textChips = null
    }

    private fun recyclePendingCrop() {
        pendingCrop?.let {
            if (!it.isRecycled) it.recycle()
        }
        pendingCrop = null
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private companion object {
        const val MIN_SELECTION_DP = 24
    }
}
