package com.gotcha.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return

        createChannel(context)

        when (type) {
            "alarm" -> handleAlarm(context, intent)
            "timer" -> handleTimer(context, intent)
        }
    }

    private fun handleAlarm(context: Context, intent: Intent) {
        val id = intent.getLongExtra("alarm_id", -1)
        val label = intent.getStringExtra("label") ?: "Alarm"
        val hour = intent.getIntExtra("hour", 0)
        val minute = intent.getIntExtra("minute", 0)
        val vibrate = intent.getBooleanExtra("vibrate", true)
        val days = intent.getIntArrayExtra("days")

        showNotification(context, "alarm_$id", label, 1000 + id.toInt())

        // Reschedule if recurring
        if (days != null && days.isNotEmpty()) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val currentDow = now.get(Calendar.DAY_OF_WEEK)
            for (offset in 1..7) {
                val check = currentDow + offset
                val dow = if (check > 7) check - 7 else check
                if (dow in days.toSet()) {
                    target.add(Calendar.DAY_OF_MONTH, offset)
                    break
                }
            }
            val rescheduleIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("type", "alarm")
                putExtra("alarm_id", id)
                putExtra("label", label)
                putExtra("hour", hour)
                putExtra("minute", minute)
                putExtra("vibrate", vibrate)
                putExtra("days", days)
            }
            val pi = PendingIntent.getBroadcast(context, id.toInt(), rescheduleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAlarmClock(AlarmManager.AlarmClockInfo(target.timeInMillis, pi), pi)
        }
    }

    private fun handleTimer(context: Context, intent: Intent) {
        val id = intent.getLongExtra("timer_id", -1)
        val label = intent.getStringExtra("label") ?: "Timer"
        showNotification(context, "timer_$id", "$label time's up!", 2000 + id.toInt())
    }

    private fun showNotification(context: Context, tag: String, text: String, notifyId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Gotcha")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(tag, notifyId, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Alarms & Timers", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "gotcha_alarms"
    }
}
