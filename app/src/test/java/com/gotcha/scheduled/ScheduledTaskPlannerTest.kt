package com.gotcha.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** [ScheduledTaskPlanner]: what a task does when it fires, and how it moves on (issue #132). */
class ScheduledTaskPlannerTest {

    private val zone = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun daily(now: Long = at("2026-10-19T07:00"), retries: Int = 1) = ScheduledTaskPlanner.armed(
        ScheduledTask(
            id = "t",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            repeat = Repeat(RepeatKind.DAILY),
            maxRetries = retries
        ),
        now,
        zone
    )

    @Test
    fun `arming sets the next time from now`() {
        assertEquals(at("2026-10-19T08:00"), daily().nextRunAt)
        assertEquals(at("2026-10-20T08:00"), daily(now = at("2026-10-19T09:00")).nextRunAt)
    }

    @Test
    fun `a task waits until its time, then runs`() {
        val task = daily()
        assertEquals(
            ScheduledTaskPlanner.Decision.Wait,
            ScheduledTaskPlanner.decide(task, at("2026-10-19T07:59"), zone)
        )
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(task, at("2026-10-19T08:00"), zone))
        // An alarm a few seconds early still counts.
        assertEquals(
            ScheduledTaskPlanner.Decision.Run,
            ScheduledTaskPlanner.decide(task, at("2026-10-19T08:00") - 10_000, zone)
        )
    }

    @Test
    fun `a paused task never runs`() {
        val task = daily().copy(enabled = false)
        assertNull(task.dueAt)
        assertEquals(
            ScheduledTaskPlanner.Decision.Wait,
            ScheduledTaskPlanner.decide(task, at("2026-10-19T08:00"), zone)
        )
    }

    @Test
    fun `a task found well past its time is recorded as missed and moves on`() {
        val decision = ScheduledTaskPlanner.decide(daily(), at("2026-10-19T10:00"), zone)
        val missed = (decision as ScheduledTaskPlanner.Decision.Missed).task
        assertEquals(TaskRunStatus.MISSED, missed.lastRun!!.status)
        assertEquals(at("2026-10-20T08:00"), missed.nextRunAt)
    }

    @Test
    fun `a task a little late still runs`() {
        assertEquals(
            ScheduledTaskPlanner.Decision.Run,
            ScheduledTaskPlanner.decide(daily(), at("2026-10-19T08:40"), zone)
        )
    }

    @Test
    fun `starting the run moves the task to its next time`() {
        val started = ScheduledTaskPlanner.started(daily(), "chat", at("2026-10-19T08:00"), zone)
        assertEquals(at("2026-10-20T08:00"), started.nextRunAt)
        assertNull(started.retryAt)
    }

    @Test
    fun `a failure is retried after the delay, without skipping the next time`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), "chat", now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", "No network", now + minute)
        assertEquals(now + minute + 10 * minute, failed.retryAt)
        assertEquals(failed.retryAt, failed.dueAt)
        assertEquals("No network", failed.lastRun!!.detail)

        // The retry fires; its start must leave tomorrow's run where it is.
        val retryAt = failed.retryAt!!
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(failed, retryAt, zone))
        val retried = ScheduledTaskPlanner.started(failed, "chat", retryAt, zone)
        assertEquals(at("2026-10-20T08:00"), retried.nextRunAt)

        // Out of retries: the second failure is final.
        val failedAgain = ScheduledTaskPlanner.finished(retried, TaskRunStatus.FAILED, "chat", null, retryAt + minute)
        assertNull(failedAgain.retryAt)
        assertEquals(at("2026-10-20T08:00"), failedAgain.dueAt)
        assertEquals(2, failedAgain.history.size)
    }

    @Test
    fun `a task with no retries gives a failure up at once`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(retries = 0), "chat", now, zone)
        assertNull(ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now).retryAt)
    }

    @Test
    fun `a busy runner makes the task wait, then give the occurrence up`() {
        val now = at("2026-10-19T08:00")
        val waiting = ScheduledTaskPlanner.busy(daily(), now, zone)
        // The past time is claimed, so it can't keep the alarm firing.
        assertEquals(now + ScheduledTaskPlanner.BUSY_RECHECK_MS, waiting.dueAt)
        assertEquals(at("2026-10-20T08:00"), waiting.nextRunAt)
        assertEquals(now, ScheduledTaskPlanner.busy(waiting, now + 2 * minute, zone).busySince)
        // A long wait is not "missed": it was due all along.
        val later = now + 50 * minute
        assertEquals(
            ScheduledTaskPlanner.Decision.Run,
            ScheduledTaskPlanner.decide(waiting.copy(retryAt = later), later, zone)
        )

        val gaveUp = ScheduledTaskPlanner.busy(waiting, now + hour, zone)
        assertEquals(TaskRunStatus.SKIPPED_BUSY, gaveUp.lastRun!!.status)
        assertEquals(at("2026-10-20T08:00"), gaveUp.dueAt)
    }

    @Test
    fun `a run that waited for a busy runner still moves to the next time when it starts`() {
        val now = at("2026-10-19T08:00")
        val waiting = ScheduledTaskPlanner.busy(daily(), now, zone)
        val started = ScheduledTaskPlanner.started(waiting, "chat", now + 4 * minute, zone)
        assertEquals(at("2026-10-20T08:00"), started.nextRunAt)
        assertNull(started.busySince)
    }

    @Test
    fun `a same-chat task keeps the chat its first run made`() {
        val task = daily().copy(sameChat = true)
        assertEquals("chat-1", ScheduledTaskPlanner.started(task, "chat-1", at("2026-10-19T08:00"), zone).chatSessionId)
        assertNull(ScheduledTaskPlanner.started(daily(), "chat-1", at("2026-10-19T08:00"), zone).chatSessionId)
    }

    @Test
    fun `a one-time task has nothing left once it has run`() {
        val once = ScheduledTaskPlanner.armed(
            ScheduledTask(id = "t", name = "n", prompt = "p", startLocal = "2026-10-19T08:00"),
            at("2026-10-19T07:00"),
            zone
        )
        val started = ScheduledTaskPlanner.started(once, "chat", at("2026-10-19T08:00"), zone)
        assertNull(started.dueAt)
        assertNull(ScheduledTaskPlanner.nextAlarm(listOf(started)))
    }

    @Test
    fun `the alarm is set for the soonest task`() {
        val soon = daily()
        val later = daily(now = at("2026-10-19T09:00")).copy(id = "u")
        assertEquals(soon.nextRunAt, ScheduledTaskPlanner.nextAlarm(listOf(later, soon)))
        assertTrue(ScheduledTaskPlanner.nextAlarm(emptyList()) == null)
    }

    @Test
    fun `history keeps the newest runs only`() {
        var task = daily()
        repeat(
            ScheduledTask.MAX_HISTORY + 5
        ) { task = task.withRun(TaskRun(at = it.toLong(), status = TaskRunStatus.DONE)) }
        assertEquals(ScheduledTask.MAX_HISTORY, task.history.size)
        assertEquals((ScheduledTask.MAX_HISTORY + 4).toLong(), task.history.last().at)
    }
}
