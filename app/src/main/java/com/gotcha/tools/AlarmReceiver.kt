package com.gotcha.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return

        createChannels(context)

        when (type) {
            "alarm" -> handleAlarm(context, intent)
            "timer" -> handleTimer(context, intent)
        }
    }

    private fun handleAlarm(context: Context, intent: Intent) {
        val id = intent.getLongExtra("alarm_id", -1)
        val label = intent.getStringExtra("label") ?: "Alarm"
        val vibrate = intent.getBooleanExtra("vibrate", true)

        showNotification(context, "alarm_$id", label, 1000 + id.toInt(), vibrate)

        // Reschedules recurring alarms and removes fired one-shot alarms from storage.
        AlarmTool(context).onAlarmFired(id)
    }

    private fun handleTimer(context: Context, intent: Intent) {
        val id = intent.getLongExtra("timer_id", -1)
        val label = intent.getStringExtra("label") ?: "Timer"
        showNotification(context, "timer_$id", "$label time's up!", 2000 + id.toInt(), vibrate = true)
        AlarmTool(context).onTimerFired(id)
    }

    private fun showNotification(context: Context, tag: String, text: String, notifyId: Int, vibrate: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = Intent(context, com.gotcha.MainActivity::class.java).apply {
            action = com.gotcha.notifications.NotificationDispatcher.ACTION_SHOW_NOTIFICATION
            putExtra(com.gotcha.notifications.NotificationDispatcher.EXTRA_NOTIFICATION_ID, notifyId)
            putExtra(com.gotcha.notifications.NotificationDispatcher.EXTRA_NOTIFICATION_TITLE, "Gotcha")
            putExtra(com.gotcha.notifications.NotificationDispatcher.EXTRA_NOTIFICATION_BODY, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = android.app.PendingIntent.getActivity(
            context,
            notifyId,
            tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, if (vibrate) CHANNEL_ID else CHANNEL_ID_SILENT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Gotcha")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        nm.notify(tag, notifyId, notification)
    }

    private fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alarms & Timers", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_SILENT,
                "Alarms & Timers (no vibration)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "gotcha_alarms"
        private const val CHANNEL_ID_SILENT = "gotcha_alarms_silent"
    }
}
