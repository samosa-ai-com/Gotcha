package com.gotcha.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Keeps one alarm armed for the scheduled task due soonest (issue #132), and
 * re-arms it after every change, every firing, and after a reboot, an app
 * update or a clock change (through `NotificationRescheduleReceiver`).
 *
 * The alarm is exact when Android allows it. That also matters for the run
 * itself: an exact alarm is what lets Gotcha start its keep-alive service from
 * the background on Android 12 and later. Without the permission, the alarm
 * goes off within a window instead, and the task asks for a tap
 * ([TaskRunStatus.NEEDS_TAP]) rather than starting a run Android would freeze.
 */
object ScheduledTaskScheduler {

    private const val TAG = "ScheduledTasks"

    /** How late Android may deliver the alarm when exact alarms are not allowed. */
    private const val WINDOW_MS = 10L * 60L * 1000L

    /** Arms the alarm for the soonest task, or cancels it when nothing is due. Reads prefs: off the main thread. */
    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = fireIntent(context)
        val at = ScheduledTaskPlanner.nextAlarm(ScheduledTaskStore(context).all())
        if (at == null) {
            alarm.cancel(pending)
            return
        }
        try {
            if (canRunUnattended(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
            }
        } catch (e: SecurityException) {
            // The exact-alarm grant was withdrawn between the check and the call.
            Log.w(TAG, "Exact alarm refused; using a window", e)
            alarm.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
        }
    }

    /** True when a task can start its run with nobody there: exact alarms are allowed. */
    fun canRunUnattended(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun fireIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ScheduledTaskReceiver::class.java).setAction(ScheduledTaskReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
