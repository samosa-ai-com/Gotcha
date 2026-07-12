package com.gotcha.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager registrations do not survive a reboot, but the alarm/timer
 * records in SharedPreferences do. Re-registers everything after boot so
 * stored alarms actually ring.
 */
class AlarmBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmTool(context).rescheduleAll()
        }
    }
}
