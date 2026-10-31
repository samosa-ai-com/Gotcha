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

    /**
     * A task that fired while another run holds Gotcha is queued, not lost.
     * One quiet slot per task, updated in place on every recheck: same id and
     * onlyAlertOnce, so waiting never buzzes. Cancelled when the run starts,
     * gives up, or the task is paused, edited or deleted.
     *
     * The tap only opens the app: its intent carries a distinct action so the
     * RUN_TASK_ID extra of a needs-tap PendingIntent sharing this slot can
     * never leak onto it and start a run on tap.
     */
    @Suppress("MissingPermission") // canPost() checks it.
    fun postWaiting(task: ScheduledTask) {
        if (!ChatCompletionNotifier(context).canPost()) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            notificationId(task.id),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_WAITING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = context.getString(R.string.scheduled_waiting_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.scheduled_waiting_title, task.name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
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

        /** The tap of a waiting notification only opens the app; see [postWaiting]. */
        const val ACTION_OPEN_WAITING = "com.gotcha.scheduled.OPEN_WAITING"

        /** One slot per task, clear of the chat slots and Gotcha's other fixed ids. */
        internal fun notificationId(taskId: String): Int = 0x5C00_0000 or (taskId.hashCode() and 0x00FF_FFFF)
    }
}

/**
 * Keeps a busy wait's notification true after a save: a wait the save cleared
 * must not keep it, a surviving wait is re-posted so its title stays fresh.
 * [hadWait] is the wait state before the save, read inside the store update
 * so a wait starting mid-save cannot slip through. The task is re-read
 * before acting: a run that moved it after the save owns the notification
 * from there, and the stale snapshot could repost a cleared wait or cancel a
 * new one. Untouched otherwise, so a tap-to-run prompt sharing the slot
 * survives an unrelated edit; pausing always dismisses.
 */
fun syncWaitingNotification(
    notifier: ScheduledTaskNotifier,
    store: ScheduledTaskStore,
    hadWait: Boolean,
    saved: ScheduledTask
) {
    val current = store.get(saved.id) ?: saved
    val hasWait = current.enabled && current.busySince != null && current.retryAt != null
    when {
        !current.enabled || (hadWait && !hasWait) -> notifier.cancel(current.id)
        hadWait && hasWait -> notifier.postWaiting(current)
    }
}
