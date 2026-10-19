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

    /**
     * [task] switched on or off from Settings. Pausing keeps everything as it is.
     * Resuming keeps a retry or a busy wait that is still ahead, so a quick
     * off/on loses nothing; one whose time passed during the pause was cancelled
     * by it. The scheduled time moves on past the pause the same way.
     */
    fun toggled(task: ScheduledTask, enabled: Boolean, now: Long, zone: ZoneId): ScheduledTask {
        if (!enabled) return task.copy(enabled = false)
        if (task.enabled) return task
        val nextRunAt = task.nextRunAt?.takeIf { it > now } ?: nextOccurrence(task, now, zone)
        val retryAt = task.retryAt?.takeIf { it > now && (nextRunAt == null || it < nextRunAt) }
        return task.copy(
            enabled = true,
            nextRunAt = nextRunAt,
            retryAt = retryAt,
            retriesUsed = if (retryAt != null) task.retriesUsed else 0,
            busySince = if (retryAt != null) task.busySince else null
        )
    }

    /**
     * [edited] as saved over [existing] (null for a new task): re-armed when its
     * schedule changed, [toggled] when only on/off did, and otherwise left with
     * its times as they are.
     */
    fun edited(existing: ScheduledTask?, edited: ScheduledTask, now: Long, zone: ZoneId): ScheduledTask = when {
        existing == null || edited.startLocal != existing.startLocal || edited.repeat != existing.repeat ->
            armed(edited, now, zone)
        edited.enabled != existing.enabled ->
            toggled(edited.copy(enabled = existing.enabled), edited.enabled, now, zone)
        else -> edited
    }

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
     *
     * When the next scheduled time comes while an earlier occurrence is still
     * waiting, the earlier one is given up as [TaskRunStatus.SKIPPED_BUSY] and
     * the new one starts a wait of its own: one run can't stand for two
     * occurrences, and the new one gets its full [BUSY_GIVE_UP_MS].
     */
    fun busy(task: ScheduledTask, now: Long, zone: ZoneId): ScheduledTask {
        val newOccurrence = scheduleIsDue(task, now)
        val skipped = TaskRun(at = now, status = TaskRunStatus.SKIPPED_BUSY)
        if (newOccurrence && task.busySince != null) {
            return task.withRun(skipped).copy(
                nextRunAt = nextAfter(task, now, zone),
                retryAt = now + BUSY_RECHECK_MS,
                retriesUsed = 0,
                busySince = now
            )
        }
        val since = task.busySince ?: now
        if (now - since >= BUSY_GIVE_UP_MS) {
            return task.copy(retryAt = null, retriesUsed = 0, busySince = null).withRun(skipped)
        }
        val claimed = if (newOccurrence) {
            task.copy(nextRunAt = nextAfter(task, now, zone), retriesUsed = 0)
        } else {
            task
        }
        return claimed.copy(busySince = since, retryAt = now + BUSY_RECHECK_MS)
    }

    /**
     * The run has started: the occurrence is used up, so the task moves to its
     * next time now. A failure that comes back later may still set [ScheduledTask.retryAt].
     * A retry delivered so late (the phone asleep, say) that the next scheduled
     * time has come too is one run for both, and counts as the new occurrence.
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
     * the last failure, closes the occurrence. So does a failure whose retry
     * would come no sooner than the next scheduled time: that run takes the
     * retry's place, rather than the retry using up the next occurrence.
     */
    fun finished(
        task: ScheduledTask,
        status: TaskRunStatus,
        sessionId: String?,
        detail: String?,
        now: Long
    ): ScheduledTask {
        val recorded = task.withRun(TaskRun(at = now, status = status, sessionId = sessionId, detail = detail))
        val retryAt = now + task.retryDelayMinutes.coerceAtLeast(1) * MINUTE_MS
        val beforeNext = task.nextRunAt?.let { retryAt < it } ?: true
        return if (status == TaskRunStatus.FAILED && task.retriesUsed < task.maxRetries && beforeNext) {
            recorded.copy(retryAt = retryAt, retriesUsed = task.retriesUsed + 1)
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
