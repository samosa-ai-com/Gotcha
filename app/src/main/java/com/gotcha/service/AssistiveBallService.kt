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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that hosts the floating assistive ball over other apps.
 *
 * The ball behaves like a Messenger bubble: long-press it (3s) to start a
 * hands-free voice call with the agent ([CallSessionController]), tap it for
 * Start/Pause/End controls (or, during a call, to expand the [CallChatWindow]
 * transcript), long-press 5s during a call to end it, and drag it onto the ✕
 * target to hide the ball. It owns its own STT/TTS engines because the chat
 * UI's ViewModel dies when another app is foregrounded.
 *
 * Started/stopped from the in-app toggle via [ACTION_START] / [ACTION_STOP].
 */
class AssistiveBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ttsEngine: TtsEngine
    private lateinit var sttEngine: SttEngine
    private lateinit var overlay: AssistiveBallOverlay
    private lateinit var chatWindow: CallChatWindow
    private lateinit var callController: CallSessionController

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
        chatWindow = CallChatWindow(this).apply {
            onStart = { startOrResumeCall() }
            onPause = { callController.pause() }
            onEnd = { endCall() }
        }
        overlay = AssistiveBallOverlay(this).apply {
            onDismiss = { stopBall() }
            onStartCall = { startOrResumeCall() }
            onPauseCall = { callController.pause() }
            onEndCall = { endCall() }
            onToggleChatWindow = { if (chatWindow.isShowing()) chatWindow.hide() else chatWindow.show() }
            isCallActive = { callController.isActive() }
        }
        callController.onError = { overlay.showError(it) }
        callController.onCaptureChrome = { hide ->
            if (hide) {
                overlay.hideChromeForCapture()
                chatWindow.setVisibleForCapture(false)
            } else {
                overlay.showChromeAfterCapture()
                chatWindow.setVisibleForCapture(true)
            }
        }

        scope.launch {
            callController.state.collect { state ->
                overlay.setCallActive(state != CallState.IDLE)
                chatWindow.setStatus(state, callController.statusLine.value)
                if (state == CallState.IDLE) chatWindow.hide()
            }
        }
        scope.launch {
            callController.transcript.collect { chatWindow.setTranscript(it) }
        }
        scope.launch {
            callController.statusLine.collect { chatWindow.setStatus(callController.state.value, it) }
        }

        sweepOrphanedCalls()
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
        overlay.dismiss()
        ttsEngine.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Call control ----

    private fun startOrResumeCall() {
        if (callController.state.value == CallState.PAUSED) {
            callController.resume()
        } else {
            callController.startCall()
        }
    }

    private fun endCall() {
        callController.endCall()
        chatWindow.hide()
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

    // ---- Lifecycle helpers ----

    private fun stopBall() {
        // Keep the persisted toggle in sync when the ball is hidden by the
        // drag-to-✕ gesture (not just from the in-app toggle), so the app
        // reopens with it off. An active call is ended (and deleted) first.
        callController.endCall()
        chatWindow.hide()
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
