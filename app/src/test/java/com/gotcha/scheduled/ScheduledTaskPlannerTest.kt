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

    private fun hourly(now: Long = at("2026-10-19T07:00")) = ScheduledTaskPlanner.armed(
        ScheduledTask(
            id = "h",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            repeat = Repeat(RepeatKind.HOURLY)
        ),
        now,
        zone
    )

    @Test
    fun `a retry that would land after the next scheduled time is left to that run`() {
        val started = ScheduledTaskPlanner.started(hourly(), "chat", at("2026-10-19T08:00"), zone)
        assertEquals(at("2026-10-19T09:00"), started.nextRunAt)

        // Failed at 08:55: a retry at 09:05 would come after the 09:00 run.
        val late = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, at("2026-10-19T08:55"))
        assertNull(late.retryAt)
        assertEquals(0, late.retriesUsed)
        assertEquals(at("2026-10-19T09:00"), late.dueAt)
        assertEquals(TaskRunStatus.FAILED, late.lastRun!!.status)

        // Failed at 08:30: the retry at 08:40 comes first, and keeps 09:00 as it is.
        val early = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, at("2026-10-19T08:30"))
        assertEquals(at("2026-10-19T08:40"), early.retryAt)
        val retried = ScheduledTaskPlanner.started(early, "chat", at("2026-10-19T08:40"), zone)
        assertEquals(at("2026-10-19T09:00"), retried.nextRunAt)
        assertEquals(1, retried.retriesUsed)
    }

    @Test
    fun `a retry delivered after the next scheduled time is one run for both`() {
        val started = ScheduledTaskPlanner.started(hourly(), "chat", at("2026-10-19T08:00"), zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, at("2026-10-19T08:30"))
        // The phone slept through 08:40 and woke at 09:01.
        val now = at("2026-10-19T09:01")
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(failed, now, zone))
        val run = ScheduledTaskPlanner.started(failed, "chat", now, zone)
        assertEquals(at("2026-10-19T10:00"), run.nextRunAt)
        assertNull(run.retryAt)
        assertEquals(0, run.retriesUsed)
    }

    @Test
    fun `a new occurrence that comes while an older one waits on a busy runner replaces it`() {
        val waiting = ScheduledTaskPlanner.busy(hourly(), at("2026-10-19T08:00"), zone)
        assertEquals(at("2026-10-19T09:00"), waiting.nextRunAt)
        val recheck = at("2026-10-19T08:58")
        val stillWaiting = ScheduledTaskPlanner.busy(waiting.copy(retryAt = recheck), recheck, zone)
        assertEquals(at("2026-10-19T08:00"), stillWaiting.busySince)

        // 09:00 comes while 08:00 still waits: 08:00 is given up, and 09:00 waits on its own.
        val now = at("2026-10-19T09:00")
        val replaced = ScheduledTaskPlanner.busy(stillWaiting, now, zone)
        assertEquals(TaskRunStatus.SKIPPED_BUSY, replaced.lastRun!!.status)
        assertEquals(now, replaced.busySince)
        assertEquals(at("2026-10-19T10:00"), replaced.nextRunAt)
        assertEquals(now + ScheduledTaskPlanner.BUSY_RECHECK_MS, replaced.dueAt)
    }

    @Test
    fun `a quick pause and resume keeps a pending retry`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), "chat", now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now + minute)

        val paused = ScheduledTaskPlanner.toggled(failed, false, now + 2 * minute, zone)
        assertNull(paused.dueAt)
        val resumed = ScheduledTaskPlanner.toggled(paused, true, now + 3 * minute, zone)
        assertEquals(failed.retryAt, resumed.retryAt)
        assertEquals(1, resumed.retriesUsed)
        assertEquals(failed.dueAt, resumed.dueAt)
    }

    @Test
    fun `a resume after the retry's time drops it and moves the schedule past the pause`() {
        val now = at("2026-10-19T08:00")
        val started = ScheduledTaskPlanner.started(daily(), "chat", now, zone)
        val failed = ScheduledTaskPlanner.finished(started, TaskRunStatus.FAILED, "chat", null, now + minute)
        val paused = ScheduledTaskPlanner.toggled(failed, false, now + 2 * minute, zone)

        val resumed = ScheduledTaskPlanner.toggled(paused, true, at("2026-10-21T12:00"), zone)
        assertNull(resumed.retryAt)
        assertEquals(0, resumed.retriesUsed)
        assertEquals(at("2026-10-22T08:00"), resumed.nextRunAt)
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
    fun `a manual start while busy queues behind the run and fires at its retry`() {
        // Nothing scheduled due: Run now at 07:00 only leaves a wait.
        val now = at("2026-10-19T07:00")
        val queued = ScheduledTaskPlanner.busy(daily(now), now, zone)
        assertEquals(at("2026-10-19T08:00"), queued.nextRunAt)
        assertEquals(now, queued.busySince)
        val retryAt = queued.retryAt!!
        assertEquals(now + ScheduledTaskPlanner.BUSY_RECHECK_MS, retryAt)
        // The retry is late by design, not missed: it runs.
        assertEquals(ScheduledTaskPlanner.Decision.Run, ScheduledTaskPlanner.decide(queued, retryAt, zone))
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

    @Test
    fun `a zone change keeps the daily wall time`() {
        // Daily 08:00 armed in Berlin; after today's run the next is 26 Oct
        // 08:00 Berlin. The phone then moves to Tokyo (same instant): the
        // stored moment would fire at 16:00 Tokyo, but rebasing moves it to
        // 08:00 Tokyo wall time.
        val berlin = ZoneId.of("Europe/Berlin")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val armed = ScheduledTaskPlanner.armed(
            ScheduledTask(
                id = "t",
                name = "n",
                prompt = "p",
                startLocal = "2026-10-25T08:00",
                repeat = Repeat(RepeatKind.DAILY)
            ),
            LocalDateTime.parse("2026-10-25T09:00").atZone(berlin).toInstant().toEpochMilli(),
            berlin
        )
        assertEquals(
            LocalDateTime.parse("2026-10-26T08:00").atZone(berlin).toInstant().toEpochMilli(),
            armed.nextRunAt
        )
        val sameInstantInTokyo = LocalDateTime.parse("2026-10-25T09:00")
            .atZone(berlin).toInstant().toEpochMilli()
        val rebased = ScheduledTaskPlanner.rebasedForZone(armed, sameInstantInTokyo, tokyo)
        assertEquals(
            LocalDateTime.parse("2026-10-26T08:00").atZone(tokyo).toInstant().toEpochMilli(),
            rebased.nextRunAt
        )
        // The wall time reads back as 08:00 in Tokyo.
        assertEquals(
            "2026-10-26T08:00",
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(rebased.nextRunAt!!), tokyo).toString()
        )
    }

    @Test
    fun `a zone change leaves hourly tasks retries and history alone`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val hourly = ScheduledTaskPlanner.armed(
            ScheduledTask(
                id = "h",
                name = "n",
                prompt = "p",
                startLocal = "2026-10-25T00:00",
                repeat = Repeat(RepeatKind.HOURLY, interval = 2)
            ),
            LocalDateTime.parse("2026-10-25T00:00").atZone(berlin).toInstant().toEpochMilli(),
            berlin
        )
        val nowInTokyo = hourly.nextRunAt!! - 60_000L
        assertEquals(hourly, ScheduledTaskPlanner.rebasedForZone(hourly, nowInTokyo, tokyo))

        val retrying = daily().copy(retryAt = at("2026-10-19T07:30"), retriesUsed = 1)
        // Across a real zone shift too: the retry is a real delay, not wall time.
        val tokyoNow = LocalDateTime.parse("2026-10-19T07:00").atZone(ZoneId.of("Europe/Berlin"))
            .toInstant().toEpochMilli()
        val rebased = ScheduledTaskPlanner.rebasedForZone(retrying, tokyoNow, ZoneId.of("Asia/Tokyo"))
        assertEquals(at("2026-10-19T07:30"), rebased.retryAt)
        assertEquals(1, rebased.retriesUsed)
    }

    @Test
    fun `a zone change never resurrects a spent one-off`() {
        val spent = ScheduledTask(
            id = "t",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            nextRunAt = null
        )
        assertEquals(
            spent,
            ScheduledTaskPlanner.rebasedForZone(spent, at("2026-10-19T09:00"), ZoneId.of("Asia/Tokyo"))
        )
    }

    @Test
    fun `a spent one-off stays spent through pause resume and busy waits`() {
        // In the overlap hour a resume must not re-arm off the later pass.
        val berlin = ZoneId.of("Europe/Berlin")
        val spent = ScheduledTask(
            id = "t",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-25T02:40",
            enabled = false,
            nextRunAt = null
        )
        val betweenPasses = java.time.ZonedDateTime.of(
            LocalDateTime.parse("2026-10-25T02:37"),
            berlin
        ).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertNull(ScheduledTaskPlanner.toggled(spent, true, betweenPasses, berlin).nextRunAt)
        // A busy wait on a due one-off claims no next time either.
        val firstPass = LocalDateTime.parse("2026-10-25T02:40")
            .atZone(berlin).toInstant().toEpochMilli()
        val busy = ScheduledTaskPlanner.busy(
            ScheduledTask(
                id = "t",
                name = "n",
                prompt = "p",
                startLocal = "2026-10-25T02:40",
                nextRunAt = firstPass
            ),
            firstPass,
            berlin
        )
        assertNull(busy.nextRunAt)
    }
}
