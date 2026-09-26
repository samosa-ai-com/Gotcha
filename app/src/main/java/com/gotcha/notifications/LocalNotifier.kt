package com.gotcha.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts Gotcha's proactive notifications (issues #100, #101) and files each in
 * the inbox. Daily tips keep their own low-importance channel; reminders
 * (unfinished chats, routines, quiet spells) share another, so either can be
 * silenced from Android's settings without the other.
 *
 * One slot per category: an unread reminder is replaced by the next of its
 * kind rather than stacking beside it.
 */
class LocalNotifier(private val context: Context) {

    /** True when Android will actually show what [post] posts. */
    fun canPost(): Boolean = ChatCompletionNotifier(context).canPost()

    @Suppress("MissingPermission") // canPost() checks it.
    fun post(candidate: LocalCandidate, store: LocalNotificationStore): Boolean {
        if (!canPost()) return false
        val tip = candidate.category == NotificationCategory.DAILY_TIP
        val channel = if (tip) TIP_CHANNEL_ID else REMINDER_CHANNEL_ID
        ensureChannels()
        val entryId = store.addEntry(candidate.category, candidate.title, candidate.body, candidate.target)
        val notifyId = notificationId(candidate.category)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.gotcha.R.drawable.ic_notification)
            .setContentTitle(candidate.title)
            .setContentText(candidate.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(candidate.body))
            .setCategory(if (tip) NotificationCompat.CATEGORY_RECOMMENDATION else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(candidate.target, entryId, notifyId))
        if (!tip) {
            // A reminder may name a chat: the lock screen gets a generic line only.
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, channel)
                        .setSmallIcon(com.gotcha.R.drawable.ic_notification)
                        .setContentTitle(PUBLIC_TITLE)
                        .build()
                )
        }
        NotificationManagerCompat.from(context).notify(notifyId, builder.build())
        return true
    }

    private fun tapIntent(target: NotificationTarget, entryId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, com.gotcha.MainActivity::class.java).apply {
            action = ACTION_OPEN_LOCAL_NOTIFICATION
            putExtra(LocalNotificationStore.EXTRA_INBOX_ENTRY_ID, entryId)
            putTargetExtras(target)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannels() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(TIP_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(TIP_CHANNEL_ID, "Daily tips", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "One thing to try with Gotcha each day"
                }
            )
        }
        if (mgr.getNotificationChannel(REMINDER_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Unfinished chats, routines that are due, and a nudge after a quiet spell"
                }
            )
        }
    }

    companion object {
        const val TIP_CHANNEL_ID = "gotcha_daily_tips"
        const val REMINDER_CHANNEL_ID = "gotcha_reminders"
        const val ACTION_OPEN_LOCAL_NOTIFICATION = "com.gotcha.ACTION_OPEN_LOCAL_NOTIFICATION"
        const val EXTRA_DRAFT_PROMPT = "com.gotcha.DRAFT_PROMPT"
        const val EXTRA_DRAFT_MODE = "com.gotcha.DRAFT_MODE"
        private const val PUBLIC_TITLE = "Gotcha has a suggestion"

        internal fun notificationId(category: NotificationCategory): Int = 0x7100 + category.ordinal

        /** The extras that tell MainActivity where a tap should go. */
        fun Intent.putTargetExtras(target: NotificationTarget): Intent = apply {
            when (target) {
                is NotificationTarget.Chat -> putExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID, target.sessionId)
                is NotificationTarget.Draft -> {
                    putExtra(EXTRA_DRAFT_PROMPT, target.prompt)
                    putExtra(EXTRA_DRAFT_MODE, target.mode.name)
                }
                NotificationTarget.Home -> Unit
            }
        }
    }
}
