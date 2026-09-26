package com.gotcha.notifications

import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.tools.AgentMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What a notification in Gotcha is about (issue #100). The first four are
 * proactive: Gotcha decides to send them from on-device behaviour, so they share
 * the master switch, quiet hours and the daily cap. Task-finished and server
 * messages answer something already asked for or sent, and only appear here so
 * the inbox can list them.
 */
enum class NotificationCategory(val label: String, val proactive: Boolean) {
    UNFINISHED_CHAT("Unfinished chat", proactive = true),
    ROUTINE("Routine", proactive = true),
    INACTIVITY("Reminder", proactive = true),
    DAILY_TIP("Daily tip", proactive = true),
    TASK_FINISHED("Task finished", proactive = false),
    SERVER("From Samosa AI", proactive = false)
}

/** Where tapping a notification (or its inbox entry) goes. */
sealed interface NotificationTarget {
    /** Gotcha's home screen: there is no more specific place. */
    data object Home : NotificationTarget

    /** An existing chat. */
    data class Chat(val sessionId: String) : NotificationTarget

    /** A new chat in [mode], with [prompt] in the composer and not sent. */
    data class Draft(val prompt: String, val mode: AgentMode) : NotificationTarget
}

/**
 * A notification Gotcha could send. [dedupKey] names the occasion — this
 * failed run, this inactive spell, today's tip — so the same occasion is never
 * notified twice.
 */
data class LocalCandidate(
    val category: NotificationCategory,
    val dedupKey: String,
    val title: String,
    val body: String,
    val target: NotificationTarget
)

/** One chat, as the triggers see it. Sample chats are left out before this is built. */
data class ChatDigest(
    val id: String,
    val title: String,
    val sensitive: Boolean,
    val runs: List<RunSummary>
)

/** A proactive notification that was sent: the memory behind the daily cap and deduplication. */
@kotlinx.serialization.Serializable
data class SentRecord(val key: String, val category: String, val at: Long)

internal const val HOUR_MS = 3_600_000L
internal const val DAY_MS = 24L * HOUR_MS

/** A chat is only called unfinished once the user has had this long to come back to it. */
internal const val UNFINISHED_MIN_AGE_MS = 2L * HOUR_MS

/** ...and not once it is this old: by then it has been left, not forgotten. */
internal const val UNFINISHED_MAX_AGE_MS = 3L * DAY_MS

/** Someone who opened Gotcha this recently is using it, and needs no reminder. */
internal const val RECENTLY_OPENED_MS = 2L * HOUR_MS

/** One proactive notification per category in this window, whatever the cap allows. */
internal const val CATEGORY_COOLDOWN_MS = 20L * HOUR_MS

/** A request asked on this many different days is a routine. */
internal const val ROUTINE_MIN_DAYS = 3

/**
 * True when [minuteOfDay] falls inside quiet hours from [start] to [end]
 * (minutes after midnight). A window that crosses midnight, like the default
 * 22:00–08:00, wraps; equal ends mean no quiet hours at all.
 */
internal fun inQuietHours(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
    start == end -> false
    start < end -> minuteOfDay in start until end
    else -> minuteOfDay >= start || minuteOfDay < end
}

/**
 * Why [candidate] may not be sent now, or null when it may. Pure, so every
 * anti-spam rule is unit tested:
 *  - the master switch and the candidate's own category switch;
 *  - quiet hours;
 *  - deduplication: an occasion already notified is never notified again;
 *  - a cooldown per category, and a cap on proactive notifications per day.
 */
internal fun blockReason(
    candidate: LocalCandidate,
    settings: Settings,
    sent: List<SentRecord>,
    now: Long,
    zone: ZoneId
): String? {
    if (!settings.localNotificationsEnabled) return "off"
    if (!categoryEnabled(candidate.category, settings)) return "category_off"
    val local = Instant.ofEpochMilli(now).atZone(zone)
    if (settings.quietHoursEnabled &&
        inQuietHours(local.hour * 60 + local.minute, settings.quietHoursStartMinute, settings.quietHoursEndMinute)
    ) {
        return "quiet_hours"
    }
    if (sent.any { it.key == candidate.dedupKey }) return "duplicate"
    if (sent.any { it.category == candidate.category.name && now - it.at < CATEGORY_COOLDOWN_MS }) {
        return "cooldown"
    }
    val today = local.toLocalDate()
    val sentToday = sent.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
    if (sentToday >= settings.maxLocalNotificationsPerDay) return "daily_cap"
    return null
}

private fun categoryEnabled(category: NotificationCategory, settings: Settings): Boolean = when (category) {
    NotificationCategory.UNFINISHED_CHAT -> settings.unfinishedChatRemindersEnabled
    NotificationCategory.ROUTINE -> settings.routineSuggestionsEnabled
    NotificationCategory.INACTIVITY -> settings.inactivityRemindersEnabled
    NotificationCategory.DAILY_TIP -> settings.dailyTipsEnabled
    NotificationCategory.TASK_FINISHED -> settings.chatCompletionNotificationsEnabled
    NotificationCategory.SERVER -> settings.serverMessagesEnabled
}

/**
 * The behaviour-based notifications worth sending now, best first: a chat left
 * unfinished, then a routine that is due, then a reminder after a quiet spell.
 * Sensitive chats are never used. With [mention] off, the text stays generic
 * and names no chat or request; the tap still goes to the same place.
 */
internal fun behaviorCandidates(
    chats: List<ChatDigest>,
    lastOpenedAt: Long,
    inactivityDays: Int,
    mention: Boolean,
    now: Long,
    zone: ZoneId
): List<LocalCandidate> {
    val usable = chats.filterNot { it.sensitive }
    return listOfNotNull(
        unfinishedChat(usable, mention, now),
        dueRoutine(usable, mention, now, zone),
        inactivityReminder(usable, lastOpenedAt, inactivityDays, mention, now)
    )
}

/**
 * The most recent chat whose last run failed, was stopped, or ended on a
 * question to the user — between [UNFINISHED_MIN_AGE_MS] and
 * [UNFINISHED_MAX_AGE_MS] ago.
 */
internal fun unfinishedChat(chats: List<ChatDigest>, mention: Boolean, now: Long): LocalCandidate? {
    val (chat, run) = chats
        .mapNotNull { chat -> chat.runs.maxByOrNull { it.endedAt }?.let { chat to it } }
        .filter { (_, run) -> now - run.endedAt in UNFINISHED_MIN_AGE_MS..UNFINISHED_MAX_AGE_MS }
        .filter { (_, run) -> !run.succeeded || run.finalReply.trimEnd().endsWith("?") }
        .maxByOrNull { (_, run) -> run.endedAt }
        ?: return null
    val asked = run.succeeded
    val body = when {
        !mention -> "A chat was left unfinished. Tap to pick it up."
        asked -> "Gotcha asked you something in “${chat.title}”. Tap to answer."
        else -> "“${chat.title}” stopped before it was done. Tap to pick it up."
    }
    return LocalCandidate(
        category = NotificationCategory.UNFINISHED_CHAT,
        dedupKey = "unfinished:${chat.id}:${run.endedAt}",
        title = if (asked) "Gotcha is waiting for you" else "Finish what you started",
        body = body,
        target = NotificationTarget.Chat(chat.id)
    )
}

/**
 * A request the user makes regularly that is due again: asked on at least
 * [ROUTINE_MIN_DAYS] different days, and as many days have passed since the
 * last time as usually do (the median gap), but no more than two beyond it —
 * a routine long abandoned is not nagged about.
 */
internal fun dueRoutine(chats: List<ChatDigest>, mention: Boolean, now: Long, zone: ZoneId): LocalCandidate? {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val runs = chats.flatMap { it.runs }.filter { it.succeeded && routineKey(it.userPrompt).isNotEmpty() }
    return runs.groupBy { routineKey(it.userPrompt) }
        .mapNotNull { (key, group) ->
            val days = group.map { day(it.endedAt) }.distinct().sorted()
            if (days.size < ROUTINE_MIN_DAYS) return@mapNotNull null
            val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
            val usualGap = gaps[gaps.size / 2]
            val sinceLast = ChronoUnit.DAYS.between(days.last(), today)
            if (sinceLast < usualGap || sinceLast > usualGap + 2) return@mapNotNull null
            val latest = group.maxBy { it.endedAt }
            Triple(key, latest, days.size)
        }
        // The most established routine wins.
        .maxByOrNull { it.third }
        ?.let { (key, latest, _) ->
            val prompt = latest.userPrompt.trim()
            val preview = if (prompt.length > ROUTINE_PREVIEW_CHARS) {
                prompt.take(ROUTINE_PREVIEW_CHARS).trimEnd() + "…"
            } else {
                prompt
            }
            LocalCandidate(
                category = NotificationCategory.ROUTINE,
                dedupKey = "routine:${key.hashCode()}:${day(latest.endedAt)}",
                title = "Time for your usual?",
                body = if (mention) {
                    "You often ask: “$preview”. Tap to ask again."
                } else {
                    "One of your regular requests may be due. Tap to see it."
                },
                target = NotificationTarget.Draft(
                    prompt = prompt,
                    mode = runCatching { AgentMode.valueOf(latest.agentMode) }.getOrDefault(AgentMode.MONITOR)
                )
            )
        }
}

private const val ROUTINE_PREVIEW_CHARS = 80

/**
 * What makes two requests "the same" for routine detection: case, digits
 * (dates, times, amounts) and punctuation are ignored. Too short a request is
 * not a routine.
 */
internal fun routineKey(prompt: String): String {
    val key = prompt.lowercase()
        // A time or amount ("7:30", "2.5") is one number, not two.
        .replace(Regex("\\d+([:.,]\\d+)*"), "#")
        .replace(Regex("[^\\p{L}#]+"), " ")
        .trim()
        .take(ROUTINE_KEY_CHARS)
    return if (key.count { it.isLetter() } < ROUTINE_MIN_LETTERS) "" else key
}

private const val ROUTINE_KEY_CHARS = 80
private const val ROUTINE_MIN_LETTERS = 8

/**
 * A nudge once Gotcha has not been opened for [inactivityDays]. One per quiet
 * spell: the key is the last time it was opened. With [mention] on it offers
 * the most recent chat back.
 */
internal fun inactivityReminder(
    chats: List<ChatDigest>,
    lastOpenedAt: Long,
    inactivityDays: Int,
    mention: Boolean,
    now: Long
): LocalCandidate? {
    if (lastOpenedAt <= 0L || now - lastOpenedAt < inactivityDays * DAY_MS) return null
    val recent = chats
        .mapNotNull { chat -> chat.runs.maxOfOrNull { it.endedAt }?.let { chat to it } }
        .maxByOrNull { it.second }
        ?.first
        ?.takeIf { mention }
    return LocalCandidate(
        category = NotificationCategory.INACTIVITY,
        dedupKey = "inactive:$lastOpenedAt",
        title = if (recent != null) "Pick up where you left off" else "Anything Gotcha can do for you?",
        body = if (recent != null) {
            "Your last chat was “${recent.title}”. Tap to open it."
        } else {
            "Ask Gotcha to handle something on your phone. Tap to open."
        },
        target = recent?.let { NotificationTarget.Chat(it.id) } ?: NotificationTarget.Home
    )
}

/** The daily tip as a candidate: at most one a day. */
internal fun tipCandidate(tip: DailyTip, now: Long, zone: ZoneId): LocalCandidate = LocalCandidate(
    category = NotificationCategory.DAILY_TIP,
    dedupKey = "tip:${Instant.ofEpochMilli(now).atZone(zone).toLocalDate()}",
    title = tip.title,
    body = tip.body,
    target = NotificationTarget.Draft(tip.prompt, tip.agent)
)

/**
 * Whether a chat is kept out of notifications. The user's own choice wins;
 * otherwise chats with the Doctor persona are, since health is private.
 */
internal fun isChatSensitive(personaId: String?, choice: Boolean?): Boolean =
    choice ?: (personaId in SENSITIVE_PERSONAS)

/** Personas whose chats are kept out of notifications unless the user says otherwise. */
internal val SENSITIVE_PERSONAS = setOf("doctor")
