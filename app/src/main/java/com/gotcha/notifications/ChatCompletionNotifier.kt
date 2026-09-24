package com.gotcha.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gotcha.data.CompletionPreview

/** How a chat run ended, as the task-finished notification reports it. */
enum class RunOutcome { DONE, FAILED, STOPPED }

/**
 * Posts the "task finished" notification for a chat run that ended while Gotcha
 * was in the background (issue #97). One slot per chat: a newer run in the same
 * chat replaces the older notification instead of stacking beside it, and
 * opening the chat clears it.
 *
 * Kept apart from [NotificationDispatcher], which owns server messages: the two
 * have separate channels so either can be silenced without the other.
 */
class ChatCompletionNotifier(private val context: Context) {

    /** True when Android will actually show what [notify] posts. */
    fun canPost(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Posts the notification for [sessionId]. [reply] is the run's last assistant
     * message (or error text); [preview] decides how much of it is shown.
     * With [named] false — a chat kept out of notifications, or chats not to be
     * mentioned at all (issue #100) — neither the chat nor the reply appears.
     * [inboxEntryId] is the inbox entry a tap marks read. Returns false when
     * nothing was posted.
     */
    @Suppress("MissingPermission") // canPost() checks it.
    fun notify(
        sessionId: String,
        chatTitle: String,
        outcome: RunOutcome,
        reply: String?,
        preview: CompletionPreview,
        named: Boolean = true,
        inboxEntryId: String? = null
    ): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val notifyId = notificationId(sessionId)
        val title = if (named) notificationTitle(chatTitle, outcome) else anonymousTitle(outcome)
        val shown = if (named) preview else CompletionPreview.NONE
        val body = previewText(reply, shown) ?: defaultBody(outcome)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.gotcha.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Replies can be personal: the lock screen gets a generic line only.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.gotcha.R.drawable.ic_notification)
                    .setContentTitle(PUBLIC_TITLE)
                    .build()
            )
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(sessionId, notifyId, inboxEntryId))
        if (shown == CompletionPreview.FULL) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        NotificationManagerCompat.from(context).notify(notifyId, builder.build())
        return true
    }

    /** Clears [sessionId]'s notification, e.g. once the user has opened that chat. */
    fun cancel(sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(sessionId))
    }

    private fun openChatIntent(sessionId: String, requestCode: Int, inboxEntryId: String?): PendingIntent {
        val intent = Intent(context, com.gotcha.MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT_SESSION
            putExtra(EXTRA_OPEN_SESSION_ID, sessionId)
            inboxEntryId?.let { putExtra(LocalNotificationStore.EXTRA_INBOX_ENTRY_ID, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Task finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a chat task finishes while you are in another app"
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "gotcha_chat_completion"
        const val ACTION_OPEN_CHAT_SESSION = "com.gotcha.ACTION_OPEN_CHAT_SESSION"
        const val EXTRA_OPEN_SESSION_ID = "com.gotcha.OPEN_SESSION_ID"
        private const val PUBLIC_TITLE = "Gotcha finished a task"
        private const val SHORT_PREVIEW_CHARS = 140

        /** Stable per chat, so a later run's notification replaces the earlier one. */
        internal fun notificationId(sessionId: String): Int = "chat:$sessionId".hashCode() and 0x7FFF_FFFF

        internal fun notificationTitle(chatTitle: String, outcome: RunOutcome): String {
            val chat = chatTitle.ifBlank { "Chat" }
            return when (outcome) {
                RunOutcome.DONE -> "Done: $chat"
                RunOutcome.FAILED -> "Failed: $chat"
                RunOutcome.STOPPED -> "Stopped: $chat"
            }
        }

        /** The title when the chat may not be named. */
        internal fun anonymousTitle(outcome: RunOutcome): String = when (outcome) {
            RunOutcome.DONE -> "Task finished"
            RunOutcome.FAILED -> "Task failed"
            RunOutcome.STOPPED -> "Task stopped"
        }

        internal fun defaultBody(outcome: RunOutcome): String = when (outcome) {
            RunOutcome.DONE -> "Gotcha finished your task. Tap to see the reply."
            RunOutcome.FAILED -> "Gotcha couldn't finish your task. Tap to see what happened."
            RunOutcome.STOPPED -> "The task was stopped. Tap to open the chat."
        }

        /**
         * The reply as the notification shows it, or null when [preview] hides it
         * or there is nothing to show. Replies are Markdown; the notification is
         * plain text, so the common markers are dropped.
         */
        internal fun previewText(reply: String?, preview: CompletionPreview): String? {
            if (preview == CompletionPreview.NONE) return null
            val plain = reply?.let(::plainText)?.takeIf { it.isNotBlank() } ?: return null
            if (preview == CompletionPreview.FULL) return plain
            // A collapsed notification shows one line, so a short preview flows as one.
            val oneLine = plain.replace('\n', ' ')
            if (oneLine.length <= SHORT_PREVIEW_CHARS) return oneLine
            return oneLine.take(SHORT_PREVIEW_CHARS).trimEnd() + "…"
        }

        private fun plainText(markdown: String): String =
            markdown.lines()
                .map { it.trim() }
                // Dividers (---, ***) carry nothing in plain text.
                .filterNot { it.matches(Regex("[-*_]{3,}")) }
                .map { line ->
                    line.replace(Regex("^#{1,6}\\s+"), "")
                        .replace(Regex("^[-*+]\\s+"), "• ")
                        .replace(Regex("\\*\\*|__|`"), "")
                }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
    }
}
