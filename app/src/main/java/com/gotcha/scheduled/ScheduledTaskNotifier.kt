package com.gotcha.scheduled

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gotcha.MainActivity
import com.gotcha.R
import com.gotcha.notifications.ChatCompletionNotifier

/**
 * The "tap to run" notification of a task Android would not let run on its own
 * (issue #132). The tap opens Gotcha, which runs the task there and shows its
 * chat: with Gotcha in front, its keep-alive service may start again.
 */
class ScheduledTaskNotifier(private val context: Context) {

    @Suppress("MissingPermission") // canPost() checks it.
    fun postNeedsTap(task: ScheduledTask) {
        if (!ChatCompletionNotifier(context).canPost()) return
        ensureChannel()
        val tap = PendingIntent.getActivity(
            context,
            notificationId(task.id),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_RUN_TASK_ID, task.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = context.getString(R.string.scheduled_needs_tap_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.scheduled_needs_tap_title, task.name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tap)
            // The task name can be personal: the lock screen gets a generic line.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.scheduled_needs_tap_public_title))
                    .build()
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(task.id), notification)
    }

    fun cancel(taskId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_scheduled_tasks),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_scheduled_tasks_description) }
        )
    }

    companion object {
        const val CHANNEL_ID = "gotcha_scheduled_tasks"

        /** On the tap intent: the task MainActivity should run. */
        const val EXTRA_RUN_TASK_ID = "com.gotcha.scheduled.RUN_TASK_ID"

        /** One slot per task, clear of the chat slots and Gotcha's other fixed ids. */
        internal fun notificationId(taskId: String): Int = 0x5C00_0000 or (taskId.hashCode() and 0x00FF_FFFF)
    }
}
