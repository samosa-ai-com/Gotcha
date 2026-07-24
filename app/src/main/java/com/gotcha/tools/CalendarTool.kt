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
            val (rangeStart, rangeEnd, rangeDesc) = resolveRange(daysAhead, fromDate, toDate)

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
                if (cursor == null) {
                    return ToolResult.error("Could not read the calendar (permission may not be granted).")
                }
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
                val indices = EventCursorIndices(idIdx, titleIdx, beginIdx, endIdx, locIdx, descIdx, statusIdx, calIdx)
                while (cursor.moveToNext() && count < 50) {
                    out.append(formatEventRow(cursor, indices))
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
        calendarName: String? = null,
        attendees: List<String>? = null,
        recurrence: String? = null,
        recurrenceCount: Int? = null,
        recurrenceUntil: String? = null
    ): ToolResult {
        if (title.isBlank()) {
            return ToolResult.error(
                "Please provide a title for the event. The title is required to create one."
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.WRITE_CALENDAR,
                "Adding a calendar event needs the Calendar permission. I have requested it — please grant it and ask again."
            )
        }
        var startMs = CalendarRecurrence.parseWhen(start)
            ?: return ToolResult.error(
                "Could not understand the start time '$start'. Use 'yyyy-MM-dd HH:mm' (e.g. '2026-07-17 15:30') " +
                    "or epoch milliseconds."
            )
        var endMs = if (end.isNullOrBlank()) {
            startMs + 60 * 60 * 1000
        } else {
            CalendarRecurrence.parseWhen(end) ?: return ToolResult.error(
                "Could not understand the end time '$end'. Use 'yyyy-MM-dd HH:mm' (e.g. " +
                    "'2026-07-17 17:00') or epoch milliseconds."
            )
        }

        if (allDay == true) {
            // All-day events must use UTC-midnight boundaries with EVENT_TIMEZONE="UTC"
            // (Android calendar provider convention), else they render on the wrong day.
            startMs = CalendarRecurrence.utcMidnight(startMs)
            endMs = CalendarRecurrence.utcMidnight(endMs).let { if (it <= startMs) startMs + 86_400_000L else it }
        }

        val rrule = recurrence?.let {
            CalendarRecurrence.buildRRule(it, recurrenceCount, recurrenceUntil)
                ?: return ToolResult.error(
                    "Unknown recurrence '$it'. Use one of: daily, weekly, monthly, yearly."
                )
        }

        return try {
            val calendarId = resolveCalendarId(calendarName)
                ?: return ToolResult.error(calendarIdErrorMessage(calendarName))

            val values = buildEventValues(
                NewEventSpec(calendarId, title, startMs, endMs, rrule, allDay, location, description)
            )
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ToolResult.error(
                    "The calendar rejected the new event. You may check that the calendar is writable and try " +
                        "again."
                )
            val eventId = uri.lastPathSegment!!.toLong()

            insertReminder(eventId, reminderMinutes)
            // Add attendees if requested — synced Google calendars send real invites.
            addAttendees(eventId, attendees)

            val extras = createEventExtrasSummary(allDay, rrule, recurrence, reminderMinutes, attendees)
            ToolResult.ok("Added '$title' on ${readable.format(Date(startMs))}$extras.")
        } catch (e: Exception) {
            ToolResult.error("Could not create the event: ${e.message}")
        }
    }

    private fun resolveCalendarId(calendarName: String?): Long? =
        if (!calendarName.isNullOrBlank()) findCalendarByName(calendarName) else defaultWritableCalendarId()

    private fun calendarIdErrorMessage(calendarName: String?): String =
        if (!calendarName.isNullOrBlank()) {
            "No calendar named '$calendarName' was found. Available calendars: " +
                listCalendarNames().joinToString(", ").ifBlank { "(none)" } +
                ". Omit calendarName to use the default writable calendar."
        } else {
            "No writable calendar was found on this device. You may add a Google account or use the " +
                "device's calendar app to set one up."
        }

    private data class NewEventSpec(
        val calendarId: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val rrule: String?,
        val allDay: Boolean?,
        val location: String?,
        val description: String?
    )

    private fun buildEventValues(spec: NewEventSpec): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, spec.calendarId)
        put(CalendarContract.Events.TITLE, spec.title)
        put(CalendarContract.Events.DTSTART, spec.startMs)
        if (spec.rrule != null) {
            // Recurring events use DURATION instead of DTEND (CalendarContract requirement).
            put(CalendarContract.Events.DURATION, CalendarRecurrence.iso8601Duration(spec.endMs - spec.startMs))
            put(CalendarContract.Events.RRULE, spec.rrule)
        } else {
            put(CalendarContract.Events.DTEND, spec.endMs)
        }
        put(CalendarContract.Events.EVENT_TIMEZONE, if (spec.allDay == true) "UTC" else TimeZone.getDefault().id)
        if (spec.allDay == true) put(CalendarContract.Events.ALL_DAY, 1)
        if (!spec.location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, spec.location)
        if (!spec.description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, spec.description)
    }

    private fun insertReminder(eventId: Long, reminderMinutes: Int?) {
        if (reminderMinutes == null || reminderMinutes < 0) return
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, reminderMinutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
    }

    private fun createEventExtrasSummary(
        allDay: Boolean?,
        rrule: String?,
        recurrence: String?,
        reminderMinutes: Int?,
        attendees: List<String>?
    ): String = buildString {
        if (allDay == true) append(" all-day")
        if (rrule != null) append(", repeating $recurrence")
        if (reminderMinutes != null && reminderMinutes >= 0) append(", reminder $reminderMinutes min before")
        if (!attendees.isNullOrEmpty()) append(", ${attendees.size} attendee(s) invited")
    }

    fun editEvent(
        eventId: Long,
        title: String? = null,
        start: String? = null,
        end: String? = null,
        location: String? = null,
        description: String? = null,
        allDay: Boolean? = null,
        reminderMinutes: Int? = null,
        attendees: List<String>? = null,
        recurrence: String? = null,
        recurrenceCount: Int? = null,
        recurrenceUntil: String? = null
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
            var startMs: Long? = null
            var endMs: Long? = null
            if (title != null) values.put(CalendarContract.Events.TITLE, title)
            if (start != null) {
                startMs = CalendarRecurrence.parseWhen(start) ?: return ToolResult.error(
                    "Could not understand the start time '$start'. Use 'yyyy-MM-dd " +
                        "HH:mm' (e.g. '2026-07-17 15:30') or epoch milliseconds."
                )
                values.put(CalendarContract.Events.DTSTART, startMs)
            }
            if (end != null) {
                endMs = CalendarRecurrence.parseWhen(end) ?: return ToolResult.error(
                    "Could not understand the end time '$end'. Use 'yyyy-MM-dd HH:mm' " +
                        "(e.g. '2026-07-17 17:00') or epoch milliseconds."
                )
            }
            if (location != null) values.put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description != null) values.put(CalendarContract.Events.DESCRIPTION, description)
            if (allDay != null) values.put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)

            if (recurrence != null) {
                val rrule = CalendarRecurrence.buildRRule(recurrence, recurrenceCount, recurrenceUntil)
                    ?: return ToolResult.error(
                        "Unknown recurrence '$recurrence'. Use one of: daily, weekly, monthly, yearly."
                    )
                values.put(CalendarContract.Events.RRULE, rrule)
                if (startMs != null && endMs != null) {
                    values.put(CalendarContract.Events.DURATION, CalendarRecurrence.iso8601Duration(endMs - startMs))
                } else if (endMs != null) {
                    // Recurring events use DURATION, not DTEND — caller must supply both to change length.
                    return ToolResult.error(
                        "To change the length of a recurring event, provide both 'start' and 'end'."
                    )
                }
            } else if (endMs != null) {
                values.put(CalendarContract.Events.DTEND, endMs)
            }

            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = context.contentResolver.update(uri, values, null, null)
            if (rows == 0) {
                return ToolResult.error(
                    "Event $eventId not found or could not be updated. You may use list_calendar_events to find the correct event ID."
                )
            }

            if (attendees != null) replaceAttendees(eventId, attendees)

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
                return ToolResult.error(
                    "Event $eventId not found or could not be deleted. You may use list_calendar_events to verify the correct event ID."
                )
            }
            ToolResult.ok("Deleted event $eventId.")
        } catch (e: Exception) {
            ToolResult.error("Could not delete the event: ${e.message}")
        }
    }

    private data class EventCursorIndices(
        val id: Int,
        val title: Int,
        val begin: Int,
        val end: Int,
        val location: Int,
        val description: Int,
        val status: Int,
        val calendarName: Int
    )

    private fun formatEventRow(cursor: android.database.Cursor, idx: EventCursorIndices): String {
        val eventId = cursor.getLong(idx.id)
        val title = cursor.getString(idx.title) ?: "(untitled)"
        val begin = readable.format(Date(cursor.getLong(idx.begin)))
        val end = readable.format(Date(cursor.getLong(idx.end)))
        val loc = cursor.getString(idx.location)
        val desc = cursor.getString(idx.description)
        val status = when (cursor.getInt(idx.status)) {
            CalendarContract.Instances.STATUS_CONFIRMED -> "confirmed"
            CalendarContract.Instances.STATUS_TENTATIVE -> "tentative"
            CalendarContract.Instances.STATUS_CANCELED -> "cancelled"
            else -> null
        }
        val calName = cursor.getString(idx.calendarName)

        return buildString {
            append("[id=$eventId]  $begin")
            if (status != null) append(" [$status]")
            append("  $title")
            if (calName != null) append("  📅 $calName")
            append("\n    ends $end")
            if (!loc.isNullOrBlank()) append("\n    📍 $loc")
            if (!desc.isNullOrBlank()) append("\n    ${desc.take(200)}")
            reminderMinutesFor(eventId).takeIf { it.isNotEmpty() }?.let {
                append("\n    ⏰ reminder(s): ${it.joinToString(", ") { m -> "${m}m before" }}")
            }
            attendeesFor(eventId).takeIf { it.isNotEmpty() }?.let {
                append("\n    👤 attendees: ${it.joinToString(", ")}")
            }
            append("\n")
        }
    }

    private fun resolveRange(daysAhead: Int?, fromDate: String?, toDate: String?): Triple<Long, Long, String> {
        val now = System.currentTimeMillis()
        if (fromDate == null && toDate == null) {
            val days = (daysAhead ?: 7).coerceIn(1, 365)
            return Triple(now, now + days * 24L * 60 * 60 * 1000, "next $days day(s)")
        }
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val rangeStart = if (fromDate != null) {
            try { df.parse(fromDate)?.time ?: now } catch (_: Exception) { now }
        } else {
            now
        }
        val rangeEnd = if (toDate != null) {
            try {
                df.parse(toDate)?.time?.plus(86_400_000L - 1) ?: (now + 86400000L)
            } catch (_: Exception) {
                now + 86400000L
            }
        } else {
            rangeStart + 86400000L
        }
        return Triple(rangeStart, rangeEnd, "${fromDate ?: "today"} → ${toDate ?: "tomorrow"}")
    }

    private fun reminderMinutesFor(eventId: Long): List<Int> {
        val minutes = mutableListOf<Int>()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
            while (cursor.moveToNext()) minutes.add(cursor.getInt(idx))
        }
        return minutes
    }

    private fun attendeesFor(eventId: Long): List<String> {
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(CalendarContract.Attendees.ATTENDEE_EMAIL)
            while (cursor.moveToNext()) cursor.getString(idx)?.let { emails.add(it) }
        }
        return emails
    }

    private fun addAttendees(eventId: Long, attendees: List<String>?) {
        if (attendees.isNullOrEmpty()) return
        attendees.forEach { email ->
            if (email.isBlank()) return@forEach
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email.trim())
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_NONE)
            }
            context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, values)
        }
    }

    private fun replaceAttendees(eventId: Long, attendees: List<String>) {
        context.contentResolver.delete(
            CalendarContract.Attendees.CONTENT_URI,
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
        addAttendees(eventId, attendees)
    }

    private fun listCalendarNames(): List<String> {
        val names = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                cursor.getString(nameIdx)?.let { names.add(it) }
            }
        }
        return names
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
