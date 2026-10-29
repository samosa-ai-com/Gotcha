package com.gotcha.scheduled

import com.gotcha.tools.AgentMode
import java.time.LocalDateTime

/**
 * The Morning briefing template (Second Brain Phase 3): a read-only daily
 * task that writes the structured day plan (timetable, dues, calendar check)
 * as its chat response.
 *
 * It ships as a preset in Settings ▸ Scheduled tasks: present but paused, so
 * the user opts in with the normal toggle. The same prefilled form is offered
 * to re-add it after a delete. It runs in MONITOR with a prompt that forbids
 * writes, so the briefing can never change anything even if the prompt were
 * ignored.
 */
object MorningBriefing {

    const val TEMPLATE_NAME = "Morning briefing"

    /** Next 7:00 local time strictly after [now]. */
    fun nextSevenAm(now: LocalDateTime): LocalDateTime {
        val seven = now.toLocalDate().atTime(7, 0)
        return if (seven.isAfter(now)) seven else seven.plusDays(1)
    }

    /** Self-contained read-only briefing prompt naming the vault's absolute paths. */
    fun promptFor(memoryRoot: String): String = buildString {
        appendLine("Morning briefing. Read-only: do not create, modify or delete any file,")
        appendLine("task, calendar event, alarm or setting. Create nothing in this run;")
        appendLine("the user approves any follow-up in chat.")
        appendLine()
        appendLine("Sources, in order:")
        appendLine("1. SOUL.md and MEMORY.md are in your [memory_startup] message. Follow them.")
        appendLine("2. Load what the day needs with read_file using ABSOLUTE paths:")
        appendLine("$memoryRoot/MEMORY.md, $memoryRoot/user.md, plus their linked notes")
        appendLine("for anything due or ongoing today.")
        appendLine("3. Check dues with list_scheduled_tasks. Compare dues in the next 7 days")
        appendLine("against existing reminder tasks and only surface what is missing;")
        appendLine("never surface a reminder that already exists.")
        appendLine("4. Calendar pass: query today's events on every available calendar with")
        appendLine("list_calendar_events (live, device timezone). Anchor fixed commitments")
        appendLine("first, checking for collisions.")
        appendLine()
        appendLine("Then output the day plan EXACTLY in this shape. Day blocks are real")
        appendLine("markdown tables (header row + separator row, one row per item,")
        appendLine("chronological, fixed commitments first). No bullet lists for scheduled items.")
        appendLine()
        appendLine("Good [morning/afternoon/evening, depending on the current time] - [one-line theme].")
        appendLine()
        appendLine("**Morning:**")
        appendLine("| Time | Item | Est. | Done when |")
        appendLine("|------|------|------|-----------|")
        appendLine("| [time] | [item] | [~estimate] | [concrete end-state] |")
        appendLine()
        appendLine("**Afternoon:** (same table shape)")
        appendLine("**Evening:** (same table shape)")
        appendLine()
        appendLine("**Not today:** [parked items, comma-separated, one line]")
        appendLine()
        appendLine("**Watch out:**")
        appendLine("- [what to drop or shorten if the day overflows; omit this section if it fits]")
        appendLine("- [dues in the next 7 days, one line]")
        appendLine()
        appendLine("**Calendar check:** [one line - collisions found, or clear on every calendar]")
        appendLine()
        appendLine("**Proposed reminders (do not create):**")
        appendLine("[one line per reminder: what + when; omit section if none missing]")
        appendLine()
        appendLine("Hard rules: every table row carries an estimate (~15m style) and estimates")
        appendLine("sum to a sane day; plain words, never field names or status labels; only facts")
        appendLine("from memory, dues and live calendars, never invent details; max ~350 words.")
        appendLine("Your final message IS the plan.")
    }

    /** Prefilled task the form offers; the user saving it is the approval. */
    fun template(memoryRoot: String, now: LocalDateTime = LocalDateTime.now()): ScheduledTask =
        ScheduledTask(
            id = "",
            name = TEMPLATE_NAME,
            prompt = promptFor(memoryRoot),
            startLocal = nextSevenAm(now).withSecond(0).withNano(0).toString(),
            repeat = Repeat(kind = RepeatKind.DAILY),
            agentMode = AgentMode.MONITOR.name
        )
}
