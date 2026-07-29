package com.gotcha.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.core.graphics.ColorUtils
import com.gotcha.R
import com.gotcha.data.SettingsRepository
import com.gotcha.data.settingsChangeNotifier
import com.gotcha.service.EntityType
import com.gotcha.service.ProactiveSessionItem
import com.gotcha.service.SmartActionDetector
import com.gotcha.ui.theme.OverlaySkin
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.animationsEnabled
import com.gotcha.ui.theme.overlaySkin
import kotlin.math.abs
import kotlin.math.hypot

/** Accessibility label for the ball root view — used by UiAutomator to find the overlay. */
const val ASSISTIVE_BALL_CONTENT_DESCRIPTION = "Gotcha assistive ball"

/**
 * What the agent is doing, as far as the ball is concerned.
 *
 * Deliberately coarser than the call window's [com.gotcha.service.CallState]:
 * a 56dp disc at the edge of somebody else's screen can carry three states
 * legibly and no more.
 */
enum class BallActivity {
    /** Nothing running. No ring. */
    IDLE,

    /** Waiting on a model. */
    THINKING,

    /** Running a tool — reaching into the device. */
    ACTING
}

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

    private var statusRingView: View? = null
    private var statusRingDrawable: RingDrawable? = null
    private var statusRingAnimator: ValueAnimator? = null
    private var activity: BallActivity = BallActivity.IDLE

    /** What the card is showing, so a theme change can rebuild it as it was. */
    private var cardMessage: String? = null
    private var cardShowClose: Boolean = true

    private var dockSide: Int = DOCK_SIDE_END
    private val ballParams: WindowManager.LayoutParams by lazy { ballLayoutParams() }
    private val settingsRepository by lazy { SettingsRepository(appContext) }

    /**
     * The skin the chrome on screen is currently wearing.
     *
     * The menu and the card are rebuilt every time they open, so they pick up a
     * new theme on their own. The ball does not: it is built once in [show] and
     * then lives for as long as the service does, which meant a theme changed
     * in Settings did not reach it until the ball was switched off and on
     * again. This is what [restyleIfSkinChanged] compares against.
     */
    private var appliedSkinId: String? = null

    /**
     * Repaint when the theme changes underneath us.
     *
     * Registered on [settingsChangeNotifier] — the raw file — rather than on
     * the repository's own encrypted view, because that view keeps its listener
     * list per instance and Appearance saves through an instance of its own.
     *
     * The key is ignored: it arrives encrypted, and `save` writes every setting
     * at once so almost anything fires this. Reading the skin back and
     * comparing is the only thing that works, and it is what makes the listener
     * cheap enough not to care how often it runs.
     */
    private val skinWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { restyleIfSkinChanged() }
    }

    private fun currentSkinId(): String =
        runCatching { settingsRepository.load().skinId }.getOrDefault(Skins.DEFAULT_ID)

    /** The active skin, translated for View code. */
    private fun palette(): OverlaySkin = overlaySkin(appContext, currentSkinId())

    private fun accentColor(): Int = palette().accent

    /**
     * Rebuild whatever is on screen in the new theme.
     *
     * The ball is restyled in place rather than recreated: its window carries
     * the drag position and the touch listener, and tearing it down to change a
     * colour would drop both.
     */
    private fun restyleIfSkinChanged() {
        if (ballView == null) return
        val skinId = currentSkinId()
        if (skinId == appliedSkinId) return
        appliedSkinId = skinId

        val colors = overlaySkin(appContext, skinId)
        ballView?.let { applyBallSkin(it, colors) }
        if (statusRingView != null) refreshStatusRing()
        if (menuView != null) {
            removeMenu()
            // Not a fresh open, so no clipboard poke: that launches an activity,
            // and changing theme is not a reason to take focus off whatever the
            // user is actually looking at.
            showMenu(requestClipboard = false)
        }
        cardMessage?.let { showCard(it, cardShowClose) }
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    /**
     * Ball/ring/menu (and optionally the error/info card) visibility toggle.
     * [includeCard] is false for call-active toggling: an error card raised
     * mid-call must stay visible across the frequent state changes a call
     * goes through, not flicker in and out with every turn.
     */
    private fun setChromeVisible(visible: Boolean, includeCard: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        ballView?.visibility = visibility
        if (visible) ballView?.alpha = PEEK_ALPHA
        ringView?.visibility = visibility
        statusRingView?.visibility = visibility
        menuView?.visibility = visibility
        dismissTargetView?.visibility = visibility
        if (includeCard) cardView?.visibility = visibility
    }

    fun hideChromeForCapture() {
        mainHandler.post { setChromeVisible(visible = false, includeCard = true) }
    }

    fun showChromeAfterCapture() {
        mainHandler.post { setChromeVisible(visible = true, includeCard = true) }
    }

    fun setCallActive(active: Boolean) {
        mainHandler.post {
            if (active) {
                setChromeVisible(visible = false, includeCard = false)
                removeMenu()
            } else {
                setChromeVisible(visible = true, includeCard = false)
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
                refreshStatusRing()
                // SharedPreferences keeps only a weak reference to a listener;
                // [skinWatcher] is a field of this object, which the service
                // holds, so it survives as long as the ball does.
                runCatching {
                    settingsChangeNotifier(appContext)
                        .registerOnSharedPreferenceChangeListener(skinWatcher)
                }
            } catch (_: Exception) {
                ballView = null
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            runCatching {
                settingsChangeNotifier(appContext)
                    .unregisterOnSharedPreferenceChangeListener(skinWatcher)
            }
            removeLongPressRing()
            removeStatusRing()
            removeMenu()
            removeCard()
            removeDismissTarget()
            ballView?.let { safeRemove(it) }
            ballView = null
            appliedSkinId = null
        }
    }

    fun setProactiveSessionItems(items: List<ProactiveSessionItem>) {
        mainHandler.post {
            proactiveSessionItems = items
            if (items.isEmpty()) {
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
            cardMessage = message
            cardShowClose = showClose
            val colors = palette()
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                applyOverlayCard(colors, horizontalDp = 20, verticalDp = 16)
            }
            val scroll = ScrollView(appContext).apply {
                addView(
                    TextView(appContext).apply {
                        text = message
                        typeface = colors.sans
                        setTextColor(colors.onSurface)
                        textSize = colors.bodySp
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(240)
                )
            }
            container.addView(scroll)
            if (showClose) {
                // Not a menu row: nothing to line up with, so it stays centred.
                container.addView(
                    tapButton("Close", colors, iconRes = null, asMenuRow = false) { hideCard() }
                )
            }
            try {
                windowManager.addView(container, cardLayoutParams(container))
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

    /**
     * The ball is a designed object, not a pasted icon.
     *
     * It used to be the bare mark on its own white disc — the adaptive-icon
     * asset, wearing the launcher's colours over whatever app happened to be
     * underneath. Now it is the skin's ground with the same edge highlight and
     * shadow the cards get, so it reads as one of our controls in the same
     * theme as everything else, and it keeps its shape over a photograph.
     *
     * The shadow is tighter than a card's: the window is a fixed 56dp and every
     * dock, peek and dismiss measurement in this class is written against that,
     * so the gutter is taken out of the disc rather than added to the window.
     */
    private fun buildBall(): View {
        val size = dp(BALL_SIZE_DP)
        appliedSkinId = currentSkinId()
        return android.widget.ImageView(appContext).apply {
            contentDescription = ASSISTIVE_BALL_CONTENT_DESCRIPTION
            // The in-app mark. The launcher icon is adaptive now, so it would
            // draw at two-thirds size inside its own safe zone.
            setImageResource(R.drawable.gotcha_logo)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            applyBallSkin(this, palette())
            alpha = PEEK_ALPHA
            setOnTouchListener(ballTouchListener())
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /**
     * Dress the ball in [colors]. Separate from [buildBall] because a theme
     * change repaints the existing view rather than making a new one — the
     * window it lives in carries the drag position and the touch listener.
     */
    private fun applyBallSkin(view: View, colors: OverlaySkin) {
        val disc = overlayCardBackground(
            density = appContext.resources.displayMetrics.density,
            colors = colors,
            radiusDp = BALL_SIZE_DP / 2f,
            fill = colors.ground,
            shadowRadiusDp = BALL_SHADOW_DP
        )
        view.background = disc
        val inset = disc.shadowPadPx + dp(BALL_MARK_INSET_DP)
        view.setPadding(inset, inset, inset, inset)
    }

    private fun toggleMenu() {
        if (menuView != null) removeMenu() else showMenu()
    }

    private fun showMenu(requestClipboard: Boolean = true) {
        removeMenu()
        // Trigger a fresh clipboard read if no smart action is already staged.
        if (requestClipboard && pendingSmartAction == null) {
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
            applyOverlayCard(colors, horizontalDp = 10, verticalDp = 10)
            setOnClickListener { }
        }
        val menuShadowPad = (menuCard.background as? OverlayCardDrawable)?.shadowPadPx ?: 0

        buildProactiveMenuContent(menuCard, colors)

        val appNavRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        appNavRow.addView(
            tapButton("Open App", colors, R.drawable.ic_overlay_open_app, bold = true) {
                removeMenu()
                onOpenApp()
            }
        )
        appNavRow.addView(
            tapButton("Screenshot", colors, R.drawable.ic_overlay_screenshot, bold = true) {
                removeMenu()
                onTakeScreenshot()
            }
        )
        appNavRow.addView(
            tapButton("Lens", colors, R.drawable.ic_overlay_lens, bold = true) {
                removeMenu()
                onStartLens()
            }
        )
        menuCard.addView(appNavRow)

        val metrics = appContext.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        // The view is the card plus its shadow gutter; the numbers below are all
        // about where the *card* lands, so the gutter is added to the width and
        // taken back off the margins.
        val menuWidth = dp(MENU_WIDTH_DP) + menuShadowPad * 2
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
            this.leftMargin = leftMargin - menuShadowPad
            this.topMargin = topMargin - menuShadowPad
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

    private fun buildProactiveMenuContent(menuCard: LinearLayout, colors: OverlaySkin) {
        // Always show clipboard/smart action at the top
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

        // Then show proactive screen-scan entities if any
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
                    typeface = colors.sans
                    textSize = colors.labelSp
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
                        typeface = colors.sans
                        textSize = colors.labelSp
                        setTextColor(colors.buttonText)
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(colors.buttonRadiusDp.toInt()).toFloat()
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
        }
    }

    private fun getCategoryIcon(type: EntityType): String = when (type) {
        EntityType.QR_CODE -> "📷"
        EntityType.BARCODE -> "📊"
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

    /**
     * A row in the ball's menu.
     *
     * [iconRes] is a vector, never an emoji: emoji are drawn by whichever font
     * the OEM shipped, at whatever weight and in whatever colour that vendor
     * chose, so they are the one element in the menu guaranteed not to match
     * the app. Rows without one keep the text on the same column as rows with
     * one, so the menu reads as a list rather than as two lists.
     *
     * [bold] is for the fixed commands — Open App, Screenshot, Lens. They are
     * always there and always mean the same thing, and the weight separates
     * them from the smart-action rows above, which are suggestions that come
     * and go with whatever is on the clipboard.
     */
    private fun tapButton(
        label: String,
        colors: OverlaySkin,
        iconRes: Int? = null,
        asMenuRow: Boolean = true,
        bold: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val iconPx = dp(MENU_ICON_DP)
        val iconGutter = iconPx + dp(MENU_ICON_GAP_DP)
        return TextView(appContext).apply {
            text = label
            typeface = if (bold) Typeface.create(colors.sans, Typeface.BOLD) else colors.sans
            textSize = colors.bodySp
            setTextColor(colors.buttonText)
            gravity = if (asMenuRow) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
            if (!asMenuRow) {
                setPadding(dp(14), dp(8), dp(14), dp(8))
            } else if (iconRes != null) {
                val icon = androidx.core.content.ContextCompat.getDrawable(appContext, iconRes)
                icon?.setBounds(0, 0, iconPx, iconPx)
                icon?.setTint(colors.buttonText)
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(MENU_ICON_GAP_DP)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            } else {
                setPadding(dp(14) + iconGutter, dp(8), dp(14), dp(8))
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(colors.buttonRadiusDp.toInt()).toFloat()
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
        cardMessage = null
    }

    /**
     * Tell the ball what the agent is doing.
     *
     * The in-app indicator breathes; the ball is the same object in another
     * place and should breathe with it. Same 900ms reverse, same 0.32→1.0 —
     * see `ActivityPulse` in ChatComponents.kt. Two indicators pulsing at
     * different rates would read as two different things happening.
     */
    fun setActivity(state: BallActivity) {
        mainHandler.post {
            if (state == activity) return@post
            activity = state
            refreshStatusRing()
        }
    }

    private fun refreshStatusRing() {
        removeStatusRing()
        if (activity == BallActivity.IDLE || ballView == null) return

        val density = appContext.resources.displayMetrics.density
        val ballPx = dp(BALL_SIZE_DP)
        val viewSize = (BALL_SIZE_DP * STATUS_RING_WINDOW_SCALE * density).toInt()
        val accent = accentColor()
        val acting = activity == BallActivity.ACTING

        val drawable = RingDrawable().apply {
            fillColor = Color.TRANSPARENT
            strokeColor = accent
            strokeWidth = STATUS_RING_STROKE_DP * density
            ringRadius = ballPx * STATUS_RING_RADIUS
            // Acting reaches into the device, so it carries a halo as well as a
            // ring. Thinking is only waiting, and gets the ring alone.
            if (acting) {
                auraColor = accent
                auraRadius = ballPx * STATUS_AURA_RADIUS
            }
        }

        val view = View(appContext).apply { background = drawable }
        val params = statusRingLayoutParams(viewSize)
        try {
            windowManager.addView(view, params)
            statusRingView = view
            statusRingDrawable = drawable
        } catch (_: Exception) {
            statusRingView = null
            statusRingDrawable = null
            return
        }

        if (!animationsEnabled(appContext)) {
            // "Remove animations" is a system-wide preference, and a floating
            // window over every other app is the last place to ignore it. The
            // state still shows — it just holds still.
            drawable.strokeColor = accent
            if (acting) drawable.auraIntensity = PULSE_MAX * AURA_SHARE
            return
        }

        statusRingAnimator = ValueAnimator.ofFloat(PULSE_MIN, PULSE_MAX).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                drawable.strokeColor = ColorUtils.setAlphaComponent(accent, (p * 255).toInt())
                if (acting) drawable.auraIntensity = p * AURA_SHARE
            }
            start()
        }
    }

    private fun statusRingLayoutParams(viewSize: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            viewSize,
            viewSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val ballPx = dp(BALL_SIZE_DP)
            x = ballParams.x + ballPx / 2 - viewSize / 2
            y = ballParams.y + ballPx / 2 - viewSize / 2
        }

    /**
     * Keep the ring on the ball. Called from every place the ball moves —
     * drag, dock, expand, clamp — because the ring is its own window and does
     * not come along on its own.
     */
    private fun followBallWithStatusRing() {
        val view = statusRingView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val ballPx = dp(BALL_SIZE_DP)
        params.x = ballParams.x + ballPx / 2 - params.width / 2
        params.y = ballParams.y + ballPx / 2 - params.height / 2
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) { }
    }

    private fun removeStatusRing() {
        statusRingAnimator?.cancel()
        statusRingAnimator = null
        statusRingView?.let { safeRemove(it) }
        statusRingView = null
        statusRingDrawable = null
    }

    private fun showLongPressRing() {
        removeLongPressRing()
        val density = appContext.resources.displayMetrics.density
        val viewSize = (BALL_SIZE_DP * 3.2f * density).toInt()
        val ballPx = dp(BALL_SIZE_DP)
        val accent = accentColor()
        val drawable = RingDrawable().apply {
            fillColor = Color.TRANSPARENT
            strokeColor = accent
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
                    // Accent, not white: the ring was set from the skin at
                    // construction and then painted over on the first frame.
                    drawable.strokeColor = ColorUtils.setAlphaComponent(accent, ((1f - p * 0.6f) * 255).toInt())
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
                        followBallWithStatusRing()
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
        followBallWithStatusRing()
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
        followBallWithStatusRing()
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
                followBallWithStatusRing()
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
        val colors = palette()
        val target = TextView(appContext).apply {
            text = "✕"
            textSize = 22f
            typeface = colors.sans
            setTextColor(colors.onSurface)
            gravity = Gravity.CENTER
            // Was #CC222222 with a white rim, which is a fifth theme nobody
            // chose. The rim is the same lit edge the cards wear.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors.surface)
                setStroke(dp(2), colors.edgeHighlight)
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

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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

    /** Wide enough for [card] plus the room its shadow needs on either side. */
    private fun cardLayoutParams(card: View): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(320) + ((card.background as? OverlayCardDrawable)?.shadowPadPx ?: 0) * 2,
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

        /** Taken out of the disc, not added to the window — see [buildBall]. */
        const val BALL_SHADOW_DP = 4f

        /** How far the mark sits inside the disc. */
        const val BALL_MARK_INSET_DP = 9

        const val MENU_WIDTH_DP = 290

        /** Sized against bodyMedium, so the row reads as icon-then-label. */
        const val MENU_ICON_DP = 18
        const val MENU_ICON_GAP_DP = 12

        // The status ring. The pulse is ActivityPulse's, to the millisecond.
        const val PULSE_MS = 900L
        const val PULSE_MIN = 0.32f
        const val PULSE_MAX = 1f

        /** The aura is the quieter half of the pair; the ring carries the read. */
        const val AURA_SHARE = 0.45f

        /** Just outside the disc, which is inset from the 56dp window. */
        const val STATUS_RING_RADIUS = 0.54f
        const val STATUS_RING_STROKE_DP = 2f
        const val STATUS_AURA_RADIUS = 1.15f

        /** Room for the aura to fade out as a circle inside a square window. */
        const val STATUS_RING_WINDOW_SCALE = 2.6f

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
