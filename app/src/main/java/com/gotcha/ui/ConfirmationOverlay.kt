package com.gotcha.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gotcha.data.SettingsRepository
import com.gotcha.ui.theme.OverlaySkin
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin

/**
 * A confirmation prompt drawn as a floating window on top of whatever app is in the
 * foreground, using SYSTEM_ALERT_WINDOW.
 *
 * Unlike the in-app Compose dialog, this stays visible after Gotcha hands control
 * to another app (e.g. after `open_app` launches Settings), so a sensitive accessibility
 * action can still be approved instead of the tool loop blocking on a dialog the user
 * can no longer see. All window work is posted to the main thread.
 */
class ConfirmationOverlay(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var view: View? = null

    /** Whether we currently hold the "Display over other apps" permission. */
    fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(summary: String, onAllow: () -> Unit, onDeny: () -> Unit) {
        mainHandler.post {
            removeView()
            val card = buildCard(summary, onAllow, onDeny)
            try {
                windowManager.addView(card, layoutParams(card))
                view = card
            } catch (_: Exception) {
                view = null
            }
        }
    }

    fun dismiss() {
        mainHandler.post { removeView() }
    }

    private fun removeView() {
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Already detached; ignore.
            }
        }
        view = null
    }

    private fun buildCard(summary: String, onAllow: () -> Unit, onDeny: () -> Unit): View {
        val colors = overlaySkin(
            appContext,
            runCatching { SettingsRepository(appContext).load().skinId }
                .getOrDefault(Skins.OVERLAY_FALLBACK_ID)
        )
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            applyOverlayCard(colors, horizontalDp = 20, verticalDp = 20)
        }

        val title = TextView(appContext).apply {
            text = "Gotcha — confirm action"
            setTextColor(colors.onSurface)
            textSize = colors.titleSp
            typeface = colors.sans
            setPadding(0, 0, 0, dp(8))
        }

        val body = TextView(appContext).apply {
            text = summary
            setTextColor(colors.onSurfaceVariant)
            textSize = colors.bodySp
            typeface = colors.sans
        }

        val buttonRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        // Platform [Button]s until now, which meant the one card that asks the
        // user to approve something was the one card drawn in someone else's
        // design. Allow is the accented one: it is the answer that does work.
        buttonRow.addView(choice("Deny", colors, filled = false, onClick = onDeny))
        buttonRow.addView(choice("Allow", colors, filled = true, onClick = onAllow))

        container.addView(title)
        container.addView(body)
        container.addView(buttonRow)
        return container
    }

    private fun choice(
        label: String,
        colors: OverlaySkin,
        filled: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(appContext).apply {
        text = label
        typeface = colors.sans
        textSize = colors.bodySp
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setTextColor(if (filled) colors.onAccent else colors.onSurfaceVariant)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(colors.buttonRadiusDp.toInt()).toFloat()
            setColor(if (filled) colors.accent else colors.surface)
            if (!filled) setStroke(dp(1), colors.outline)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(8), 0, 0, 0) }
        setOnClickListener { onClick() }
    }

    /**
     * [card]'s width is the card plus the room its shadow needs on either side.
     * Sizing the window to the card alone would clip the shadow to a hard
     * square edge — the exact artefact the shadow is there to avoid.
     */
    private fun layoutParams(card: View): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val shadowPad = (card.background as? OverlayCardDrawable)?.shadowPadPx ?: 0
        // FLAG_NOT_FOCUSABLE keeps us from stealing the keyboard, but the window still
        // receives touch events, so the Allow/Deny buttons remain tappable.
        return WindowManager.LayoutParams(
            dp(320) + shadowPad * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
