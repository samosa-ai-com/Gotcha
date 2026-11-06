package com.gotcha.scheduled

import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** [MorningBriefing]: the read-only daily briefing template (Second Brain Phase 3). */
class MorningBriefingTest {

    @Test
    fun `next seven am stays today before seven`() {
        assertEquals(
            LocalDateTime.parse("2026-10-29T07:00"),
            MorningBriefing.nextSevenAm(LocalDateTime.parse("2026-10-29T06:59"))
        )
    }

    @Test
    fun `next seven am rolls to tomorrow after seven`() {
        assertEquals(
            LocalDateTime.parse("2026-10-30T07:00"),
            MorningBriefing.nextSevenAm(LocalDateTime.parse("2026-10-29T07:00"))
        )
        assertEquals(
            LocalDateTime.parse("2026-10-30T07:00"),
            MorningBriefing.nextSevenAm(LocalDateTime.parse("2026-10-29T22:15"))
        )
    }

    @Test
    fun `the prompt is self-contained and read-only`() {
        val prompt = MorningBriefing.promptFor("/vault/Gotcha/memory")
        assertTrue(prompt.contains("/vault/Gotcha/memory/MEMORY.md"))
        assertTrue(prompt.contains("/vault/Gotcha/memory/user.md"))
        assertTrue(prompt.contains("ABSOLUTE"))
        assertTrue(prompt.contains("Read-only"))
        assertTrue(prompt.contains("list_scheduled_tasks"))
        assertTrue(prompt.contains("list_calendar_events"))
        assertTrue(prompt.contains("| Time | Item | Est. | Done when |"))
        assertTrue(prompt.contains("**Not today:**"))
        assertTrue(prompt.contains("**Calendar check:**"))
        assertTrue(prompt.contains("Your final message IS the plan."))
        assertTrue(!prompt.contains("speak"))
    }

    @Test
    fun `the template runs monitor daily at seven`() {
        val task = MorningBriefing.template(
            "/vault/Gotcha/memory",
            LocalDateTime.parse("2026-10-29T22:15")
        )
        assertEquals(MorningBriefing.TEMPLATE_NAME, task.name)
        assertEquals(AgentMode.MONITOR, task.mode)
        assertEquals(RepeatKind.DAILY, task.repeat.kind)
        assertEquals("2026-10-30T07:00", task.startLocal)
        assertTrue(task.enabled)
    }
}
