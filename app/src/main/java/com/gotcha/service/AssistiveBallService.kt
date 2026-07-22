package com.gotcha.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.gotcha.MainActivity
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SettingsRepository
import com.gotcha.ui.AssistiveBallOverlay
import com.gotcha.ui.CallChatWindow
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
    private lateinit var screenCompanionController: ScreenCompanionController
    private lateinit var screenCompanionPanel: com.gotcha.ui.ScreenCompanionPanelOverlay
    private lateinit var screenLensController: ScreenLensController
    private val webFetchTool by lazy { com.gotcha.tools.WebFetchTool() }
    private val llmClient by lazy {
        val s = settingsRepository.load()
        com.gotcha.llm.LLMClient(
            apiKey = s.effectiveApiKey,
            baseUrl = s.effectiveBaseUrl,
            model = s.model,
            context = this
        )
    }

    val proactiveSessionManager = ProactiveSessionManager()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        val s = settingsRepository.load()
        ttsEngine = TtsEngine(this, s.ttsApiBaseUrl, s.apiKey)
        sttEngine = SttEngine(this, s.sttApiBaseUrl, s.apiKey)
        callController = CallSessionController(
            appContext = applicationContext,
            scope = scope,
            settingsRepository = settingsRepository,
            sttEngine = sttEngine,
            ttsEngine = ttsEngine
        )

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
            isCallActive = { callController.isActive() }
        }

        callController.onError = { overlay.showError(it) }
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
                    overlay.hideChromeForCapture()
                    chatWindow.show()
                } else if (state == CallState.IDLE) {
                    chatWindow.hide()
                    overlay.showChromeAfterCapture()
                }
                chatWindow.setState(state)
                overlay.setCallActive(active)
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
                val compressed = if (attachScreenshot) {
                    com.gotcha.tools.ScreenPerception.compressScreenshot(maxDimension = 1024, quality = 85)
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

                val response = llmClient.chat(history.toList())
                val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                history.add(
                    com.gotcha.llm.ChatMessage(
                        "assistant",
                        kotlinx.serialization.json.JsonPrimitive(replyText)
                    )
                )
                screenCompanionPanel.updateResponse(replyText)
            } catch (e: Exception) {
                screenCompanionPanel.updateResponse("Error: ${e.message}")
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
            val fetched = withContext(Dispatchers.IO) { webFetchTool.fetch(url, "text") }
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
                val response = llmClient.chat(history.toList())
                val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                history.add(
                    com.gotcha.llm.ChatMessage("assistant", kotlinx.serialization.json.JsonPrimitive(replyText))
                )
                screenCompanionPanel.updateResponse(replyText)
            } catch (e: Exception) {
                screenCompanionPanel.updateResponse("Error: ${e.message}")
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
            onError = { overlay.showError(it) }
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
            // Prefer a structured action (dial / navigate / convert / schedule / reply)
            // when the copied text carries recognisable data; otherwise fall back to
            // the generic translate/summarize offer.
            val smart = SmartActionDetector.detect(clipText, allowChat = true)
            if (smart != null) {
                android.util.Log.d("AssistiveBallService", "Setting smart action: ${smart.label}")
                overlay.setSmartActionAvailable(smart.label, smart.prompt)
            } else {
                android.util.Log.d("AssistiveBallService", "Setting smart action: Text copied. Translate?")
                overlay.setSmartActionAvailable(
                    "📋 Translate: ${SmartActionDetector.snippet(clipText, 24)}",
                    "Translate the copied text to English if it is not, otherwise summarize it:\n\n$clipText"
                )
            }
        }
    }

    /**
     * Resolve a native structured action (encoded by [SmartActionDetector]) into
     * an Android system intent — navigate on a map, open the dialer, or create a
     * calendar event. Failures are surfaced on the ball's error card.
     */
    private fun handleNativeAction(prompt: String) {
        val decoded = SmartActionDetector.decode(prompt) ?: return
        val (type, payload) = decoded
        try {
            val intent = when (type) {
                SmartActionDetector.TYPE_NAVIGATE ->
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(payload)))
                SmartActionDetector.TYPE_DIAL ->
                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(payload)))
                SmartActionDetector.TYPE_CALENDAR ->
                    Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, payload)
                    }
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
                else -> null
            } ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            overlay.showError("Couldn't open action: ${e.message}")
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
                val response = llmClient.chat(currentHistory)
                val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                activeCompanionHistory.add(
                    com.gotcha.llm.ChatMessage("assistant", kotlinx.serialization.json.JsonPrimitive(replyText))
                )
                screenCompanionPanel.updateResponse(replyText)
            } catch (e: Exception) {
                screenCompanionPanel.updateResponse("Error: ${e.message}")
            }
        }
    }

    /** OCR a Lens crop via ML Kit on-device text recognition and copy to clipboard. */
    private fun ocrCropToClipboard(bitmap: android.graphics.Bitmap) {
        overlay.showError("Reading text…")
        scope.launch {
            try {
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                )
                val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                val result = withContext(Dispatchers.Default) {
                    com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
                }
                val text = result.text.trim()
                if (text.isBlank()) {
                    overlay.showError("No text found in selection")
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lens text", text))
                }
            } catch (e: Exception) {
                overlay.showError("Couldn't read text: ${e.message}")
            }
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
                        val response = llmClient.chat(currentHistory)
                        val replyText = response.choices.firstOrNull()?.message?.textContent ?: "No response"
                        activeCompanionHistory.add(
                            com.gotcha.llm.ChatMessage(
                                "assistant",
                                kotlinx.serialization.json.JsonPrimitive(replyText)
                            )
                        )
                        updateResponse(replyText)
                    } catch (e: Exception) {
                        updateResponse("Error: ${e.message}")
                    }
                }
            }
        }
    }

    // ---- Screen Companion panel voice I/O (STT / TTS) ----

    /** Start voice typing in the panel using the configured STT provider. */
    private fun startPanelVoiceInput() {
        val s = settingsRepository.load()
        when (s.sttProvider) {
            com.gotcha.audio.AudioProvider.ANDROID -> {
                if (!hasMicPermission()) {
                    overlay.showError("Microphone permission not granted.")
                    screenCompanionPanel.setListening(false)
                    return
                }
                if (sttEngine.startAndroidListening()) {
                    panelVoiceActive = true
                } else {
                    overlay.showError("Failed to start speech recognition.")
                    screenCompanionPanel.setListening(false)
                }
            }
            com.gotcha.audio.AudioProvider.API -> {
                if (s.sttApiBaseUrl.isBlank() || s.sttApiModel.isBlank()) {
                    overlay.showError("Configure an STT API URL and model in settings.")
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
            com.gotcha.audio.AudioProvider.NONE -> {
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
        if (provider == com.gotcha.audio.AudioProvider.NONE) {
            screenCompanionPanel.setListening(false)
            return
        }
        scope.launch {
            val result = sttEngine.stopListeningAndTranscribe(provider, s.sttApiModel)
            screenCompanionPanel.setListening(false)
            result
                .onSuccess { text -> if (text.isNotBlank()) screenCompanionPanel.appendVoiceInput(text) }
                .onFailure { e -> overlay.showError("Transcription failed: ${e.message}") }
        }
    }

    /** Read the panel's current response aloud using the configured TTS provider. */
    private fun readPanelResponseAloud(text: String) {
        val s = settingsRepository.load()
        if (s.ttsProvider == com.gotcha.audio.AudioProvider.NONE) {
            overlay.showError("No TTS provider configured. Enable one in settings.")
            screenCompanionPanel.setSpeaking(false)
            return
        }
        scope.launch {
            ttsEngine.speak(
                text = text,
                provider = s.ttsProvider,
                apiModel = s.ttsApiModel,
                voice = ""
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
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        _isRunning.value = false
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                overlay.showError("Screenshot requires Android 11+")
                return@launch
            }
            try {
                val service = GotchaAccessibilityService.instance
                if (service == null) {
                    overlay.showError("Accessibility service not available")
                    return@launch
                }
                var bitmap = service.takeScreenshotBitmap()
                if (bitmap == null) {
                    delay(1200L)
                    bitmap = service.takeScreenshotBitmap()
                }
                if (bitmap == null) {
                    overlay.showError("Screenshot failed — try again")
                    return@launch
                }
                val timestamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    java.util.Locale.US
                ).format(java.util.Date())
                val fileName = "Screenshot_$timestamp.png"
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/Gotcha"
                        )
                    }
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    overlay.showError("Screenshot saved: $fileName")
                } else {
                    overlay.showError("Screenshot save failed")
                }
                bitmap.recycle()
            } catch (e: Throwable) {
                overlay.showError("Screenshot error: ${e.message}")
            }
        }
    }

    // ---- Lifecycle helpers ----

    private fun stopBall() {
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        @Volatile
        var instance: AssistiveBallService? = null
            private set

        fun onProactiveEntitiesDiscovered(entities: List<DetectedEntity>, packageName: String? = null) {
            val service = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val sessionItems = service.proactiveSessionManager.mergeEntities(entities, packageName)
                service.overlay.setProactiveSessionItems(sessionItems)
            }
        }

        fun startIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_STOP)
    }
}
