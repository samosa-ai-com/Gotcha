package com.gotcha.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.gotcha.R
import com.gotcha.data.SettingsRepository
import com.gotcha.data.ThemeMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating "assistive ball" drawn over other apps via SYSTEM_ALERT_WINDOW,
 * reworked into a Messenger-Bubbles-style call head:
 *
 *  - Tap: Open App / Screenshot menu when idle; toggles the call chat window
 *    during a call.
 *  - Long-press (3s idle / 5s during a call): start or end a voice call.
 *  - Drag: moves the ball; an ✕ target appears at the bottom of the screen
 *    and dropping the ball on it hides the ball (Messenger-style dismiss).
 *
 * Structurally modelled on [ConfirmationOverlay]: a [WindowManager] +
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] window, gated on
 * [Settings.canDrawOverlays], with all window mutations posted to the main thread.
 */
@Suppress("TooManyFunctions")
class AssistiveBallOverlay(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Callbacks — set by the host service before [show].
    var onDismiss: () -> Unit = {}
    var onOpenApp: () -> Unit = {}
    var onTakeScreenshot: () -> Unit = {}
    var onStartCall: () -> Unit = {}
    var onEndCall: () -> Unit = {}
    var onToggleChatWindow: () -> Unit = {}

    /** Queried per gesture: is a voice call active (any non-idle state)? */
    var isCallActive: () -> Boolean = { false }

    private var ballView: View? = null
    private var ringView: View? = null
    private var ringDrawable: RingDrawable? = null
    private var ringAnimator: ValueAnimator? = null
    private var menuView: View? = null
    private var cardView: View? = null
    private var dismissTargetView: View? = null

    private val ballParams: WindowManager.LayoutParams by lazy { ballLayoutParams() }

    private val settingsRepository by lazy { SettingsRepository(appContext) }
    private val figtree: Typeface? by lazy {
        runCatching { ResourcesCompat.getFont(appContext, R.font.figtree) }.getOrNull()
    }

    /** Colors mirroring the app's Material scheme (ui/theme/Color.kt) for raw views. */
    private class OverlayPalette(
        val surface: Int,
        val onSurface: Int,
        val outline: Int,
        val buttonBg: Int,
        val buttonText: Int
    )

    /** Follows the in-app theme setting; SYSTEM tracks the device dark mode. */
    private fun isDarkTheme(): Boolean {
        val mode = runCatching { settingsRepository.load().themeMode }.getOrDefault(ThemeMode.SYSTEM)
        return when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM ->
                appContext.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun palette(): OverlayPalette = if (isDarkTheme()) {
        OverlayPalette(
            surface = 0xFF1E293B.toInt(), // DarkSurface
            onSurface = 0xFFE2E8F0.toInt(),
            outline = 0xFF334155.toInt(), // DarkSurfaceVariant
            buttonBg = 0xFF334155.toInt(),
            buttonText = 0xFFE2E8F0.toInt()
        )
    } else {
        OverlayPalette(
            surface = 0xFFFBFDF9.toInt(), // LightSurface
            onSurface = 0xFF191C1A.toInt(),
            outline = 0xFFDBE5E0.toInt(), // LightSurfaceVariant
            buttonBg = 0xFFE8F6F2.toInt(), // LightPrimaryContainer
            buttonText = 0xFF002117.toInt() // LightOnPrimaryContainer
        )
    }

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
            removeLongPressRing()
            removeMenu()
            removeCard()
            removeDismissTarget()
            ballView?.let { safeRemove(it) }
            ballView = null
        }
    }

    /** Tint the ball's rim green while a call is active so the 5s end gesture is discoverable. */
    fun setCallActive(active: Boolean) {
        mainHandler.post {
            (ballView as? ImageView)?.foreground = ballRim(active)
        }
    }

    // ---- Capture chrome ----

    /**
     * Hide all of our own overlay windows (menu, status card, and the ball) so they are
     * not baked into a screen capture taken for the call's screen context. Call
     * [showChromeAfterCapture] once the screenshot has been taken.
     */
    fun hideChromeForCapture() {
        mainHandler.post {
            removeLongPressRing()
            removeMenu()
            removeCard()
            ballView?.visibility = View.GONE
        }
    }

    /** Restore the ball after a capture. */
    fun showChromeAfterCapture() {
        mainHandler.post {
            ballView?.visibility = View.VISIBLE
        }
    }

    // ---- Status card (errors only) ----

    fun showError(text: String) = showCard(text, showClose = true)

    // ---- Ball ----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBall(): View {
        return ImageView(appContext).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Clip the square launcher icon into a circular "ball".
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            // Subtle rim so the logo reads on any background (green during calls).
            foreground = ballRim(isCallActive())
            setOnTouchListener(ballTouchListener())
        }
    }

    private fun ballRim(callActive: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (callActive) {
            setStroke(dp(2), Color.parseColor("#CC34C759"))
        } else {
            setStroke(dp(1), Color.parseColor("#66FFFFFF"))
        }
    }

    // ---- Long-press ring ----

    /** Show a growing ring around the ball during the 3s hold to start a call. */
    private fun showLongPressRing() {
        removeLongPressRing()
        val density = appContext.resources.displayMetrics.density
        // View is 3.5x the ball so the ring can grow to ~1.6x the ball radius
        // without hitting the view's bounds. The screen edge may still clip
        // when the ball is near the edge — that's intended.
        val viewSize = (BALL_SIZE_DP * 3.5f * density).toInt()
        val ballPx = dp(BALL_SIZE_DP)
        val drawable = RingDrawable()
        val view = View(appContext).apply { background = drawable }
        val params = WindowManager.LayoutParams(
            viewSize,
            viewSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballParams.x + ballPx / 2 - viewSize / 2
            y = ballParams.y + ballPx / 2 - viewSize / 2
        }
        try {
            windowManager.addView(view, params)
            ringView = view
            ringDrawable = drawable

            val minRadius = ballPx * 0.50f
            val maxRadius = ballPx * 1.60f
            val strokePx = 2.5f * density
            val fillBase = Color.WHITE
            val strokeBase = Color.WHITE

            ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LONG_PRESS_START_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    drawable.ringRadius = minRadius + (maxRadius - minRadius) * p
                    // Stroke fades from full to ~40% opacity as it expands
                    drawable.strokeColor = ColorUtils.setAlphaComponent(
                        strokeBase,
                        ((1f - p * 0.6f) * 255).toInt()
                    )
                    drawable.strokeWidth = strokePx
                    // Fill tints the area between ball and ring, fades out
                    drawable.fillColor = ColorUtils.setAlphaComponent(
                        fillBase,
                        ((0.18f * (1f - p)) * 255).toInt()
                    )
                }
                start()
            }
        } catch (_: Exception) {
            ringView = null
            ringDrawable = null
        }
    }

    private fun hideLongPressRing() {
        removeLongPressRing()
    }

    private fun removeLongPressRing() {
        ringAnimator?.cancel()
        ringAnimator = null
        ringView?.let { safeRemove(it) }
        ringView = null
        ringDrawable = null
    }

    @Suppress("CyclomaticComplexMethod")
    private fun ballTouchListener(): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchDownRawX = 0f
        var touchDownRawY = 0f
        var dragging = false
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            ballView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (isCallActive()) onEndCall() else onStartCall()
        }
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ballParams.x
                    startY = ballParams.y
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    dragging = false
                    longPressFired = false
                    mainHandler.postDelayed(
                        longPressRunnable,
                        if (isCallActive()) LONG_PRESS_END_MS else LONG_PRESS_START_MS
                    )
                    if (!isCallActive()) showLongPressRing()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownRawX).toInt()
                    val dy = (event.rawY - touchDownRawY).toInt()
                    if (!dragging && !longPressFired && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        hideLongPressRing()
                        removeMenu()
                        showDismissTarget()
                    }
                    if (dragging) {
                        ballParams.x = startX + dx
                        ballParams.y = startY + dy
                        try {
                            windowManager.updateViewLayout(ballView, ballParams)
                        } catch (_: Exception) { }
                        updateDismissTargetHighlight(isOverDismissTarget())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    hideLongPressRing()
                    val droppedOnTarget = dragging && isOverDismissTarget()
                    removeDismissTarget()
                    when {
                        longPressFired -> { /* handled by the runnable */ }
                        droppedOnTarget -> onDismiss()
                        dragging -> clampBallIntoBounds()
                        isCallActive() -> onToggleChatWindow()
                        else -> toggleMenu()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    hideLongPressRing()
                    removeDismissTarget()
                    if (dragging) clampBallIntoBounds()
                    true
                }
                else -> false
            }
        }
    }

    private fun clampBallIntoBounds() {
        val metrics = appContext.resources.displayMetrics
        val maxX = metrics.widthPixels - dp(BALL_SIZE_DP)
        val maxY = metrics.heightPixels - dp(BALL_SIZE_DP)
        ballParams.x = ballParams.x.coerceIn(0, maxX.coerceAtLeast(0))
        ballParams.y = ballParams.y.coerceIn(0, maxY.coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(ballView, ballParams)
        } catch (_: Exception) { }
    }

    // ---- Dismiss target (drag the ball onto the ✕ to hide it) ----

    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        val target = TextView(appContext).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC222222"))
                setStroke(dp(2), Color.parseColor("#66FFFFFF"))
            }
        }
        val params = WindowManager.LayoutParams(
            dp(DISMISS_TARGET_SIZE_DP),
            dp(DISMISS_TARGET_SIZE_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(DISMISS_TARGET_MARGIN_DP)
        }
        try {
            windowManager.addView(target, params)
            dismissTargetView = target
        } catch (_: Exception) {
            dismissTargetView = null
        }
    }

    private fun removeDismissTarget() {
        dismissTargetView?.let { safeRemove(it) }
        dismissTargetView = null
    }

    private fun updateDismissTargetHighlight(hovering: Boolean) {
        val scale = if (hovering) 1.25f else 1f
        dismissTargetView?.scaleX = scale
        dismissTargetView?.scaleY = scale
    }

    /** True when the ball's centre is within snapping distance of the ✕ target's centre. */
    private fun isOverDismissTarget(): Boolean {
        if (dismissTargetView == null) return false
        val metrics = appContext.resources.displayMetrics
        val ballCenterX = ballParams.x + dp(BALL_SIZE_DP) / 2f
        val ballCenterY = ballParams.y + dp(BALL_SIZE_DP) / 2f
        val targetCenterX = metrics.widthPixels / 2f
        val targetCenterY = metrics.heightPixels -
            dp(DISMISS_TARGET_MARGIN_DP) - dp(DISMISS_TARGET_SIZE_DP) / 2f
        val distance = hypot(ballCenterX - targetCenterX, ballCenterY - targetCenterY)
        return distance <= dp(DISMISS_SNAP_RADIUS_DP)
    }

    // ---- Expanded menu (idle only; taps during a call toggle the chat window) ----

    private fun toggleMenu() {
        if (menuView != null) removeMenu() else showMenu()
    }

    private fun showMenu() {
        removeMenu()
        val colors = palette()
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(colors.surface)
                setStroke(dp(1), colors.outline)
            }
        }

        container.addView(
            tapButton("\uD83D\uDCF1  Open App", colors) {
                removeMenu()
                onOpenApp()
            }
        )
        container.addView(
            tapButton("\uD83D\uDCF7  Screenshot", colors) {
                removeMenu()
                onTakeScreenshot()
            }
        )

        val params = menuLayoutParams()
        try {
            windowManager.addView(container, params)
            menuView = container
        } catch (_: Exception) {
            menuView = null
        }
    }

    private fun tapButton(label: String, colors: OverlayPalette, onClick: () -> Unit): TextView {
        return TextView(appContext).apply {
            text = label
            typeface = figtree ?: Typeface.DEFAULT
            textSize = 14f
            setTextColor(colors.buttonText)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(colors.buttonBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
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
            val colors = palette()
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(colors.surface)
                    setStroke(dp(1), colors.outline)
                }
            }
            val scroll = ScrollView(appContext).apply {
                addView(
                    TextView(appContext).apply {
                        text = message
                        typeface = figtree ?: Typeface.DEFAULT
                        setTextColor(colors.onSurface)
                        textSize = 14f
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(240)
                )
            }
            container.addView(scroll)
            if (showClose) {
                container.addView(tapButton("Close", colors) { hideCard() })
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun ballLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(BALL_SIZE_DP),
            dp(BALL_SIZE_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

    private fun menuLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(MENU_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Anchor near the ball, clamped so the menu stays on screen.
            val metrics = appContext.resources.displayMetrics
            x = (ballParams.x).coerceIn(0, (metrics.widthPixels - dp(MENU_WIDTH_DP)).coerceAtLeast(0))
            y = (ballParams.y + dp(64)).coerceIn(0, (metrics.heightPixels - dp(140)).coerceAtLeast(0))
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

    private companion object {
        const val BALL_SIZE_DP = 56
        const val MENU_WIDTH_DP = 200
        const val LONG_PRESS_START_MS = 2000L
        const val LONG_PRESS_END_MS = 2000L
        const val DISMISS_TARGET_SIZE_DP = 64
        const val DISMISS_TARGET_MARGIN_DP = 32
        const val DISMISS_SNAP_RADIUS_DP = 56
    }
}
