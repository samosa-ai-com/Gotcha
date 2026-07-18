package com.gotcha.service

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
 * Drives "Lens" mode: the user drags a bounding box over the screen, and the
 * selected region is cropped from a fresh accessibility screenshot. The crop can
 * then be sent to the LLM (explain / translate), copied to the clipboard, or
 * saved to the public Downloads folder.
 *
 * All window mutations are posted to the main thread. The controller owns exactly
 * one crop overlay and one action menu at a time.
 */
class ScreenLensController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Show the companion panel with a prompt and stream the LLM reply into it. */
    private val onAskAboutCrop: (prompt: String, base64Jpeg: String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cropOverlay: View? = null
    private var actionMenu: View? = null

    /** Begin a Lens capture: add the full-screen crop overlay. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError("Lens mode requires Android 11+")
            return
        }
        mainHandler.post {
            removeActionMenu()
            removeCropOverlay()
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
        }
    }

    private fun onRegionSelected(rectInView: Rect) {
        val overlay = cropOverlay
        val viewW = overlay?.width ?: 0
        val viewH = overlay?.height ?: 0
        // Hide the scrim overlay before capturing so it isn't baked into the shot.
        removeCropOverlay()
        scope.launch {
            val service = GotchaAccessibilityService.instance
            if (service == null) {
                withContext(Dispatchers.Main) { onError("Accessibility service not available") }
                return@launch
            }
            // Small delay so the removed overlay is gone from the real display.
            kotlinx.coroutines.delay(120)
            var full = service.takeScreenshotBitmap()
            if (full == null) {
                kotlinx.coroutines.delay(500)
                full = service.takeScreenshotBitmap()
            }
            if (full == null) {
                withContext(Dispatchers.Main) { onError("Screenshot failed — try again") }
                return@launch
            }
            val cropped = cropToSelection(full, rectInView, viewW, viewH)
            full.recycle()
            if (cropped == null) {
                withContext(Dispatchers.Main) { onError("Selection was empty") }
                return@launch
            }
            withContext(Dispatchers.Main) { showActionMenu(cropped) }
        }
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

    private fun showActionMenu(cropped: Bitmap) {
        removeActionMenu()
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F21E1E1E"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
        }
        container.addView(menuTitle("Lens selection"))
        container.addView(
            menuButton("🔍  Ask / Explain") {
                removeActionMenu()
                val base64 = encodeJpeg(cropped)
                cropped.recycle()
                if (base64 != null) {
                    onAskAboutCrop(
                        "Explain what is shown in this cropped screen region. If it " +
                            "contains text in another language, translate it to English.",
                        base64
                    )
                } else {
                    onError("Couldn't encode selection")
                }
            }
        )
        container.addView(
            menuButton("📋  Copy Image") {
                removeActionMenu()
                copyImageToClipboard(cropped)
                cropped.recycle()
            }
        )
        container.addView(
            menuButton("💾  Save to Downloads") {
                removeActionMenu()
                saveToDownloads(cropped)
                cropped.recycle()
            }
        )
        container.addView(
            menuButton("✕  Cancel") {
                removeActionMenu()
                cropped.recycle()
            }
        )

        val params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            windowManager.addView(container, params)
            actionMenu = container
        } catch (_: Exception) {
            actionMenu = null
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

    private fun toast(msg: String) {
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun menuTitle(text: String): TextView = TextView(appContext).apply {
        this.text = text
        setTextColor(Color.CYAN)
        textSize = 13f
        setPadding(dp(12), dp(6), dp(12), dp(10))
    }

    private fun menuButton(label: String, onClick: () -> Unit): TextView = TextView(appContext).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#33FFFFFF"))
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
}
