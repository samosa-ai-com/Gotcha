package com.gotcha.connectors.calendar

import com.gotcha.connectors.ToolRouter
import com.gotcha.connectors.google.GoogleConnector
import com.gotcha.connectors.microsoft.MicrosoftConnector
import com.gotcha.tools.CalendarRecurrence
import com.gotcha.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Router owning the calendar tools across three backends.
 *
 * The on-device calendar (CalendarContract, via [DeviceCalendar]) stays the default and is used
 * verbatim — connector calendars are opt-in via the `source` argument, so every
 * existing prompt and behaviour is unchanged when nothing is connected. There is
 * deliberately one tool per operation rather than parallel `gcal_*`/`ms_*` tools.
 *
 * Event ids: device events keep their bare numeric ids (so `edit_calendar_event`
 * and `delete_calendar_event`, which stay device-only, keep working), while
 * connector events are prefixed `gcal:` / `ms:`.
 */
@Suppress("TooManyFunctions") // three tools x three backends, plus shared JSON/arg helpers
class CalendarTools(
    private val device: DeviceCalendar,
    private val google: () -> GoogleConnector?,
    private val microsoft: () -> MicrosoftConnector?,
    private val clock: () -> Long = System::currentTimeMillis
) : ToolRouter {

    companion object {
        private const val DEFAULT_MAX = 50
        private const val DEFAULT_SLOT_MINUTES = 30
        private const val MINUTE_MILLIS = 60_000L
        private const val MAX_FREE_SLOTS = 12
        const val SOURCE_DEVICE = "device"
        const val SOURCE_GOOGLE = "google"
        const val SOURCE_MICROSOFT = "microsoft"
    }

    override val toolNames: Set<String> =
        setOf("list_calendar_events", "create_calendar_event", "check_availability")

    override suspend fun execute(name: String, args: JsonObject): ToolResult = try {
        when (name) {
            "list_calendar_events" -> listEvents(args)
            "create_calendar_event" -> createEvent(args)
            "check_availability" -> checkAvailability(args)
            else -> ToolResult.error("Unknown calendar tool '$name'.")
        }
    } catch (e: Exception) {
        ToolResult.error("$name failed: ${e.message}")
    }

    // ---- list_calendar_events ----

    private suspend fun listEvents(args: JsonObject): ToolResult {
        val window = window(args)
        return when (val source = args.optString("source")?.lowercase() ?: SOURCE_DEVICE) {
            SOURCE_DEVICE -> device.listEvents(
                daysAhead = args.optInt("days_ahead"),
                fromDate = args.optString("from_date"),
                toDate = args.optString("to_date"),
                search = args.optString("search")
            )
            SOURCE_GOOGLE -> listGoogleEvents(args, window)
            SOURCE_MICROSOFT -> listMicrosoftEvents(args, window)
            else -> unknownSource(source)
        }
    }

    private suspend fun listGoogleEvents(args: JsonObject, window: Window): ToolResult {
        val connector = readyGoogle() ?: return googleUnavailable()
        val rows = connector.listCalendarEvents(
            calendarId = args.optString("calendar_name") ?: "primary",
            timeMin = CalendarWindow.toRfc3339(window.startMs),
            timeMax = CalendarWindow.toRfc3339(window.endMs),
            query = args.optString("search"),
            max = DEFAULT_MAX
        ).map { it.jsonObject }

        if (rows.isEmpty()) return ToolResult.ok("No Google Calendar events ${window.description}.")
        val text = rows.joinToString("\n") { event ->
            val start = event["start"]?.jsonObject?.let {
                it.str("dateTime") ?: it.str("date")
            }.orEmpty()
            val end = event["end"]?.jsonObject?.let { it.str("dateTime") ?: it.str("date") }.orEmpty()
            val location = event.str("location")?.let { " @ $it" }.orEmpty()
            "[gcal:${event.str("id").orEmpty()}] ${event.str("summary") ?: "(no title)"}\n" +
                "    $start → $end$location"
        }
        return ToolResult.ok("Google Calendar events ${window.description} (${rows.size}):\n$text")
    }

    private suspend fun listMicrosoftEvents(args: JsonObject, window: Window): ToolResult {
        val connector = readyMicrosoft() ?: return microsoftUnavailable()
        val search = args.optString("search")
        val rows = connector.calendarView(
            CalendarWindow.toRfc3339(window.startMs),
            CalendarWindow.toRfc3339(window.endMs),
            DEFAULT_MAX
        ).map { it.jsonObject }.filter { event ->
            search == null || event.str("subject")?.contains(search, ignoreCase = true) == true
        }

        if (rows.isEmpty()) return ToolResult.ok("No Outlook calendar events ${window.description}.")
        val text = rows.joinToString("\n") { event ->
            val start = event["start"]?.jsonObject?.str("dateTime").orEmpty()
            val end = event["end"]?.jsonObject?.str("dateTime").orEmpty()
            val location = event["location"]?.jsonObject?.str("displayName")
                ?.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
            "[ms:${event.str("id").orEmpty()}] ${event.str("subject") ?: "(no title)"}\n" +
                "    $start → $end$location"
        }
        return ToolResult.ok("Outlook calendar events ${window.description} (${rows.size}):\n$text")
    }

    // ---- create_calendar_event ----

    private suspend fun createEvent(args: JsonObject): ToolResult =
        when (val source = args.optString("source")?.lowercase() ?: SOURCE_DEVICE) {
            SOURCE_DEVICE -> createDeviceEvent(args)
            SOURCE_GOOGLE -> createGoogleEvent(args)
            SOURCE_MICROSOFT -> createMicrosoftEvent(args)
            else -> unknownSource(source)
        }

    private fun createDeviceEvent(args: JsonObject): ToolResult {
        val title = args.optString("title")
            ?: return ToolResult.error("create_calendar_event needs a 'title'.")
        val start = args.optString("start")
            ?: return ToolResult.error("create_calendar_event needs a 'start'.")
        return device.createEvent(
            title = title,
            start = start,
            end = args.optString("end"),
            location = args.optString("location"),
            description = args.optString("description"),
            allDay = args.optBoolean("all_day"),
            reminderMinutes = args.optInt("reminder_minutes"),
            calendarName = args.optString("calendar_name"),
            attendees = args.optStringList("attendees"),
            recurrence = args.optString("recurrence"),
            recurrenceCount = args.optInt("recurrence_count"),
            recurrenceUntil = args.optString("recurrence_until")
        )
    }

    private suspend fun createGoogleEvent(args: JsonObject): ToolResult {
        val connector = readyGoogle() ?: return googleUnavailable()
        val spec = parseNewEvent(args) ?: return missingTimes()
        val payload = buildJsonObject {
            put("summary", JsonPrimitive(spec.title))
            spec.location?.let { put("location", JsonPrimitive(it)) }
            spec.description?.let { put("description", JsonPrimitive(it)) }
            put("start", googleTime(spec.startMs))
            put("end", googleTime(spec.endMs))
            if (spec.attendees.isNotEmpty()) {
                put(
                    "attendees",
                    buildJsonArray {
                        spec.attendees.forEach {
                            add(buildJsonObject { put("email", JsonPrimitive(it)) })
                        }
                    }
                )
            }
        }
        val id = connector.insertCalendarEvent(args.optString("calendar_name") ?: "primary", payload)
        return ToolResult.ok(
            "Added '${spec.title}' to Google Calendar on ${CalendarWindow.readable(spec.startMs)} " +
                "(id gcal:$id)."
        )
    }

    private suspend fun createMicrosoftEvent(args: JsonObject): ToolResult {
        val connector = readyMicrosoft() ?: return microsoftUnavailable()
        val spec = parseNewEvent(args) ?: return missingTimes()
        val payload = buildJsonObject {
            put("subject", JsonPrimitive(spec.title))
            spec.description?.let {
                put(
                    "body",
                    buildJsonObject {
                        put("contentType", JsonPrimitive("text"))
                        put("content", JsonPrimitive(it))
                    }
                )
            }
            spec.location?.let {
                put("location", buildJsonObject { put("displayName", JsonPrimitive(it)) })
            }
            put("start", graphTime(spec.startMs))
            put("end", graphTime(spec.endMs))
            if (spec.attendees.isNotEmpty()) {
                put(
                    "attendees",
                    buildJsonArray {
                        spec.attendees.forEach { addr ->
                            add(
                                buildJsonObject {
                                    put(
                                        "emailAddress",
                                        buildJsonObject { put("address", JsonPrimitive(addr)) }
                                    )
                                    put("type", JsonPrimitive("required"))
                                }
                            )
                        }
                    }
                )
            }
        }
        val id = connector.createEvent(payload)
        return ToolResult.ok(
            "Added '${spec.title}' to the Outlook calendar on " +
                "${CalendarWindow.readable(spec.startMs)} (id ms:$id)."
        )
    }

    // ---- check_availability ----

    private suspend fun checkAvailability(args: JsonObject): ToolResult {
        val window = window(args)
        val slotMinutes = (args.optInt("duration_minutes") ?: DEFAULT_SLOT_MINUTES).coerceAtLeast(1)
        val busy = mutableListOf<BusyBlock>()
        val sources = mutableListOf<String>()

        google()?.takeIf { it.isConnected() && it.hasCalendar() }?.let { connector ->
            val calendars = connector.freeBusy(
                listOf("primary"),
                CalendarWindow.toRfc3339(window.startMs),
                CalendarWindow.toRfc3339(window.endMs)
            )
            calendars.values.forEach { calendar ->
                calendar.jsonObject["busy"]?.jsonArray?.forEach { entry ->
                    val block = entry.jsonObject
                    val start = CalendarWindow.parseRfc3339(block.str("start"))
                    val end = CalendarWindow.parseRfc3339(block.str("end"))
                    if (start != null && end != null) busy.add(BusyBlock(start, end))
                }
            }
            sources.add("Google Calendar")
        }

        microsoft()?.takeIf { it.isConnected() }?.let { connector ->
            connector.busyBlocks(
                CalendarWindow.toRfc3339(window.startMs),
                CalendarWindow.toRfc3339(window.endMs),
                slotMinutes
            ).forEach { schedule ->
                schedule.jsonObject["scheduleItems"]?.jsonArray?.forEach { entry ->
                    val item = entry.jsonObject
                    val start = CalendarWindow.parseRfc3339(item["start"]?.jsonObject?.str("dateTime"))
                    val end = CalendarWindow.parseRfc3339(item["end"]?.jsonObject?.str("dateTime"))
                    if (start != null && end != null) busy.add(BusyBlock(start, end))
                }
            }
            sources.add("Outlook")
        }

        if (sources.isEmpty()) {
            return ToolResult.error(
                "check_availability needs a connected calendar account (Google with the Calendar " +
                    "scope, or Microsoft) — connect one in Settings → Connectors. For on-device " +
                    "calendars use list_calendar_events and read the gaps."
            )
        }

        val merged = CalendarWindow.merge(busy)
        val free = CalendarWindow.freeSlots(
            busy,
            window.startMs,
            window.endMs,
            slotMinutes * MINUTE_MILLIS
        )
        val busyText = if (merged.isEmpty()) {
            "Busy: nothing."
        } else {
            "Busy (${merged.size}):\n" + merged.joinToString("\n") {
                "    ${CalendarWindow.readable(it.startMs)} → ${CalendarWindow.readable(it.endMs)}"
            }
        }
        val freeText = if (free.isEmpty()) {
            "No free slot of at least $slotMinutes minutes."
        } else {
            "Free slots of ≥$slotMinutes min (showing ${minOf(free.size, MAX_FREE_SLOTS)} " +
                "of ${free.size}):\n" +
                free.take(MAX_FREE_SLOTS).joinToString("\n") {
                    "    ${CalendarWindow.readable(it.startMs)} → ${CalendarWindow.readable(it.endMs)}"
                }
        }
        return ToolResult.ok(
            "Availability ${window.description} from ${sources.joinToString(" + ")}:\n" +
                "$busyText\n$freeText"
        )
    }

    // ---- shared helpers ----

    private data class NewEvent(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val location: String?,
        val description: String?,
        val attendees: List<String>
    )

    private fun parseNewEvent(args: JsonObject): NewEvent? {
        val title = args.optString("title") ?: return null
        val start = args.optString("start")?.let { CalendarRecurrence.parseWhen(it) } ?: return null
        val end = args.optString("end")?.let { CalendarRecurrence.parseWhen(it) }
            ?: (start + 60 * MINUTE_MILLIS)
        return NewEvent(
            title = title,
            startMs = start,
            endMs = end,
            location = args.optString("location"),
            description = args.optString("description"),
            attendees = args.optStringList("attendees").orEmpty()
        )
    }

    private fun missingTimes(): ToolResult = ToolResult.error(
        "create_calendar_event needs 'title' and a parseable 'start' " +
            "(e.g. '2026-07-17 15:30')."
    )

    private fun window(args: JsonObject): Window = CalendarWindow.resolve(
        daysAhead = args.optInt("days_ahead"),
        fromDate = args.optString("from_date"),
        toDate = args.optString("to_date"),
        now = clock()
    )

    private fun googleTime(epochMs: Long): JsonObject = buildJsonObject {
        put("dateTime", JsonPrimitive(CalendarWindow.toRfc3339(epochMs)))
        put("timeZone", JsonPrimitive("UTC"))
    }

    private fun graphTime(epochMs: Long): JsonObject = buildJsonObject {
        put("dateTime", JsonPrimitive(CalendarWindow.toRfc3339(epochMs).removeSuffix("Z")))
        put("timeZone", JsonPrimitive("UTC"))
    }

    /** The Google connector when it can actually serve calendar calls, else null. */
    private fun readyGoogle(): GoogleConnector? =
        google()?.takeIf { it.isConnected() && it.hasCalendar() }

    /** Explains which of the two preconditions readyGoogle() failed on. */
    private fun googleUnavailable(): ToolResult {
        val connector = google()
        return if (connector != null && connector.isConnected()) {
            ToolResult.error(
                "The Google connector is signed in for Gmail only. Reconnect it in Settings → " +
                    "Connectors with \"Calendar\" ticked to grant calendar access."
            )
        } else {
            ToolResult.error(
                "Google is not connected. Connect it in Settings → Connectors, or use " +
                    "source=\"device\" for the phone's own calendar."
            )
        }
    }

    private fun readyMicrosoft(): MicrosoftConnector? = microsoft()?.takeIf { it.isConnected() }

    private fun microsoftUnavailable(): ToolResult = ToolResult.error(
        "Microsoft is not connected. Connect it in Settings → Connectors, or use " +
            "source=\"device\" for the phone's own calendar."
    )

    private fun unknownSource(source: String): ToolResult = ToolResult.error(
        "Unknown source '$source'. Use \"device\" (default), \"google\" or \"microsoft\"."
    )

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.optString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.optInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.optBoolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.optStringList(key: String): List<String>? {
        val element = this[key] ?: return null
        return runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrElse {
            element.jsonPrimitive.contentOrNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        }?.takeIf { it.isNotEmpty() }
    }
}
