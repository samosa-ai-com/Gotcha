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
import com.gotcha.agent.QuickAskEngine
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.LLMClient
import com.gotcha.ui.AssistiveBallOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the floating assistive ball over other apps.
 *
 * It owns its own copies of the STT/TTS engines and LLM client (the chat UI's
 * [com.gotcha.agent.ChatViewModel] dies when another app is foregrounded), and
 * drives the lightweight single-turn [QuickAskEngine] for the two "press & talk"
 * options. The third option simply relaunches [MainActivity] into the chat.
 *
 * Started/stopped from the in-app toggle via [ACTION_START] / [ACTION_STOP].
 */
class AssistiveBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ttsEngine: TtsEngine
    private lateinit var sttEngine: SttEngine
    private lateinit var overlay: AssistiveBallOverlay
    private lateinit var quickAsk: QuickAskEngine

    @Volatile private var isBusy = false
    @Volatile private var listening = false

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        val s = settingsRepository.load()
        ttsEngine = TtsEngine(this, s.ttsApiBaseUrl, s.apiKey)
        sttEngine = SttEngine(this, s.sttApiBaseUrl, s.apiKey)
        quickAsk = QuickAskEngine(
            settingsRepository = settingsRepository,
            sttEngine = sttEngine,
            ttsEngine = ttsEngine,
            llmProvider = ::buildLlmClient
        )
        overlay = AssistiveBallOverlay(this).apply {
            onOpenChat = { openChat() }
            onDismiss = { stopBall() }
            onStartTalk = { withScreen -> startTalk(withScreen) }
            onStopTalk = { stopTalk() }
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
        overlay.dismiss()
        ttsEngine.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Option 3: open chat ----

    private fun openChat() {
        android.util.Log.d("AssistiveBall", "openChat requested")
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
        } ?: Intent(this, MainActivity::class.java).apply {
            setAction(Intent.ACTION_MAIN)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
        }
        
        try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = android.app.ActivityOptions.makeBasic()
                options.pendingIntentBackgroundActivityStartMode = 
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
                android.util.Log.d("AssistiveBall", "PendingIntent sent with ActivityOptions")
            } else {
                pendingIntent.send()
                android.util.Log.d("AssistiveBall", "PendingIntent sent")
            }
        } catch (e: Exception) {
            android.util.Log.e("AssistiveBall", "PendingIntent.send() failed", e)
            try {
                startActivity(intent)
                android.util.Log.d("AssistiveBall", "startActivity() called as fallback")
            } catch (e2: Exception) {
                android.util.Log.e("AssistiveBall", "startActivity() also failed", e2)
            }
        }
    }

    // ---- Options 1 & 2: press & talk ----

    private var pendingWithScreen = false

    private fun startTalk(withScreen: Boolean) {
        if (isBusy) return
        if (buildLlmClient() == null) {
            overlay.showError("Set up your API key in Gotcha first.")
            return
        }
        if (!quickAsk.canListen()) {
            overlay.showError("No speech-to-text provider is configured. Enable one in Settings.")
            return
        }
        val started = quickAsk.startListening()
        if (!started) {
            overlay.showError("Couldn't start listening. Check the microphone permission.")
            return
        }
        listening = true
        pendingWithScreen = withScreen
        overlay.showListening()
    }

    private fun stopTalk() {
        if (!listening) return
        listening = false
        val withScreen = pendingWithScreen
        isBusy = true
        scope.launch {
            try {
                val question = quickAsk.stopAndTranscribe()
                if (question.isBlank()) {
                    overlay.showError("I didn't catch that. Try again.")
                    return@launch
                }

                var screenshot: String? = null
                var screenText: String? = null
                if (withScreen) {
                    // Screen questions require the accessibility service (it provides both
                    // the screenshot and the on-screen text). Guide the user if it's off.
                    if (!quickAsk.isAccessibilityAvailable()) {
                        overlay.showError(
                            "I can't see your screen yet. Turn on Gotcha under " +
                                "Settings ▸ Accessibility, then use Screen Share again. " +
                                "(For a screen-free question, use the Talk option.)"
                        )
                        return@launch
                    }
                    // Hide our own overlays so they aren't captured, and let the compositor
                    // paint a clean frame of the underlying app before we grab it.
                    overlay.hideChromeForCapture()
                    delay(350)
                    screenshot = quickAsk.captureScreenBase64()
                    screenText = quickAsk.captureScreenText()
                    overlay.showChromeAfterCapture()
                }

                overlay.showThinking()
                val answer = quickAsk.ask(question, screenshot, screenText, screenRequested = withScreen)
                overlay.showAnswer(answer)
                quickAsk.speak(answer)
            } catch (e: Exception) {
                overlay.showError(friendlyError(e))
            } finally {
                isBusy = false
            }
        }
    }

    // ---- Lifecycle helpers ----

    private fun stopBall() {
        // Keep the persisted toggle in sync when the ball is hidden from its own
        // menu (not just from the in-app toggle), so the app reopens with it off.
        settingsRepository.save(settingsRepository.load().copy(assistiveBallEnabled = false))
        _isRunning.value = false
        overlay.dismiss()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildLlmClient(): LLMClient? {
        val s = settingsRepository.load()
        return if (s.isConfigured) {
            LLMClient(
                apiKey = s.apiKey,
                baseUrl = s.baseUrl,
                model = s.model,
                context = applicationContext,
                apiTimeoutSeconds = s.apiTimeoutSeconds
            )
        } else null
    }

    private fun startAsForeground() {
        createChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gotcha assistive ball")
            .setContentText("Tap the floating ball to ask about your screen.")
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

    private fun friendlyError(e: Exception): String = when {
        e is retrofit2.HttpException && e.code() == 401 ->
            "The API rejected the key (401). Check your API key in settings."
        e is retrofit2.HttpException ->
            "The API returned an error (HTTP ${e.code()})."
        e is java.io.IOException ->
            "Network problem. Check your connection and try again."
        else -> "Something went wrong: ${e.message}"
    }

    companion object {
        const val ACTION_START = "com.gotcha.assistiveball.START"
        const val ACTION_STOP = "com.gotcha.assistiveball.STOP"

        private val _isRunning = MutableStateFlow(false)
        /** Live running state of the ball, so UI toggles can track "Hide ball" too. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        private const val CHANNEL_ID = "assistive_ball"
        private const val NOTIFICATION_ID = 4711

        fun startIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AssistiveBallService::class.java).setAction(ACTION_STOP)
    }
}
