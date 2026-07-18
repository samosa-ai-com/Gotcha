package com.gotcha.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.gotcha.agent.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCompanionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSmartActionReady: (label: String, prompt: String) -> Unit,
    private val onReadClipboardRequest: () -> Unit = {},
    /**
     * Offer a primary action plus an optional secondary ("alt") action — used to
     * pair "Extract text?" with "Translate Screenshot" on a screenshot trigger.
     */
    private val onSmartActionPairReady: (
        label: String,
        prompt: String,
        altLabel: String,
        altPrompt: String
    ) -> Unit = { l, p, _, _ -> onSmartActionReady(l, p) }
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenshotObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            performLightweightScan(triggerType = "Screenshot")
        }
    }

    private val appChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ScreenCompanionController", "appChangeReceiver received action: ${intent?.action}")
            if (intent?.action == ACTION_APP_CHANGED) {
                performLightweightScan(triggerType = "AppChange")
            } else if (intent?.action == ACTION_CLIPBOARD_CHANGED) {
                performLightweightScan(triggerType = "Clipboard")
            }
        }
    }

    fun start() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            screenshotObserver
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_APP_CHANGED)
            addAction(ACTION_CLIPBOARD_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(appChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(appChangeReceiver, filter)
        }
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(screenshotObserver)
        try {
            context.unregisterReceiver(appChangeReceiver)
        } catch (_: Exception) {}
    }

    private fun performLightweightScan(triggerType: String) {
        scope.launch {
            withContext(Dispatchers.Default) {
                if (triggerType == "Screenshot") {
                    withContext(Dispatchers.Main) {
                        onSmartActionPairReady(
                            "📸 Screenshot taken. Extract text?",
                            "Extract the text from this screenshot.",
                            "🌐 Translate Screenshot",
                            TRANSLATE_SCREENSHOT_PROMPT
                        )
                    }
                    return@withContext
                }

                if (triggerType == "Clipboard") {
                    android.util.Log.d(
                        "ScreenCompanionController",
                        "Clipboard trigger: request read on Main"
                    )
                    // We must read clipboard from Main thread via overlay to avoid SecurityException
                    withContext(Dispatchers.Main) {
                        onReadClipboardRequest()
                    }
                    return@withContext
                }

                if (triggerType == "AppChange") {
                    val root = GotchaAccessibilityService.instance?.rootInActiveWindow
                    val pkg = root?.packageName?.toString() ?: ""
                    root?.recycle()

                    val isIgnoredForLinks = pkg.contains("browser", ignoreCase = true) ||
                        pkg.contains("chrome", ignoreCase = true) ||
                        pkg.contains("firefox", ignoreCase = true) ||
                        pkg.contains("chromium", ignoreCase = true) ||
                        pkg.contains("messaging", ignoreCase = true) ||
                        pkg.contains("messages", ignoreCase = true) ||
                        pkg.contains("com.gotcha")

                    val screenText = ScreenSnapshot.captureScreenText(limit = 40) ?: return@withContext
                    val url = SmartActionDetector.extractUrl(screenText)

                    if (url != null && !isIgnoredForLinks) {
                        withContext(Dispatchers.Main) {
                            val fetch = SmartActionDetector.fetchAction(url)
                            onSmartActionReady(fetch.label, fetch.prompt)
                        }
                    } else {
                        // Structured-data (address / phone / currency / calendar) scan.
                        val smart = SmartActionDetector.detect(screenText, allowChat = false)
                        if (smart != null) {
                            withContext(Dispatchers.Main) {
                                onSmartActionReady(smart.label, smart.prompt)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_APP_CHANGED = "com.gotcha.action.APP_CHANGED"
        const val ACTION_CLIPBOARD_CHANGED = "com.gotcha.action.CLIPBOARD_CHANGED"

        /**
         * OCR-translate prompt for the "Translate Screenshot" action. The word
         * "screenshot" in it also drives [AssistiveBallService] to attach the
         * captured image to the request.
         */
        const val TRANSLATE_SCREENSHOT_PROMPT =
            "Extract any text present on this screenshot, translate it to English " +
                "(or the user's system language), and display both the original text " +
                "and its translation side-by-side using a markdown table."
    }
}
