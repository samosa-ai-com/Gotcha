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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gotcha.R
import com.gotcha.i18n.StringLookup
import com.gotcha.i18n.stringLookup
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
 *
 * The notification sits in its chat's slot ([ChatCompletionNotifier.notificationId]).
 * A run that ends in the background hands its task-finished notification to
 * [finish], and the service posts it into that slot as it leaves the foreground,
 * so the user sees one notification turn from "working" into "done" (issue #115).
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
                        val startId = lastStartId
                        // Stopped only once the finished notification is up: the
                        // service keeps the process from being reclaimed until then.
                        // stopSelf(startId) does nothing if a newer start arrived meanwhile.
                        leaveForeground { stopSelf(startId) }
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
        // Normally already done by the follower; the system can stop the service before it runs.
        leaveForeground()
        scope?.cancel()
        scope = null
        follower = null
        super.onDestroy()
    }

    private fun goForeground(chat: RunningChat?) {
        ensureChannel(this)
        val id = chat?.let { ChatCompletionNotifier.notificationId(it.sessionId) } ?: NOTIFICATION_ID
        // A new run in another chat while the service is still up: the last
        // run's slot is let go first, or it would be left behind.
        if (foregroundId != null && foregroundId != id) leaveForeground()
        val notification = buildNotification(this, chat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
        foregroundId = id
        // Same slot, new run: what the last run left to post is out of date.
        completion = null
    }

    /**
     * Gives up the foreground. With a [completion] waiting, the ongoing
     * notification is detached and replaced by it in place; otherwise removed.
     * Detached first, so the completion is posted as a plain notification and
     * keeps no foreground-service flags. [then] runs once that is done, which
     * for a completion can be up to two seconds later.
     */
    private fun leaveForeground(then: () -> Unit = {}) {
        val id = foregroundId
        val pending = completion
        foregroundId = null
        completion = null
        if (id != null && pending != null) {
            stopForeground(STOP_FOREGROUND_DETACH)
            postWhenDetached(applicationContext, id, pending, DETACH_POLLS, then)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            then()
        }
    }

    companion object {
        private const val TAG = "ChatRunService"
        const val CHANNEL_ID = "gotcha_chat_running"
        const val ACTION_STOP_RUN = "com.gotcha.ACTION_STOP_CHAT_RUN"
        internal const val NOTIFICATION_ID = 0x4A77_E8

        private val _running = MutableStateFlow<RunningChat?>(null)

        private val mainHandler = Handler(Looper.getMainLooper())
        private const val DETACH_POLLS = 40
        private const val DETACH_POLL_MS = 50L

        // Main thread only, like the follower and the runner's cleanup.
        /** The notification id the service holds the foreground with; null when it holds none. */
        private var foregroundId: Int? = null

        /** The task-finished notification to put in [foregroundId]'s slot as the service leaves the foreground. */
        private var completion: Notification? = null

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

        /**
         * Posts [notification] in slot [id] once Android has detached the ongoing
         * one there. Android does that later, by posting its own copy of the
         * ongoing notification again without the foreground-service flag; posted
         * before that, the completion would be overwritten by it (seen on Android 16).
         * Gives up waiting after about two seconds, and drops [notification] if a
         * new run has taken the slot meanwhile. [then] runs after, either way.
         */
        @Suppress("MissingPermission") // ChatCompletionNotifier.build checked it.
        private fun postWhenDetached(
            context: Context,
            id: Int,
            notification: Notification,
            pollsLeft: Int,
            then: () -> Unit
        ) {
            if (foregroundId == id) {
                then()
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            val stillForeground = manager?.activeNotifications.orEmpty().any {
                it.id == id && it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            }
            if (stillForeground && pollsLeft > 0) {
                mainHandler.postDelayed(
                    { postWhenDetached(context, id, notification, pollsLeft - 1, then) },
                    DETACH_POLL_MS
                )
                return
            }
            NotificationManagerCompat.from(context).notify(id, notification)
            then()
        }

        /** The run has ended: the service removes its notification and stops. */
        fun stop() {
            _running.value = null
        }

        /**
         * The run in [sessionId] has ended and [notification] reports it. When the
         * service holds that chat's slot, the notification takes the ongoing one's
         * place as the service stops; otherwise (the service never got to the
         * foreground) it is posted as is.
         */
        @Suppress("MissingPermission") // ChatCompletionNotifier.build checked it.
        fun finish(context: Context, sessionId: String, notification: Notification) {
            val id = ChatCompletionNotifier.notificationId(sessionId)
            if (_running.value?.sessionId == sessionId && foregroundId == id) {
                completion = notification
            } else {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
            stop()
        }

        internal fun title(chat: RunningChat?, text: StringLookup): String {
            val name = chat?.chatTitle?.takeIf { it.isNotBlank() } ?: return text(R.string.run_public_title)
            // A name already cut short with "…" doesn't need a second one.
            return text(if (name.endsWith("…")) R.string.run_title_cut else R.string.run_title, name)
        }

        internal fun buildNotification(context: Context, chat: RunningChat?): Notification {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.gotcha.R.drawable.ic_notification_running)
                .setContentTitle(title(chat, context.stringLookup()))
                .setContentText(context.getString(R.string.run_body))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                // The chat's name can be personal: the lock screen gets a generic line only.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(com.gotcha.R.drawable.ic_notification_running)
                        .setContentTitle(context.getString(R.string.run_public_title))
                        .build()
                )
                .addAction(0, context.getString(R.string.action_stop), stopIntent(context))
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
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_run),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.channel_run_description)
                    setShowBadge(false)
                }
            )
        }
    }
}
