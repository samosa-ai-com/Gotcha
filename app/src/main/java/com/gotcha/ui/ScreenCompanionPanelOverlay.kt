package com.gotcha.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gotcha.data.SettingsRepository
import com.gotcha.ui.theme.OverlaySkin
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin
import io.noties.markwon.Markwon

class ScreenCompanionPanelOverlay(private val context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val markwon = Markwon.create(appContext)

    private val rotationWatcher = OverlayRotationWatcher(context) { _, _ -> handleScreenChanged() }

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelShadowPad: Int = 0
    private var responseTextView: TextView? = null
    private var inputEditText: EditText? = null
    private var micButton: TextView? = null
    private var speakerButton: TextView? = null

    var onDismiss: () -> Unit = {}
    var onSendInput: (text: String) -> Unit = {}

    /** Voice typing (STT): toggled by the mic icon. Host starts/stops recognition. */
    var onStartVoiceInput: () -> Unit = {}
    var onStopVoiceInput: () -> Unit = {}

    /** Read aloud (TTS): toggled by the speaker icon on the response block. */
    var onReadAloud: (text: String) -> Unit = {}
    var onStopReadAloud: () -> Unit = {}

    private var isListening = false
    private var isSpeaking = false

    /**
     * The skin the visible panel was built with. [setListening] and
     * [setSpeaking] fire long after [show] and need the same palette the rest
     * of the panel is wearing.
     */
    private var currentColors: OverlaySkin? = null

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    /**
     * The active skin, in View form. This panel used to paint itself `#CC1E1E1E`
     * with a `Color.CYAN` stroke — Deep Space, hardcoded, from before there was
     * more than one theme.
     */
    private fun skin(): OverlaySkin = overlaySkin(
        appContext,
        runCatching { SettingsRepository(appContext).load().skinId }
            .getOrDefault(Skins.DEFAULT_ID)
    )

    fun setVisibleForCapture(visible: Boolean) {
        mainHandler.post {
            panelView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @SuppressLint("ClickableViewAccessibility")
    fun show(initialPrompt: String, resultText: String? = null) {
        if (panelView != null) {
            try {
                windowManager.removeView(panelView)
            } catch (_: Exception) {}
            panelView = null
        }

        val colors = skin()
        currentColors = colors
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            applyOverlayCard(colors, horizontalDp = 16, verticalDp = 16)
        }
        val shadowPad = (container.background as? OverlayCardDrawable)?.shadowPadPx ?: 0

        val headerLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleText = TextView(appContext).apply {
            text = "Screen Companion"
            setTextColor(colors.onSurface)
            textSize = colors.titleSp
            typeface = Typeface.create(colors.sans, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val speakerBtn = TextView(appContext).apply {
            text = "🔊"
            setTextColor(colors.onSurfaceVariant)
            textSize = colors.titleSp
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                if (isSpeaking) {
                    onStopReadAloud()
                    setSpeaking(false)
                } else {
                    val text = responseTextView?.text?.toString()
                    if (!text.isNullOrBlank()) {
                        onReadAloud(text)
                        setSpeaking(true)
                    }
                }
            }
        }
        speakerButton = speakerBtn
        val closeButton = TextView(appContext).apply {
            text = "✕"
            setTextColor(colors.onSurfaceVariant)
            textSize = colors.titleSp
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { dismiss() }
        }
        headerLayout.addView(titleText)
        headerLayout.addView(speakerBtn)
        headerLayout.addView(closeButton)
        container.addView(headerLayout)

        val scrollView = ScrollView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val chatContainer = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        val promptView = TextView(appContext).apply {
            text = initialPrompt
            setTextColor(colors.onSurfaceVariant)
            textSize = colors.labelSp
            typeface = colors.sans
            setPadding(0, 0, 0, dp(8))
        }
        chatContainer.addView(promptView)

        responseTextView = TextView(appContext).apply {
            text = resultText ?: "Thinking..."
            setTextColor(colors.onSurface)
            textSize = colors.bodySp
            typeface = colors.sans
            setPadding(0, dp(8), 0, dp(16))
            // Ensure links are clickable
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        chatContainer.addView(responseTextView)
        scrollView.addView(chatContainer)
        container.addView(scrollView)

        val chipsLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        val copyChip = createChip("Copy").apply {
            setOnClickListener {
                val text = responseTextView?.text?.toString()
                if (!text.isNullOrBlank()) {
                    val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Copied from Assistant", text))
                    android.widget.Toast.makeText(appContext, "Copied!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        val shareChip = createChip("Share").apply {
            setOnClickListener {
                val text = responseTextView?.text?.toString()
                if (!text.isNullOrBlank()) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = android.content.Intent.createChooser(intent, "Share")
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(chooser)
                }
            }
        }
        chipsLayout.addView(copyChip)
        chipsLayout.addView(shareChip)
        container.addView(chipsLayout)

        val inputLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(colors.buttonRadiusDp.toInt()).toFloat()
                setColor(colors.buttonBg)
            }
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }

        val editText = EditText(appContext).apply {
            hint = "Ask a follow-up..."
            setHintTextColor(colors.onSurfaceVariant)
            setTextColor(colors.onSurface)
            typeface = colors.sans
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = colors.labelSp
            maxLines = 3
        }
        inputEditText = editText

        val micBtn = TextView(appContext).apply {
            text = "🎤"
            setTextColor(colors.onSurfaceVariant)
            textSize = colors.titleSp
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                if (isListening) {
                    onStopVoiceInput()
                    setListening(false)
                } else {
                    onStartVoiceInput()
                    setListening(true)
                }
            }
        }
        micButton = micBtn

        val sendButton = Button(appContext).apply {
            text = "Send"
            // Was Color.CYAN — Deep Space, from before there was a second theme.
            setTextColor(colors.accent)
            typeface = colors.sans
            background = null
            setOnClickListener {
                val input = editText.text.toString()
                if (input.isNotBlank()) {
                    onSendInput(input)
                    editText.text.clear()
                }
            }
        }
        inputLayout.addView(editText)
        inputLayout.addView(micBtn)
        inputLayout.addView(sendButton)
        container.addView(inputLayout)

        // Sized to the card plus the room its shadow needs, so the shadow is not
        // clipped to the window's square edge.
        val params = WindowManager.LayoutParams(
            0,
            0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        panelShadowPad = shadowPad
        applyPanelSize(params)

        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Exception) {}
            }
            false
        }

        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Exception) {}
            }
            false
        }

        editText.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Exception) {}
                true
            } else {
                false
            }
        }

        try {
            windowManager.addView(container, params)
            panelView = container
            panelParams = params
            rotationWatcher.start()
        } catch (_: Exception) {}
    }

    /**
     * Size the panel to the card it wants to be, or to the screen if that is
     * smaller.
     *
     * The panel is centred, so rotating never strands it — but it asked for a
     * flat 400dp of height, and a phone in landscape is only about 410dp tall.
     * The card overflowed top and bottom, taking the close button and the
     * input row off screen with it.
     */
    private fun applyPanelSize(params: WindowManager.LayoutParams) {
        val metrics = appContext.resources.displayMetrics
        val maxWidth = metrics.widthPixels - dp(SCREEN_MARGIN_DP) * 2
        val maxHeight = metrics.heightPixels - dp(SCREEN_MARGIN_DP) * 2
        params.width = (dp(PANEL_WIDTH_DP) + panelShadowPad * 2).coerceAtMost(maxWidth)
        params.height = (dp(PANEL_HEIGHT_DP) + panelShadowPad * 2).coerceAtMost(maxHeight)
    }

    private fun handleScreenChanged() {
        val view = panelView ?: return
        val params = panelParams ?: return
        applyPanelSize(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun createChip(label: String): TextView {
        val colors = currentColors ?: skin()
        return TextView(appContext).apply {
            text = label
            setTextColor(colors.buttonText)
            textSize = colors.bodySp
            typeface = colors.sans
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(colors.buttonRadiusDp.toInt()).toFloat()
                setColor(colors.buttonBg)
                setStroke(dp(1), colors.outline)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
    }

    fun updateResponse(markdownText: String) {
        mainHandler.post {
            responseTextView?.let { tv ->
                markwon.setMarkdown(tv, markdownText)
            }
        }
    }

    /** Append recognised speech to the follow-up input field (voice typing). */
    fun appendVoiceInput(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            inputEditText?.let { et ->
                val existing = et.text?.toString().orEmpty()
                val joined = if (existing.isBlank()) text else "$existing $text"
                et.setText(joined)
                et.setSelection(joined.length)
            }
        }
    }

    /**
     * Reflect STT listening state on the mic icon (tinted while active).
     *
     * Recording stays red in every skin, the way the call window's amber and
     * coral do: a colour that tells the user a microphone is open has to mean
     * the same thing whichever theme they picked.
     */
    fun setListening(listening: Boolean) {
        isListening = listening
        mainHandler.post {
            micButton?.apply {
                text = if (listening) "⏺" else "🎤"
                setTextColor(if (listening) RECORDING_RED else currentColors?.onSurfaceVariant ?: Color.WHITE)
            }
        }
    }

    /** Reflect TTS playback state on the speaker icon (tinted while speaking). */
    fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        mainHandler.post {
            speakerButton?.apply {
                text = if (speaking) "⏹" else "🔊"
                val resting = currentColors?.onSurfaceVariant ?: Color.WHITE
                setTextColor(if (speaking) currentColors?.accent ?: Color.WHITE else resting)
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            rotationWatcher.stop()
            panelView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
            }
            panelView = null
            panelParams = null
            inputEditText = null
            micButton = null
            speakerButton = null
            isListening = false
            isSpeaking = false
            currentColors = null
            onDismiss()
        }
    }

    private companion object {
        /** Semantic, not thematic: an open microphone is red in every skin. */
        val RECORDING_RED = Color.parseColor("#E23B3B")

        const val PANEL_WIDTH_DP = 320
        const val PANEL_HEIGHT_DP = 400

        /** Breathing room so the card never runs into the display edge. */
        const val SCREEN_MARGIN_DP = 16
    }
}
