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

    /** How soon [armHandoff]'s alarm fires when a pass was cut short. */
    const val HANDOFF_MS = 60L * 1000L

    private const val ALARM_REQUEST = 0
    private const val HANDOFF_REQUEST = 1

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

    /**
     * Arms a second alarm a little ahead, for a firing whose work may outlive
     * the receiver: if Android kills the process before the pass records its
     * runs and re-arms, this one fires and the pass is made again. Cheap, as it
     * reads nothing, so it is set before any of the slow work starts.
     */
    fun armHandoff(context: Context, now: Long = System.currentTimeMillis()) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = now + HANDOFF_MS
        val pending = fireIntent(context, HANDOFF_REQUEST)
        try {
            if (canRunUnattended(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused; using an inexact handoff", e)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** The pass finished: the handoff armed by [armHandoff] is not needed. */
    fun cancelHandoff(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(fireIntent(context, HANDOFF_REQUEST))
    }

    /** True when a task can start its run with nobody there: exact alarms are allowed. */
    fun canRunUnattended(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun fireIntent(context: Context, request: Int = ALARM_REQUEST): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, ScheduledTaskReceiver::class.java).setAction(ScheduledTaskReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
