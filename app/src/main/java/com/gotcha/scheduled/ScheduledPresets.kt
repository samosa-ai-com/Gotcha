package com.gotcha.scheduled

import androidx.annotation.StringRes
import com.gotcha.R
import com.gotcha.tools.AgentMode
import java.time.DayOfWeek
import java.time.LocalDateTime

/** One paused example offered in Settings ▸ Scheduled tasks. */
data class ScheduledPreset(
    /** Stable id, persisted on the task so it stays an example across launches. */
    val id: String,
    /** The task name, as stored. Examples keep English names like the briefing. */
    val name: String,
    @StringRes val description: Int,
    /** Builds the paused task, from the memory root and the moment it is seeded. */
    val build: (memoryRoot: String, now: LocalDateTime) -> ScheduledTask
)

/**
 * The examples section of the scheduled tasks page.
 *
 * Every one is seeded paused, so the first thing a user sees is what a schedule
 * is *for* rather than an empty list and a form. They cover the four shapes a
 * task can take: a daily plan, a daily reflection, a weekly review, and a
 * weekday digest. All run in MONITOR and either write nothing at all or ask
 * before saving, so turning one on can never surprise the user with an action.
 *
 * Ids are persisted, so keep them stable.
 */
object ScheduledPresets {

    const val MORNING_BRIEFING = "morning_briefing"
    const val EVENING_WRAP_UP = "evening_wrap_up"
    const val WEEKLY_REVIEW = "weekly_review"
    const val INBOX_CATCH_UP = "inbox_catch_up"

    val all: List<ScheduledPreset> = listOf(
        ScheduledPreset(
            id = MORNING_BRIEFING,
            name = MorningBriefing.TEMPLATE_NAME,
            description = R.string.scheduled_example_briefing
        ) { root, now -> MorningBriefing.template(root, now) },
        ScheduledPreset(
            id = EVENING_WRAP_UP,
            name = "Evening wrap-up",
            description = R.string.scheduled_example_evening
        ) { root, now -> wrapUp(root, now) },
        ScheduledPreset(
            id = WEEKLY_REVIEW,
            name = "Weekly review",
            description = R.string.scheduled_example_weekly
        ) { root, now -> weeklyReview(root, now) },
        ScheduledPreset(
            id = INBOX_CATCH_UP,
            name = "Inbox catch-up",
            description = R.string.scheduled_example_inbox
        ) { root, now -> inboxCatchUp(root, now) }
    )

    /** [preset] as a paused task, ready to store. */
    fun task(preset: ScheduledPreset, memoryRoot: String, now: LocalDateTime): ScheduledTask =
        preset.build(memoryRoot, now).copy(enabled = false)

    private fun wrapUp(root: String, now: LocalDateTime): ScheduledTask = ScheduledTask(
        id = "",
        name = "Evening wrap-up",
        prompt = buildString {
            appendLine("Evening wrap-up. Read-only unless I approve: create, modify or delete")
            appendLine("nothing in this run.")
            appendLine("1. SOUL.md and MEMORY.md are in your [memory_startup] message. Follow them.")
            appendLine("2. Open today's and recent notes under $root/notes and $root/areas with")
            appendLine("   ABSOLUTE paths, plus the people cards that came up today.")
            appendLine("3. Check today's calendar with list_calendar_events and my tasks with")
            appendLine("   list_scheduled_tasks.")
            appendLine("4. Reply in plain words, max ~200: what got done, what is still open, and")
            appendLine("   one thing to carry into tomorrow.")
            appendLine("5. Then propose at most three Second Brain notes or MEMORY.md edits, each")
            appendLine("   with the exact text you would save. Save nothing; I will approve in chat.")
            appendLine("Never invent an event or a fact. Your final message IS the wrap-up.")
        },
        startLocal = nextAt(now, 21, 0).toString(),
        repeat = Repeat(kind = RepeatKind.DAILY),
        agentMode = AgentMode.MONITOR.name
    )

    private fun weeklyReview(root: String, now: LocalDateTime): ScheduledTask = ScheduledTask(
        id = "",
        name = "Weekly review",
        prompt = buildString {
            appendLine("Weekly review. Read-only unless I approve: create, modify or delete")
            appendLine("nothing in this run.")
            appendLine("1. SOUL.md and MEMORY.md are in your [memory_startup] message. Follow them.")
            appendLine("2. List and open the notes touched in the last 7 days under $root/notes,")
            appendLine("   $root/areas and $root/people with ABSOLUTE paths.")
            appendLine("3. Reply in plain words, max ~250: the week in review (what happened), what")
            appendLine("   is still open, and what is safe to drop.")
            appendLine("4. Then propose the MEMORY.md updates this week needs (links to add or")
            appendLine("   remove, lines now stale) and up to three new or updated notes, with the")
            appendLine("   exact text. Save nothing; I will approve in chat.")
            appendLine("Plain words, never field names. Your final message IS the review.")
        },
        startLocal = nextWeekday(now, 18, 0, setOf(DayOfWeek.SUNDAY)).toString(),
        repeat = Repeat(kind = RepeatKind.WEEKLY, weekdays = listOf(DayOfWeek.SUNDAY.value)),
        agentMode = AgentMode.MONITOR.name
    )

    private fun inboxCatchUp(root: String, now: LocalDateTime): ScheduledTask = ScheduledTask(
        id = "",
        name = "Inbox catch-up",
        prompt = buildString {
            appendLine("Inbox catch-up. Read-only: do not send, reply to, dismiss or change")
            appendLine("anything, and do not open apps.")
            appendLine("1. Read my unread notifications with read_notifications.")
            appendLine("2. Group them into: needs a reply today, waiting on someone, and noise.")
            appendLine("3. Max ~150 words, plain words, naming the sender or app for anything that")
            appendLine("   needs me. Never invent a message.")
            appendLine("4. End with the one thing worth doing first.")
            appendLine()
            appendLine("If I keep notes about people, relate a sender to their card under")
            appendLine("$root/people when it genuinely helps.")
        },
        startLocal = nextWeekday(
            now,
            18,
            30,
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            )
        ).toString(),
        repeat = Repeat(
            kind = RepeatKind.WEEKLY,
            weekdays = listOf(
                DayOfWeek.MONDAY.value,
                DayOfWeek.TUESDAY.value,
                DayOfWeek.WEDNESDAY.value,
                DayOfWeek.THURSDAY.value,
                DayOfWeek.FRIDAY.value
            )
        ),
        agentMode = AgentMode.MONITOR.name
    )

    /** The next [hour]:[minute] local time strictly after [now]. */
    internal fun nextAt(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        val at = now.toLocalDate().atTime(hour, minute)
        return if (at.isAfter(now)) at else at.plusDays(1)
    }

    /** The next [hour]:[minute] on one of [days], strictly after [now]. */
    internal fun nextWeekday(
        now: LocalDateTime,
        hour: Int,
        minute: Int,
        days: Set<DayOfWeek>
    ): LocalDateTime {
        var date = now.toLocalDate()
        repeat(8) {
            val at = date.atTime(hour, minute)
            if (date.dayOfWeek in days && at.isAfter(now)) return at
            date = date.plusDays(1)
        }
        return now.toLocalDate().plusWeeks(1).atTime(hour, minute)
    }
}
