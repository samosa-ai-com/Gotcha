package com.gotcha.data

import com.gotcha.GotchaApp
import com.gotcha.scheduled.ScheduledTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageOverviewTest {

    private val now = 1_000L * 24 * 60 * 60 * 1000 // day 1000

    private fun run(startedAt: Long, succeeded: Boolean = true, tools: List<ToolSummary> = emptyList()) =
        RunSummary(
            startedAt = startedAt,
            endedAt = startedAt + 1,
            userPrompt = "p",
            finalReply = "r",
            model = "m",
            agentMode = "OPERATOR",
            delegated = false,
            succeeded = succeeded,
            toolCalls = tools
        )

    private fun session(id: String, runs: List<RunSummary>, isSample: Boolean = false) = ChatSession(
        id = id,
        title = id,
        lastModified = 0L,
        messages = emptyList(),
        runSummaries = runs,
        isSample = isSample
    )

    private fun build(
        chats: List<ChatSession> = emptyList(),
        calls: List<ChatSession> = emptyList(),
        tasks: List<ScheduledTask> = emptyList(),
        crashLog: String = ""
    ) = buildUsageOverview(chats, calls, tasks, crashLog, now)

    @Test
    fun `sample chats are left out of the counts`() {
        val overview = build(
            chats = listOf(
                session("mine", listOf(run(now - 1000))),
                session("sample", listOf(run(now - 1000), run(now - 2000)), isSample = true)
            )
        )
        assertEquals(1, overview.stats.chatSessions)
        assertEquals(1, overview.stats.totalRuns)
    }

    @Test
    fun `recent runs count only the last seven days`() {
        val day = 24L * 60 * 60 * 1000
        val overview = build(
            chats = listOf(session("a", listOf(run(now - day), run(now - 6 * day), run(now - 8 * day)))),
            calls = listOf(session("b", listOf(run(now - 2 * day))))
        )
        assertEquals(3, overview.recentRuns)
        assertEquals(now - 8 * day, overview.firstRunAt)
    }

    @Test
    fun `no runs means no first use date`() {
        assertNull(build().firstRunAt)
    }

    @Test
    fun `crash entries are split on the separator GotchaApp writes`() {
        val sep = GotchaApp.CRASH_ENTRY_SEPARATOR
        assertEquals(0, countCrashEntries(""))
        assertEquals(1, countCrashEntries("first"))
        assertEquals(3, countCrashEntries("a${sep}b${sep}c"))
    }
}
