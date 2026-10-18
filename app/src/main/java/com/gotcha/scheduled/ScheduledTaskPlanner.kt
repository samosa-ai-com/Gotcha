package com.gotcha.scheduled

import java.time.ZoneId

/**
 * The bookkeeping of scheduled tasks (issue #132), kept free of Android so it
 * is tested on the JVM: what a task does when its alarm fires, and how each
 * outcome moves it on to its next time.
 */
object ScheduledTaskPlanner {

    /** A task this late (the phone was off, or deep asleep) is recorded as missed rather than run. */
    const val CATCH_UP_MS = 60L * 60L * 1000L

    /** How often a task waiting for another run to end looks again. */
    const val BUSY_RECHECK_MS = 2L * 60L * 1000L

    /** How long a task waits for another run to end before it gives this occurrence up. */
    const val BUSY_GIVE_UP_MS = 60L * 60L * 1000L

    /** Early enough to count as due: alarms may be delivered a little before their time. */
    const val DUE_SLACK_MS = 30L * 1000L

    sealed class Decision {
        /** Not due yet. */
        object Wait : Decision()

        /** Due: start the run. */
        object Run : Decision()

        /** Too late to run; [task] has the miss recorded and its next time set. */
        data class Missed(val task: ScheduledTask) : Decision()
    }

    /** A new or edited [task], with its next time worked out from [now]. */
    fun armed(task: ScheduledTask, now: Long, zone: ZoneId): ScheduledTask =
        task.copy(
            nextRunAt = nextOccurrence(task, now, zone),
            retryAt = null,
            retriesUsed = 0,
            busySince = null
        )

    fun decide(task: ScheduledTask, now: Long, zone: ZoneId): Decision {
        val due = task.dueAt ?: return Decision.Wait
        if (due > now + DUE_SLACK_MS) return Decision.Wait
        // A retry or a wait for a busy runner is late by design, not missed.
        val waitingAlready = task.retryAt != null && due == task.retryAt
        if (!waitingAlready && now - due > CATCH_UP_MS) {
            return Decision.Missed(
                advance(task, now, zone).withRun(TaskRun(at = now, status = TaskRunStatus.MISSED))
            )
        }
        return Decision.Run
    }

    /**
     * Another run holds Gotcha: wait a little and look again, up to
     * [BUSY_GIVE_UP_MS]. The occurrence is claimed as the wait starts (the task
     * moves to its next time, and the wait is a [ScheduledTask.retryAt]), or its
     * time, now past, would stay the soonest alarm and fire again at once.
     */
    fun busy(task: ScheduledTask, now: Long, zone: ZoneId): ScheduledTask {
        val since = task.busySince ?: now
        if (now - since >= BUSY_GIVE_UP_MS) {
            return task.copy(retryAt = null, retriesUsed = 0, busySince = null)
                .withRun(TaskRun(at = now, status = TaskRunStatus.SKIPPED_BUSY))
        }
        val claimed = if (scheduleIsDue(task, now)) {
            task.copy(nextRunAt = nextAfter(task, now, zone), retriesUsed = 0)
        } else {
            task
        }
        return claimed.copy(busySince = since, retryAt = now + BUSY_RECHECK_MS)
    }

    /**
     * The run has started: the occurrence is used up, so the task moves to its
     * next time now. A failure that comes back later may still set [ScheduledTask.retryAt].
     */
    fun started(task: ScheduledTask, sessionId: String, now: Long, zone: ZoneId): ScheduledTask {
        val newOccurrence = scheduleIsDue(task, now)
        return task.copy(
            nextRunAt = if (newOccurrence) nextAfter(task, now, zone) else task.nextRunAt,
            retryAt = null,
            retriesUsed = if (newOccurrence) 0 else task.retriesUsed,
            busySince = null,
            chatSessionId = if (task.sameChat) sessionId else task.chatSessionId
        )
    }

    /**
     * The run in [sessionId] ended with [status]. A failure is tried again after
     * the task's retry delay while it has retries left; every other outcome, and
     * the last failure, closes the occurrence.
     */
    fun finished(
        task: ScheduledTask,
        status: TaskRunStatus,
        sessionId: String?,
        detail: String?,
        now: Long
    ): ScheduledTask {
        val recorded = task.withRun(TaskRun(at = now, status = status, sessionId = sessionId, detail = detail))
        return if (status == TaskRunStatus.FAILED && task.retriesUsed < task.maxRetries) {
            recorded.copy(
                retryAt = now + task.retryDelayMinutes.coerceAtLeast(1) * MINUTE_MS,
                retriesUsed = task.retriesUsed + 1
            )
        } else {
            recorded.copy(retryAt = null, retriesUsed = 0)
        }
    }

    /** [task] could not be started at all; recorded without a retry. */
    fun notStarted(
        task: ScheduledTask,
        status: TaskRunStatus,
        detail: String?,
        now: Long,
        zone: ZoneId
    ): ScheduledTask =
        advance(task, now, zone).withRun(TaskRun(at = now, status = status, detail = detail))

    /** The earliest time any of [tasks] is due, or null when none is. */
    fun nextAlarm(tasks: List<ScheduledTask>): Long? = tasks.mapNotNull { it.dueAt }.minOrNull()

    /**
     * Gives the current occurrence up. When it was the scheduled time that fired,
     * the task moves on to its next one after [now]; when it was a retry, the
     * next scheduled time was already set as the run started, and stays.
     */
    private fun advance(task: ScheduledTask, now: Long, zone: ZoneId): ScheduledTask =
        task.copy(
            nextRunAt = if (scheduleIsDue(task, now)) nextAfter(task, now, zone) else task.nextRunAt,
            retryAt = null,
            retriesUsed = 0,
            busySince = null
        )

    /** True when [task]'s scheduled time, not just a retry, has come. */
    private fun scheduleIsDue(task: ScheduledTask, now: Long): Boolean =
        task.nextRunAt?.let { it <= now + DUE_SLACK_MS } ?: false

    private fun nextAfter(task: ScheduledTask, now: Long, zone: ZoneId): Long? =
        nextOccurrence(task, maxOf(now, task.nextRunAt ?: now), zone)

    private const val MINUTE_MS = 60L * 1000L
}
