package com.gotcha.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.util.Calendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AlarmTool(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gotcha_alarms", Context.MODE_PRIVATE)
    private var nextId: Long
        get() = prefs.getLong("next_id", 1)
        set(v) = prefs.edit().putLong("next_id", v).apply()

    private val dayNames = mapOf(
        "sun" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY,
        "mon" to Calendar.MONDAY, "monday" to Calendar.MONDAY,
        "tue" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY
    )
    private val dayNumbers = dayNames.values.toSet()

    fun setAlarm(hour: Int, minute: Int, message: String? = null, days: List<String>? = null, vibrate: Boolean? = null): ToolResult {
        if (hour !in 0..23) return ToolResult.error("Hour must be 0-23.")
        if (minute !in 0..59) return ToolResult.error("Minute must be 0-59.")
        exactAlarmError()?.let { return it }
        val dayInts = parseDays(days)
        val id = nextId++
        val label = message?.takeIf { it.isNotBlank() }

        val record = AlarmRecord(id, hour, minute, dayInts, label, vibrate ?: true)
        try {
            scheduleAlarm(record)
        } catch (e: SecurityException) {
            return ToolResult.error(exactAlarmDeniedMessage(e))
        }
        saveAlarm(record)

        val dayStr = if (dayInts.isNotEmpty()) " (${days?.joinToString(",")})" else ""
        val extra = buildString {
            if (dayInts.isNotEmpty()) append(" repeating$dayStr")
            if (vibrate == false) append(" silent")
        }
        return ToolResult.ok("Set alarm '$label' for %02d:%02d$extra (id=$id).".format(hour, minute))
    }

    fun setTimer(seconds: Int, message: String? = null, hours: Int? = null, minutes: Int? = null): ToolResult {
        val totalSeconds = seconds + (hours ?: 0) * 3600 + (minutes ?: 0) * 60
        if (totalSeconds < 1) return ToolResult.error("Timer length must be at least 1 second.")
        exactAlarmError()?.let { return it }
        val id = nextId++
        val label = message?.takeIf { it.isNotBlank() }

        val record = TimerRecord(id, totalSeconds, label)
        try {
            scheduleTimer(record)
        } catch (e: SecurityException) {
            return ToolResult.error(exactAlarmDeniedMessage(e))
        }
        saveTimer(record)

        val extra = buildString {
            if (hours != null && hours > 0) append(" ${hours}h")
            if (minutes != null && minutes > 0) append(" ${minutes}m")
            if (seconds > 0) append(" ${seconds}s")
        }
        return ToolResult.ok("Started ${label ?: "a timer"} for${extra.trimStart()} (id=$id).")
    }

    fun listAlarms(): ToolResult {
        val alarms = loadAlarms()
        if (alarms.isEmpty()) return ToolResult.ok("No alarms set.")
        val sb = StringBuilder()
        alarms.forEach { a ->
            if (!isScheduled(alarmRequestCode(a.id))) {
                // Registration was lost (reboot, force-stop) — re-register from storage.
                try { scheduleAlarm(a) } catch (_: SecurityException) {}
            }
            val dayStr = if (a.days.isNotEmpty()) {
                a.days.mapNotNull { e -> dayNames.entries.firstOrNull { it.value == e }?.key?.take(3) }.joinToString(",")
            } else "once"
            sb.append("- $dayStr %02d:%02d".format(a.hour, a.minute))
            if (a.label != null) sb.append("  ${a.label}")
            sb.append("  (id=${a.id})\n")
        }
        return ToolResult.ok(sb.trimEnd().toString())
    }

    fun listTimers(): ToolResult {
        val all = loadTimers()
        val now = System.currentTimeMillis()
        val timers = all.filter { it.triggerAt > now }
        if (timers.size != all.size) saveTimers(timers)
        if (timers.isEmpty()) return ToolResult.ok("No timers running.")
        val sb = StringBuilder()
        timers.forEach { t ->
            val remaining = (t.triggerAt - now) / 1000
            sb.append("- ${t.label ?: "Timer"}: ${remaining / 60}m ${remaining % 60}s remaining (id=${t.id})\n")
        }
        return ToolResult.ok(sb.trimEnd().toString())
    }

    fun editAlarm(id: Long, hour: Int? = null, minute: Int? = null, message: String? = null, days: List<String>? = null, vibrate: Boolean? = null): ToolResult {
        val alarms = loadAlarmsMutable()
        val idx = alarms.indexOfFirst { it.id == id }
        if (idx == -1) return ToolResult.error("Alarm $id not found.")
        exactAlarmError()?.let { return it }

        val old = alarms[idx]
        val record = AlarmRecord(
            id = id,
            hour = hour ?: old.hour,
            minute = minute ?: old.minute,
            days = if (days != null) parseDays(days) else old.days,
            label = message ?: old.label,
            vibrate = vibrate ?: old.vibrate
        )
        try {
            scheduleAlarm(record)
        } catch (e: SecurityException) {
            return ToolResult.error(exactAlarmDeniedMessage(e))
        }
        alarms[idx] = record
        saveAlarms(alarms)
        return ToolResult.ok("Updated alarm $id.")
    }

    fun deleteAlarm(id: Long): ToolResult {
        val alarms = loadAlarms()
        if (alarms.none { it.id == id }) return ToolResult.error("Alarm $id not found.")
        val a = alarms.first { it.id == id }
        val label = a.label ?: "Alarm at %02d:%02d".format(a.hour, a.minute)
        return ToolResult.ok("CONFIRM_DELETE_ALARM:$id:$label")
    }

    fun doDeleteAlarm(id: Long): ToolResult {
        val alarms = loadAlarmsMutable()
        if (alarms.none { it.id == id }) return ToolResult.error("Alarm $id not found.")
        cancelPendingIntent(alarmRequestCode(id))
        saveAlarms(alarms.filter { it.id != id })
        return ToolResult.ok("Deleted alarm $id.")
    }

    fun deleteTimer(id: Long): ToolResult {
        val timers = loadTimers()
        if (timers.none { it.id == id }) return ToolResult.error("Timer $id not found.")
        val t = timers.first { it.id == id }
        val label = t.label ?: "Timer"
        return ToolResult.ok("CONFIRM_DELETE_TIMER:$id:$label")
    }

    fun doDeleteTimer(id: Long): ToolResult {
        val timers = loadTimersMutable()
        if (timers.none { it.id == id }) return ToolResult.error("Timer $id not found.")
        cancelPendingIntent(timerRequestCode(id))
        saveTimers(timers.filter { it.id != id })
        return ToolResult.ok("Deleted timer $id.")
    }

    // ---- firing callbacks (from AlarmReceiver) ----

    /** Reschedules a recurring alarm for its next occurrence, or removes a
     *  one-shot alarm from storage so list_alarms stays accurate. */
    fun onAlarmFired(id: Long) {
        val alarms = loadAlarms()
        val a = alarms.firstOrNull { it.id == id } ?: return
        if (a.days.isEmpty()) {
            saveAlarms(alarms.filter { it.id != id })
        } else {
            try { scheduleAlarm(a, skipToday = true) } catch (_: SecurityException) {}
        }
    }

    /** Removes a fired timer from storage so list_timers stays accurate. */
    fun onTimerFired(id: Long) {
        saveTimers(loadTimers().filter { it.id != id })
    }

    /** Re-registers everything in storage with AlarmManager and drops expired
     *  timers. Called after boot, since registrations don't survive a reboot. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val allTimers = loadTimers()
        val timers = allTimers.filter { it.triggerAt > now }
        if (timers.size != allTimers.size) saveTimers(timers)
        try {
            loadAlarms().forEach { scheduleAlarm(it) }
            timers.forEach { scheduleTimer(it) }
        } catch (_: SecurityException) {
            // Exact-alarm permission was revoked; nothing can be re-registered
            // until the user grants it again.
        }
    }

    // ---- scheduling ----

    // Alarms and timers share the next_id counter, so they need disjoint
    // PendingIntent request-code spaces: positive for alarms, negative for timers.
    private fun alarmRequestCode(id: Long) = id.toInt()
    private fun timerRequestCode(id: Long) = -id.toInt()

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun scheduleAlarm(a: AlarmRecord, skipToday: Boolean = false) {
        val triggerAt = nextAlarmTime(a.hour, a.minute, a.days, skipToday)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("type", "alarm")
            putExtra("alarm_id", a.id)
            putExtra("label", a.label ?: "Alarm")
            putExtra("vibrate", a.vibrate)
        }
        val pi = PendingIntent.getBroadcast(context, alarmRequestCode(a.id), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager().setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
    }

    private fun scheduleTimer(t: TimerRecord) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("type", "timer")
            putExtra("timer_id", t.id)
            putExtra("label", t.label ?: "Timer")
        }
        val pi = PendingIntent.getBroadcast(context, timerRequestCode(t.id), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // setExactAndAllowWhileIdle (not setExact) so the timer fires on time in Doze.
        alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.triggerAt, pi)
    }

    private fun isScheduled(requestCode: Int): Boolean {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null
    }

    private fun exactAlarmError(): ToolResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager().canScheduleExactAlarms()) {
            return ToolResult.error(
                "The 'Alarms & reminders' special permission is not granted, so exact alarms cannot be scheduled. " +
                    "Ask the user to enable it for Gotcha under Settings > Apps > Special app access > Alarms & reminders."
            )
        }
        return null
    }

    private fun exactAlarmDeniedMessage(e: SecurityException) =
        "Could not schedule: ${e.message}. Ask the user to enable 'Alarms & reminders' for Gotcha in system settings."

    // ---- storage ----

    private data class AlarmRecord(
        val id: Long, val hour: Int, val minute: Int,
        val days: List<Int>, val label: String?, val vibrate: Boolean
    )

    private data class TimerRecord(
        val id: Long, val seconds: Int, val label: String?,
        val triggerAt: Long = System.currentTimeMillis() + seconds * 1000L
    )

    private fun saveAlarm(r: AlarmRecord) {
        val list = loadAlarmsMutable()
        list.add(r)
        saveAlarms(list)
    }

    private fun saveAlarms(list: List<AlarmRecord>) {
        val json = buildJsonArray {
            list.forEach { a ->
                add(buildJsonObject {
                    put("id", a.id)
                    put("hour", a.hour)
                    put("minute", a.minute)
                    val daysArr = buildJsonArray { a.days.forEach { d -> add(kotlinx.serialization.json.JsonPrimitive(d)) } }
                    put("days", daysArr)
                    if (a.label != null) put("label", a.label)
                    put("vibrate", a.vibrate)
                })
            }
        }
        prefs.edit().putString("alarms", json.toString()).apply()
    }

    private fun loadAlarms(): List<AlarmRecord> = try {
        val arr = Json.parseToJsonElement(prefs.getString("alarms", "[]") ?: "[]").jsonArray
        arr.mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val hour = o["hour"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val minute = o["minute"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            AlarmRecord(
                id = id,
                hour = hour,
                minute = minute,
                days = o["days"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
                label = o["label"]?.jsonPrimitive?.content,
                vibrate = o["vibrate"]?.jsonPrimitive?.booleanOrNull ?: true
            )
        }.filterNotNull()
    } catch (_: Exception) { emptyList() }

    private fun loadAlarmsMutable(): MutableList<AlarmRecord> = loadAlarms().toMutableList()

    private fun saveTimer(r: TimerRecord) {
        val list = loadTimersMutable()
        list.add(r)
        saveTimers(list)
    }

    private fun saveTimers(list: List<TimerRecord>) {
        val json = buildJsonArray {
            list.forEach { t ->
                add(buildJsonObject {
                    put("id", t.id)
                    put("seconds", t.seconds)
                    if (t.label != null) put("label", t.label)
                    put("triggerAt", t.triggerAt)
                })
            }
        }
        prefs.edit().putString("timers", json.toString()).apply()
    }

    private fun loadTimers(): List<TimerRecord> = try {
        val arr = Json.parseToJsonElement(prefs.getString("timers", "[]") ?: "[]").jsonArray
        arr.mapNotNull { e ->
            val o = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val seconds = o["seconds"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            TimerRecord(
                id = id,
                seconds = seconds,
                label = o["label"]?.jsonPrimitive?.content,
                triggerAt = o["triggerAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: (System.currentTimeMillis() + seconds * 1000L)
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun loadTimersMutable(): MutableList<TimerRecord> = loadTimers().toMutableList()

    private fun cancelPendingIntent(requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        if (pi != null) {
            alarmManager().cancel(pi)
            pi.cancel()
        }
    }

    // ---- helpers ----

    private fun parseDays(days: List<String>?): List<Int> {
        if (days.isNullOrEmpty()) return emptyList()
        return days.mapNotNull { d -> dayNames[d.trim().lowercase()] }.distinct()
    }

    private fun nextAlarmTime(hour: Int, minute: Int, days: List<Int>, skipToday: Boolean = false): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (days.isEmpty()) {
            if (target.before(now)) target.add(Calendar.DAY_OF_MONTH, 1)
            return target.timeInMillis
        }
        val currentDow = now.get(Calendar.DAY_OF_WEEK)
        val firstOffset = if (skipToday) 1 else 0
        for (offset in firstOffset..6) {
            val check = currentDow + offset
            val dow = if (check > 7) check - 7 else check
            if (dow in days) {
                target.add(Calendar.DAY_OF_MONTH, offset)
                if (offset == 0 && target.before(now)) continue
                return target.timeInMillis
            }
        }
        target.add(Calendar.DAY_OF_MONTH, 7)
        return target.timeInMillis
    }
}
