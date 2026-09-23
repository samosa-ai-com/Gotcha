package com.gotcha.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts the daily tip (issue #101): one use case a day, and a tap opens a new
 * chat with its prompt in the composer. One slot, so a tip left unread is
 * replaced by the next day's rather than stacking beside it.
 *
 * Its own low-importance channel, so tips can be silenced from Android's
 * settings without touching task-finished or server notifications.
 */
class DailyTipNotifier(private val context: Context) {

    /** True when Android will actually show what [notify] posts. */
    fun canPost(): Boolean = ChatCompletionNotifier(context).canPost()

    @Suppress("MissingPermission") // canPost() checks it.
    fun notify(tip: DailyTip): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.gotcha.R.drawable.ic_notification)
            .setContentTitle(tip.title)
            .setContentText(tip.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(tip.body))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openTipIntent(tip))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun openTipIntent(tip: DailyTip): PendingIntent {
        val intent = Intent(context, com.gotcha.MainActivity::class.java).apply {
            action = ACTION_OPEN_DAILY_TIP
            putExtra(EXTRA_DAILY_TIP_ID, tip.id)
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
            NotificationChannel(CHANNEL_ID, "Daily tips", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One thing to try with Gotcha each day"
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "gotcha_daily_tips"
        const val ACTION_OPEN_DAILY_TIP = "com.gotcha.ACTION_OPEN_DAILY_TIP"
        const val EXTRA_DAILY_TIP_ID = "com.gotcha.DAILY_TIP_ID"
        private const val NOTIFICATION_ID = 0x7101
    }
}
