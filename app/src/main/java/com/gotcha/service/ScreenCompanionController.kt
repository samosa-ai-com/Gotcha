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
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class ScreenCompanionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSmartActionReady: (label: String, prompt: String) -> Unit,
    private val onReadClipboardRequest: () -> Unit = {},
    private val onSmartActionPairReady: (
        label: String,
        prompt: String,
        altLabel: String,
        altPrompt: String
    ) -> Unit = { l, p, _, _ -> onSmartActionReady(l, p) }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanDebounceJob: Job? = null
    private var lastScreenTextHash: Int = 0

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
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            appChangeReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(screenshotObserver)
        try {
            context.unregisterReceiver(appChangeReceiver)
        } catch (_: Exception) {}
    }

    private fun performLightweightScan(triggerType: String) {
        val settings = runCatching { SettingsRepository(context).load() }.getOrNull()
        if (settings != null && !settings.proactiveEnabled) return

        if (triggerType == "Screenshot") {
            onSmartActionPairReady(
                "📸 Screenshot taken. Extract text?",
                "Extract the text from this screenshot.",
                "🌐 Translate Screenshot",
                TRANSLATE_SCREENSHOT_PROMPT
            )
            return
        }

        if (triggerType == "Clipboard") {
            if (settings == null || settings.proactiveScanClipboard) {
                onReadClipboardRequest()
            }
            return
        }

        if (triggerType == "AppChange") {
            if (settings != null && !settings.proactiveScanScreen) return

            // Debounce 600ms trailing
            scanDebounceJob?.cancel()
            scanDebounceJob = scope.launch {
                delay(DEBOUNCE_DELAY_MS)
                withContext(Dispatchers.Default) {
                    val root = GotchaAccessibilityService.instance?.rootInActiveWindow
                    val pkg = root?.packageName?.toString() ?: ""
                    root?.recycle()

                    if (settings != null && settings.proactiveAppBlacklist.contains(pkg)) {
                        return@withContext
                    }

                    val screenText = ScreenSnapshot.captureScreenText(limit = 120) ?: return@withContext
                    val currentHash = screenText.hashCode()
                    if (currentHash == lastScreenTextHash) return@withContext
                    lastScreenTextHash = currentHash

                    val prefCurrency = settings?.preferredCurrency ?: "USD"
                    val allEntities = SmartActionDetector.detectAll(screenText, allowChat = false, targetCurrency = prefCurrency)
                    val actionableEntities = allEntities.filter { item ->
                        item.confidence >= 0.85f &&
                            item.type != com.gotcha.service.EntityType.CHAT_REPLY &&
                            item.type != com.gotcha.service.EntityType.GENERIC_TEXT
                    }
                    withContext(Dispatchers.Main) {
                        if (actionableEntities.isNotEmpty()) {
                            AssistiveBallService.onProactiveEntitiesDiscovered(actionableEntities, pkg)
                            val topAction = actionableEntities.first().primaryAction
                            if (topAction != null) {
                                onSmartActionReady(topAction.label, topAction.prompt)
                            }
                        } else {
                            AssistiveBallService.onProactiveEntitiesDiscovered(emptyList(), pkg)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_APP_CHANGED = "com.gotcha.action.APP_CHANGED"
        const val ACTION_CLIPBOARD_CHANGED = "com.gotcha.action.CLIPBOARD_CHANGED"
        private const val DEBOUNCE_DELAY_MS = 600L

        const val TRANSLATE_SCREENSHOT_PROMPT =
            "Extract any text present on this screenshot, translate it to English " +
                "(or the user's system language), and display both the original text " +
                "and its translation side-by-side using a markdown table."
    }
}
