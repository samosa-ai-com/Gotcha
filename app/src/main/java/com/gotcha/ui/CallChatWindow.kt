package com.gotcha.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gotcha.agent.MessageKind
import com.gotcha.service.CallState
import com.gotcha.service.CallTranscriptItem

/**
 * Messenger-style expanded chat window for an active voice call, drawn as a
 * bottom-anchored overlay (same SYSTEM_ALERT_WINDOW pattern as
 * [AssistiveBallOverlay]). Shows the live transcript plus a header with the
 * call status and Start / Pause / End controls. Voice-only: there is no text
 * input row, so the window never needs focus (FLAG_NOT_FOCUSABLE throughout).
 */
@Suppress("TooManyFunctions")
class CallChatWindow(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Callbacks — set by the host service before [show].
    var onStart: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onEnd: () -> Unit = {}
    var onMicClick: () -> Unit = {}

    private var rootView: View? = null
    private var statusText: TextView? = null
    private var micButton: Button? = null
    private var startButton: Button? = null
    private var pauseButton: Button? = null
    private var endButton: Button? = null
    private var listContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null

    // Last known state so a freshly (re)built window renders current data.
    private var lastState: CallState = CallState.IDLE
    private var lastStatusLine: String? = null
    private var lastItems: List<CallTranscriptItem> = emptyList()

    fun show() {
        mainHandler.post {
            if (rootView != null) return@post
            val root = buildWindow()
            try {
                windowManager.addView(root, layoutParams())
                rootView = root
                renderStatus()
                renderTranscript()
            } catch (_: Exception) {
                rootView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            rootView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) { }
            }
            rootView = null
            statusText = null
            micButton = null
            startButton = null
            pauseButton = null
            endButton = null
            listContainer = null
            scrollView = null
        }
    }

    fun isShowing(): Boolean = rootView != null

    /** Temporarily hide the window so it is not baked into a screen capture. */
    fun setVisibleForCapture(visible: Boolean) {
        mainHandler.post {
            rootView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun setStatus(state: CallState, statusLine: String?) {
        mainHandler.post {
            lastState = state
            lastStatusLine = statusLine
            renderStatus()
        }
    }

    fun setTranscript(items: List<CallTranscriptItem>) {
        mainHandler.post {
            lastItems = items
            renderTranscript()
        }
    }

    // ---- Rendering ----

    private fun renderStatus() {
        val state = lastState
        statusText?.text = when (state) {
            CallState.IDLE -> "No active call"
            CallState.STARTING -> "Starting…"
            CallState.READY -> "🎤 Tap mic to speak"
            CallState.LISTENING -> "🔴 Recording…"
            CallState.THINKING -> lastStatusLine ?: "Thinking…"
            CallState.SPEAKING -> "🔊 Speaking…"
            CallState.WAITING_USER -> "🎤 Tap mic to answer"
            CallState.PAUSED -> "⏸ Paused"
            CallState.ENDING -> "Ending…"
        }
        micButton?.text = when (state) {
            CallState.LISTENING -> "⏹"
            else -> "🎤"
        }
        micButton?.isEnabled = state == CallState.READY ||
            state == CallState.WAITING_USER ||
            state == CallState.LISTENING
        startButton?.isEnabled = state == CallState.PAUSED || state == CallState.IDLE
        pauseButton?.isEnabled = state != CallState.IDLE && state != CallState.PAUSED && state != CallState.ENDING
        endButton?.isEnabled = state != CallState.IDLE && state != CallState.ENDING
    }

    private fun renderTranscript() {
        val container = listContainer ?: return
        container.removeAllViews()
        for (item in lastItems) {
            container.addView(bubbleFor(item))
        }
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubbleFor(item: CallTranscriptItem): View {
        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
        }
        val bubble = TextView(appContext).apply {
            text = if (item.kind == MessageKind.SUBAGENT) "◆ ${item.text}" else item.text
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (appContext.resources.displayMetrics.widthPixels * MAX_BUBBLE_WIDTH_FRACTION).toInt()
        }
        when (item.kind) {
            MessageKind.USER -> {
                row.gravity = Gravity.END
                bubble.setTextColor(Color.WHITE)
                bubble.textSize = 14f
                bubble.background = bubbleBackground("#6750A4")
            }
            MessageKind.ASSISTANT, MessageKind.SUBAGENT -> {
                row.gravity = Gravity.START
                bubble.setTextColor(Color.parseColor("#EEEEEE"))
                bubble.textSize = 14f
                bubble.background = bubbleBackground("#2A2A2A")
            }
            MessageKind.TOOL -> {
                row.gravity = Gravity.START
                bubble.setTextColor(Color.parseColor("#9A9A9A"))
                bubble.textSize = 11f
                bubble.background = null
            }
            MessageKind.ERROR -> {
                row.gravity = Gravity.START
                bubble.setTextColor(Color.parseColor("#CF6679"))
                bubble.textSize = 12f
                bubble.background = null
            }
        }
        row.addView(bubble)
        return row
    }

    private fun bubbleBackground(color: String) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor(color))
    }

    // ---- Window construction ----

    private fun buildWindow(): View {
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
            }
        }

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(appContext).apply {
            setTextColor(Color.parseColor("#EADDFF"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mic = headerButton("🎤") { onMicClick() }
        val start = headerButton("▶") { onStart() }
        val pauseBtn = headerButton("⏸") { onPause() }
        val end = headerButton("⏹") { onEnd() }
        header.addView(status)
        header.addView(mic)
        header.addView(start)
        header.addView(pauseBtn)
        header.addView(end)

        val scroll = ScrollView(appContext).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        val list = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(list)

        root.addView(header)
        root.addView(scroll)

        statusText = status
        micButton = mic
        startButton = start
        pauseButton = pauseBtn
        endButton = end
        listContainer = list
        scrollView = scroll
        return root
    }

    private fun headerButton(label: String, onClick: () -> Unit) = Button(appContext).apply {
        text = label
        isAllCaps = false
        minWidth = dp(48)
        minimumWidth = dp(48)
        setOnClickListener { onClick() }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val metrics = appContext.resources.displayMetrics
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels * WINDOW_HEIGHT_FRACTION).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val WINDOW_HEIGHT_FRACTION = 0.65f
        const val MAX_BUBBLE_WIDTH_FRACTION = 0.78f
    }
}
