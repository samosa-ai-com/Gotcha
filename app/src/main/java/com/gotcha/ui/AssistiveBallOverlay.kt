package com.gotcha.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating "assistive ball" drawn over other apps via SYSTEM_ALERT_WINDOW.
 *
 * Structurally modelled on [ConfirmationOverlay]: a [WindowManager] +
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] window, gated on
 * [Settings.canDrawOverlays], with all window mutations posted to the main thread.
 *
 * It manages three independent windows:
 *  - the draggable ball itself (always visible while the service runs),
 *  - an expanded menu with the three options, and
 *  - a status/answer card that reports listening/thinking/answer/error state.
 *
 * The ball is touchable (no FLAG_NOT_TOUCHABLE) so it can be dragged and tapped; the
 * two "talk" options are press-and-hold (down = start recording, up = stop & ask).
 */
class AssistiveBallOverlay(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Callbacks — set by the host service before [show].
    var onOpenChat: () -> Unit = {}
    var onDismiss: () -> Unit = {}
    var onStartTalk: (withScreen: Boolean) -> Unit = {}
    var onStopTalk: () -> Unit = {}

    private var ballView: View? = null
    private var menuView: View? = null
    private var cardView: View? = null

    private val ballParams: WindowManager.LayoutParams by lazy { ballLayoutParams() }

    /** Whether we currently hold the "Display over other apps" permission. */
    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    fun show() {
        mainHandler.post {
            if (ballView != null) return@post
            val ball = buildBall()
            try {
                windowManager.addView(ball, ballParams)
                ballView = ball
            } catch (_: Exception) {
                ballView = null
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            removeMenu()
            removeCard()
            ballView?.let { safeRemove(it) }
            ballView = null
        }
    }

    // ---- Status / answer card ----

    fun showListening() = showCard("Listening… release to send", showClose = false)
    fun showThinking() = showCard("Thinking…", showClose = false)
    fun showAnswer(text: String) = showCard(text, showClose = true)
    fun showError(text: String) = showCard(text, showClose = true)

    // ---- Ball ----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBall(): View {
        val size = dp(56)
        return TextView(appContext).apply {
            text = "AI"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            width = size
            height = size
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6750A4"))
                setStroke(dp(2), Color.parseColor("#EADDFF"))
            }
            setOnTouchListener(ballTouchListener())
        }
    }

    private fun ballTouchListener(): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchDownRawX = 0f
        var touchDownRawY = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ballParams.x
                    startY = ballParams.y
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownRawX).toInt()
                    val dy = (event.rawY - touchDownRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        removeMenu()
                    }
                    if (dragging) {
                        ballParams.x = startX + dx
                        ballParams.y = startY + dy
                        try {
                            windowManager.updateViewLayout(ballView, ballParams)
                        } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        clampBallIntoBounds()
                    } else {
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampBallIntoBounds() {
        val metrics = appContext.resources.displayMetrics
        val maxX = metrics.widthPixels - dp(56)
        val maxY = metrics.heightPixels - dp(56)
        ballParams.x = ballParams.x.coerceIn(0, maxX.coerceAtLeast(0))
        ballParams.y = ballParams.y.coerceIn(0, maxY.coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(ballView, ballParams)
        } catch (_: Exception) { }
    }

    // ---- Expanded menu ----

    private fun toggleMenu() {
        if (menuView != null) removeMenu() else showMenu()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        removeMenu()
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
            }
        }

        container.addView(menuTitle("Gotcha"))
        container.addView(talkButton("🖥  Screen Share — hold & talk", withScreen = true))
        container.addView(talkButton("🎤  Talk — hold & talk", withScreen = false))
        container.addView(tapButton("💬  Open Chat") {
            removeMenu()
            onOpenChat()
        })
        container.addView(tapButton("✕  Hide ball") {
            onDismiss()
        })

        val params = menuLayoutParams()
        try {
            windowManager.addView(container, params)
            menuView = container
        } catch (_: Exception) {
            menuView = null
        }
    }

    private fun menuTitle(text: String) = TextView(appContext).apply {
        this.text = text
        setTextColor(Color.parseColor("#EADDFF"))
        textSize = 13f
        setPadding(dp(4), 0, dp(4), dp(8))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun talkButton(label: String, withScreen: Boolean): Button {
        return Button(appContext).apply {
            text = label
            isAllCaps = false
            // Press-and-hold. The menu (and thus this button) must stay attached until
            // ACTION_UP, otherwise the button would never receive the release event.
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        onStartTalk(withScreen)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        onStopTalk()
                        removeMenu()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun tapButton(label: String, onClick: () -> Unit): Button {
        return Button(appContext).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun removeMenu() {
        menuView?.let { safeRemove(it) }
        menuView = null
    }

    // ---- Card ----

    private fun showCard(message: String, showClose: Boolean) {
        mainHandler.post {
            removeCard()
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#1E1E1E"))
                    setStroke(dp(1), Color.parseColor("#3A3A3A"))
                }
            }
            val scroll = ScrollView(appContext).apply {
                addView(TextView(appContext).apply {
                    text = message
                    setTextColor(Color.parseColor("#EEEEEE"))
                    textSize = 14f
                })
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(240)
                )
            }
            container.addView(scroll)
            if (showClose) {
                container.addView(Button(appContext).apply {
                    text = "Close"
                    isAllCaps = false
                    setOnClickListener { hideCard() }
                })
            }
            try {
                windowManager.addView(container, cardLayoutParams())
                cardView = container
            } catch (_: Exception) {
                cardView = null
            }
        }
    }

    fun hideCard() {
        mainHandler.post { removeCard() }
    }

    private fun removeCard() {
        cardView?.let { safeRemove(it) }
        cardView = null
    }

    // ---- Helpers ----

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already detached; ignore.
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun ballLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

    private fun menuLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(240),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Anchor near the ball, clamped so the menu stays on screen.
            val metrics = appContext.resources.displayMetrics
            x = (ballParams.x).coerceIn(0, (metrics.widthPixels - dp(240)).coerceAtLeast(0))
            y = (ballParams.y + dp(64)).coerceIn(0, (metrics.heightPixels - dp(260)).coerceAtLeast(0))
        }

    private fun cardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
