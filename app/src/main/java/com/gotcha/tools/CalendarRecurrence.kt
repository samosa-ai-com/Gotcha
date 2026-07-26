package com.gotcha.tools

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure date/recurrence helpers for [CalendarTool] — no ContentResolver access. */
internal object CalendarRecurrence {

    fun parseWhen(value: String): Long? {
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

    /** Floors [epochMs] to the start of its UTC calendar day. */
    fun utcMidnight(epochMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** RFC 5545 RRULE for a simple daily/weekly/monthly/yearly recurrence. */
    fun buildRRule(frequency: String, count: Int?, until: String?): String? {
        val freq = when (frequency.trim().lowercase()) {
            "daily" -> "DAILY"
            "weekly" -> "WEEKLY"
            "monthly" -> "MONTHLY"
            "yearly", "annually" -> "YEARLY"
            else -> return null
        }
        return buildString {
            append("FREQ=$freq")
            if (count != null && count > 0) {
                append(";COUNT=$count")
            } else if (!until.isNullOrBlank()) {
                val untilMs = parseWhen(until)
                if (untilMs != null) {
                    val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    append(";UNTIL=${utc.format(Date(untilMs))}")
                }
            }
        }
    }

    /** RFC 5545 duration string (e.g. "P0DT1H0M0S") for the events DURATION column. */
    fun iso8601Duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "P0DT${hours}H${minutes}M${seconds}S"
    }
}
