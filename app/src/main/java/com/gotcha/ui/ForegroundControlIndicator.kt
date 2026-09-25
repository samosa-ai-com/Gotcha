package com.gotcha.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.gotcha.data.SettingsRepository
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin

/**
 * A small status card at the top of the screen, over whatever app Gotcha is
 * working in (issue #98): "Gotcha is controlling WhatsApp…" while it is, then
 * "Gotcha is done…" for a few seconds once the user has their app back.
 *
 * Deliberately non-touchable and non-focusable. The agent taps the app beneath
 * it, and a gesture that landed on this window instead would be swallowed; it
 * also must never become the active window the agent reads. Hidden around
 * screen captures ([setCaptureHidden]) so it never appears in a screenshot.
 * No-op without the "Display over other apps" permission.
 */
class ForegroundControlIndicator(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: View? = null
    private val autoDismiss = Runnable { removeView() }

    fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    /** Shows [text] until [dismiss] or the next [showDone]. */
    fun showControlling(text: String) = show(text, dismissAfterMs = null)

    /** Shows [text] briefly, then removes itself. */
    fun showDone(text: String) = show(text, dismissAfterMs = DONE_VISIBLE_MS)

    fun dismiss() {
        mainHandler.post { removeView() }
    }

    /** Hides the card for the length of a screen capture so it never appears in the frame. */
    fun setCaptureHidden(hidden: Boolean) {
        mainHandler.post { view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    }

    private fun show(text: String, dismissAfterMs: Long?) {
        mainHandler.post {
            removeView()
            if (!canShow()) return@post
            val card = buildCard(text)
            try {
                windowManager.addView(card, windowParams())
                view = card
                dismissAfterMs?.let { mainHandler.postDelayed(autoDismiss, it) }
            } catch (_: Exception) {
                view = null
            }
        }
    }

    private fun removeView() {
        mainHandler.removeCallbacks(autoDismiss)
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Already detached; ignore.
            }
        }
        view = null
    }

    private fun buildCard(text: String): View {
        val colors = overlaySkin(
            appContext,
            runCatching { SettingsRepository(appContext).load().skinId }
                .getOrDefault(Skins.OVERLAY_FALLBACK_ID)
        )
        return TextView(appContext).apply {
            this.text = text
            setTextColor(colors.onSurface)
            textSize = colors.bodySp
            typeface = colors.sans
            applyOverlayCard(colors, horizontalDp = 16, verticalDp = 10)
        }
    }

    private fun windowParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_OFFSET_DP * appContext.resources.displayMetrics.density).toInt()
        }

    private companion object {
        const val DONE_VISIBLE_MS = 4_000L
        const val TOP_OFFSET_DP = 48
    }
}
