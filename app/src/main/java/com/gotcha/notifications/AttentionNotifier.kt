package com.gotcha.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gotcha.data.CompletionPreview

/** What a paused run is waiting on. */
enum class AttentionKind { QUESTION, CONFIRMATION }

/**
 * Posts "Gotcha needs your answer" while a run is paused on the user — a
 * question from the agent or an "Allow these actions?" confirmation — and they
 * are not in Gotcha to see the dialog (issue #108). Without it the run just
 * sits there: the dialog is the only other sign it is waiting.
 *
 * Only one run goes at a time, so there is one slot: a newer ask replaces the
 * older one. Its own high-importance channel, apart from task-finished
 * notifications, because the run is blocked until the user answers.
 */
class AttentionNotifier(private val context: Context) {

    /** True when Android will actually show what [notify] posts. */
    fun canPost(): Boolean = ChatCompletionNotifier(context).canPost()

    /**
     * Posts the notification for the run in [sessionId]. [question] is the
     * agent's question (Markdown), shown as [preview] allows. With [named] false
     * (issue #100) neither the chat nor the question appears. Returns false when
     * nothing was posted.
     */
    @Suppress("MissingPermission") // canPost() checks it.
    fun notify(
        sessionId: String,
        kind: AttentionKind,
        chatTitle: String,
        question: String?,
        preview: CompletionPreview,
        named: Boolean = true
    ): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val shown = if (named && kind == AttentionKind.QUESTION) preview else CompletionPreview.NONE
        val body = questionPreview(question, shown) ?: defaultBody(kind)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.gotcha.R.drawable.ic_notification)
            .setContentTitle(title(kind))
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // A question can be personal: the lock screen gets a generic line only.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.gotcha.R.drawable.ic_notification)
                    .setContentTitle(PUBLIC_TITLE)
                    .build()
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(sessionId))
        if (named) builder.setSubText(chatTitle.ifBlank { null })
        if (shown == CompletionPreview.FULL) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        return true
    }

    /** Clears the notification: the ask was answered, skipped, timed out or the run stopped. */
    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun openChatIntent(sessionId: String): PendingIntent {
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

    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Waiting for your answer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When Gotcha has paused a task to ask you something while you are in another app"
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "gotcha_needs_input"
        private const val NOTIFICATION_ID = 0x4A77_E7
        private const val PUBLIC_TITLE = "Gotcha is waiting for you"
        private val FENCE_LINE = Regex("^\\s*(```|~~~).*$")

        internal fun title(kind: AttentionKind): String = when (kind) {
            AttentionKind.QUESTION -> "Gotcha has a question"
            AttentionKind.CONFIRMATION -> "Gotcha is waiting for your OK"
        }

        internal fun defaultBody(kind: AttentionKind): String = when (kind) {
            AttentionKind.QUESTION -> "Your task is paused until you answer. Tap to open it."
            AttentionKind.CONFIRMATION -> "Your task is paused until you allow or deny its next step. Tap to open it."
        }

        /**
         * The question as the notification shows it, or null when [preview]
         * hides it. Fence lines are dropped so a command inside shows as itself.
         */
        internal fun questionPreview(question: String?, preview: CompletionPreview): String? {
            val text = question?.lines()?.filterNot { FENCE_LINE.matches(it) }?.joinToString("\n")
            return ChatCompletionNotifier.previewText(text, preview)
        }
    }
}
