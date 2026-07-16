package com.gotcha.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.gotcha.service.CallState
import kotlin.math.abs

/**
 * Two floating draggable glass buttons drawn over other apps during a voice
 * call. The left button changes emoji and behavior based on call state.
 * The right end-call button requires a 3-second hold (shown via an expanding
 * ring drawn as a separate [WindowManager] overlay) to fire — taps alone
 * are ignored.
 */
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
            removeRingOverlay()
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

    @Suppress("SetTextI18n")
    private fun renderButtons() {
        val s = currentState
        when (s) {
            CallState.READY, CallState.WAITING_USER -> {
                actionBtn?.visibility = View.VISIBLE
                (actionBtn as? TextView)?.text = "\uD83C\uDFA4"
                actionBtn?.alpha = 1f
            }
            CallState.LISTENING -> {
                actionBtn?.visibility = View.VISIBLE
                (actionBtn as? TextView)?.text = "\u23F9"
                actionBtn?.alpha = 1f
            }
            CallState.THINKING, CallState.SPEAKING -> {
                actionBtn?.visibility = View.VISIBLE
                (actionBtn as? TextView)?.text = "\uD83D\uDED1"
                actionBtn?.alpha = 1f
            }
            else -> {
                actionBtn?.visibility = View.GONE
            }
        }
        endWrapper?.alpha = if (s != CallState.IDLE && s != CallState.ENDING) 0.9f else 0.25f
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
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isEnd) {
                colors = intArrayOf(
                    Color.parseColor("#66FF6B6B"),
                    Color.parseColor("#33CC3333")
                )
                setStroke(dp(1.5f), Color.parseColor("#AAFF6B6B"))
            } else {
                colors = intArrayOf(
                    Color.parseColor("#44FFFFFF"),
                    Color.parseColor("#22C0C0C0")
                )
                setStroke(dp(1.5f), Color.parseColor("#66FFFFFF"))
            }
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TL_BR
        }

        return TextView(appContext).apply {
            text = emoji
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 2f, Color.parseColor("#80000000"))
            this.background = bg
            setElevation(dp(3f).toFloat())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
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

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
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

            val btnPx = dp(BTN_SIZE_DP.toFloat())
            val minRadius = btnPx * 0.50f
            val maxRadius = btnPx * 1.60f
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
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

    private companion object {
        const val BTN_SIZE_DP = 44
        const val END_LONG_PRESS_MS = 2000L
    }
}
