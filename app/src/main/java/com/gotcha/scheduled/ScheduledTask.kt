package com.gotcha.scheduled

import com.gotcha.tools.AgentMode
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How often a [ScheduledTask] comes round again (issue #132). */
@Serializable
enum class RepeatKind { NONE, HOURLY, DAILY, WEEKLY, MONTHLY }

/**
 * A task's repeat rule. [interval] is the N in "every N hours" or "every N
 * days", and is ignored by the other kinds. [weekdays] are ISO day numbers
 * (1 = Monday … 7 = Sunday) for [RepeatKind.WEEKLY]; empty means the start
 * date's own weekday. A monthly task repeats on its start date's day of the
 * month, or the month's last day when the month is shorter.
 */
@Serializable
data class Repeat(
    val kind: RepeatKind = RepeatKind.NONE,
    val interval: Int = 1,
    val weekdays: List<Int> = emptyList()
)

/** How one firing of a task ended, as its history lists it. */
@Serializable
enum class TaskRunStatus {
    /** The run finished with a reply. */
    DONE,

    /** The run ended on an error: the model was unreachable, not configured, … */
    FAILED,

    /** Someone stopped the run. */
    STOPPED,

    /** The phone was off or asleep well past the time, so the run was not started. */
    MISSED,

    /** Another run held Gotcha for too long, so this one never got its turn. */
    SKIPPED_BUSY,

    /** Android would not let the task run unattended; a notification asks for a tap. */
    NEEDS_TAP,

    /** The task could not be started: its files went missing, say. [TaskRun.detail] says why. */
    NOT_STARTED
}

@Serializable
data class TaskRun(
    val at: Long,
    val status: TaskRunStatus,
    /** The chat the run went into, when it started one. */
    val sessionId: String? = null,
    /** The error shown at the end of a failed run, or why it did not start. */
    val detail: String? = null
)

/**
 * A prompt Gotcha sends itself at a set time (issue #132). When it fires, an
 * ordinary agent run starts with [prompt] (and [attachmentNames]' files) as the
 * user's message, in a new chat, or in [chatSessionId] when [sameChat] is on.
 *
 * [startLocal] is a local date-time ("2026-10-19T08:00"), not an instant, so a
 * daily 8:00 task stays at 8:00 wherever the phone is and across a change to
 * summer time. [nextRunAt] is the next time it is due by its schedule, null once
 * a one-time task has had its turn; [retryAt] is a sooner re-try of the current
 * one, after a failure or while another run holds Gotcha.
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val startLocal: String,
    val repeat: Repeat = Repeat(),
    val enabled: Boolean = true,
    /** [AgentMode] name. */
    val agentMode: String = AgentMode.MONITOR.name,
    val personaId: String? = null,
    val sameChat: Boolean = false,
    val chatSessionId: String? = null,
    /** How many times a failed run is tried again before the occurrence is given up. */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val retryDelayMinutes: Int = DEFAULT_RETRY_DELAY_MINUTES,
    /** True for a task the agent set up with schedule_agent_task, which the user approved. */
    val createdByAgent: Boolean = false,
    /** Names of the files stored with the task; the files themselves are in [ScheduledTaskStore]. */
    val attachmentNames: List<String> = emptyList(),
    /**
     * Absolute path of the memory note this task works off (a people/, areas/
     * or notes/ record), or null when unlinked. The note names the task back
     * in its `task_ref` frontmatter: the Second Brain link both ways.
     */
    val memoryNotePath: String? = null,
    val createdAt: Long = 0L,
    val nextRunAt: Long? = null,
    val retryAt: Long? = null,
    /** Retries already used on the current occurrence. */
    val retriesUsed: Int = 0,
    /** When the current occurrence first found another run in progress. */
    val busySince: Long? = null,
    /** Newest last, capped at [MAX_HISTORY]. */
    val history: List<TaskRun> = emptyList()
) {
    val mode: AgentMode
        get() = runCatching { AgentMode.valueOf(agentMode) }.getOrDefault(AgentMode.MONITOR)

    val start: LocalDateTime
        get() = LocalDateTime.parse(startLocal)

    /** When this task should next fire, if it is on and has anything left to run. */
    val dueAt: Long?
        get() = if (!enabled) null else listOfNotNull(nextRunAt, retryAt).minOrNull()

    /** The most recent run, if any. */
    val lastRun: TaskRun? get() = history.lastOrNull()

    fun withRun(run: TaskRun): ScheduledTask = copy(history = (history + run).takeLast(MAX_HISTORY))

    companion object {
        const val MAX_HISTORY = 20
        const val DEFAULT_MAX_RETRIES = 1
        const val DEFAULT_RETRY_DELAY_MINUTES = 10
        const val MAX_RETRIES = 5
        const val MAX_INTERVAL = 365
    }
}

/**
 * The first time [task] is due strictly after [after], by its schedule alone
 * (retries aside), or null when there is none: a one-time task whose time has
 * passed. Pure, so the calendar rules are tested on the JVM.
 */
fun nextOccurrence(task: ScheduledTask, after: Long, zone: ZoneId): Long? {
    val start = task.start
    val startAt = start.atZone(zone).toInstant().toEpochMilli()
    if (task.repeat.kind == RepeatKind.NONE) {
        // A one-off in the repeated hour when summer time ends: the earlier
        // pass may be gone while the later one still lies ahead, so fall
        // through to it instead of reporting nothing left to run. Arming only:
        // once the task fires, the planner spends it (null) rather than
        // re-arming per pass.
        if (startAt > after) return startAt
        return laterOffsetAtOverlap(start, zone)?.takeIf { it > after }
    }
    if (startAt > after) {
        // The start itself, unless a weekly task does not run on that weekday.
        if (task.repeat.kind != RepeatKind.WEEKLY || start.dayOfWeek in weekdaysOf(task.repeat, start)) {
            return startAt
        }
    }
    val interval = task.repeat.interval.coerceIn(1, ScheduledTask.MAX_INTERVAL).toLong()
    val afterLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(after), zone)
    return when (task.repeat.kind) {
        // Handled above; kept for compiler exhaustiveness.
        RepeatKind.NONE -> null
        RepeatKind.HOURLY -> {
            // Hours are counted in real time, so a summer-time change does not
            // stretch or skip one.
            val step = interval * HOUR_MS
            val k = if (after < startAt) 0 else (after - startAt) / step + 1
            startAt + k * step
        }
        RepeatKind.DAILY -> {
            val daysSince = ChronoUnit.DAYS.between(start.toLocalDate(), afterLocal.toLocalDate()).coerceAtLeast(0)
            var k = daysSince / interval
            var candidate: Long
            do {
                candidate = start.plusDays(k * interval).atZone(zone).toInstant().toEpochMilli()
                k++
            } while (candidate <= after)
            candidate
        }
        RepeatKind.WEEKLY -> {
            val days = weekdaysOf(task.repeat, start)
            var date = maxOf(start.toLocalDate(), afterLocal.toLocalDate())
            // Eight days always reach the next listed weekday after today's has passed.
            repeat(DAYS_TO_SCAN) {
                if (date.dayOfWeek in days) {
                    val at = date.atTime(start.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    if (at > after && at >= startAt) return at
                }
                date = date.plusDays(1)
            }
            null
        }
        RepeatKind.MONTHLY -> {
            val day = start.dayOfMonth
            var month = YearMonth.from(maxOf(start.toLocalDate(), afterLocal.toLocalDate()))
            // The current month may already be past; the next one never is.
            repeat(2) {
                val date = month.atDay(minOf(day, month.lengthOfMonth()))
                val at = date.atTime(start.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                if (at > after && at >= startAt) return at
                month = month.plusMonths(1)
            }
            null
        }
    }
}

private fun weekdaysOf(repeat: Repeat, start: LocalDateTime): Set<DayOfWeek> =
    repeat.weekdays.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet().ifEmpty { setOf(start.dayOfWeek) }

/**
 * True when wall-clock [local] still lies ahead of [now] in [zone]: either
 * pass counts on the night summer time ends (issue #150). The form's
 * "that time has passed" check and [nextOccurrence]'s one-off path share it,
 * so a picked time the form accepts is one the schedule can still reach.
 * Repeating candidates keep the earlier pass only: a daily task in that hour
 * runs once, at the first pass.
 */
fun isWallTimeFuture(local: LocalDateTime, zone: ZoneId, now: Long): Boolean {
    if (local.atZone(zone).toInstant().toEpochMilli() > now) return true
    return laterOffsetAtOverlap(local, zone)?.let { it > now } == true
}

/** The second pass of an ambiguous wall time, or null when unambiguous. */
private fun laterOffsetAtOverlap(local: LocalDateTime, zone: ZoneId): Long? {
    val early = local.atZone(zone)
    val late = early.withLaterOffsetAtOverlap()
    if (late == early) return null
    return late.toInstant().toEpochMilli()
}

private const val HOUR_MS = 60L * 60L * 1000L
private const val DAYS_TO_SCAN = 8
