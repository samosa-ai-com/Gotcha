package com.gotcha.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.gotcha.R
import com.gotcha.ui.ScreenCropOverlayView
import com.gotcha.ui.applyOverlayCard
import com.gotcha.ui.theme.OverlaySkin
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin
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
    private val onError: (String) -> Unit,
    private val onCaptureChrome: (hide: Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext

    /**
     * The active skin, re-read on every build rather than cached, so a theme
     * change in Settings reaches Lens without the window being torn down.
     */
    private fun currentColors(): OverlaySkin = overlaySkin(
        appContext,
        runCatching { com.gotcha.data.SettingsRepository(appContext).load().skinId }
            .getOrDefault(Skins.DEFAULT_ID)
    )

    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Lens does not survive a rotation, and should not pretend to.
     *
     * Everything the session is holding — the crop rectangle, the accessibility
     * bounds the annotations are drawn at, the chip bar's placement, the crop
     * bitmap already taken — is in the pixels of the screen it started on. When
     * the device rotates, the app underneath re-lays itself out, so those
     * coordinates no longer point at the thing the user was selecting. Closing
     * is the only honest answer; re-placing the chrome would leave a selection
     * that looks right and means nothing.
     */
    private val rotationWatcher = com.gotcha.ui.OverlayRotationWatcher(context) { _, _ -> cancel() }

    private var cropOverlay: View? = null
    private var actionMenu: View? = null
    private var textChips: View? = null

    /** Where the chip bar ended up, so the action menu can sit under it. */
    private var chipBarBounds: Rect? = null
    private var pendingCrop: Bitmap? = null

    /** Begin a Lens capture: add the full-screen crop overlay and auto-annotate UI elements. */
    fun start() {
        mainHandler.post {
            removeActionMenu()
            removeCropOverlay()
            removeTextChips()
            recyclePendingCrop()

            val service = GotchaAccessibilityService.instance
            val annotatedEntities = service?.extractScreenEntitiesWithBounds() ?: emptyList()

            val overlay = ScreenCropOverlayView(
                appContext,
                colors = currentColors(),
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
                },
                // A grouped chip opens its members instead of firing one of them.
                // The Lens overlay stays up behind the menu: picking from a list
                // of prices is a choice, and closing the thing they are listed on
                // would leave nothing to choose against.
                onAnnotatedGroupSelected = { group ->
                    showActionMenu(
                        group.members
                            .mapNotNull { it.primaryAction }
                            .distinctBy { it.prompt }
                            .take(MAX_GROUP_MENU_ITEMS)
                    )
                }
            )
            overlay.setAnnotatedEntities(annotatedEntities)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            try {
                windowManager.addView(overlay, params)
                cropOverlay = overlay
                rotationWatcher.start()
            } catch (_: Exception) {
                cropOverlay = null
                onError("Couldn't start Lens mode")
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            rotationWatcher.stop()
            removeCropOverlay()
            removeActionMenu()
            removeTextChips()
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

            withContext(Dispatchers.Main) {
                overlay?.setCaptureMode(true)
                onCaptureChrome(true)
            }
            kotlinx.coroutines.delay(250L)
            var full = service.takeScreenshotBitmap()
            if (full == null) {
                kotlinx.coroutines.delay(600L)
                full = service.takeScreenshotBitmap()
            }
            withContext(Dispatchers.Main) {
                onCaptureChrome(false)
                overlay?.setCaptureMode(false)
            }
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
        val cleanText = regionText?.replace(Regex("\\s+"), " ")?.trim()
        val colors = currentColors()

        val chipsLayout = android.widget.LinearLayout(appContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            applyOverlayCard(colors, horizontalDp = 6, verticalDp = 4)
        }

        if (!cleanText.isNullOrBlank()) {
            chipsLayout.addView(
                chipButton("Select", colors, R.drawable.ic_lens_select) {
                    showSelectableTextCard(cleanText)
                }
            )
        }

        chipsLayout.addView(
            chipButton("Copy", colors, R.drawable.ic_lens_copy) {
                val crop = pendingCrop
                if (crop != null && !crop.isRecycled) {
                    val cropCopy = runCatching { crop.copy(crop.config, false) }.getOrNull()
                    if (cropCopy != null) {
                        onOcrToClipboard(cropCopy)
                    }
                }
                cancel()
            }
        )

        val settings = runCatching { com.gotcha.data.SettingsRepository(appContext).load() }.getOrNull()
        val preferredLang = settings?.preferredLanguage ?: "English"
        val isAlreadyInPreferredLang = !cleanText.isNullOrBlank() && SmartActionDetector.isTextInLanguage(cleanText, preferredLang)

        if (!isAlreadyInPreferredLang) {
            chipsLayout.addView(
                chipButton("Translate", colors, R.drawable.ic_lens_translate) {
                    val prompt = "Extract text from this screenshot, translate it to $preferredLang, " +
                        "and display both original and translated text."
                    encodePendingCrop { base64 -> onImagePrompt(base64, prompt) }
                    cancel()
                }
            )
        }

        chipsLayout.addView(
            chipButton("Save", colors, R.drawable.ic_lens_save) {
                val crop = pendingCrop
                if (crop != null && !crop.isRecycled) {
                    val cropCopy = runCatching { crop.copy(crop.config, false) }.getOrNull()
                    if (cropCopy != null) {
                        saveCropToGallery(cropCopy)
                    }
                }
                cancel()
            }
        )

        chipsLayout.addView(
            chipButton("Ask", colors, R.drawable.ic_lens_ask) {
                encodePendingCrop { base64 -> onAskAboutCrop(base64) }
                cancel()
            }
        )

        // Measured, not guessed. This used to assume 300dp × 42dp, which was
        // already approximate and became more so once the bar grew icons and an
        // OverlayCardDrawable gutter — a shadow lives outside the card, so the
        // view is wider than the card you can see.
        val (chipBarWidth, chipBarHeight) = measure(chipsLayout)
        val placed = placeNear(finalRect, chipBarWidth, chipBarHeight, overlay.width, overlay.height)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = placed.left
            y = placed.top
        }

        try {
            windowManager.addView(chipsLayout, params)
            textChips = chipsLayout
            chipBarBounds = placed
        } catch (_: Exception) {
            textChips = null
            chipBarBounds = null
        }
    }

    /** [view]'s size when it is allowed to be exactly as big as it wants. */
    private fun measure(view: View): Pair<Int, Int> {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(spec, spec)
        return view.measuredWidth to view.measuredHeight
    }

    /**
     * Put a [w]×[h] panel just below [anchor], or just above it when there is no
     * room, or pinned to the top when there is room for neither. Horizontally
     * centred on the anchor and kept on screen.
     */
    private fun placeNear(anchor: Rect, w: Int, h: Int, screenW: Int, screenH: Int): Rect {
        val density = appContext.resources.displayMetrics.density
        val gap = (GAP_DP * density).toInt()
        val margin = (EDGE_MARGIN_DP * density).toInt()

        val y = when {
            anchor.bottom + gap + h <= screenH -> anchor.bottom + gap
            anchor.top - gap - h >= 0 -> anchor.top - gap - h
            else -> margin
        }
        val x = (anchor.centerX() - w / 2).coerceIn(
            margin,
            (screenW - w - margin).coerceAtLeast(margin)
        )
        return Rect(x, y, x + w, y + h)
    }

    /**
     * One chip on the Lens bar. The icon sits *above* the label rather than
     * beside it, unlike the ball's menu rows: five of these have to fit across a
     * phone, and side-by-side icons would push the bar wider than the screen.
     */
    private fun chipButton(
        label: String,
        colors: OverlaySkin,
        iconRes: Int? = null,
        onClick: () -> Unit
    ): TextView {
        val density = appContext.resources.displayMetrics.density
        val iconPx = (CHIP_ICON_DP * density).toInt()
        return TextView(appContext).apply {
            text = label
            textSize = colors.labelSp
            typeface = colors.sans
            setTextColor(colors.buttonText)
            gravity = Gravity.CENTER
            if (iconRes != null) {
                val icon = androidx.core.content.ContextCompat.getDrawable(appContext, iconRes)
                icon?.setBounds(0, 0, iconPx, iconPx)
                icon?.setTint(colors.buttonText)
                setCompoundDrawablesRelative(null, icon, null, null)
                compoundDrawablePadding = (CHIP_ICON_GAP_DP * density).toInt()
            }
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun showSelectableTextCard(text: String) {
        removeActionMenu()
        val density = appContext.resources.displayMetrics.density
        val colors = currentColors()

        val cardLayout = android.widget.LinearLayout(appContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            applyOverlayCard(colors, horizontalDp = 16, verticalDp = 14)
        }

        val titleView = TextView(appContext).apply {
            this.text = "Extracted Text"
            textSize = colors.titleSp
            setTypeface(colors.sans, android.graphics.Typeface.BOLD)
            setTextColor(colors.onSurface)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        cardLayout.addView(titleView)

        val textView = TextView(appContext).apply {
            this.text = text
            textSize = colors.bodySp
            typeface = colors.sans
            setTextColor(colors.onSurfaceVariant)
            setTextIsSelectable(true)
        }

        val scroll = android.widget.ScrollView(appContext).apply {
            addView(textView)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * density).toInt()
            )
        }
        cardLayout.addView(scroll)

        val btnRow = android.widget.LinearLayout(appContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }

        btnRow.addView(
            chipButton("Copy All", colors, R.drawable.ic_lens_copy) {
                val clipService = Context.CLIPBOARD_SERVICE
                val clipManager = appContext.getSystemService(clipService) as? android.content.ClipboardManager
                clipManager?.setPrimaryClip(android.content.ClipData.newPlainText("Extracted Text", text))
                android.widget.Toast.makeText(
                    appContext,
                    "Copied to clipboard",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                cancel()
            }
        )

        btnRow.addView(
            chipButton("Close", colors) {
                cancel()
            }
        )
        cardLayout.addView(btnRow)

        val params = WindowManager.LayoutParams(
            (300 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(cardLayout, params)
            actionMenu = cardLayout
        } catch (_: Exception) {
            actionMenu = null
        }
    }

    private fun showActionMenu(actions: List<SmartAction>) {
        removeActionMenu()
        if (actions.isEmpty()) return

        val density = appContext.resources.displayMetrics.density
        val colors = currentColors()
        val menuLayout = android.widget.LinearLayout(appContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            applyOverlayCard(colors, horizontalDp = 8, verticalDp = 8)
        }

        for (action in actions) {
            val btn = TextView(appContext).apply {
                text = action.label
                textSize = colors.bodySp
                typeface = colors.sans
                setTextColor(colors.buttonText)
                setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = colors.buttonRadiusDp * density
                    setColor(colors.buttonBg)
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

        // Anchored under the chip bar rather than at Gravity.CENTER, which put
        // the suggestions squarely on top of the region the user had just drawn
        // a box around.
        val menuWidth = (MENU_WIDTH_DP * density).toInt()
        val overlay = cropOverlay
        val anchor = chipBarBounds
        val params = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (anchor != null && overlay != null) {
                val (_, menuHeight) = measure(menuLayout)
                val placed = placeNear(anchor, menuWidth, menuHeight, overlay.width, overlay.height)
                gravity = Gravity.TOP or Gravity.START
                x = placed.left
                y = placed.top
            } else {
                gravity = Gravity.CENTER
            }
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
        chipBarBounds = null
    }

    private fun recyclePendingCrop() {
        pendingCrop?.let {
            if (!it.isRecycled) it.recycle()
        }
        pendingCrop = null
    }

    /**
     * Save a cropped bitmap to the device gallery. Takes ownership of [bitmap] —
     * the caller must NOT use or recycle it after this call; the method will
     * recycle it in its `finally` block once writing completes.
     */
    private fun saveCropToGallery(bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            try {
                val filename = "Gotcha_Lens_${System.currentTimeMillis()}.png"
                val location = com.gotcha.data.GotchaStorage.saveScreenshot(appContext, filename, bitmap)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        appContext,
                        "Saved image to $location",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Failed to save image: ${e.message}")
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    /**
     * JPEG-encode the pending crop off the main thread and hand the result to
     * [onEncoded] back on it.
     *
     * The caller is expected to `cancel()` immediately after calling this, which
     * recycles [pendingCrop] — so this takes its own copy first rather than
     * racing that. Encoding used to happen inline in the click listener, which
     * meant compressing a screen-sized bitmap on the UI thread while the overlay
     * was still up.
     */
    private fun encodePendingCrop(onEncoded: (String) -> Unit) {
        val crop = pendingCrop
        if (crop == null || crop.isRecycled) return
        val copy = runCatching { crop.copy(crop.config, false) }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val base64 = try {
                bitmapToBase64(copy)
            } catch (_: Exception) {
                null
            } finally {
                if (!copy.isRecycled) copy.recycle()
            }
            if (base64 != null) {
                withContext(Dispatchers.Main) { onEncoded(base64) }
            } else {
                withContext(Dispatchers.Main) { onError("Couldn't prepare the image") }
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private companion object {
        const val MIN_SELECTION_DP = 24
        const val CHIP_ICON_DP = 18
        const val CHIP_ICON_GAP_DP = 3
        const val MENU_WIDTH_DP = 240

        /** Cap on a grouped chip's menu — past this it is a list, not a choice. */
        const val MAX_GROUP_MENU_ITEMS = 8
        const val GAP_DP = 12
        const val EDGE_MARGIN_DP = 12
    }
}
