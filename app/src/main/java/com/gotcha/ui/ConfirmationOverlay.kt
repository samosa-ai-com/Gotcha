package com.gotcha.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    fun show(summary: String, onAllow: () -> Unit, onDeny: () -> Unit) {
        mainHandler.post {
            removeView()
            val card = buildCard(summary, onAllow, onDeny)
            try {
                windowManager.addView(card, layoutParams())
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
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
            }
        }

        val title = TextView(appContext).apply {
            text = "Gotcha — confirm action"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        }

        val body = TextView(appContext).apply {
            text = summary
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
        }

        val buttonRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        val deny = Button(appContext).apply {
            text = "Deny"
            setOnClickListener { onDeny() }
        }
        val allow = Button(appContext).apply {
            text = "Allow"
            setOnClickListener { onAllow() }
        }
        buttonRow.addView(deny)
        buttonRow.addView(allow)

        container.addView(title)
        container.addView(body)
        container.addView(buttonRow)
        return container
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // FLAG_NOT_FOCUSABLE keeps us from stealing the keyboard, but the window still
        // receives touch events, so the Allow/Deny buttons remain tappable.
        return WindowManager.LayoutParams(
            dp(320),
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
