package com.gotcha.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gotcha.notifications.ChatCompletionNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chat run the ongoing notification is about. [chatTitle] null: the chat may not be named (issue #100). */
data class RunningChat(val sessionId: String, val chatTitle: String?)

/**
 * Keeps Gotcha's process alive while a chat run is in progress (issue #105).
 * The run itself lives in `ChatRunner` (issue #111); this service only holds a foreground
 * slot, with an ongoing "Gotcha is working…" notification, so Android does not
 * reclaim the process once the user leaves the app mid-task.
 *
 * [start] and [stop] only set the wanted state; the service follows it. [stop]
 * never stops the service directly, because a service started with
 * startForegroundService that is brought down before it has called
 * startForeground crashes the app, and a quickly failing run can end before the
 * service is up.
 */
class ChatRunService : Service() {

    private var scope: CoroutineScope? = null
    private var follower: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP_RUN) {
            _stopRequests.tryEmit(Unit)
            return START_NOT_STICKY
        }
        // Required within seconds of startForegroundService, even when the run has
        // already ended: the follower below then takes the service straight down.
        goForeground(_running.value)
        if (follower == null) {
            val serviceScope = MainScope().also { scope = it }
            follower = serviceScope.launch {
                _running.collect { chat ->
                    if (chat == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Only if no newer start arrived meanwhile.
                        stopSelf(lastStartId)
                    } else {
                        goForeground(chat)
                    }
                }
            }
        }
        // A process killed mid-run has nothing to resume: the run died with it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        follower = null
        super.onDestroy()
    }

    private fun goForeground(chat: RunningChat?) {
        ensureChannel(this)
        val notification = buildNotification(this, chat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ChatRunService"
        const val CHANNEL_ID = "gotcha_chat_running"
        const val ACTION_STOP_RUN = "com.gotcha.ACTION_STOP_CHAT_RUN"
        internal const val NOTIFICATION_ID = 0x4A77_E8
        private const val PUBLIC_TITLE = "Gotcha is working on a task…"

        private val _running = MutableStateFlow<RunningChat?>(null)

        /** The run the service is (or should be) keeping alive; null when none. */
        val running: StateFlow<RunningChat?> = _running.asStateFlow()

        private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Emits when the user taps Stop on the ongoing notification. */
        val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

        /**
         * Starts keeping the process alive for [chat]. Call it as the run starts,
         * while Gotcha is still in the foreground: Android refuses to start a
         * foreground service from the background, and then the run just goes on
         * without one, as it did before.
         */
        fun start(context: Context, chat: RunningChat) {
            _running.value = chat
            try {
                ContextCompat.startForegroundService(context, Intent(context, ChatRunService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is one of these.
                Log.w(TAG, "Could not keep the run alive in the background: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not keep the run alive in the background: ${e.message}")
            }
        }

        /**
         * Updates the notification of the run in progress, e.g. once its chat has a
         * title. Never starts the service: a run that has none keeps going without.
         */
        fun update(chat: RunningChat) {
            _running.update { current -> if (current?.sessionId == chat.sessionId) chat else current }
        }

        /** The run has ended: the service removes its notification and stops. */
        fun stop() {
            _running.value = null
        }

        internal fun title(chat: RunningChat?): String {
            val name = chat?.chatTitle?.takeIf { it.isNotBlank() } ?: return PUBLIC_TITLE
            // A name already cut short with "…" doesn't need a second one.
            return if (name.endsWith("…")) "Gotcha is working on “$name”" else "Gotcha is working on “$name”…"
        }

        internal fun buildNotification(context: Context, chat: RunningChat?): android.app.Notification {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.gotcha.R.drawable.ic_notification)
                .setContentTitle(title(chat))
                .setContentText("Tap to follow along. You can keep using your phone.")
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                // The chat's name can be personal: the lock screen gets a generic line only.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(com.gotcha.R.drawable.ic_notification)
                        .setContentTitle(PUBLIC_TITLE)
                        .build()
                )
                .addAction(0, "Stop", stopIntent(context))
            chat?.let { builder.setContentIntent(openChatIntent(context, it.sessionId)) }
            return builder.build()
        }

        private fun openChatIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(context, com.gotcha.MainActivity::class.java).apply {
                action = ChatCompletionNotifier.ACTION_OPEN_CHAT_SESSION
                putExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            NOTIFICATION_ID,
            Intent(context, ChatRunService::class.java).setAction(ACTION_STOP_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Task in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Gotcha works on a task, so Android keeps it running " +
                        "when you switch to another app"
                    setShowBadge(false)
                }
            )
        }
    }
}
