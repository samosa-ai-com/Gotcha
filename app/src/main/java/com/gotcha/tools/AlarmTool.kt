package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

class AlarmTool(private val context: Context) {

    /** Set a clock alarm via the AlarmClock intent (needs SET_ALARM, a normal permission). */
    fun setAlarm(hour: Int, minute: Int, message: String?): ToolResult {
        if (hour !in 0..23) return ToolResult.error("Hour must be between 0 and 23 (got $hour).")
        if (minute !in 0..59) return ToolResult.error("Minute must be between 0 and 59 (got $minute).")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Set an alarm for %02d:%02d.".format(hour, minute), "set an alarm")
    }

    /** Start a countdown timer via the AlarmClock intent. */
    fun setTimer(seconds: Int, message: String?): ToolResult {
        if (seconds < 1) return ToolResult.error("Timer length must be at least 1 second.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "Started a ${seconds}s timer.", "start a timer")
    }

    private fun launch(intent: Intent, success: String, action: String): ToolResult {
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No clock app on this device can $action.")
            }
            context.startActivity(intent)
            ToolResult.ok(success)
        } catch (e: Exception) {
            ToolResult.error("Could not $action: ${e.message}")
        }
    }
}
