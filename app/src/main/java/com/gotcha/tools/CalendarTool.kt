package com.gotcha.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalendarTool(private val context: Context) {

    private val readable = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    @Suppress("CyclomaticComplexMethod")
    fun listEvents(
        daysAhead: Int? = null,
        fromDate: String? = null,
        toDate: String? = null,
        search: String? = null
    ): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_CALENDAR,
                "The Calendar permission is not granted. Go to Settings → Permissions → Calendar and enable it, then ask again."
            )
        }
        return try {
            val now = System.currentTimeMillis()
            val rangeStart: Long
            val rangeEnd: Long
            val rangeDesc: String

            if (fromDate != null || toDate != null) {
                val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                rangeStart = if (fromDate != null) {
                    try { df.parse(fromDate)?.time ?: now } catch (_: Exception) { now }
                } else {
                    now
                }
                rangeEnd = if (toDate != null) {
                    try { df.parse(toDate)?.time?.plus(86_400_000L - 1) ?: (now + 86400000L) } catch (
                        _: Exception
                    ) { now + 86400000L }
                } else {
                    rangeStart + 86400000L
                }
                rangeDesc = "${fromDate ?: "today"} → ${toDate ?: "tomorrow"}"
            } else {
                val days = (daysAhead ?: 7).coerceIn(1, 365)
                rangeStart = now
                rangeEnd = now + days * 24L * 60 * 60 * 1000
                rangeDesc = "next $days day(s)"
            }

            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, rangeStart)
            ContentUris.appendId(builder, rangeEnd)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )

            var selection: String? = null
            var selectionArgs: Array<String>? = null
            if (!search.isNullOrBlank()) {
                selection = "${CalendarContract.Instances.TITLE} LIKE ?"
                selectionArgs = arrayOf("%$search%")
            }

            context.contentResolver.query(
                builder.build(),
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            ).use { cursor ->
                if (cursor == null) return ToolResult.error("Could not read the calendar.")
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
                val statusIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
                val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                val out = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < 50) {
                    val eventId = cursor.getLong(idIdx)
                    val title = cursor.getString(titleIdx) ?: "(untitled)"
                    val begin = readable.format(Date(cursor.getLong(beginIdx)))
                    val end = readable.format(Date(cursor.getLong(endIdx)))
                    val loc = cursor.getString(locIdx)
                    val desc = cursor.getString(descIdx)
                    val status = when (cursor.getInt(statusIdx)) {
                        CalendarContract.Instances.STATUS_CONFIRMED -> "confirmed"
                        CalendarContract.Instances.STATUS_TENTATIVE -> "tentative"
                        CalendarContract.Instances.STATUS_CANCELED -> "cancelled"
                        else -> null
                    }
                    val calName = cursor.getString(calIdx)

                    out.append("[id=$eventId]  $begin")
                    if (status != null) out.append(" [$status]")
                    out.append("  $title")
                    if (calName != null) out.append("  📅 $calName")
                    out.append("\n    ends $end")
                    if (!loc.isNullOrBlank()) out.append("\n    📍 $loc")
                    if (!desc.isNullOrBlank()) out.append("\n    ${desc.take(200)}")
                    out.append("\n")
                    count++
                }
                if (count == 0) {
                    val hint = if (!search.isNullOrBlank()) " matching '$search'" else ""
                    ToolResult.ok("No events $rangeDesc$hint.")
                } else {
                    ToolResult.ok("Events $rangeDesc ($count):\n$out")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read the calendar: ${e.message}")
        }
    }

    fun createEvent(
        title: String,
        start: String,
        end: String? = null,
        location: String? = null,
        description: String? = null,
        allDay: Boolean? = null,
        reminderMinutes: Int? = null,
        calendarName: String? = null
    ): ToolResult {
        if (title.isBlank()) return ToolResult.error("Please provide a title for the event.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.WRITE_CALENDAR,
                "Adding a calendar event needs the Calendar permission. I have requested it — please grant it and ask again."
            )
        }
        val startMs = parseWhen(start)
            ?: return ToolResult.error("Could not understand the start time '$start'. Use 'yyyy-MM-dd HH:mm' or epoch millis.")
        val endMs = if (end.isNullOrBlank()) {
            startMs + 60 * 60 * 1000
        } else {
            parseWhen(end) ?: return ToolResult.error("Could not understand the end time '$end'.")
        }

        if (allDay == true) {
            // all-day events: set to midnight of the start date
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = startMs
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            // startMs is already set above from parseWhen
        }

        return try {
            val calendarId = if (!calendarName.isNullOrBlank()) {
                findCalendarByName(calendarName)
            } else {
                null
                    ?: defaultWritableCalendarId()
                    ?: return ToolResult.error("No writable calendar was found on this device.")
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (allDay == true) put(CalendarContract.Events.ALL_DAY, 1)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ToolResult.error("The calendar rejected the new event.")

            // Add reminder if requested
            if (reminderMinutes != null && reminderMinutes >= 0) {
                val reminderValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, uri.lastPathSegment!!.toLong())
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }

            val extras = buildString {
                if (allDay == true) append(" all-day")
                if (reminderMinutes != null && reminderMinutes >= 0) append(", reminder $reminderMinutes min before")
            }
            ToolResult.ok("Added '$title' on ${readable.format(Date(startMs))}$extras.")
        } catch (e: Exception) {
            ToolResult.error("Could not create the event: ${e.message}")
        }
    }

    fun editEvent(
        eventId: Long,
        title: String? = null,
        start: String? = null,
        end: String? = null,
        location: String? = null,
        description: String? = null,
        allDay: Boolean? = null,
        reminderMinutes: Int? = null
    ): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.WRITE_CALENDAR,
                "The Calendar permission is not granted. Go to Settings → Permissions → Calendar and enable it, then ask again."
            )
        }
        return try {
            val values = ContentValues()
            if (title != null) values.put(CalendarContract.Events.TITLE, title)
            if (start != null) {
                val ms = parseWhen(start) ?: return ToolResult.error("Could not understand the start time '$start'.")
                values.put(CalendarContract.Events.DTSTART, ms)
            }
            if (end != null) {
                val ms = parseWhen(end) ?: return ToolResult.error("Could not understand the end time '$end'.")
                values.put(CalendarContract.Events.DTEND, ms)
            }
            if (location != null) values.put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description != null) values.put(CalendarContract.Events.DESCRIPTION, description)
            if (allDay != null) values.put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)

            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows == 0) {
                return ToolResult.error("Event $eventId not found or could not be updated.")
            }

            // Update reminders: clear existing, add new if requested
            if (reminderMinutes != null) {
                context.contentResolver.delete(
                    CalendarContract.Reminders.CONTENT_URI,
                    "${CalendarContract.Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString())
                )
                if (reminderMinutes >= 0) {
                    val reminderValues = ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                }
            }

            ToolResult.ok("Updated event $eventId.")
        } catch (e: Exception) {
            ToolResult.error("Could not update the event: ${e.message}")
        }
    }

    fun deleteEvent(eventId: Long): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.WRITE_CALENDAR,
                "The Calendar permission is not granted. Go to Settings → Permissions → Calendar and enable it, then ask again."
            )
        }
        return ToolResult.ok("CONFIRM_DELETE_CALENDAR_EVENT:$eventId")
    }

    fun doDeleteEvent(eventId: Long): ToolResult {
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows == 0) {
                return ToolResult.error("Event $eventId not found or could not be deleted.")
            }
            ToolResult.ok("Deleted event $eventId.")
        } catch (e: Exception) {
            ToolResult.error("Could not delete the event: ${e.message}")
        }
    }

    private fun findCalendarByName(name: String): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val calName = cursor.getString(nameIdx)
                if (calName?.contains(name, ignoreCase = true) == true) {
                    return cursor.getLong(idIdx)
                }
            }
        }
        return null
    }

    private fun parseWhen(value: String): Long? {
        val v = value.trim()
        v.toLongOrNull()?.let { return it }
        val formats = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (f in formats) {
            try {
                return SimpleDateFormat(f, Locale.getDefault()).parse(v)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun defaultWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
            }
        }
        return null
    }
}
