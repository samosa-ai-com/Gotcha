package com.gotcha.tools

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Tier 3 — draws a floating text overlay on top of other apps using SYSTEM_ALERT_WINDOW.
 *
 * View/WindowManager work must happen on the main thread, so every mutation is posted to
 * the main looper. A single overlay view is tracked at a time; showing a new one replaces it.
 */
class OverlayTool(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var overlayView: TextView? = null
    private var autoHide: Runnable? = null

    /** Show [text] as a floating banner for [durationMs] (0 = until hide_overlay is called). */
    fun showOverlay(text: String, durationMs: Int): ToolResult {
        if (!canDrawOverlays()) return notEnabled()
        if (text.isBlank()) return ToolResult.error("Provide some text to display in the overlay.")
        val duration = durationMs.coerceIn(0, 300_000)
        mainHandler.post {
            removeView()
            val view = TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(220, 0, 0, 0))
                setPadding(32, 24, 32, 24)
                textSize = 16f
            }
            windowManager.addView(view, layoutParams())
            overlayView = view
            if (duration > 0) {
                autoHide = Runnable { removeView() }.also { mainHandler.postDelayed(it, duration.toLong()) }
            }
        }
        return ToolResult.ok(
            if (duration > 0) "Showing an overlay for ${duration / 1000}s: \"$text\"."
            else "Showing a persistent overlay: \"$text\". Call hide_overlay to remove it."
        )
    }

    fun hideOverlay(): ToolResult {
        if (overlayView == null) return ToolResult.ok("No overlay is currently showing.")
        mainHandler.post { removeView() }
        return ToolResult.ok("Overlay hidden.")
    }

    private fun removeView() {
        autoHide?.let { mainHandler.removeCallbacks(it) }
        autoHide = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // View may already be detached; ignore.
            }
        }
        overlayView = null
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun notEnabled() = ToolResult.permissionNeeded(
        ToolResult.OVERLAY_ACCESS,
        "Drawing over other apps needs the \"Display over other apps\" permission. I have opened " +
            "that settings page — please enable it for Gotcha and ask again."
    )
}
