package com.gotcha.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
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
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.gotcha.R
import com.gotcha.data.SettingsRepository
import com.gotcha.data.ThemeMode
import com.gotcha.service.EntityType
import com.gotcha.service.ProactiveSessionItem
import com.gotcha.service.SmartActionDetector
import kotlin.math.abs
import kotlin.math.hypot

/** Accessibility label for the ball root view — used by UiAutomator to find the overlay. */
const val ASSISTIVE_BALL_CONTENT_DESCRIPTION = "Gotcha assistive ball"

@Suppress("TooManyFunctions", "LargeClass", "MaxLineLength", "ComplexCondition")
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
    var onStartLens: () -> Unit = {}
    var onStartCall: () -> Unit = {}
    var onEndCall: () -> Unit = {}
    var onToggleChatWindow: () -> Unit = {}
    var onSmartActionSelected: (prompt: String) -> Unit = {}
    var onRequestClipboardCheck: () -> Unit = {}
    var isPanelOpen: Boolean = false

    var isCallActive: () -> Boolean = { false }

    private var proactiveSessionItems: List<ProactiveSessionItem> = emptyList()
    private var pendingSmartAction: Pair<String, String>? = null
    private var pendingSmartActionAlt: Pair<String, String>? = null
    private var lastOfferedLabel: String? = null
    private val smartActionClearRunnable = Runnable { clearSmartAction() }

    private var ballView: View? = null
    private var ringView: View? = null
    private var ringDrawable: RingDrawable? = null
    private var ringAnimator: ValueAnimator? = null
    private var menuView: View? = null
    private var cardView: View? = null
    private var dismissTargetView: View? = null

    private var dockSide: Int = DOCK_SIDE_END
    private val ballParams: WindowManager.LayoutParams by lazy { ballLayoutParams() }
    private val settingsRepository by lazy { SettingsRepository(appContext) }
    private val figtree: Typeface? by lazy {
        runCatching { ResourcesCompat.getFont(appContext, R.font.figtree) }.getOrNull()
    }

    private class OverlayPalette(
        val surface: Int,
        val onSurface: Int,
        val outline: Int,
        val buttonBg: Int,
        val buttonText: Int
    )

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
            surface = 0xFF1E293B.toInt(),
            onSurface = 0xFFE2E8F0.toInt(),
            outline = 0xFF334155.toInt(),
            buttonBg = 0xFF334155.toInt(),
            buttonText = 0xFFE2E8F0.toInt()
        )
    } else {
        OverlayPalette(
            surface = 0xFFFBFDF9.toInt(),
            onSurface = 0xFF191C1A.toInt(),
            outline = 0xFFDBE5E0.toInt(),
            buttonBg = 0xFFE8F6F2.toInt(),
            buttonText = 0xFF002117.toInt()
        )
    }

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    fun hideChromeForCapture() {
        mainHandler.post {
            ballView?.visibility = View.INVISIBLE
        }
    }

    fun showChromeAfterCapture() {
        mainHandler.post {
            ballView?.visibility = View.VISIBLE
            ballView?.alpha = PEEK_ALPHA
        }
    }

    fun setCallActive(active: Boolean) {
        mainHandler.post {
            if (active) {
                hideChromeForCapture()
                removeMenu()
            } else {
                showChromeAfterCapture()
            }
        }
    }

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

    fun setProactiveSessionItems(items: List<ProactiveSessionItem>) {
        mainHandler.post {
            proactiveSessionItems = items
            if (items.isNotEmpty()) {
                val topEntity = items.first().entity
                topEntity.primaryAction?.let { action ->
                    setSmartActionAvailable(action.label, action.prompt)
                }
            } else {
                clearSmartAction()
            }
        }
    }

    fun setSmartActionPairAvailable(label: String, prompt: String, altLabel: String, altPrompt: String) {
        setSmartActionAvailable(label, prompt, altLabel, altPrompt)
    }

    fun setSmartActionAvailable(label: String, prompt: String) {
        setSmartActionAvailable(label, prompt, null, null)
    }

    fun setSmartActionAvailable(label: String, prompt: String, altLabel: String?, altPrompt: String?) {
        mainHandler.post {
            if (isCallActive()) return@post
            val currentLabel = pendingSmartAction?.first ?: ""
            if (currentLabel.contains("copied", ignoreCase = true) && !label.contains("copied", ignoreCase = true)) {
                return@post
            }
            if (label == lastOfferedLabel) return@post

            lastOfferedLabel = label
            pendingSmartAction = Pair(label, prompt)
            pendingSmartActionAlt = if (altLabel != null && altPrompt != null) Pair(altLabel, altPrompt) else null
            mainHandler.removeCallbacks(smartActionClearRunnable)
            mainHandler.postDelayed(smartActionClearRunnable, 45000L)
        }
    }

    fun showError(message: String) {
        showCard("⚠️ $message", showClose = true)
    }

    fun showCard(message: String, showClose: Boolean = true) {
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

    private fun clearSmartAction() {
        mainHandler.post {
            pendingSmartAction = null
            pendingSmartActionAlt = null
            lastOfferedLabel = null
            removeMenu()
        }
    }

    private fun buildBall(): View {
        val size = dp(BALL_SIZE_DP)
        return android.widget.ImageView(appContext).apply {
            contentDescription = ASSISTIVE_BALL_CONTENT_DESCRIPTION
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = PEEK_ALPHA
            setOnTouchListener(ballTouchListener())
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun toggleMenu() {
        if (menuView != null) removeMenu() else showMenu()
    }

    private fun showMenu() {
        removeMenu()
        // Trigger a fresh clipboard read if no smart action is already staged.
        if (pendingSmartAction == null) {
            onRequestClipboardCheck()
        }
        val colors = palette()

        val rootLayout = FrameLayout(appContext).apply {
            setOnClickListener {
                removeMenu()
            }
        }

        val menuCard = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(colors.surface)
                setStroke(dp(1), colors.outline)
            }
            setOnClickListener { }
        }

        buildProactiveMenuContent(menuCard, colors)

        val appNavRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        appNavRow.addView(
            tapButton("📱 Open App", colors) {
                removeMenu()
                onOpenApp()
            }
        )
        appNavRow.addView(
            tapButton("📸 Screenshot", colors) {
                removeMenu()
                onTakeScreenshot()
            }
        )
        appNavRow.addView(
            tapButton("🔍 Lens", colors) {
                removeMenu()
                onStartLens()
            }
        )
        menuCard.addView(appNavRow)

        val metrics = appContext.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val menuWidth = dp(MENU_WIDTH_DP)
        val maxCardHeight = (screenHeight - dp(48)).coerceAtLeast(dp(200))

        menuCard.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxCardHeight, View.MeasureSpec.AT_MOST)
        )
        val actualCardHeight = menuCard.measuredHeight.coerceAtMost(maxCardHeight)

        val ballY = ballParams.y
        val spaceBelow = screenHeight - (ballY + dp(64))
        val spaceAbove = ballY - dp(8)

        val maxTop = (screenHeight - actualCardHeight - dp(16)).coerceAtLeast(dp(16))
        val topMargin = if (spaceBelow >= actualCardHeight || spaceBelow >= spaceAbove) {
            (ballY + dp(64)).coerceIn(dp(16), maxTop)
        } else {
            (ballY - actualCardHeight - dp(8)).coerceIn(dp(16), maxTop)
        }
        val leftMargin = (ballParams.x).coerceIn(dp(8), (screenWidth - menuWidth - dp(8)).coerceAtLeast(dp(8)))

        val cardParams = FrameLayout.LayoutParams(menuWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            this.leftMargin = leftMargin
            this.topMargin = topMargin
        }
        rootLayout.addView(menuCard, cardParams)

        val params = fullScreenMenuLayoutParams()
        try {
            windowManager.addView(rootLayout, params)
            menuView = rootLayout
        } catch (_: Exception) {
            menuView = null
        }
    }

    private fun buildProactiveMenuContent(menuCard: LinearLayout, colors: OverlayPalette) {
        if (proactiveSessionItems.isNotEmpty()) {
            val scroll = ScrollView(appContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(240)
                )
            }
            val listContent = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (item in proactiveSessionItems.take(4)) {
                val entity = item.entity
                val headerText = TextView(appContext).apply {
                    text = "${getCategoryIcon(entity.type)}  ${SmartActionDetector.snippet(entity.normalizedValue, 24)}"
                    typeface = figtree ?: Typeface.DEFAULT
                    textSize = 12f
                    setTextColor(colors.onSurface)
                    setPadding(dp(6), dp(6), dp(6), dp(4))
                }
                listContent.addView(headerText)

                val chipRow = LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(2), dp(2), dp(2), dp(8))
                }
                for (action in entity.actions) {
                    val chipBtn = TextView(appContext).apply {
                        text = action.label
                        typeface = figtree ?: Typeface.DEFAULT
                        textSize = 12f
                        setTextColor(colors.buttonText)
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(10).toFloat()
                            setColor(colors.buttonBg)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                        setOnClickListener {
                            val currentPrompt = action.prompt
                            removeMenu()
                            onSmartActionSelected(currentPrompt)
                        }
                    }
                    chipRow.addView(chipBtn)
                }
                listContent.addView(chipRow)
            }
            scroll.addView(listContent)
            menuCard.addView(scroll)
        } else {
            pendingSmartAction?.let { (label, prompt) ->
                menuCard.addView(
                    tapButton(label, colors) {
                        removeMenu()
                        onSmartActionSelected(prompt)
                    }
                )
            }
            pendingSmartActionAlt?.let { (altLabel, altPrompt) ->
                menuCard.addView(
                    tapButton(altLabel, colors) {
                        removeMenu()
                        onSmartActionSelected(altPrompt)
                    }
                )
            }
        }
    }

    private fun getCategoryIcon(type: EntityType): String = when (type) {
        EntityType.OTP -> "🔑"
        EntityType.PHONE -> "📞"
        EntityType.ADDRESS -> "📍"
        EntityType.EMAIL -> "📧"
        EntityType.URL -> "🌐"
        EntityType.CALENDAR -> "📅"
        EntityType.CURRENCY -> "💵"
        EntityType.TRACKING_NUMBER -> "📦"
        EntityType.CHAT_REPLY -> "💬"
        EntityType.GENERIC_TEXT -> "📋"
    }

    private fun tapButton(label: String, colors: OverlayPalette, onClick: () -> Unit): TextView {
        return TextView(appContext).apply {
            text = label
            typeface = figtree ?: Typeface.DEFAULT
            textSize = 13f
            setTextColor(colors.buttonText)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(colors.buttonBg)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
            setOnClickListener { onClick() }
        }
    }

    private fun removeMenu() {
        menuView?.let { safeRemove(it) }
        menuView = null
    }

    private fun hideCard() {
        mainHandler.post { removeCard() }
    }

    private fun removeCard() {
        cardView?.let { safeRemove(it) }
        cardView = null
    }

    private fun showLongPressRing() {
        removeLongPressRing()
        val density = appContext.resources.displayMetrics.density
        val viewSize = (BALL_SIZE_DP * 3.2f * density).toInt()
        val ballPx = dp(BALL_SIZE_DP)
        val drawable = RingDrawable().apply {
            fillColor = Color.TRANSPARENT
            strokeColor = Color.WHITE
            strokeWidth = 2.5f * density
        }
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
            ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = LONG_PRESS_START_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    drawable.ringRadius = minRadius + (maxRadius - minRadius) * p
                    drawable.strokeColor = ColorUtils.setAlphaComponent(Color.WHITE, ((1f - p * 0.6f) * 255).toInt())
                }
                start()
            }
        } catch (_: Exception) {
            ringView = null
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
                    cancelAutoDock()
                    ballView?.alpha = 1.0f
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
                            ballView?.requestFocus()
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
                        longPressFired -> { }
                        droppedOnTarget -> onDismiss()
                        dragging -> {
                            dockSide = sideForX(ballParams.x)
                            clampBallIntoBounds()
                            scheduleAutoDock()
                        }
                        isCallActive() -> onToggleChatWindow()
                        else -> {
                            expandFromEdge()
                            toggleMenu()
                            scheduleAutoDock()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    hideLongPressRing()
                    removeDismissTarget()
                    if (dragging) {
                        dockSide = sideForX(ballParams.x)
                        clampBallIntoBounds()
                        scheduleAutoDock()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampBallIntoBounds() {
        val metrics = appContext.resources.displayMetrics
        val maxY = metrics.heightPixels - dp(BALL_SIZE_DP)
        val minX = -dp(BALL_SIZE_DP - PEEK_DP)
        val maxX = metrics.widthPixels - dp(PEEK_DP)
        ballParams.x = ballParams.x.coerceIn(minX, maxX)
        ballParams.y = ballParams.y.coerceIn(0, maxY.coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(ballView, ballParams)
        } catch (_: Exception) { }
    }

    private fun screenWidth(): Int = appContext.resources.displayMetrics.widthPixels

    private fun dockedX(side: Int): Int = when (side) {
        DOCK_SIDE_END -> screenWidth() - dp(PEEK_DP)
        else -> -dp(BALL_SIZE_DP - PEEK_DP)
    }

    private fun dockedExpandX(side: Int): Int = when (side) {
        DOCK_SIDE_END -> screenWidth() - dp(BALL_SIZE_DP) - dp(DOCK_MARGIN_DP)
        else -> dp(DOCK_MARGIN_DP)
    }

    private fun sideForX(leftX: Int): Int {
        val centre = leftX + dp(BALL_SIZE_DP) / 2
        return if (centre < screenWidth() / 2) DOCK_SIDE_START else DOCK_SIDE_END
    }

    private fun expandFromEdge() {
        cancelAutoDock()
        ballParams.x = dockedExpandX(dockSide)
        val view = ballView ?: return
        try {
            windowManager.updateViewLayout(view, ballParams)
        } catch (_: Exception) { }
        ValueAnimator.ofFloat(view.alpha, 1.0f).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                view.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun dockToEdge() {
        val view = ballView ?: return
        val targetX = dockedX(dockSide)
        val startX = ballParams.x
        val startAlpha = view.alpha
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                ballParams.x = (startX + (targetX - startX) * p).toInt()
                try {
                    windowManager.updateViewLayout(view, ballParams)
                } catch (_: Exception) { }
                view.alpha = startAlpha + (PEEK_ALPHA - startAlpha) * p
            }
            start()
        }
    }

    private val autoDockRunnable = Runnable { if (!isCallActive()) dockToEdge() }

    private fun scheduleAutoDock() {
        if (isCallActive()) return
        cancelAutoDock()
        mainHandler.postDelayed(autoDockRunnable, AUTO_DOCK_DELAY_MS)
    }

    private fun cancelAutoDock() {
        mainHandler.removeCallbacks(autoDockRunnable)
    }

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

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) { }
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
            x = dockedX(dockSide)
            y = dp(160)
        }

    private fun fullScreenMenuLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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

    fun readClipboardWithFocus(onResult: (android.content.ClipData?) -> Unit) {
        ClipboardReaderActivity.onClipboardRead = onResult
        val intent = android.content.Intent(appContext, ClipboardReaderActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
            onResult(null)
        }
    }

    private companion object {
        const val BALL_SIZE_DP = 56
        const val MENU_WIDTH_DP = 290
        const val LONG_PRESS_START_MS = 2000L
        const val LONG_PRESS_END_MS = 2000L
        const val DISMISS_TARGET_SIZE_DP = 64
        const val DISMISS_TARGET_MARGIN_DP = 32
        const val DISMISS_SNAP_RADIUS_DP = 56

        const val PEEK_DP = 28
        const val PEEK_ALPHA = 0.35f
        const val DOCK_MARGIN_DP = 16
        const val AUTO_DOCK_DELAY_MS = 2500L

        const val DOCK_SIDE_START = 0
        const val DOCK_SIDE_END = 1
    }
}
