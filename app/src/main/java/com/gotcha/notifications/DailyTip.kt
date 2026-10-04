package com.gotcha.notifications

import androidx.annotation.StringRes
import com.gotcha.R
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
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val prompt: Int,
    val tools: Set<String>,
    val agent: AgentMode = AgentMode.MONITOR
)

/** The use cases a daily tip is drawn from. Ids are persisted, so keep them stable. */
val DAILY_TIPS = listOf(
    DailyTip(
        id = "read_screen",
        title = R.string.daily_tip_read_screen_title,
        body = R.string.daily_tip_read_screen_body,
        prompt = R.string.daily_tip_read_screen_prompt,
        tools = setOf("read_screen")
    ),
    DailyTip(
        id = "catch_up",
        title = R.string.daily_tip_catch_up_title,
        body = R.string.daily_tip_catch_up_body,
        prompt = R.string.daily_tip_catch_up_prompt,
        tools = setOf("read_notifications")
    ),
    DailyTip(
        id = "big_files",
        title = R.string.daily_tip_big_files_title,
        body = R.string.daily_tip_big_files_body,
        prompt = R.string.daily_tip_big_files_prompt,
        tools = setOf("list_files", "get_storage_info")
    ),
    DailyTip(
        id = "summarise_pdf",
        title = R.string.daily_tip_summarise_pdf_title,
        body = R.string.daily_tip_summarise_pdf_body,
        prompt = R.string.daily_tip_summarise_pdf_prompt,
        tools = setOf("read_file")
    ),
    DailyTip(
        id = "calendar_today",
        title = R.string.daily_tip_calendar_today_title,
        body = R.string.daily_tip_calendar_today_body,
        prompt = R.string.daily_tip_calendar_today_prompt,
        tools = setOf("list_calendar_events")
    ),
    DailyTip(
        id = "calendar_add",
        title = R.string.daily_tip_calendar_add_title,
        body = R.string.daily_tip_calendar_add_body,
        prompt = R.string.daily_tip_calendar_add_prompt,
        tools = setOf("create_calendar_event"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "set_alarm",
        title = R.string.daily_tip_set_alarm_title,
        body = R.string.daily_tip_set_alarm_body,
        prompt = R.string.daily_tip_set_alarm_prompt,
        tools = setOf("set_alarm", "set_timer"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "send_whatsapp",
        title = R.string.daily_tip_send_whatsapp_title,
        body = R.string.daily_tip_send_whatsapp_body,
        prompt = R.string.daily_tip_send_whatsapp_prompt,
        tools = setOf("navigate_app"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "send_sms",
        title = R.string.daily_tip_send_sms_title,
        body = R.string.daily_tip_send_sms_body,
        prompt = R.string.daily_tip_send_sms_prompt,
        tools = setOf("send_sms"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "recent_sms",
        title = R.string.daily_tip_recent_sms_title,
        body = R.string.daily_tip_recent_sms_body,
        prompt = R.string.daily_tip_recent_sms_prompt,
        tools = setOf("read_recent_sms")
    ),
    DailyTip(
        id = "missed_calls",
        title = R.string.daily_tip_missed_calls_title,
        body = R.string.daily_tip_missed_calls_body,
        prompt = R.string.daily_tip_missed_calls_prompt,
        tools = setOf("read_call_log")
    ),
    DailyTip(
        id = "battery",
        title = R.string.daily_tip_battery_title,
        body = R.string.daily_tip_battery_body,
        prompt = R.string.daily_tip_battery_prompt,
        tools = setOf("get_battery_info", "get_app_usage")
    ),
    DailyTip(
        id = "screen_time",
        title = R.string.daily_tip_screen_time_title,
        body = R.string.daily_tip_screen_time_body,
        prompt = R.string.daily_tip_screen_time_prompt,
        tools = setOf("get_app_usage")
    ),
    DailyTip(
        id = "web_search",
        title = R.string.daily_tip_web_search_title,
        body = R.string.daily_tip_web_search_body,
        prompt = R.string.daily_tip_web_search_prompt,
        tools = setOf("websearch", "webfetch")
    ),
    DailyTip(
        id = "do_not_disturb",
        title = R.string.daily_tip_do_not_disturb_title,
        body = R.string.daily_tip_do_not_disturb_body,
        prompt = R.string.daily_tip_do_not_disturb_prompt,
        tools = setOf("set_dnd"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "wifi",
        title = R.string.daily_tip_wifi_title,
        body = R.string.daily_tip_wifi_body,
        prompt = R.string.daily_tip_wifi_prompt,
        tools = setOf("toggle_wifi", "open_setting"),
        agent = AgentMode.OPERATOR
    ),
    DailyTip(
        id = "podcast",
        title = R.string.daily_tip_podcast_title,
        body = R.string.daily_tip_podcast_body,
        prompt = R.string.daily_tip_podcast_prompt,
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
