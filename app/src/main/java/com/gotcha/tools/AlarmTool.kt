package com.gotcha.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.AlarmClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Hybrid alarm management: alarms are created in the system clock app via
 * [AlarmClock.ACTION_SET_ALARM] when a clock app handles it (so they appear in
 * its alarm list and ring with the full alarm UI), with a shadow record kept in
 * SharedPreferences so list/edit/delete by ID still work. Devices without a
 * compatible clock app fall back to local AlarmManager alarms.
 *
 * Limitations of the delegated path:
 * - Alarms the user creates manually in the clock app cannot be enumerated;
 *   only the system-wide next alarm is visible (via getNextAlarmClock).
 * - Editing/deleting a clock-app alarm uses ACTION_DISMISS_ALARM, which is
 *   best-effort: some clock apps only disable the alarm or skip the next
 *   occurrence of a recurring alarm instead of removing it.
 * - The shadow list desyncs if the user edits Gotcha-created alarms directly
 *   in the clock app.
 *
 * Timers always stay local: there is no intent to cancel a running clock-app
 * timer, which would break delete_timer.
 */
@Suppress("TooManyFunctions")
class AlarmTool(private val context: Context) {

    companion object {
        // Not a documented android.provider.AlarmClock constant; some clock apps
        // (e.g. Google Clock) honor it best-effort to dismiss a ringing timer.
        private const val ACTION_DISMISS_TIMER = "android.intent.action.DISMISS_TIMER"
    }

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
        val label = message?.takeIf { it.isNotBlank() }
        val triggerAt = if (dayInts.isEmpty()) nextAlarmTime(hour, minute, dayInts) else null

        val dayStr = if (dayInts.isNotEmpty()) " (${days?.joinToString(",")})" else ""
        val extra = buildString {
            if (dayInts.isNotEmpty()) append(" repeating$dayStr")
            if (vibrate == false) append(" silent")
        }

        // Prefer the system clock app so the alarm shows up in its list and
        // rings with the full alarm UI (snooze/dismiss, alarm sound).
        if (dispatchClockIntent(buildSetAlarmIntent(hour, minute, label, dayInts, vibrate ?: true))) {
            val id = nextId++
            saveAlarm(
                AlarmRecord(id, hour, minute, dayInts, label, vibrate ?: true, system = true, triggerAt = triggerAt)
            )
            return ToolResult.ok(
                "Set alarm '$label' for %02d:%02d$extra in the system clock app (id=$id).".format(hour, minute)
            )
        }

        // No compatible clock app — schedule locally via AlarmManager.
        exactAlarmError()?.let { return it }
        val id = nextId++
        val record = AlarmRecord(id, hour, minute, dayInts, label, vibrate ?: true, triggerAt = triggerAt)
        try {
            scheduleAlarm(record)
        } catch (e: SecurityException) {
            return ToolResult.error(exactAlarmDeniedMessage(e))
        }
        saveAlarm(record)
        val time = "%02d:%02d".format(hour, minute)
        return ToolResult.ok(
            "Set alarm '$label' for $time$extra (id=$id). No clock app handled it, " +
                "so it was scheduled in-app and rings as a notification."
        )
    }

    fun setTimer(
        seconds: Int,
        message: String? = null,
        hours: Int? = null,
        minutes: Int? = null,
        system: Boolean = false
    ): ToolResult {
        val totalSeconds = seconds + (hours ?: 0) * 3600 + (minutes ?: 0) * 60
        if (totalSeconds < 1) return ToolResult.error("Timer length must be at least 1 second.")
        val label = message?.takeIf { it.isNotBlank() }

        if (system) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            if (!dispatchClockIntent(intent)) {
                return ToolResult.error(
                    "No clock app handled the system timer request. Omit system=true to use an in-app timer instead."
                )
            }
            val id = nextId++
            saveTimer(TimerRecord(id, totalSeconds, label, system = true))
            return ToolResult.ok(
                "Started ${label ?: "a timer"} for ${totalSeconds}s in the system clock app (id=$id). " +
                    "Use dismiss_timer while it's ringing; delete_timer only removes it from this assistant's list."
            )
        }

        exactAlarmError()?.let { return it }
        val id = nextId++
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

    /** Opens the system clock app's alarms list (ACTION_SHOW_ALARMS). */
    fun showAlarms(): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        return if (dispatchClockIntent(intent)) {
            ToolResult.ok("Opened the alarms list in the clock app.")
        } else {
            ToolResult.error("No clock app handled the show-alarms request.")
        }
    }

    /** Snoozes the currently ringing alarm (ACTION_SNOOZE_ALARM). No effect if no alarm is ringing. */
    fun snoozeAlarm(minutes: Int? = null): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
            if (minutes != null && minutes > 0) putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, minutes)
        }
        return if (dispatchClockIntent(intent)) {
            ToolResult.ok(
                "Sent a snooze request to the clock app" +
                    (minutes?.let { " for $it minute(s)" } ?: "") +
                    ". This only has an effect if an alarm is currently ringing."
            )
        } else {
            ToolResult.error("No clock app handled the snooze request.")
        }
    }

    /**
     * Dismisses a currently ringing system-clock-app timer (best-effort; not
     * all clock apps support this action). Distinct from [deleteTimer], which
     * only removes an in-app timer's shadow record.
     */
    fun dismissTimer(): ToolResult {
        val intent = Intent(ACTION_DISMISS_TIMER)
        return if (dispatchClockIntent(intent)) {
            ToolResult.ok(
                "Sent a dismiss request to the clock app. This only has an effect if a timer is " +
                    "currently ringing there; support varies by clock app."
            )
        } else {
            ToolResult.error(
                "No clock app handled the dismiss-timer request (this action isn't supported by all clock apps)."
            )
        }
    }

    fun listAlarms(): ToolResult {
        var alarms = loadAlarms()

        // One-shot clock-app alarms that already rang: Gotcha never sees them
        // fire, so prune by stored trigger time instead.
        val now = System.currentTimeMillis()
        val expired = alarms.filter { it.system && it.days.isEmpty() && it.triggerAt != null && it.triggerAt < now }
        if (expired.isNotEmpty()) {
            alarms = alarms - expired.toSet()
            saveAlarms(alarms)
        }

        val sb = StringBuilder()
        if (alarms.isEmpty()) {
            sb.append("No alarms set by this assistant.")
        } else {
            alarms.forEach { a ->
                if (!a.system && !isScheduled(alarmRequestCode(a.id))) {
                    // Registration was lost (reboot, force-stop) — re-register from storage.
                    try { scheduleAlarm(a) } catch (_: SecurityException) {}
                }
                val dayStr = if (a.days.isNotEmpty()) {
                    a.days.mapNotNull { e ->
                        dayNames.entries.firstOrNull { it.value == e }?.key?.take(
                            3
                        )
                    }.joinToString(",")
                } else {
                    "once"
                }
                sb.append("- $dayStr %02d:%02d".format(a.hour, a.minute))
                if (a.label != null) sb.append("  ${a.label}")
                sb.append("  (id=${a.id}, ${if (a.system) "clock app" else "in-app"})\n")
            }
        }
        nextSystemAlarmLine()?.let { sb.append("\n").append(it) }
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

    fun editAlarm(
        id: Long,
        hour: Int? = null,
        minute: Int? = null,
        message: String? = null,
        days: List<String>? = null,
        vibrate: Boolean? = null
    ): ToolResult {
        val alarms = loadAlarmsMutable()
        val idx = alarms.indexOfFirst { it.id == id }
        if (idx == -1) return ToolResult.error("Alarm $id not found.")

        val old = alarms[idx]
        val newDays = if (days != null) parseDays(days) else old.days
        val record = AlarmRecord(
            id = id,
            hour = hour ?: old.hour,
            minute = minute ?: old.minute,
            days = newDays,
            label = message ?: old.label,
            vibrate = vibrate ?: old.vibrate,
            system = old.system,
            triggerAt = if (newDays.isEmpty()) nextAlarmTime(hour ?: old.hour, minute ?: old.minute, newDays) else null
        )

        if (old.system) {
            // No edit intent exists: dismiss the old alarm (best effort) and create a new one.
            dismissSystemAlarm(old)
            if (!dispatchClockIntent(
                    buildSetAlarmIntent(record.hour, record.minute, record.label, record.days, record.vibrate)
                )
            ) {
                return ToolResult.error(
                    "Alarm $id lives in the system clock app but no clock app handled the update intent. Edit it in the clock app directly."
                )
            }
            alarms[idx] = record
            saveAlarms(alarms)
            return ToolResult.ok(
                "Updated alarm $id in the system clock app. The old version was dismissed best-effort — " +
                    "if a duplicate or disabled alarm remains, ask the user to remove it in the clock app."
            )
        }

        exactAlarmError()?.let { return it }
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
        val a = alarms.firstOrNull { it.id == id } ?: return ToolResult.error("Alarm $id not found.")
        saveAlarms(alarms.filter { it.id != id })
        if (a.system) {
            val dismissed = dismissSystemAlarm(a)
            return if (dismissed) {
                ToolResult.ok(
                    "Deleted alarm $id. It lived in the system clock app, so it was dismissed best-effort — " +
                        "some clock apps only disable it or skip the next occurrence; the user can verify in the clock app."
                )
            } else {
                ToolResult.ok(
                    "Removed alarm $id from this assistant's list, but no clock app handled the " +
                        "dismiss intent — ask the user to delete it in the clock app."
                )
            }
        }
        cancelPendingIntent(alarmRequestCode(id))
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
        val t = timers.firstOrNull { it.id == id } ?: return ToolResult.error("Timer $id not found.")
        saveTimers(timers.filter { it.id != id })
        if (t.system) {
            return ToolResult.ok(
                "Removed timer $id from this assistant's list, but it lives in the system clock app — " +
                    "use dismiss_timer while it's ringing, or ask the user to cancel it there."
            )
        }
        cancelPendingIntent(timerRequestCode(id))
        return ToolResult.ok("Deleted timer $id.")
    }

    // ---- firing callbacks (from AlarmReceiver, local alarms/timers only) ----

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

    /** Re-registers local alarms/timers with AlarmManager and drops expired
     *  timers. Called after boot, since registrations don't survive a reboot.
     *  Clock-app alarms are the clock app's responsibility and are skipped. */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val allTimers = loadTimers()
        val timers = allTimers.filter { it.triggerAt > now }
        if (timers.size != allTimers.size) saveTimers(timers)
        try {
            loadAlarms().filter { !it.system }.forEach { scheduleAlarm(it) }
            timers.forEach { scheduleTimer(it) }
        } catch (_: SecurityException) {
            // Exact-alarm permission was revoked; nothing can be re-registered
            // until the user grants it again.
        }
    }

    // ---- system clock app delegation ----

    private fun buildSetAlarmIntent(hour: Int, minute: Int, label: String?, dayInts: List<Int>, vibrate: Boolean) =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (dayInts.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(dayInts))
            putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

    private fun dismissSystemAlarm(a: AlarmRecord): Boolean {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            putExtra(AlarmClock.EXTRA_HOUR, a.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return dispatchClockIntent(intent)
    }

    /** Fires an AlarmClock intent at the clock app. Returns false if no app
     *  handles it, so callers can fall back to local scheduling. */
    private fun dispatchClockIntent(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** The next alarm scheduled system-wide, from any app — the only part of
     *  other apps' alarms Android exposes. */
    private fun nextSystemAlarmLine(): String? {
        val next = alarmManager().nextAlarmClock ?: return null
        val time = SimpleDateFormat("EEE MMM d HH:mm", Locale.getDefault()).format(Date(next.triggerTime))
        val pkg = next.showIntent?.creatorPackage
        return "Next system-wide alarm (any app): $time" + (if (pkg != null) " (set by $pkg)" else "")
    }

    // ---- local scheduling ----

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
        val pi = PendingIntent.getBroadcast(
            context,
            alarmRequestCode(a.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager().setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
    }

    private fun scheduleTimer(t: TimerRecord) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("type", "timer")
            putExtra("timer_id", t.id)
            putExtra("label", t.label ?: "Timer")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            timerRequestCode(t.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // setExactAndAllowWhileIdle (not setExact) so the timer fires on time in Doze.
        alarmManager().setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.triggerAt, pi)
    }

    private fun isScheduled(requestCode: Int): Boolean {
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags) != null
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
        val id: Long,
        val hour: Int,
        val minute: Int,
        val days: List<Int>,
        val label: String?,
        val vibrate: Boolean,
        val system: Boolean = false,
        val triggerAt: Long? = null
    )

    private data class TimerRecord(
        val id: Long,
        val seconds: Int,
        val label: String?,
        val triggerAt: Long = System.currentTimeMillis() + seconds * 1000L,
        val system: Boolean = false
    )

    private fun saveAlarm(r: AlarmRecord) {
        val list = loadAlarmsMutable()
        list.add(r)
        saveAlarms(list)
    }

    private fun saveAlarms(list: List<AlarmRecord>) {
        val json = buildJsonArray {
            list.forEach { a ->
                add(
                    buildJsonObject {
                        put("id", a.id)
                        put("hour", a.hour)
                        put("minute", a.minute)
                        val daysArr =
                            buildJsonArray { a.days.forEach { d -> add(kotlinx.serialization.json.JsonPrimitive(d)) } }
                        put("days", daysArr)
                        if (a.label != null) put("label", a.label)
                        put("vibrate", a.vibrate)
                        put("system", a.system)
                        if (a.triggerAt != null) put("triggerAt", a.triggerAt)
                    }
                )
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
                vibrate = o["vibrate"]?.jsonPrimitive?.booleanOrNull ?: true,
                system = o["system"]?.jsonPrimitive?.booleanOrNull ?: false,
                triggerAt = o["triggerAt"]?.jsonPrimitive?.content?.toLongOrNull()
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
                add(
                    buildJsonObject {
                        put("id", t.id)
                        put("seconds", t.seconds)
                        if (t.label != null) put("label", t.label)
                        put("triggerAt", t.triggerAt)
                        put("system", t.system)
                    }
                )
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
                triggerAt = o["triggerAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: (System.currentTimeMillis() + seconds * 1000L),
                system = o["system"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun loadTimersMutable(): MutableList<TimerRecord> = loadTimers().toMutableList()

    private fun cancelPendingIntent(requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pi != null) {
            alarmManager().cancel(pi)
            pi.cancel()
        }
    }

    // ---- helpers ----

    private fun parseDays(days: List<String>?): List<Int> {
        if (days.isNullOrEmpty()) return emptyList()
        return days.flatMap { d ->
            when (val key = d.trim().lowercase()) {
                "weekdays" -> listOf(
                    Calendar.MONDAY,
                    Calendar.TUESDAY,
                    Calendar.WEDNESDAY,
                    Calendar.THURSDAY,
                    Calendar.FRIDAY
                )
                "weekend", "weekends" -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
                "daily", "everyday" -> dayNumbers.toList()
                else -> listOfNotNull(dayNames[key])
            }
        }.distinct()
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
