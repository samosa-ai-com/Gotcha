package com.gotcha.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.gotcha.data.SettingsRepository
import com.gotcha.service.CallState
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin
import kotlin.math.abs

/**
 * Two floating draggable glass buttons drawn over other apps during a voice
 * call. The left button changes emoji and behavior based on call state.
 * The right end-call button requires a 3-second hold (shown via an expanding
 * ring drawn as a separate [WindowManager] overlay) to fire — taps alone
 * are ignored.
 */
@Suppress("TooManyFunctions", "LargeClass")
class CallChatWindow(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    var onStartMic: () -> Unit = {}
    var onStopMic: () -> Unit = {}
    var onInterrupt: () -> Unit = {}
    var onEndCall: () -> Unit = {}

    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var actionBtn: View? = null
    private var endBtn: View? = null
    private var endWrapper: View? = null

    // Ring as a separate overlay window (not inside the layout)
    private var ringOverlayView: View? = null
    private var ringOverlayAnimator: ValueAnimator? = null

    // Action button ring overlay (static colored ring during tool execution)
    private var actionRingOverlayView: View? = null
    private var actionRingDrawable: RingDrawable? = null
    private var actionRingAnimator: ValueAnimator? = null
    private var actionBtnBg: GradientDrawable? = null
    private var currentRingColor: Int = 0

    // Glass backgrounds + state-driven animations
    private var glassAction: GlassButtonDrawable? = null
    private var glassEnd: GlassButtonDrawable? = null
    private var breatheAnimator: ValueAnimator? = null
    private var swapAnimator: ValueAnimator? = null
    private var currentActionEmoji: String = ""
    private var currentActionTint: Int = Color.TRANSPARENT
    private var currentBreatheTag: String = ""
    private var entranceAnimating: Boolean = false

    private var currentState: CallState = CallState.IDLE

    // Drag state
    private var dragging = false
    private var startX = 0
    private var startY = 0
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f

    // End-button long-press
    private var longPressFired = false
    private val endLongPressRunnable = Runnable {
        longPressFired = true
        removeRingOverlay()
        onEndCall()
    }

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            val root = buildContainer()
            val params = layoutParams()
            try {
                windowManager.addView(root, params)
                rootView = root
                rootParams = params
                renderButtons()
            } catch (_: Exception) {
                rootView = null
                rootParams = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(endLongPressRunnable)
            stopBreathe()
            swapAnimator?.cancel()
            swapAnimator = null
            removeRingOverlay()
            removeActionRingOverlay()
            rootView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) { }
            }
            rootView = null
            rootParams = null
            actionBtn = null
            endBtn = null
            endWrapper = null
        }
    }

    fun isShowing(): Boolean = rootView != null

    fun setVisibleForCapture(visible: Boolean) {
        mainHandler.post {
            rootView?.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) removeRingOverlay()
        }
    }

    /** Update button emoji, visibility, and action from the current call state. */
    fun setState(state: CallState) {
        mainHandler.post {
            currentState = state
            renderButtons()
        }
    }

    // ---- Rendering ----

    /** (emoji, glass tint) for the action button in a given call state. */
    private fun actionAppearance(s: CallState): Pair<String, Int>? = when (s) {
        CallState.READY, CallState.WAITING_USER -> "\uD83C\uDFA4" to readyTint()
        CallState.LISTENING -> "\u23F9" to TINT_STOP
        CallState.THINKING, CallState.SPEAKING -> "\uD83D\uDED1" to TINT_INTERRUPT
        else -> null
    }

    @Suppress("SetTextI18n")
    private fun renderButtons() {
        val s = currentState
        val appearance = actionAppearance(s)
        val btn = actionBtn as? TextView
        if (appearance == null) {
            // Fade + shrink out, then hide.
            swapAnimator?.cancel()
            stopBreathe()
            btn?.let { v ->
                if (v.visibility == View.VISIBLE) {
                    v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                        .setDuration(160L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction { v.visibility = View.GONE }
                        .start()
                }
            }
        } else {
            val (emoji, tint) = appearance
            val firstShow = btn?.visibility != View.VISIBLE
            btn?.visibility = View.VISIBLE
            if (firstShow) {
                currentActionEmoji = emoji
                currentActionTint = tint
                btn?.text = emoji
                glassAction?.tintColor = tint
                animateEntrance(btn)
            } else if (emoji != currentActionEmoji) {
                animateSwap(btn, emoji, tint)
            }
            startBreathe(s)
        }
        // Smoothly fade the end button's active/dim state instead of snapping.
        val target = if (s != CallState.IDLE && s != CallState.ENDING) 1f else 0.28f
        endWrapper?.let { w ->
            w.animate().alpha(target).setDuration(220L)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    /** Pop-in entrance: overshoot scale + fade from a small transparent state. */
    private fun animateEntrance(btn: TextView?) {
        val v = btn ?: return
        entranceAnimating = true
        v.alpha = 0f
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(360L)
            .setInterpolator(OvershootInterpolator(2.4f))
            .withEndAction { entranceAnimating = false }
            .start()
    }

    /**
     * Animated emoji swap: shrink the current glyph away, switch text and glass
     * tint at the low point, then overshoot back \u2014 with the tint crossfading
     * across the whole motion so state changes read as one fluid gesture.
     */
    private fun animateSwap(btn: TextView?, emoji: String, tint: Int) {
        val v = btn ?: return
        swapAnimator?.cancel()
        val fromTint = currentActionTint
        currentActionEmoji = emoji
        currentActionTint = tint
        var switched = false
        swapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                // Scale dips to 0.55 at the midpoint, overshoots to ~1.08, settles.
                val scale = when {
                    p < 0.5f -> 1f - 0.45f * (p / 0.5f)
                    else -> {
                        val t = (p - 0.5f) / 0.5f
                        0.55f + 0.53f * t * t * (3f - 2f * t) - 0.08f * t
                    }
                }
                v.scaleX = scale
                v.scaleY = scale
                // Rotate a touch through the swap for life.
                v.rotation = (1f - kotlin.math.abs(0.5f - p) * 2f) * 12f
                glassAction?.tintColor = ColorUtils.blendARGB(fromTint, tint, p)
                if (!switched && p >= 0.5f) {
                    switched = true
                    v.text = emoji
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.rotation = 0f
                    glassAction?.tintColor = tint
                }
            })
            start()
        }
    }

    /**
     * Breathing glow that conveys agent state: a slow, calm pulse while waiting
     * for the user (READY / WAITING_USER), and a faster, stronger pulse while
     * the agent is actively listening / thinking / speaking.
     */
    private fun startBreathe(s: CallState) {
        val active = s == CallState.LISTENING || s == CallState.THINKING || s == CallState.SPEAKING
        val period = if (active) 1100L else 2600L
        val peak = if (active) 1f else 0.5f
        // Restart only if the cadence changed, so a steady state keeps its phase.
        val tag = "$period|$peak"
        if (breatheAnimator?.isRunning == true && currentBreatheTag == tag) return
        currentBreatheTag = tag
        breatheAnimator?.cancel()
        val glass = glassAction ?: return
        breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = period
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                val g = 0.12f + (peak - 0.12f) * p
                glass.glow = g
                // Gentle scale swell synced to the glow, skipped mid-swap/entrance.
                if (swapAnimator?.isRunning != true && !entranceAnimating) {
                    val sc = 1f + 0.05f * p * (if (active) 1f else 0.6f)
                    actionBtn?.scaleX = sc
                    actionBtn?.scaleY = sc
                }
            }
            start()
        }
    }

    private fun stopBreathe() {
        breatheAnimator?.cancel()
        breatheAnimator = null
        currentBreatheTag = ""
        glassAction?.glow = 0f
    }

    // ---- Container ----

    private fun buildContainer(): View {
        val density = appContext.resources.displayMetrics.density
        val btnSize = (BTN_SIZE_DP * density).toInt()

        actionBtn = glassButton("\uD83C\uDFA4", btnSize, isEnd = false)
        endBtn = glassButton("\uD83D\uDCDE", btnSize, isEnd = true)

        // Simple FrameLayout wrapper — just the end button size (44dp).
        // No ring inside the layout; the ring appears as a separate overlay.
        val wrapper = FrameLayout(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
        }
        endBtn!!.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wrapper.addView(endBtn!!)
        endWrapper = wrapper

        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setOnTouchListener(containerTouchListener())
        }
        actionBtn?.let { row.addView(it) }
        (actionBtn?.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, (6 * density).toInt(), 0)
        row.addView(wrapper)

        return row
    }

    private fun glassButton(emoji: String, size: Int, isEnd: Boolean): View {
        val density = appContext.resources.displayMetrics.density
        val glass = GlassButtonDrawable(if (isEnd) TINT_END else readyTint()).apply {
            fillAlpha = if (isEnd) 120 else 105
        }
        if (isEnd) glassEnd = glass else glassAction = glass

        // Tool-execution tint overlay (transparent until setActionRingColor drives
        // it). Kept as a separate oval GradientDrawable so existing setColor /
        // setAlpha behavior is unchanged; layered above the glass base. Inset so
        // it aligns with the (inset) translucent sphere, not the square bounds.
        val inset = (size * 0.14f).toInt()
        val toolTint = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        val background = android.graphics.drawable.LayerDrawable(
            arrayOf<android.graphics.drawable.Drawable>(glass, toolTint)
        ).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }

        return TextView(appContext).apply {
            text = emoji
            textSize = 19f
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
            translationY = -1.5f * density
            setTextColor(Color.WHITE)
            // Soft circular glow behind the glyph (not clipped → no square edge).
            setShadowLayer(6f * density, 0f, 1f * density, Color.parseColor("#66000000"))
            this.background = background
            layoutParams = LinearLayout.LayoutParams(size, size)
        }.also { if (!isEnd) actionBtnBg = toolTint }
    }

    // ---- Ring as a separate overlay ----

    private fun startEndRingAnimation() {
        removeRingOverlay()
        val density = appContext.resources.displayMetrics.density
        val ringSize = dp(BTN_SIZE_DP * 3.5f)
        val drawable = RingDrawable()
        val view = View(appContext).apply { background = drawable }

        // Compute the end button's center from the root overlay position.
        // Avoid getLocationOnScreen() which can be unreliable under
        // FLAG_LAYOUT_NO_LIMITS and with nested overlay windows.
        val paddingPx = (8 * density).toInt()
        val marginPx = (6 * density).toInt()
        val btnPx = (BTN_SIZE_DP * density).toInt()
        val cx = (rootParams?.x ?: 0) + paddingPx + btnPx + marginPx + btnPx / 2
        val cy = (rootParams?.y ?: 0) + btnPx / 2

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            ringSize,
            ringSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - ringSize / 2
            y = cy - ringSize / 2
        }
        try {
            windowManager.addView(view, params)
            ringOverlayView = view

            val btnSizeDp = dp(BTN_SIZE_DP.toFloat())
            val minRadius = btnSizeDp * 0.50f
            val maxRadius = btnSizeDp * 1.60f
            val strokePx = 2.5f * density
            val fillBase = Color.parseColor("#FF6B6B")
            val strokeBase = Color.parseColor("#FF6B6B")

            ringOverlayAnimator?.cancel()
            ringOverlayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = END_LONG_PRESS_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    drawable.ringRadius = minRadius + (maxRadius - minRadius) * p
                    drawable.strokeWidth = strokePx
                    drawable.strokeColor = ColorUtils.setAlphaComponent(
                        strokeBase,
                        ((1f - p * 0.6f) * 255).toInt()
                    )
                    drawable.fillColor = ColorUtils.setAlphaComponent(
                        fillBase,
                        ((0.22f * (1f - p)) * 255).toInt()
                    )
                }
                start()
            }
        } catch (_: Exception) {
            ringOverlayView = null
        }
    }

    /** Show or hide the action ring with aura glow. */
    fun setActionRingColor(color: Int?) {
        mainHandler.post {
            if (color == null) {
                removeActionRingOverlay()
                return@post
            }
            currentRingColor = color
            val density = appContext.resources.displayMetrics.density
            val paddingPx = (8 * density).toInt()
            val btnPx = (BTN_SIZE_DP * density).toInt()
            val cx = (rootParams?.x ?: 0) + paddingPx + btnPx / 2
            val cy = (rootParams?.y ?: 0) + btnPx / 2

            if (actionRingOverlayView != null) {
                actionRingDrawable?.strokeColor = color
                actionRingDrawable?.auraColor = color
                startActionRingWave(color)
                return@post
            }

            // Window must be big enough to hold the largest aura the wave /
            // breathe reaches (radius up to ~btnPx * 2.24), otherwise the round
            // glow gets clipped to the window's square edge. Sized generously
            // and centered; it's transparent and non-touchable so this is free.
            val ringSize = (BTN_SIZE_DP * 5f * density).toInt()
            val drawable = RingDrawable().apply {
                ringRadius = 0f
                strokeWidth = 2.5f * density
                strokeColor = ColorUtils.setAlphaComponent(color, 0)
                fillColor = android.graphics.Color.TRANSPARENT
                auraColor = color
                auraRadius = btnPx * 1.4f
                auraIntensity = 0f
            }
            actionRingDrawable = drawable

            val view = View(appContext).apply { background = drawable }
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                ringSize,
                ringSize,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cx - ringSize / 2
                y = cy - ringSize / 2
            }
            try {
                windowManager.addView(view, params)
                actionRingOverlayView = view
                startActionRingWave(color)
            } catch (_: Exception) {
                actionRingOverlayView = null
            }
        }
    }

    /**
     * Wave intro: aura explodes outward like a shockwave, ring appears at
     * peak and settles, button background lights up.
     */
    private fun startActionRingWave(color: Int) {
        actionRingAnimator?.cancel()
        val drawable = actionRingDrawable ?: return
        val density = appContext.resources.displayMetrics.density
        val btnPx = (BTN_SIZE_DP * density).toInt()
        val baseRadius = btnPx * 0.65f
        val baseStroke = 2.5f * density
        val bg = actionBtnBg

        // Reset button background to ring color (fully transparent → fade in)
        bg?.setColor(color)
        bg?.setAlpha(0)

        val wave = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float

                // Aura: expand from zero, overshoot, settle
                val auraScale: Float = when {
                    p < 0.35f -> {
                        val t = p / 0.35f
                        0f + 1.6f * t * t * (3f - 2f * t)
                    }
                    p < 0.60f -> {
                        val t = (p - 0.35f) / 0.25f
                        1.6f - (1.6f - 1.2f) * t * t
                    }
                    else -> {
                        val t = (p - 0.60f) / 0.4f
                        1.2f + (1.05f - 1.2f) * t
                    }
                }
                drawable.auraRadius = btnPx * 1.4f * auraScale

                // Aura intensity: fade in, peak at mid-wave, settle
                val auraIntensity: Float = when {
                    p < 0.20f -> p / 0.20f * 0.25f
                    p < 0.40f -> 0.25f + (0.55f - 0.25f) * ((p - 0.20f) / 0.20f)
                    p < 0.65f -> 0.55f - (0.55f - 0.30f) * ((p - 0.40f) / 0.25f)
                    else -> 0.30f * (1f - (p - 0.65f) / 0.35f * 0.5f)
                }
                drawable.auraIntensity = auraIntensity

                // Ring: appears after aura starts, ripples
                val ringScale: Float = when {
                    p < 0.15f -> 0f
                    p < 0.50f -> {
                        val t = (p - 0.15f) / 0.35f
                        0.2f + (1.35f - 0.2f) * t * t * (3f - 2f * t)
                    }
                    p < 0.75f -> {
                        val t = (p - 0.50f) / 0.25f
                        1.35f - (1.35f - 0.88f) * t * t
                    }
                    else -> {
                        val t = (p - 0.75f) / 0.25f
                        0.88f + (1f - 0.88f) * t * t * (3f - 2f * t)
                    }
                }
                drawable.ringRadius = if (ringScale > 0f) baseRadius * ringScale else 0f

                // Stroke: flash at peak ring expansion
                val strokeScale: Float = when {
                    p < 0.30f -> 0f
                    p < 0.42f -> ((p - 0.30f) / 0.12f) * 2.5f + 1f
                    p < 0.55f -> 3.5f - (3.5f - 1f) * ((p - 0.42f) / 0.13f)
                    else -> 1f
                }
                drawable.strokeWidth = if (strokeScale > 0f) baseStroke * strokeScale else 0f

                // Ring alpha: fade in after aura
                val ringAlpha: Float = when {
                    p < 0.20f -> 0f
                    p < 0.35f -> (p - 0.20f) / 0.15f
                    else -> 1f
                }
                drawable.strokeColor = ColorUtils.setAlphaComponent(
                    color, (ringAlpha * 255).toInt()
                )

                // Button background: fade in alongside aura
                val bgAlpha = (auraIntensity * 50).toInt().coerceIn(0, 50)
                bg?.setAlpha(bgAlpha)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startActionRingBreathe(color)
                }
            })
            start()
        }
        actionRingAnimator = wave
    }

    /**
     * Aura breathe: ring alpha and aura radius pulse in shifted phases,
     * button background follows the aura.
     */
    private fun startActionRingBreathe(color: Int) {
        val drawable = actionRingDrawable ?: return
        val baseRadius = drawable.ringRadius
        val bg = actionBtnBg

        drawable.auraRadius = baseRadius * 1.6f

        val breathe = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float

                // Ring alpha: 60% → 100% → 60%
                val ringP = 0.6f + 0.4f * p
                drawable.strokeColor = ColorUtils.setAlphaComponent(
                    color, (ringP * 255).toInt()
                )

                // Aura intensity: 15% → 35% → 15% (shifted peak for organic feel)
                val auraP = 0.12f + 0.25f * p
                drawable.auraIntensity = auraP

                // Aura radius: subtle pulse
                val auraR = 1.55f + 0.15f * p
                drawable.auraRadius = baseRadius * auraR

                // Button background: 10 → 30 alpha, follows aura
                val bgAlpha = (10 + 20 * p).toInt().coerceIn(0, 50)
                bg?.setAlpha(bgAlpha)
            }
            start()
        }
        actionRingAnimator = breathe
    }

    private fun removeActionRingOverlay() {
        actionRingAnimator?.cancel()
        actionRingAnimator = null
        actionBtnBg?.setAlpha(0)
        actionRingOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
            actionRingOverlayView = null
            actionRingDrawable = null
        }
    }

    private fun removeRingOverlay() {
        ringOverlayAnimator?.cancel()
        ringOverlayAnimator = null
        ringOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
            ringOverlayView = null
        }
    }

    // ---- Touch handling ----

    private fun isTouchOnEndWrapper(x: Float, y: Float): Boolean {
        val w = endWrapper ?: return false
        return x >= w.left && x <= w.right && y >= w.top && y <= w.bottom
    }

    private fun handleActionUp(x: Float, y: Float) {
        val root = rootView as? LinearLayout ?: return
        val count = root.childCount
        for (i in 0 until count) {
            val child = root.getChildAt(i)
            if (x >= child.left && x <= child.right &&
                y >= child.top && y <= child.bottom
            ) {
                if (child == actionBtn) performMicAction()
                break
            }
        }
    }

    /** Fire the action bound to the mic button for the current call state. */
    private fun performMicAction() {
        when (currentState) {
            CallState.READY, CallState.WAITING_USER -> onStartMic()
            CallState.LISTENING -> onStopMic()
            CallState.THINKING, CallState.SPEAKING -> onInterrupt()
            else -> { /* no-op when hidden */ }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun containerTouchListener() = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = rootParams?.x ?: 0
                startY = rootParams?.y ?: 0
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                dragging = false
                longPressFired = false

                if (isTouchOnEndWrapper(event.x, event.y) && endWrapper?.alpha != 0.25f) {
                    mainHandler.postDelayed(endLongPressRunnable, END_LONG_PRESS_MS)
                    startEndRingAnimation()
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchDownRawX).toInt()
                val dy = (event.rawY - touchDownRawY).toInt()
                if (!dragging && !longPressFired && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    mainHandler.removeCallbacks(endLongPressRunnable)
                    removeRingOverlay()
                }
                if (dragging) {
                    rootParams?.let { p ->
                        p.x = startX + dx
                        p.y = startY + dy
                        try {
                            windowManager.updateViewLayout(rootView, p)
                        } catch (_: Exception) { }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(endLongPressRunnable)
                removeRingOverlay()
                if (!dragging && !longPressFired) {
                    handleActionUp(event.x, event.y)
                }
                longPressFired = false
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(endLongPressRunnable)
                removeRingOverlay()
                true
            }
            else -> false
        }
    }

    // ---- Layout ----

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val metrics = appContext.resources.displayMetrics
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dp(BTN_SIZE_DP * 2f + 32f)
            y = metrics.heightPixels - dp(120f)
        }
    }

    private fun dp(value: Float): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    /** The resting mic tint, taken from whichever skin is on. */
    private fun readyTint(): Int = overlaySkin(
        appContext,
        runCatching { SettingsRepository(appContext).load().skinId }
            .getOrDefault(Skins.DEFAULT_ID)
    ).accent

    private companion object {
        const val BTN_SIZE_DP = 44
        const val END_LONG_PRESS_MS = 2000L

        // Glass tints per action state. Three of these are semantic and stay
        // fixed: amber, coral and red have to keep meaning the same thing in
        // every theme, the way the context meter's warning colour does. Only
        // the resting state follows the skin — see [readyTint].
        val TINT_STOP = Color.parseColor("#E8A13A") // amber — listening / stop
        val TINT_INTERRUPT = Color.parseColor("#E5544B") // coral — interrupt
        val TINT_END = Color.parseColor("#E23B3B") // red — end call
    }
}
