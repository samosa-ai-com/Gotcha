package com.gotcha.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
        val dayInts = parseDays(days)
        val id = nextId++
        val label = message?.takeIf { it.isNotBlank() }

        val triggerAt = nextAlarmTime(hour, minute, dayInts)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("type", "alarm")
            putExtra("alarm_id", id)
            putExtra("label", label ?: "Alarm")
            putExtra("hour", hour)
            putExtra("minute", minute)
            putExtra("vibrate", vibrate ?: true)
            dayInts.toIntArray().let { putExtra("days", it) }
        }
        val pi = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)

        saveAlarm(AlarmRecord(id, hour, minute, dayInts, label, vibrate ?: true))

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
        val id = nextId++
        val label = message?.takeIf { it.isNotBlank() }
        val triggerAt = System.currentTimeMillis() + totalSeconds * 1000L

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("type", "timer")
            putExtra("timer_id", id)
            putExtra("label", label ?: "Timer")
            putExtra("seconds", totalSeconds)
        }
        val pi = PendingIntent.getBroadcast(context, 10000 + id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)

        saveTimer(TimerRecord(id, totalSeconds, label))

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
        val timers = loadTimers()
        if (timers.isEmpty()) return ToolResult.ok("No timers running.")
        val sb = StringBuilder()
        timers.forEach { t ->
            val remaining = maxOf(0, t.triggerAt - System.currentTimeMillis()) / 1000
            sb.append("- ${t.label ?: "Timer"}: ${remaining / 60}m ${remaining % 60}s remaining (id=${t.id})\n")
        }
        return ToolResult.ok(sb.trimEnd().toString())
    }

    fun editAlarm(id: Long, hour: Int? = null, minute: Int? = null, message: String? = null, days: List<String>? = null, vibrate: Boolean? = null): ToolResult {
        val alarms = loadAlarmsMutable()
        val idx = alarms.indexOfFirst { it.id == id }
        if (idx == -1) return ToolResult.error("Alarm $id not found.")

        val old = alarms[idx]
        val newHour = hour ?: old.hour
        val newMinute = minute ?: old.minute
        val newLabel = message ?: old.label
        val newDays = if (days != null) parseDays(days) else old.days
        val newVibrate = vibrate ?: old.vibrate

        val triggerAt = nextAlarmTime(newHour, newMinute, newDays)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("type", "alarm")
            putExtra("alarm_id", id)
            putExtra("label", newLabel ?: "Alarm")
            putExtra("hour", newHour)
            putExtra("minute", newMinute)
            putExtra("vibrate", newVibrate)
            newDays.toIntArray().let { putExtra("days", it) }
        }
        val pi = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)

        alarms[idx] = AlarmRecord(id, newHour, newMinute, newDays, newLabel, newVibrate)
        saveAlarms(alarms)
        return ToolResult.ok("Updated alarm $id.")
    }

    fun deleteAlarm(id: Long): ToolResult {
        val alarms = loadAlarmsMutable()
        if (alarms.none { it.id == id }) return ToolResult.error("Alarm $id not found.")
        cancelPendingIntent(id.toInt())
        saveAlarms(alarms.filter { it.id != id })
        return ToolResult.ok("Deleted alarm $id.")
    }

    fun deleteTimer(id: Long): ToolResult {
        val timers = loadTimersMutable()
        if (timers.none { it.id == id }) return ToolResult.error("Timer $id not found.")
        cancelPendingIntent(10000 + id.toInt())
        saveTimers(timers.filter { it.id != id })
        return ToolResult.ok("Deleted timer $id.")
    }

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
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
            pi.cancel()
        }
    }

    // ---- helpers ----

    private fun parseDays(days: List<String>?): List<Int> {
        if (days.isNullOrEmpty()) return emptyList()
        return days.mapNotNull { d -> dayNames[d.trim().lowercase()] }.distinct()
    }

    private fun nextAlarmTime(hour: Int, minute: Int, days: List<Int>): Long {
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
        for (offset in 0..6) {
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
