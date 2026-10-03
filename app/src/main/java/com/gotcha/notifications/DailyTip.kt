package com.gotcha.notifications

import com.gotcha.tools.AgentMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * One use case the daily tip notification can suggest (issue #101).
 *
 * [title] and [body] are what the notification says; [prompt] is what lands in
 * the composer of the new chat a tap opens. Like the home-screen starters it is
 * a draft, not a request: the user edits and sends, so it may carry an unfilled
 * `…` where a name or a message belongs.
 *
 * [tools] are the tools the use case leans on. A tip whose tools the user has
 * never run is preferred, so the tip shows them something new. [agent] is the
 * mode the new chat starts in: the device actions need Operator, and a fresh
 * chat opened for them has no earlier choice of mode to override.
 */
data class DailyTip(
    val id: String,
    val title: String,
    val body: String,
    val prompt: String,
    val tools: Set<String>,
    val agent: AgentMode = AgentMode.MONITOR
)

/** The use cases a daily tip is drawn from. Ids are persisted, so keep them stable. */
val DAILY_TIPS = listOf(
    DailyTip(
        id = "read_screen",
        title = "Ask about your screen",
        body = "Stuck on something? Gotcha can read your screen and explain it.",
        prompt = "What's on my screen?",
        tools = setOf("read_screen")
    ),
    DailyTip(
        id = "catch_up",
        title = "Catch up in seconds",
        body = "Let Gotcha sum up your unread notifications.",
        prompt = "Catch me up on my unread notifications",
        tools = setOf("read_notifications")
    ),
    DailyTip(
        id = "big_files",
        title = "Free up some space",
        body = "Gotcha can find your largest files and tell you what's safe to delete.",
        prompt = "Find the largest files in my Downloads folder and tell me what's safe to delete",
        tools = setOf("list_files", "get_storage_info")
    ),
    DailyTip(
        id = "summarise_pdf",
        title = "Summarise a PDF",
        body = "No time to read it? Gotcha can summarise your latest PDF.",
        prompt = "Read the most recent PDF in my Downloads and summarise it",
        tools = setOf("read_file")
    ),
    DailyTip(
        id = "calendar_today",
        title = "Plan your day",
        body = "Ask Gotcha what's on your calendar today.",
        prompt = "What's on my calendar today?",
        tools = setOf("list_calendar_events")
    ),
    DailyTip(
        id = "calendar_add",
        title = "Add to your calendar",
        body = "Tell Gotcha about a plan and it goes straight into your calendar.",
        prompt = "Add lunch with … on Friday at 1pm to my calendar",
        tools = setOf("create_calendar_event"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "set_alarm",
        title = "Set an alarm by asking",
        body = "Gotcha can set alarms and timers for you.",
        prompt = "Set an alarm for 7am tomorrow",
        tools = setOf("set_alarm", "set_timer"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "send_whatsapp",
        title = "Send a WhatsApp hands-free",
        body = "Gotcha can open WhatsApp and send the message for you.",
        prompt = "Send a WhatsApp to … saying …",
        tools = setOf("navigate_app"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "send_sms",
        title = "Running late?",
        body = "Gotcha can text someone for you.",
        prompt = "Text … that I'm running 10 minutes late",
        tools = setOf("send_sms"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "recent_sms",
        title = "Skim your messages",
        body = "Gotcha can summarise your latest texts.",
        prompt = "Summarise my latest text messages",
        tools = setOf("read_recent_sms")
    ),
    DailyTip(
        id = "missed_calls",
        title = "Who called?",
        body = "Ask Gotcha to go through your call log.",
        prompt = "Who called me today?",
        tools = setOf("read_call_log")
    ),
    DailyTip(
        id = "battery",
        title = "What's draining your battery?",
        body = "Gotcha can check your battery and which apps use it most.",
        prompt = "Check my battery and tell me which apps have been using the most",
        tools = setOf("get_battery_info", "get_app_usage")
    ),
    DailyTip(
        id = "screen_time",
        title = "Check your screen time",
        body = "Ask Gotcha how long you spent on your phone, and where.",
        prompt = "How much time did I spend on my phone today, and on which apps?",
        tools = setOf("get_app_usage")
    ),
    DailyTip(
        id = "web_search",
        title = "Research anything",
        body = "Gotcha can search the web and sum up what it finds.",
        prompt = "Search the web for … and summarise what you find",
        tools = setOf("websearch", "webfetch")
    ),
    DailyTip(
        id = "do_not_disturb",
        title = "Need to focus?",
        body = "Gotcha can turn on Do Not Disturb for you.",
        prompt = "Turn on Do Not Disturb for the next hour",
        tools = setOf("set_dnd"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "wifi",
        title = "Change settings by asking",
        body = "Gotcha can find and flip settings for you, like Wi-Fi.",
        prompt = "Open settings and turn on Wi-Fi",
        tools = setOf("toggle_wifi", "open_setting"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "podcast",
        title = "Listen instead of reading",
        body = "Gotcha can turn a document into a short podcast.",
        prompt = "Turn the most recent PDF in my Downloads into a short podcast",
        tools = setOf("synthesize_podcast", "synthesize_podcast_dialogue"),
        agent = AgentMode.OPERATOR
    )
)

/** How many recently shown tips are kept out of the draw. */
internal const val RECENT_TIP_MEMORY = 10

/**
 * Chooses the day's tip. Pure, so the rules are unit tested:
 *  1. tips in [recentIds] are skipped, so a week never repeats one;
 *  2. of the rest, tips whose tools the user has never run ([usedTools]) win;
 *  3. ties are broken at random.
 * When every tip is recent, the draw starts over, still avoiding yesterday's.
 */
internal fun pickDailyTip(
    tips: List<DailyTip>,
    recentIds: List<String>,
    usedTools: Set<String>,
    random: Random = Random.Default
): DailyTip? {
    if (tips.isEmpty()) return null
    val fresh = tips.filter { it.id !in recentIds }
        .ifEmpty { tips.filter { it.id != recentIds.lastOrNull() } }
        .ifEmpty { tips }
    val untried = fresh.filter { tip -> tip.tools.none { it in usedTools } }
    return untried.ifEmpty { fresh }.random(random)
}

/** [recentIds] with [shownId] appended, trimmed to the newest [RECENT_TIP_MEMORY]. */
internal fun rememberShownTip(recentIds: List<String>, shownId: String): List<String> =
    (recentIds.filter { it != shownId } + shownId).takeLast(RECENT_TIP_MEMORY)

/**
 * Epoch millis of the next [minuteOfDay] strictly after [now] in [zone]. Built
 * from the local date and time rather than by adding 24 hours, so a daylight
 * saving change keeps the tip at the same time on the clock.
 */
internal fun nextDailyTipAt(nowMillis: Long, minuteOfDay: Int, zone: ZoneId): Long {
    val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
    val time = LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60)
    val today = ZonedDateTime.of(now.toLocalDate(), time, zone)
    val next = if (today.isAfter(now)) today else ZonedDateTime.of(now.toLocalDate().plusDays(1), time, zone)
    return next.toInstant().toEpochMilli()
}

/** True when [lastActiveMillis] falls on the same local day as [nowMillis]. */
internal fun usedToday(lastActiveMillis: Long?, nowMillis: Long, zone: ZoneId): Boolean {
    if (lastActiveMillis == null) return false
    fun day(millis: Long): LocalDate = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return day(lastActiveMillis) == day(nowMillis)
}
