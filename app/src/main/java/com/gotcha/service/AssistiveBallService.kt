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
class AssistiveBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ttsEngine: TtsEngine
    private lateinit var sttEngine: SttEngine
    private var activeCompanionHistory = mutableListOf<com.gotcha.llm.ChatMessage>()
    private lateinit var overlay: com.gotcha.ui.AssistiveBallOverlay
    private lateinit var chatWindow: CallChatWindow
    private lateinit var callController: CallSessionController
    private lateinit var screenCompanionController: ScreenCompanionController
    private lateinit var screenCompanionPanel: com.gotcha.ui.ScreenCompanionPanelOverlay
    private val llmClient by lazy {
        val s = settingsRepository.load()
        com.gotcha.llm.LLMClient(
            apiKey = s.effectiveApiKey,
            baseUrl = s.effectiveBaseUrl,
            model = s.model,
            context = this
        )
    }

    override fun onCreate() {
        super.onCreate()
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

        // Screen Companion Controller
        screenCompanionController = ScreenCompanionController(
            context = this,
            scope = scope,
            onSmartActionReady = { label, prompt ->
                overlay.setSmartActionAvailable(label, prompt)
            },
            onReadClipboardRequest = {
                /* ... */

                overlay.readClipboardWithFocus { clip ->
                    com.gotcha.service.GotchaAccessibilityService.lastClipboardData = clip
                    val clipText = clip?.getItemAt(0)?.text?.toString()
                    if (!clipText.isNullOrBlank()) {
                        if (android.util.Patterns.WEB_URL.matcher(clipText).find()) {
                            overlay.setSmartActionAvailable(
                                "🔗 Link copied. Summarize?",
                                "Summarize the content of the copied URL."
                            )
                        } else {
                            overlay.setSmartActionAvailable(
                                "📋 Text copied. Translate?",
                                "Translate the copied text to English if it is not, otherwise summarize it."
                            )
                        }
                    }
                }
            }
        )
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
        val attachScreenshot = prompt.contains("screenshot", ignoreCase = true)
        screenCompanionPanel.show(prompt)
        scope.launch {
            try {
                var base64: String? = null
                if (attachScreenshot && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val service = com.gotcha.service.GotchaAccessibilityService.instance
                    if (service != null) {
                        var bitmap = service.takeScreenshotBitmap()
                        if (bitmap == null) {
                            kotlinx.coroutines.delay(500)
                            bitmap = service.takeScreenshotBitmap()
                        }
                        if (bitmap != null) {
                            val (w, h) = if (bitmap.width > 1024 || bitmap.height > 1024) {
                                val ratio = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                                (bitmap.width * ratio).toInt() to (bitmap.height * ratio).toInt()
                            } else {
                                bitmap.width to bitmap.height
                            }
                            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                            if (scaled != bitmap) bitmap.recycle()
                            val output = java.io.ByteArrayOutputStream()
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
                            scaled.recycle()
                            base64 = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
                        }
                    }
                }

                val userMsg = if (base64 != null) {
                    com.gotcha.llm.visionUserMessage(prompt, base64)
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

    private fun setupScreenCompanionPanel() {
        screenCompanionPanel = com.gotcha.ui.ScreenCompanionPanelOverlay(this).apply {
            onDismiss = {
                overlay.isPanelOpen = false
                activeCompanionHistory.clear()
            }
            onSendInput = { input ->
                activeCompanionHistory.add(com.gotcha.llm.ChatMessage("user", kotlinx.serialization.json.JsonPrimitive(input)))
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
        _isRunning.value = false
        callController.endCall()
        chatWindow.hide()
        screenCompanionPanel.dismiss()
        overlay.dismiss()
        screenCompanionController.stop()
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

        fun startIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_STOP)
    }
}
