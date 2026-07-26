package com.gotcha.connectors.calendar

import com.gotcha.tools.CalendarTool
import com.gotcha.tools.ToolResult

/**
 * The device-calendar operations [CalendarTools] delegates `source="device"` to.
 *
 * Exists purely so the router is unit-testable: [CalendarTool] needs a Context
 * and a ContentResolver, which are unavailable on the JVM. Production wiring goes
 * through [CalendarToolDevice], which forwards to the untouched [CalendarTool].
 */
@Suppress("LongParameterList")
interface DeviceCalendar {
    fun listEvents(daysAhead: Int?, fromDate: String?, toDate: String?, search: String?): ToolResult

    fun createEvent(
        title: String,
        start: String,
        end: String?,
        location: String?,
        description: String?,
        allDay: Boolean?,
        reminderMinutes: Int?,
        calendarName: String?,
        attendees: List<String>?,
        recurrence: String?,
        recurrenceCount: Int?,
        recurrenceUntil: String?
    ): ToolResult
}

/** Production [DeviceCalendar] — a straight pass-through to the existing CalendarContract tool. */
class CalendarToolDevice(private val tool: CalendarTool) : DeviceCalendar {

    override fun listEvents(
        daysAhead: Int?,
        fromDate: String?,
        toDate: String?,
        search: String?
    ): ToolResult = tool.listEvents(daysAhead, fromDate, toDate, search)

    @Suppress("LongParameterList")
    override fun createEvent(
        title: String,
        start: String,
        end: String?,
        location: String?,
        description: String?,
        allDay: Boolean?,
        reminderMinutes: Int?,
        calendarName: String?,
        attendees: List<String>?,
        recurrence: String?,
        recurrenceCount: Int?,
        recurrenceUntil: String?
    ): ToolResult = tool.createEvent(
        title = title,
        start = start,
        end = end,
        location = location,
        description = description,
        allDay = allDay,
        reminderMinutes = reminderMinutes,
        calendarName = calendarName,
        attendees = attendees,
        recurrence = recurrence,
        recurrenceCount = recurrenceCount,
        recurrenceUntil = recurrenceUntil
    )
}
