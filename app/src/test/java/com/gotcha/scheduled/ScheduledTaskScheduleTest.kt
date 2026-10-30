package com.gotcha.scheduled

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** [nextOccurrence]: the calendar rules of scheduled tasks (issue #132). */
class ScheduledTaskScheduleTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun task(start: String, repeat: Repeat = Repeat()) =
        ScheduledTask(id = "t", name = "n", prompt = "p", startLocal = start, repeat = repeat)

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun next(task: ScheduledTask, after: String): String? =
        nextOccurrence(task, at(after), zone)?.let {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zone).toString()
        }

    @Test
    fun `a one-time task is due at its time, and never after`() {
        val once = task("2026-10-19T08:00")
        assertEquals("2026-10-19T08:00", next(once, "2026-10-18T12:00"))
        assertNull(next(once, "2026-10-19T08:00"))
        assertNull(next(once, "2026-10-20T08:00"))
    }

    @Test
    fun `a memory note link survives a serialization round trip`() {
        val task = ScheduledTask(
            id = "t",
            name = "n",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            memoryNotePath = "/mem/notes/plan.md"
        )
        val json = Json.encodeToString(ScheduledTask.serializer(), task)
        assertTrue(json.contains("memoryNotePath"))
        assertEquals(task, Json.decodeFromString(ScheduledTask.serializer(), json))
    }

    @Test
    fun `old stored tasks without the link field still decode`() {
        val old = """{"id":"t","name":"n","prompt":"p","startLocal":"2026-10-19T08:00"}"""
        val task = Json { ignoreUnknownKeys = true }.decodeFromString(ScheduledTask.serializer(), old)
        assertNull(task.memoryNotePath)
    }

    @Test
    fun `a daily task comes round at the same local time`() {
        val daily = task("2026-10-19T08:00", Repeat(RepeatKind.DAILY))
        assertEquals("2026-10-19T08:00", next(daily, "2026-10-01T00:00"))
        assertEquals("2026-10-20T08:00", next(daily, "2026-10-19T08:00"))
        assertEquals("2026-10-21T08:00", next(daily, "2026-10-20T09:30"))
    }

    @Test
    fun `a daily task keeps its local time across a summer-time change`() {
        val daily = task("2026-10-24T08:00", Repeat(RepeatKind.DAILY))
        // Clocks go back on 25 October 2026 in Berlin; 8:00 stays 8:00.
        assertEquals("2026-10-25T08:00", next(daily, "2026-10-24T08:00"))
        assertEquals("2026-10-26T08:00", next(daily, "2026-10-25T08:00"))
    }

    @Test
    fun `a time the clocks skip runs just after the gap`() {
        val daily = task("2026-03-28T02:30", Repeat(RepeatKind.DAILY))
        // 02:30 does not exist on 29 March 2026 in Berlin.
        assertEquals("2026-03-29T03:30", next(daily, "2026-03-28T02:30"))
    }

    @Test
    fun `every few days counts from the start date`() {
        val everyThird = task("2026-10-19T08:00", Repeat(RepeatKind.DAILY, interval = 3))
        assertEquals("2026-10-22T08:00", next(everyThird, "2026-10-19T08:00"))
        assertEquals("2026-10-22T08:00", next(everyThird, "2026-10-21T23:59"))
        assertEquals("2026-10-25T08:00", next(everyThird, "2026-10-22T08:01"))
    }

    @Test
    fun `an hourly task counts real hours, summer time or not`() {
        val everyTwoHours = task("2026-10-25T00:00", Repeat(RepeatKind.HOURLY, interval = 2))
        val first = at("2026-10-25T00:00")
        // The clocks go back at 03:00 that night: the next run is two real hours on, whatever the clock says.
        val twoHoursLater = nextOccurrence(everyTwoHours, first, zone)!!
        assertEquals(2L * 60 * 60 * 1000, twoHoursLater - first)
    }

    @Test
    fun `a weekly task runs on each listed weekday`() {
        // 19 October 2026 is a Monday.
        val weekdays = task("2026-10-19T08:00", Repeat(RepeatKind.WEEKLY, weekdays = listOf(1, 3, 5)))
        assertEquals("2026-10-19T08:00", next(weekdays, "2026-10-18T00:00"))
        assertEquals("2026-10-21T08:00", next(weekdays, "2026-10-19T08:00"))
        assertEquals("2026-10-23T08:00", next(weekdays, "2026-10-21T09:00"))
        assertEquals("2026-10-26T08:00", next(weekdays, "2026-10-23T08:00"))
    }

    @Test
    fun `a weekly task starting on an unlisted day waits for a listed one`() {
        // Starts on a Monday but runs on Saturdays only.
        val saturdays = task("2026-10-19T08:00", Repeat(RepeatKind.WEEKLY, weekdays = listOf(6)))
        assertEquals("2026-10-24T08:00", next(saturdays, "2026-10-18T00:00"))
    }

    @Test
    fun `a weekly task with no days listed runs on its start's weekday`() {
        val mondays = task("2026-10-19T08:00", Repeat(RepeatKind.WEEKLY))
        assertEquals("2026-10-26T08:00", next(mondays, "2026-10-19T08:00"))
    }

    @Test
    fun `a monthly task on the 31st falls on the last day of shorter months`() {
        val monthEnd = task("2026-01-31T09:00", Repeat(RepeatKind.MONTHLY))
        assertEquals("2026-02-28T09:00", next(monthEnd, "2026-01-31T09:00"))
        assertEquals("2026-03-31T09:00", next(monthEnd, "2026-02-28T09:00"))
        assertEquals("2026-04-30T09:00", next(monthEnd, "2026-03-31T09:00"))
    }

    @Test
    fun `a monthly task later in the month is due this month`() {
        val fifteenth = task("2026-01-15T09:00", Repeat(RepeatKind.MONTHLY))
        assertEquals("2026-10-15T09:00", next(fifteenth, "2026-10-01T00:00"))
        assertEquals("2026-11-15T09:00", next(fifteenth, "2026-10-15T09:00"))
    }

    @Test
    fun `a one-off in the repeated hour fires once, at whichever pass is armed`() {
        // Summer time ends in Berlin on 25 Oct 2026: 02:00-03:00 happens twice.
        // At 02:37 CET (the second pass) the first 02:40 (CEST) is gone but the
        // second 02:40 (CET) still lies ahead.
        val once = task("2026-10-25T02:40")
        val betweenPasses = java.time.ZonedDateTime.of(
            LocalDateTime.parse("2026-10-25T02:37"),
            zone
        ).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val got = nextOccurrence(once, betweenPasses, zone)?.let {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zone).toString()
        }
        assertEquals("2026-10-25T02:40", got)
        assertTrue(isWallTimeFuture(LocalDateTime.parse("2026-10-25T02:40"), zone, betweenPasses))
    }

    @Test
    fun `a one-off armed at the first pass does not fire again at the second`() {
        val once = task("2026-10-25T02:40")
        val firstPass = LocalDateTime.parse("2026-10-25T02:40")
            .atZone(zone).toInstant().toEpochMilli()
        // Firing at the first pass spends the task even though the wall time
        // comes round again an hour later.
        val started = ScheduledTaskPlanner.started(
            once.copy(nextRunAt = firstPass),
            "chat",
            firstPass,
            zone
        )
        assertNull(started.nextRunAt)
    }

    @Test
    fun `a daily task in the repeated hour runs once at the first pass`() {
        val daily = task("2026-10-25T02:30", Repeat(RepeatKind.DAILY))
        // Before the first pass: today at the first pass, not the second.
        val before = java.time.ZonedDateTime.of(
            LocalDateTime.parse("2026-10-25T02:10"),
            zone
        ).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val got = nextOccurrence(daily, before, zone)?.let { instant ->
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(instant), zone).toString()
        }
        assertEquals("2026-10-25T02:30", got)
        // After the first pass fired: tomorrow, not the second pass today.
        val firstPass = java.time.ZonedDateTime.of(
            LocalDateTime.parse("2026-10-25T02:30"),
            zone
        ).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val after = nextOccurrence(daily, firstPass, zone)?.let { instant ->
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(instant), zone).toString()
        }
        assertEquals("2026-10-26T02:30", after)
    }
}
