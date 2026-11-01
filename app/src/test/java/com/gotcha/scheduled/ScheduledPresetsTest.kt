package com.gotcha.scheduled

import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** The paused examples shown in Settings ▸ Scheduled tasks. */
class ScheduledPresetsTest {

    private val root = "/mem/Gotcha/memory"
    private val now = LocalDateTime.parse("2026-10-29T22:15") // a Thursday

    @Test
    fun `ids and names are unique`() {
        assertEquals(ScheduledPresets.all.size, ScheduledPresets.all.map { it.id }.distinct().size)
        assertEquals(ScheduledPresets.all.size, ScheduledPresets.all.map { it.name }.distinct().size)
    }

    @Test
    fun `every example is paused, read-only and separate from the agent's own tasks`() {
        ScheduledPresets.all.forEach { preset ->
            val task = ScheduledPresets.task(preset, root, now)

            assertFalse("${preset.id} enabled", task.enabled)
            assertEquals("Mode of ${preset.id}", AgentMode.MONITOR, task.mode)
            assertFalse("${preset.id} marked agent-made", task.createdByAgent)
            assertTrue("${preset.id} has a start time", task.startLocal.isNotBlank())
            // Nothing may be created by a run the user has not watched: every
            // example names its read-only rule in the first line.
            assertTrue("${preset.id} must say it writes nothing", task.prompt.contains("Read-only"))
        }
    }

    @Test
    fun `the briefing keeps its absolute memory paths`() {
        val briefing = ScheduledPresets.all.first { it.id == ScheduledPresets.MORNING_BRIEFING }
        val task = ScheduledPresets.task(briefing, root, now)

        assertTrue(task.prompt.contains("$root/MEMORY.md"))
        assertTrue(task.prompt.contains("Read-only"))
        assertEquals(RepeatKind.DAILY, task.repeat.kind)
        assertEquals(LocalTime.of(7, 0), task.start.toLocalTime())
    }

    @Test
    fun `the evening wrap-up runs at nine, in a new chat`() {
        val evening = ScheduledPresets.all.first { it.id == ScheduledPresets.EVENING_WRAP_UP }
        val task = ScheduledPresets.task(evening, root, now)

        assertEquals(LocalDateTime.parse("2026-10-30T21:00"), task.start)
        assertEquals(RepeatKind.DAILY, task.repeat.kind)
        assertFalse(task.sameChat)
    }

    @Test
    fun `the weekly review lands on a Sunday`() {
        val weekly = ScheduledPresets.all.first { it.id == ScheduledPresets.WEEKLY_REVIEW }
        val task = ScheduledPresets.task(weekly, root, now)

        assertEquals(DayOfWeek.SUNDAY, task.start.dayOfWeek)
        assertEquals(RepeatKind.WEEKLY, task.repeat.kind)
        assertEquals(listOf(DayOfWeek.SUNDAY.value), task.repeat.weekdays)
    }

    @Test
    fun `the inbox catch-up runs on weekdays only`() {
        val inbox = ScheduledPresets.all.first { it.id == ScheduledPresets.INBOX_CATCH_UP }
        val task = ScheduledPresets.task(inbox, root, now)

        assertTrue(task.start.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY)
        assertEquals(LocalTime.of(18, 30), task.start.toLocalTime())
        assertEquals(listOf(1, 2, 3, 4, 5), task.repeat.weekdays)
    }

    @Test
    fun `a time already past rolls to tomorrow`() {
        assertEquals(
            LocalDateTime.parse("2026-10-30T07:00"),
            ScheduledPresets.nextAt(now, 7, 0)
        )
        assertEquals(
            LocalDateTime.parse("2026-10-29T23:00"),
            ScheduledPresets.nextAt(now, 23, 0)
        )
    }

    @Test
    fun `the next weekday skips the weekend`() {
        val fridayEvening = LocalDateTime.parse("2026-10-30T19:00")

        assertEquals(
            LocalDateTime.parse("2026-11-02T18:30"),
            ScheduledPresets.nextWeekday(
                fridayEvening,
                18,
                30,
                setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            )
        )
    }
}
