package com.gotcha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.gotcha.MainActivity
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SettingsRepository
import com.gotcha.data.settingsChangeNotifier
import com.gotcha.i18n.Language
import com.gotcha.ui.AssistiveBallOverlay
import com.gotcha.ui.CallChatWindow
import com.gotcha.util.HumanReadableError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that hosts the floating assistive ball over other apps.
 *
 * When idle, the ball is shown. Long-press the ball (3s) to start a voice call.
 * During a call, the ball is hidden and replaced by three floating draggable
 * glass buttons (Mic, Stop, End). The buttons let the user push-to-talk,
 * interrupt the agent, or end the call. It owns its own STT/TTS engines
 * because the chat UI's ViewModel dies when another app is foregrounded.
 *
 * Started/stopped from the in-app toggle via [ACTION_START] / [ACTION_STOP].
 */
@Suppress("TooManyFunctions", "LargeClass")
class AssistiveBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ttsEngine: TtsEngine
    private lateinit var sttEngine: SttEngine
    private var activeCompanionHistory = mutableListOf<com.gotcha.llm.ChatMessage>()

    /**
     * A Lens crop (base64 JPEG) waiting to be attached to the user's next panel
     * message. Set when the user picks "Ask about this"; cleared once consumed.
     */
    private var pendingCropImage: String? = null

    /** True while the panel mic is actively recording, so dismiss won't try to transcribe silence. */
    private var panelVoiceActive = false
    private lateinit var overlay: com.gotcha.ui.AssistiveBallOverlay
    private lateinit var chatWindow: CallChatWindow
    private lateinit var callController: CallSessionController
    private lateinit var wakeWordDetector: WakeWordDetector

    /**
     * Raw SharedPreferences used to observe wake-word setting changes while the
     * ball is running. Cached once so register/unregister hit the same instance
     * — `SharedPreferences` matches listeners by identity for unregister.
     */
    private lateinit var settingsPrefs: SharedPreferences

    /** Reacts to wake-word settings edits while the ball is running. */
    private val wakeWordSettingsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val s = settingsRepository.load()
        if (!s.wakeWordEnabled || !s.assistiveBallEnabled) {
            wakeWordDetector.stop()
            return@OnSharedPreferenceChangeListener
        }
        // The key arrives encrypted; compare the value read back instead (see
        // settingsChangeNotifier's note). Restarting with a new threshold only
        // makes sense while idle — the state collector stops the detector
        // whenever a call becomes active anyway.
        if (s.wakeWordSensitivity != appliedWakeWordSensitivity &&
            callController.state.value == CallState.IDLE
        ) {
            maybeStartWakeWord()
        }
    }
    private var appliedWakeWordSensitivity: Float? = null
    private lateinit var screenCompanionController: ScreenCompanionController
    private lateinit var screenCompanionPanel: com.gotcha.ui.ScreenCompanionPanelOverlay
    private lateinit var screenLensController: ScreenLensController
    private val webFetchTool by lazy { com.gotcha.tools.WebFetchTool() }
    private var cachedLlmClient: com.gotcha.llm.LLMClient? = null
    private var cachedLlmKey: String? = null

    /**
     * The companion panel's LLM client. Rebuilt whenever the settings it depends
     * on change, so edits in Settings take effect without restarting the service
     * (a plain `by lazy` pinned the very first values for the service's lifetime).
     *
     * [Settings.apiTimeoutSeconds] must be passed through: omitting it left the
     * client on LLMClient's `0L` default, which OkHttp reads as *no timeout*, so
     * a stalled request hung the panel on "Thinking..." forever — it has no stop
     * button, and the `catch` that would show an error never runs.
     */
    private val llmClient: com.gotcha.llm.LLMClient
        get() {
            val s = settingsRepository.load()
            val key = listOf(
                s.effectiveApiKey.hashCode(),
                s.effectiveBaseUrl,
                s.model,
                s.apiTimeoutSeconds
            ).joinToString("|")
            cachedLlmClient?.let { if (cachedLlmKey == key) return it }
            return com.gotcha.llm.LLMClient(
                apiKey = s.effectiveApiKey,
                baseUrl = s.effectiveBaseUrl,
                model = s.model,
                context = this,
                apiTimeoutSeconds = s.apiTimeoutSeconds
            ).also {
                cachedLlmClient = it
                cachedLlmKey = key
            }
        }

    val proactiveSessionManager = ProactiveSessionManager()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        val s = settingsRepository.load()
        ttsEngine = TtsEngine(this, s.effectiveTtsBaseUrl, s.effectiveTtsApiKey)
        sttEngine = SttEngine(this, s.effectiveSttBaseUrl, s.effectiveSttApiKey)
        callController = CallSessionController(
            appContext = applicationContext,
            scope = scope,
            settingsRepository = settingsRepository,
            sttEngine = sttEngine,
            ttsEngine = ttsEngine
        )
        wakeWordDetector = WakeWordDetector(
            context = applicationContext,
            scope = scope,
            sensitivityProvider = { settingsRepository.load().wakeWordSensitivity },
            onStarted = { },
            onDetected = ::onWakeWordDetected,
            onError = { message -> overlay.showError(message) }
        )

        settingsPrefs = settingsChangeNotifier(applicationContext)
        settingsPrefs.registerOnSharedPreferenceChangeListener(wakeWordSettingsWatcher)

        // Floating call buttons (shown during a call, hidden otherwise)
        chatWindow = CallChatWindow(this).apply {
            onStartMic = { callController.startMic() }
            onStopMic = { callController.stopMic() }
            onInterrupt = { callController.stopAgent() }
            onEndCall = { callController.endCall() }
        }

        setupScreenCompanionPanel()

        // Lens mode: interactive crop selection + capture.
        setupScreenLensController()

        // Screen Companion Controller
        setupScreenCompanionController()
        screenCompanionController.start()

        // Assistive ball (shown when idle, hidden during a call)
        overlay = AssistiveBallOverlay(this).apply {
            onDismiss = {
                callController.endCall()
                stopBall()
            }
            onOpenApp = {
                val intent = Intent(this@AssistiveBallService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_FROM_ASSISTIVE_BALL, true)
                startActivity(intent)
            }
            onTakeScreenshot = { takeScreenshot() }
            onStartLens = { screenLensController.start() }
            onStartCall = { callController.startCall() }
            onEndCall = { callController.endCall() }
            onToggleChatWindow = { } // no-op during calls (ball is hidden)
            onSmartActionSelected = { prompt ->
                handleSmartActionSelected(prompt, activeCompanionHistory)
            }
            onRequestClipboardCheck = {
                handleClipboardRead()
                screenCompanionController.triggerScan(force = true)
            }
            isCallActive = { callController.isActive() }
        }

        callController.onError = { message ->
            // The overlay card is the persistent mid-call surface; the call
            // window also shows a transient line so the error is visible right
            // where the user's eyes are, even if the card is off-screen.
            overlay.showError(message)
            chatWindow.showError(message)
        }
        callController.onActionRingColor = { color -> chatWindow.setActionRingColor(color) }
        callController.onCaptureChrome = { hide ->
            if (hide) {
                overlay.hideChromeForCapture()
                chatWindow.setVisibleForCapture(false)
            } else {
                overlay.showChromeAfterCapture()
                chatWindow.setVisibleForCapture(true)
            }
        }

        // Track call state → show/hide ball + buttons
        scope.launch {
            callController.state.collect { state ->
                val active = state != CallState.IDLE && state != CallState.ENDING
                if (active) {
                    wakeWordDetector.stop()
                } else if (state == CallState.IDLE) {
                    maybeStartWakeWord()
                }
                if (active) {
                    chatWindow.show()
                } else if (state == CallState.IDLE) {
                    chatWindow.hide()
                }
                chatWindow.setState(state)
                chatWindow.setHandsFree(callController.isHandsFree)
                overlay.setCallActive(active)
            }
        }

        // Proactively pause the wake-word listener while the app's own TTS is
        // playing (e.g. a companion-panel read-aloud that says "gotcha"). The
        // post-detection isSpeaking guard in onWakeWordDetected is a second
        // line of defence against self-triggering, but stopping the listener
        // here closes the race at the source — see privacy-data-retention.md §10.3.
        scope.launch {
            ttsEngine.isSpeaking.collect { speaking ->
                if (speaking) {
                    wakeWordDetector.stop()
                } else if (callController.state.value == CallState.IDLE) {
                    maybeStartWakeWord()
                }
            }
        }

        sweepOrphanedCalls()
    }

    private fun handleSmartActionSelected(prompt: String, history: MutableList<com.gotcha.llm.ChatMessage>) {
        // Native structured actions are handled without an LLM round-trip.
        if (SmartActionDetector.isNativeAction(prompt)) {
            val decoded = SmartActionDetector.decode(prompt)
            if (decoded?.first == SmartActionDetector.TYPE_FETCH) {
                // "Summarize link": fetch the page text, then summarize it.
                handleFetchAndSummarize(decoded.second, history)
            } else {
                // dial / navigate / schedule → fire an Android intent.
                handleNativeAction(prompt)
            }
            return
        }
        // Only an explicit screenshot action attaches an image. (A generic prompt
        // that merely mentions "screen" must NOT pull one in.)
        val attachScreenshot = prompt.contains("screenshot", ignoreCase = true)
        screenCompanionPanel.show(prompt)
        overlay.isPanelOpen = true
        scope.launch {
            try {
                // The ball has no activity to raise a consent dialog from, so this
                // relies on the accessibility capture path. Without accessibility the
                // capture returns null and the prompt is left to the chat flow.
                val compressed = if (attachScreenshot) {
                    showingActivity(com.gotcha.ui.BallActivity.ACTING) {
                        com.gotcha.tools.ScreenPerception.compressScreenshot(
                            maxDimension = 1024,
                            quality = 85
                        )
                    }
                } else {
                    null
                }

                val userMsg = if (compressed != null) {
                    com.gotcha.llm.visionUserMessage(prompt, compressed.base64, compressed.format)
                } else {
                    com.gotcha.llm.ChatMessage("user", kotlinx.serialization.json.JsonPrimitive(prompt))
                }
                history.clear()
                history.add(
                    com.gotcha.llm.ChatMessage(
                        "system",
                        kotlinx.serialization.json.JsonPrimitive(
                            "You are Screen Companion. Provide a short, precise answer."
                        )
                    )
                )
                history.add(userMsg)

                val response = showingActivity(com.gotcha.ui.BallActivity.THINKING) {
                    llmClient.chat(history.toList())
                }
                val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                history.add(
                    com.gotcha.llm.ChatMessage(
                        "assistant",
                        kotlinx.serialization.json.JsonPrimitive(replyText)
                    )
                )
                screenCompanionPanel.updateResponse(replyText)
            } catch (e: Exception) {
                screenCompanionPanel.updateResponse("Error: ${HumanReadableError.format(e)}")
            }
        }
    }

    /**
     * Fetch [url]'s content with [webFetchTool] and ask the LLM to summarize the
     * page *text* (never a screenshot). This is the correct behaviour for the
     * "Summarize link?" action — the earlier version wrongly attached a screenshot.
     */
    private fun handleFetchAndSummarize(url: String, history: MutableList<com.gotcha.llm.ChatMessage>) {
        screenCompanionPanel.show("Summarize link:\n$url")
        overlay.isPanelOpen = true
        screenCompanionPanel.updateResponse("Fetching $url …")
        scope.launch {
            val fetched = showingActivity(com.gotcha.ui.BallActivity.ACTING) {
                withContext(Dispatchers.IO) { webFetchTool.fetch(url, "text") }
            }
            if (!fetched.success) {
                screenCompanionPanel.updateResponse("Couldn't fetch the link: ${fetched.message}")
                return@launch
            }
            screenCompanionPanel.updateResponse("Summarizing …")
            try {
                history.clear()
                history.add(
                    com.gotcha.llm.ChatMessage(
                        "system",
                        kotlinx.serialization.json.JsonPrimitive(
                            "You are Screen Companion. Summarize the provided web page content " +
                                "concisely: a one-line gist, then 3-5 key bullet points."
                        )
                    )
                )
                history.add(
                    com.gotcha.llm.ChatMessage(
                        "user",
                        kotlinx.serialization.json.JsonPrimitive(
                            "Summarize this page ($url):\n\n${fetched.message.take(MAX_FETCH_CHARS)}"
                        )
                    )
                )
                val response = showingActivity(com.gotcha.ui.BallActivity.THINKING) {
                    llmClient.chat(history.toList())
                }
                val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                history.add(
                    com.gotcha.llm.ChatMessage("assistant", kotlinx.serialization.json.JsonPrimitive(replyText))
                )
                screenCompanionPanel.updateResponse(replyText)
            } catch (e: Exception) {
                screenCompanionPanel.updateResponse("Error: ${HumanReadableError.format(e)}")
            }
        }
    }

    private fun setupScreenLensController() {
        screenLensController = ScreenLensController(
            context = this,
            scope = scope,
            onAskAboutCrop = { base64 ->
                openCompanionWithImage(base64)
            },
            onContextualAction = { prompt ->
                handleSmartActionSelected(prompt, activeCompanionHistory)
            },
            onImagePrompt = { base64, prompt ->
                openCompanionWithImageAndQuery(base64, prompt)
            },
            onOcrToClipboard = { bitmap ->
                ocrCropToClipboard(bitmap)
            },
            onError = { overlay.showError(it) },
            onCaptureChrome = { hide ->
                if (hide) {
                    overlay.hideChromeForCapture()
                    chatWindow.setVisibleForCapture(false)
                    screenCompanionPanel.setVisibleForCapture(false)
                } else {
                    overlay.showChromeAfterCapture()
                    chatWindow.setVisibleForCapture(true)
                    screenCompanionPanel.setVisibleForCapture(true)
                }
            }
        )
    }

    private fun setupScreenCompanionController() {
        screenCompanionController = ScreenCompanionController(
            context = this,
            scope = scope,
            onSmartActionReady = { label, prompt ->
                overlay.setSmartActionAvailable(label, prompt)
            },
            onSmartActionPairReady = { label, prompt, altLabel, altPrompt ->
                overlay.setSmartActionAvailable(label, prompt, altLabel, altPrompt)
            },
            onReadClipboardRequest = { handleClipboardRead() }
        )
    }

    /** Read the clipboard (via a focus-stealing activity) and offer a smart action. */
    private fun handleClipboardRead() {
        android.util.Log.d("AssistiveBallService", "onReadClipboardRequest called")
        overlay.readClipboardWithFocus { clip ->
            android.util.Log.d(
                "AssistiveBallService",
                "readClipboardWithFocus: null=${clip == null}, count=${clip?.itemCount ?: 0}"
            )
            com.gotcha.service.GotchaAccessibilityService.lastClipboardData = clip
            val clipText = clip?.getItemAt(0)?.text?.toString()
            android.util.Log.d("AssistiveBallService", "clip length=${clipText?.length ?: 0}")
            if (clipText.isNullOrBlank()) {
                android.util.Log.d("AssistiveBallService", "clipText is null or blank, not setting smart action")
                return@readClipboardWithFocus
            }
            val url = SmartActionDetector.extractUrl(clipText)
            if (url != null) {
                android.util.Log.d("AssistiveBallService", "Setting smart action: Summarize link ($url)")
                val fetch = SmartActionDetector.fetchAction(url)
                overlay.setSmartActionAvailable(fetch.label, fetch.prompt)
                return@readClipboardWithFocus
            }
            val settings = runCatching { com.gotcha.data.SettingsRepository(applicationContext).load() }.getOrNull()
            val preferredLang = settings?.preferredLanguage ?: "English"
            val preferredCurr = settings?.preferredCurrency ?: "USD"
            val isAlreadyTargetLang = SmartActionDetector.isTextInLanguage(clipText, preferredLang)

            val smart = SmartActionDetector.detect(
                clipText,
                allowChat = true,
                targetCurrency = preferredCurr,
                targetLanguage = preferredLang
            )

            if (!isAlreadyTargetLang) {
                val translateLabel = "🌐 Translate: ${SmartActionDetector.snippet(clipText, 20)}"
                val translatePrompt = "Translate the copied text to $preferredLang:\n\n$clipText"

                if (smart != null && !smart.label.contains("Translate", ignoreCase = true)) {
                    overlay.setSmartActionPairAvailable(
                        translateLabel,
                        translatePrompt,
                        smart.label,
                        smart.prompt
                    )
                } else {
                    overlay.setSmartActionAvailable(translateLabel, translatePrompt)
                }
            } else if (smart != null) {
                overlay.setSmartActionAvailable(smart.label, smart.prompt)
            } else {
                overlay.setSmartActionAvailable(
                    "📋 Summarize: ${SmartActionDetector.snippet(clipText, 24)}",
                    "Summarize the copied text:\n\n$clipText"
                )
            }
        }
    }

    /**
     * Resolve a native structured action (encoded by [SmartActionDetector]) into
     * an Android system intent — navigate on a map, open the dialer, or create a
     * calendar event. Failures are surfaced on the ball's error card.
     */
    /**
     * Build the "new event" intent from a `yyyy-MM-dd|HH:mm|title` payload.
     *
     * The date has to be resolved before it gets here: handed the words "Monday"
     * or "Jul 26", a calendar app has nothing to resolve them against and drops
     * the event on today. A payload with no parseable date — anything encoded
     * before this format existed — still opens the composer with a title, which
     * is what the old behaviour was.
     */
    private fun calendarInsertIntent(payload: String): Intent {
        val parts = payload.split(SmartActionDetector.PAYLOAD_SEP, limit = CALENDAR_PAYLOAD_FIELDS)
        val title = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: payload
        return Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.Events.TITLE, title)

            val date = parts.getOrNull(0)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                ?: return@apply
            val time = parts.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }

            val start = if (time != null) date.atTime(time) else date.atStartOfDay()
            val beginMs = start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val durationMs = if (time != null) DEFAULT_EVENT_DURATION_MS else DAY_MS
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, beginMs + durationMs)
            // A day with no clock time is an all-day event, not one starting at midnight.
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, time == null)
        }
    }

    private fun handleNativeAction(prompt: String) {
        val decoded = SmartActionDetector.decode(prompt) ?: return
        val (type, payload) = decoded
        try {
            val intent = when (type) {
                SmartActionDetector.TYPE_NAVIGATE ->
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(payload)))
                SmartActionDetector.TYPE_DIAL ->
                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(payload)))
                SmartActionDetector.TYPE_CALENDAR -> calendarInsertIntent(payload)
                SmartActionDetector.TYPE_SMS ->
                    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(payload)))
                SmartActionDetector.TYPE_VIEW ->
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(payload))
                SmartActionDetector.TYPE_MAILTO ->
                    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + android.net.Uri.encode(payload)))
                SmartActionDetector.TYPE_SHARE ->
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            this.type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, payload)
                        },
                        "Share"
                    )
                SmartActionDetector.TYPE_CONTACT ->
                    Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.ContactsContract.Contacts.CONTENT_URI
                        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, payload)
                    }
                SmartActionDetector.TYPE_WHATSAPP -> {
                    val cleanPhone = payload.replace(Regex("[^0-9+]"), "")
                    val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=" + android.net.Uri.encode(cleanPhone))
                    Intent(Intent.ACTION_VIEW, uri)
                }
                SmartActionDetector.TYPE_COPY -> {
                    val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipManager?.setPrimaryClip(android.content.ClipData.newPlainText("Gotcha", payload))
                    android.widget.Toast.makeText(this, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    null
                }
                SmartActionDetector.TYPE_CONVERT -> {
                    val parts = payload.split("|")
                    val price = parts.getOrNull(0) ?: payload
                    val targetCurr = parts.getOrNull(1) ?: "USD"
                    scope.launch(Dispatchers.IO) {
                        val result = CurrencyExchangeService.convert(price, targetCurr)
                        withContext(Dispatchers.Main) {
                            if (result != null) {
                                overlay.showCard(result, showClose = true)
                            } else {
                                overlay.showError("Could not fetch exchange rate for $price")
                            }
                        }
                    }
                    null
                }
                else -> null
            } ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            overlay.showError("Couldn't open action: ${HumanReadableError.format(e)}")
        }
    }

    /**
     * Open the Screen Companion panel with a Lens crop attached, but do NOT query
     * the LLM yet — the user types their own question, and the image rides along
     * with that first message (see [pendingCropImage] handling in [setupScreenCompanionPanel]).
     */
    private fun openCompanionWithImage(base64Jpeg: String) {
        activeCompanionHistory.clear()
        activeCompanionHistory.add(
            com.gotcha.llm.ChatMessage(
                "system",
                kotlinx.serialization.json.JsonPrimitive(
                    "You are Screen Companion. The user has attached a captured screen region. " +
                        "Answer their question about it precisely."
                )
            )
        )
        pendingCropImage = base64Jpeg
        overlay.isPanelOpen = true
        screenCompanionPanel.show("📸 Region attached — ask a question about it below.")
        screenCompanionPanel.updateResponse("Ask me anything about the selected region.")
    }

    /**
     * Open the panel with a Lens crop attached AND immediately query the LLM with
     * [prompt] (used by the inline "Translate" chip on image regions).
     */
    private fun openCompanionWithImageAndQuery(base64Jpeg: String, prompt: String) {
        activeCompanionHistory.clear()
        activeCompanionHistory.add(
            com.gotcha.llm.ChatMessage(
                "system",
                kotlinx.serialization.json.JsonPrimitive(
                    "You are Screen Companion. The user has attached a captured screen region."
                )
            )
        )
        activeCompanionHistory.add(com.gotcha.llm.visionUserMessage(prompt, base64Jpeg, "jpeg"))
        overlay.isPanelOpen = true
        screenCompanionPanel.show("📸 Region attached")
        screenCompanionPanel.updateResponse("Thinking...")
        val currentHistory = activeCompanionHistory.toList()
        scope.launch {
            try {
                val response = showingActivity(com.gotcha.ui.BallActivity.THINKING) {
                    llmClient.chat(currentHistory)
                }
                val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                activeCompanionHistory.add(
                    com.gotcha.llm.ChatMessage("assistant", kotlinx.serialization.json.JsonPrimitive(replyText))
                )
                screenCompanionPanel.updateResponse(replyText)
            } catch (e: Exception) {
                screenCompanionPanel.updateResponse("Error: ${HumanReadableError.format(e)}")
            }
        }
    }

    /** OCR a Lens crop via ML Kit on-device text recognition and copy to clipboard. */
    private fun ocrCropToClipboard(bitmap: android.graphics.Bitmap) {
        scope.launch {
            try {
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                )
                val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                val result = showingActivity(com.gotcha.ui.BallActivity.ACTING) {
                    withContext(Dispatchers.Default) {
                        com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
                    }
                }
                val text = result.text.trim()
                if (text.isBlank()) {
                    showToast("No text found in selection")
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lens text", text))
                    showToast("Copied to clipboard")
                }
            } catch (e: Exception) {
                showToast("Couldn't read text: ${HumanReadableError.format(e)}")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * Run [block] with the ball showing [state].
     *
     * The ball is the only piece of Gotcha on screen while the companion panel
     * is working, and until now it sat perfectly still through every model call
     * — the in-app indicator breathed and its counterpart over other apps did
     * not. This is what tells it when to.
     *
     * Ref-counted because these overlap: a Lens crop can be summarising while a
     * clipboard action fetches. The last one out turns the ring off, so a
     * finishing call cannot clear a ring that another is still using.
     */
    private suspend fun <T> showingActivity(
        state: com.gotcha.ui.BallActivity,
        block: suspend () -> T
    ): T {
        activityDepth.incrementAndGet()
        overlay.setActivity(state)
        try {
            return block()
        } finally {
            if (activityDepth.decrementAndGet() <= 0) {
                overlay.setActivity(com.gotcha.ui.BallActivity.IDLE)
            }
        }
    }

    private val activityDepth = java.util.concurrent.atomic.AtomicInteger(0)

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupScreenCompanionPanel() {
        screenCompanionPanel = com.gotcha.ui.ScreenCompanionPanelOverlay(this).apply {
            onDismiss = {
                overlay.isPanelOpen = false
                activeCompanionHistory.clear()
                pendingCropImage = null
                // Only stop/transcribe if the mic was actually recording; otherwise
                // a no-op stop would surface a spurious "Transcription failed" toast.
                if (panelVoiceActive) {
                    stopPanelVoiceInput()
                } else {
                    sttEngine.cancelListening(settingsRepository.load().sttProvider)
                }
                ttsEngine.stop()
            }
            onStartVoiceInput = { startPanelVoiceInput() }
            onStopVoiceInput = { stopPanelVoiceInput() }
            onReadAloud = { text -> readPanelResponseAloud(text) }
            onStopReadAloud = { ttsEngine.stop() }
            onSendInput = { input ->
                // If a Lens crop is pending, attach it to this first message.
                val pending = pendingCropImage
                val userMsg = if (pending != null) {
                    pendingCropImage = null
                    com.gotcha.llm.visionUserMessage(input, pending, "jpeg")
                } else {
                    com.gotcha.llm.ChatMessage("user", kotlinx.serialization.json.JsonPrimitive(input))
                }
                activeCompanionHistory.add(userMsg)
                val currentHistory = activeCompanionHistory.toList()
                updateResponse("Thinking...")
                scope.launch {
                    try {
                        val response = showingActivity(com.gotcha.ui.BallActivity.THINKING) {
                            llmClient.chat(currentHistory)
                        }
                        val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                        activeCompanionHistory.add(
                            com.gotcha.llm.ChatMessage(
                                "assistant",
                                kotlinx.serialization.json.JsonPrimitive(replyText)
                            )
                        )
                        updateResponse(replyText)
                    } catch (e: Exception) {
                        updateResponse("Error: ${HumanReadableError.format(e)}")
                    }
                }
            }
        }
    }

    // ---- Screen Companion panel voice I/O (STT / TTS) ----

    /** Start voice typing in the panel using the configured STT provider. */
    private fun startPanelVoiceInput() {
        val s = settingsRepository.load()
        sttEngine.configureApi(s.effectiveSttBaseUrl, s.effectiveSttApiKey)
        when {
            s.sttProvider == AudioProvider.ANDROID -> {
                if (!hasMicPermission()) {
                    overlay.showError("Microphone permission not granted.")
                    screenCompanionPanel.setListening(false)
                    return
                }
                if (sttEngine.startAndroidListening(Language.fromLabel(s.preferredLanguage))) {
                    panelVoiceActive = true
                } else {
                    overlay.showError("Failed to start speech recognition.")
                    screenCompanionPanel.setListening(false)
                }
            }
            s.sttProvider.isApiBased() -> {
                if (s.effectiveSttBaseUrl.isBlank() || s.sttApiModel.isBlank()) {
                    overlay.showError(
                        if (s.sttProvider == AudioProvider.SAMOSA_AI) {
                            "Configure Samosa AI for STT in settings (sign in + select a model)."
                        } else {
                            "Configure an STT API URL and model in settings."
                        }
                    )
                    screenCompanionPanel.setListening(false)
                    return
                }
                if (!hasMicPermission()) {
                    overlay.showError("Microphone permission not granted.")
                    screenCompanionPanel.setListening(false)
                    return
                }
                if (sttEngine.startRecording()) {
                    panelVoiceActive = true
                } else {
                    overlay.showError("Failed to start recording.")
                    screenCompanionPanel.setListening(false)
                }
            }
            else -> {
                overlay.showError("No STT provider configured. Enable one in settings.")
                screenCompanionPanel.setListening(false)
            }
        }
    }

    /** Stop voice typing, transcribe, and append the result to the panel input. */
    private fun stopPanelVoiceInput() {
        // Nothing was recording — don't run a transcribe that would fail on silence.
        if (!panelVoiceActive) {
            screenCompanionPanel.setListening(false)
            return
        }
        panelVoiceActive = false
        val s = settingsRepository.load()
        val provider = s.sttProvider
        if (provider == AudioProvider.NONE) {
            screenCompanionPanel.setListening(false)
            return
        }
        sttEngine.configureApi(s.effectiveSttBaseUrl, s.effectiveSttApiKey)
        scope.launch {
            val sttLanguage = s.sttLanguage.ifBlank { Language.fromLabel(s.preferredLanguage).iso639 }
            val result = sttEngine.stopListeningAndTranscribe(provider, s.sttApiModel, sttLanguage)
            screenCompanionPanel.setListening(false)
            result
                .onSuccess { text -> if (text.isNotBlank()) screenCompanionPanel.appendVoiceInput(text) }
                .onFailure { e -> overlay.showError("Transcription failed: ${HumanReadableError.format(e)}") }
        }
    }

    /** Read the panel's current response aloud using the configured TTS provider. */
    private fun readPanelResponseAloud(text: String) {
        val s = settingsRepository.load()
        if (s.ttsProvider == AudioProvider.NONE) {
            overlay.showError("No TTS provider configured. Enable one in settings.")
            screenCompanionPanel.setSpeaking(false)
            return
        }
        ttsEngine.configureApi(s.effectiveTtsBaseUrl, s.effectiveTtsApiKey)
        scope.launch {
            ttsEngine.speak(
                text = text,
                provider = s.ttsProvider,
                apiModel = s.ttsApiModel,
                voice = s.ttsVoice,
                language = Language.fromLabel(s.preferredLanguage)
            )
            // Playback completed (or was stopped) — reset the speaker icon.
            screenCompanionPanel.setSpeaking(false)
        }
    }

    private fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun onWakeWordDetected() {
        wakeWordDetector.stop()
        if (ttsEngine.isSpeaking.value) {
            // The app was reading something aloud (e.g. a screen read-aloud that
            // happened to contain "gotcha") — never start a call from our own
            // voice. Resume listening instead.
            maybeStartWakeWord()
            return
        }
        val s = settingsRepository.load()
        com.gotcha.audio.CompletionFeedback.replyArrived(
            this,
            vibrate = s.notifyVibrationEnabled,
            chime = false
        )
        callController.startWakeWordCall()
    }

    private fun maybeStartWakeWord() {
        val settings = settingsRepository.load()
        if (settings.wakeWordEnabled && settings.assistiveBallEnabled && hasMicPermission()) {
            // A running detector skips its own start() (see WakeWordDetector),
            // so a live sensitivity change would otherwise never reach the
            // matcher. Stop first to force a rebuild whenever the threshold
            // needs to move, then re-arm with the fresh value.
            if (appliedWakeWordSensitivity != settings.wakeWordSensitivity &&
                wakeWordDetector.isRunning()
            ) {
                wakeWordDetector.stop()
            }
            appliedWakeWordSensitivity = settings.wakeWordSensitivity
            wakeWordDetector.start()
        } else {
            wakeWordDetector.stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBall()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                overlay.show()
                _isRunning.value = true
                maybeStartWakeWord()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        _isRunning.value = false
        wakeWordDetector.stop()
        if (::settingsPrefs.isInitialized) {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(wakeWordSettingsWatcher)
        }
        callController.endCall()
        chatWindow.hide()
        screenCompanionPanel.dismiss()
        overlay.dismiss()
        screenCompanionController.stop()
        screenLensController.cancel()
        ttsEngine.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Call sessions are deleted when a call ends; anything still on disk is
     * left over from a crash or process kill, so clear it on startup.
     */
    private fun sweepOrphanedCalls() {
        scope.launch(Dispatchers.IO) {
            val repo = ChatHistoryRepository(applicationContext, "calls")
            repo.listSessions().forEach { repo.deleteSession(it.id) }
            try {
                File(CallSessionController.CALLS_WORKING_ROOT).deleteRecursively()
            } catch (_: Exception) { }
        }
    }

    private fun takeScreenshot() {
        scope.launch(Dispatchers.IO) {
            try {
                val service = GotchaAccessibilityService.instance
                if (service == null) {
                    withContext(Dispatchers.Main) { overlay.showError("Accessibility service not available") }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    overlay.hideChromeForCapture()
                    chatWindow.setVisibleForCapture(false)
                    screenCompanionPanel.setVisibleForCapture(false)
                }
                delay(250L)
                var bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    delay(800L)
                    bitmap = service.takeScreenshotBitmap()
                }
                withContext(Dispatchers.Main) {
                    overlay.showChromeAfterCapture()
                    chatWindow.setVisibleForCapture(true)
                    screenCompanionPanel.setVisibleForCapture(true)
                }
                if (bitmap == null) {
                    withContext(Dispatchers.Main) { overlay.showError("Screenshot failed — try again") }
                    return@launch
                }
                val timestamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    java.util.Locale.US
                ).format(java.util.Date())
                val fileName = "Screenshot_$timestamp.png"
                val location = com.gotcha.data.GotchaStorage.saveScreenshot(
                    applicationContext,
                    fileName,
                    bitmap
                )
                withContext(Dispatchers.Main) { overlay.showSuccess("Screenshot saved to $location") }
                bitmap.recycle()
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    overlay.showChromeAfterCapture()
                    chatWindow.setVisibleForCapture(true)
                    screenCompanionPanel.setVisibleForCapture(true)
                    overlay.showError("Screenshot error: ${HumanReadableError.format(e)}")
                }
            }
        }
    }

    // ---- Lifecycle helpers ----

    private fun stopBall() {
        wakeWordDetector.stop()
        if (::settingsPrefs.isInitialized) {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(wakeWordSettingsWatcher)
        }
        callController.endCall()
        chatWindow.hide()
        screenCompanionPanel.dismiss()
        settingsRepository.save(settingsRepository.load().copy(assistiveBallEnabled = false))
        _isRunning.value = false
        overlay.dismiss()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        createChannel()
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gotcha assistive ball")
            .setContentText("Tap the ball for call controls. Long-press it to start a voice call.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Assistive ball",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the floating assistive ball running." }
            )
        }
    }

    companion object {
        const val ACTION_START = "com.gotcha.assistiveball.START"
        const val ACTION_STOP = "com.gotcha.assistiveball.STOP"

        private val _isRunning = MutableStateFlow(false)

        /** Live running state of the ball, so UI toggles can track hide gestures too. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        private const val CHANNEL_ID = "assistive_ball"
        private const val NOTIFICATION_ID = 4711

        /** Cap on fetched page text handed to the LLM for link summarization. */
        private const val MAX_FETCH_CHARS = 12000

        /** `date|time|title` — see [calendarInsertIntent]. */
        private const val CALENDAR_PAYLOAD_FIELDS = 3
        private const val DEFAULT_EVENT_DURATION_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        @Volatile
        var instance: AssistiveBallService? = null
            private set

        fun onProactiveEntitiesDiscovered(entities: List<DetectedEntity>, packageName: String? = null) {
            if (instance == null) return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val currentService = instance ?: return@post
                val sessionItems = currentService.proactiveSessionManager.mergeEntities(entities, packageName)
                currentService.overlay.setProactiveSessionItems(sessionItems)
            }
        }

        fun startIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_STOP)
    }
}
