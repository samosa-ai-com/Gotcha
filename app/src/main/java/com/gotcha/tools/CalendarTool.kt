package com.gotcha.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CalendarTool(private val context: Context) {

    private val readable = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** List events in the next [daysAhead] days (needs READ_CALENDAR). */
    fun listEvents(daysAhead: Int): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_CALENDAR,
                "Reading the calendar needs the Calendar permission. I have requested it — please grant it and ask again."
            )
        }
        val days = daysAhead.coerceIn(1, 365)
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60 * 60 * 1000
        return try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, now)
            ContentUris.appendId(builder, end)
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION
            )
            context.contentResolver.query(
                builder.build(), projection, null, null,
                "${CalendarContract.Instances.BEGIN} ASC"
            ).use { cursor ->
                if (cursor == null) return ToolResult.error("Could not read the calendar.")
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val out = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < 50) {
                    val title = cursor.getString(titleIdx) ?: "(untitled)"
                    val begin = readable.format(java.util.Date(cursor.getLong(beginIdx)))
                    val loc = cursor.getString(locIdx)
                    out.append("- $begin  $title")
                    if (!loc.isNullOrBlank()) out.append("  @ $loc")
                    out.append("\n")
                    count++
                }
                if (count == 0) ToolResult.ok("No events in the next $days day(s).")
                else ToolResult.ok("Events in the next $days day(s):\n$out")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read the calendar: ${e.message}")
        }
    }

    /** Create a calendar event (needs WRITE_CALENDAR). start/end accept epoch millis or "yyyy-MM-dd HH:mm". */
    fun createEvent(title: String, start: String, end: String?, location: String?): ToolResult {
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
        val endMs = if (end.isNullOrBlank()) startMs + 60 * 60 * 1000
        else parseWhen(end) ?: return ToolResult.error("Could not understand the end time '$end'.")
        return try {
            val calendarId = defaultWritableCalendarId()
                ?: return ToolResult.error("No writable calendar was found on this device.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ToolResult.error("The calendar rejected the new event.")
            ToolResult.ok("Added '$title' on ${readable.format(java.util.Date(startMs))} (event ${uri.lastPathSegment}).")
        } catch (e: Exception) {
            ToolResult.error("Could not create the event: ${e.message}")
        }
    }

    private fun parseWhen(value: String): Long? {
        val v = value.trim()
        v.toLongOrNull()?.let { return it }
        val formats = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss")
        for (f in formats) {
            try {
                return SimpleDateFormat(f, Locale.getDefault()).parse(v)?.time
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun defaultWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, args,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
            }
        }
        return null
    }
}
