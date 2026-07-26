package com.gotcha.connectors.calendar

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A resolved query window plus a human description of it for tool output. */
data class Window(val startMs: Long, val endMs: Long, val description: String)

/** A busy interval. Half-open: `[startMs, endMs)`. */
data class BusyBlock(val startMs: Long, val endMs: Long)

/**
 * Pure date-window and free/busy arithmetic shared by every calendar backend.
 * No ContentResolver, no network — unit-testable on the JVM.
 */
object CalendarWindow {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val MAX_DAYS_AHEAD = 365

    private fun rfc3339Format() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun readableFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** RFC 3339 UTC (`2026-01-01T09:00:00Z`), the format both Graph and Calendar v3 take. */
    fun toRfc3339(epochMs: Long): String = rfc3339Format().format(Date(epochMs))

    /** Parses RFC 3339 / ISO-8601 timestamps returned by Graph and Calendar v3. */
    fun parseRfc3339(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        // Graph omits the zone when Prefer: outlook.timezone is set; Calendar v3 includes it.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                // Patterns without an offset are UTC by convention for both APIs.
                if (!pattern.contains("XXX")) format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse(trimmed)?.time
            }
        }
        return null
    }

    fun readable(epochMs: Long): String = readableFormat().format(Date(epochMs))

    /**
     * Same semantics as the device calendar tool: `from_date`/`to_date` win when
     * present, else `days_ahead` (default 7) counting from [now].
     */
    fun resolve(daysAhead: Int?, fromDate: String?, toDate: String?, now: Long): Window {
        if (fromDate == null && toDate == null) {
            val days = (daysAhead ?: 7).coerceIn(1, MAX_DAYS_AHEAD)
            return Window(now, now + days * DAY_MILLIS, "next $days day(s)")
        }
        val format = dayFormat()
        val start = fromDate?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: now
        val end = toDate?.let { runCatching { format.parse(it)?.time }.getOrNull()?.plus(DAY_MILLIS - 1) }
            ?: (start + DAY_MILLIS)
        return Window(start, end, "${fromDate ?: "today"} → ${toDate ?: "tomorrow"}")
    }

    /** Sorts and coalesces overlapping/adjacent busy blocks. */
    fun merge(blocks: List<BusyBlock>): List<BusyBlock> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val merged = mutableListOf<BusyBlock>()
        sorted.forEach { block ->
            val last = merged.lastOrNull()
            if (last != null && block.startMs <= last.endMs) {
                if (block.endMs > last.endMs) merged[merged.size - 1] = last.copy(endMs = block.endMs)
            } else {
                merged.add(block)
            }
        }
        return merged
    }

    /**
     * Gaps between [busy] blocks inside `[windowStart, windowEnd)` that are at
     * least [minMillis] long. [busy] need not be sorted or merged.
     */
    fun freeSlots(
        busy: List<BusyBlock>,
        windowStart: Long,
        windowEnd: Long,
        minMillis: Long
    ): List<BusyBlock> {
        val slots = mutableListOf<BusyBlock>()
        var cursor = windowStart
        merge(busy).forEach { block ->
            if (block.startMs > cursor && block.startMs - cursor >= minMillis) {
                slots.add(BusyBlock(cursor, block.startMs))
            }
            if (block.endMs > cursor) cursor = block.endMs
        }
        if (windowEnd > cursor && windowEnd - cursor >= minMillis) {
            slots.add(BusyBlock(cursor, windowEnd))
        }
        return slots
    }
}
