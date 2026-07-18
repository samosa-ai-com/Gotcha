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
import android.util.Patterns
import com.gotcha.agent.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCompanionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSmartActionReady: (label: String, prompt: String) -> Unit,
    private val onReadClipboardRequest: () -> Unit = {}
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
                        onSmartActionReady(
                            "📸 Screenshot taken. Extract text?",
                            "Extract the text from this screenshot."
                        )
                    }
                    return@withContext
                }

                if (triggerType == "Clipboard") {
                    // We must read clipboard from Main thread via overlay to avoid SecurityException
                    withContext(Dispatchers.Main) {
                        onReadClipboardRequest()
                    }
                    return@withContext
                }

                if (triggerType == "AppChange") {
                    val screenText = ScreenSnapshot.captureScreenText(limit = 40) ?: return@withContext
                    if (Patterns.WEB_URL.matcher(screenText).find() || screenText.contains("http", ignoreCase = true)) {
                        withContext(Dispatchers.Main) {
                            onSmartActionReady(
                                "🔗 Link detected. Summarize?",
                                "Summarize the content of the webpage shown on screen."
                            )
                        }
                    } else if (screenText.contains("Exception") || screenText.contains("Error", ignoreCase = true)) {
                        withContext(Dispatchers.Main) {
                            onSmartActionReady(
                                "⚠️ Error detected. Explain?",
                                "Explain the error code or stack trace shown on the screen."
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_APP_CHANGED = "com.gotcha.action.APP_CHANGED"
        const val ACTION_CLIPBOARD_CHANGED = "com.gotcha.action.CLIPBOARD_CHANGED"
    }
}
