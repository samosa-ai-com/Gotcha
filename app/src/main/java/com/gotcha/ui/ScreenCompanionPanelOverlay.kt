package com.gotcha.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import androidx.core.graphics.ColorUtils
import io.noties.markwon.Markwon

class ScreenCompanionPanelOverlay(private val context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var responseTextView: TextView? = null

    var onDismiss: () -> Unit = {}
    var onSendInput: (text: String) -> Unit = {}

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @SuppressLint("ClickableViewAccessibility")
    fun show(initialPrompt: String, resultText: String? = null) {
        if (panelView != null) {
            try {
                windowManager.removeView(panelView)
            } catch (_: Exception) {}
            panelView = null
        }

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#CC1E1E1E"))
                setStroke(dp(1), ColorUtils.setAlphaComponent(Color.CYAN, 80))
            }
        }

        val headerLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleText = TextView(appContext).apply {
            text = "Screen Companion"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = TextView(appContext).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            textSize = 18f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { dismiss() }
        }
        headerLayout.addView(titleText)
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
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        chatContainer.addView(promptView)

        responseTextView = TextView(appContext).apply {
            text = resultText ?: "Thinking..."
            setTextColor(Color.WHITE)
            textSize = 15f
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
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }

        val editText = EditText(appContext).apply {
            hint = "Ask a follow-up..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            maxLines = 3
        }

        val sendButton = Button(appContext).apply {
            text = "Send"
            setTextColor(Color.CYAN)
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
        inputLayout.addView(sendButton)
        container.addView(inputLayout)

        val params = WindowManager.LayoutParams(
            dp(320),
            dp(400),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

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
        } catch (_: Exception) {}
    }

    private fun createChip(label: String): TextView {
        return TextView(appContext).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#444444"))
                setStroke(dp(1), Color.GRAY)
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
                val markwon = Markwon.create(appContext)
                markwon.setMarkdown(tv, markdownText)
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            panelView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
            }
            panelView = null
            onDismiss()
        }
    }
}
