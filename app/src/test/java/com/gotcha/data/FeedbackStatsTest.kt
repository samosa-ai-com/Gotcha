package com.gotcha.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeedbackStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(id: String, runs: List<RunSummary> = emptyList()) = ChatSession(
        id = id,
        title = id,
        lastModified = 0L,
        messages = emptyList(),
        runSummaries = runs
    )

    private fun run(
        model: String = "deepseek-v4-flash",
        mode: String = "MONITOR",
        delegated: Boolean = false,
        succeeded: Boolean = true,
        tools: List<ToolSummary> = emptyList()
    ) = RunSummary(
        startedAt = 0L,
        endedAt = 1L,
        userPrompt = "prompt",
        finalReply = "reply",
        model = model,
        agentMode = mode,
        delegated = delegated,
        succeeded = succeeded,
        toolCalls = tools
    )

    @Test
    fun `aggregates across chat and call repositories`() = runBlocking {
        val chats = ChatHistoryRepository(tmp.newFolder("chats"))
        val calls = ChatHistoryRepository(tmp.newFolder("calls"))
        chats.saveSession(
            session(
                "a",
                runs = listOf(
                    run(tools = listOf(ToolSummary("read_screen", true, "ok"))),
                    run(
                        delegated = true,
                        succeeded = false,
                        tools = listOf(ToolSummary("webfetch", false, "err"))
                    )
                )
            )
        )
        calls.saveSession(
            session(
                "b",
                runs = listOf(run(mode = "OPERATOR", tools = listOf(ToolSummary("read_screen", true, "ok"))))
            )
        )

        val stats = computeFeedbackStats(chats, calls)

        assertEquals(1, stats.chatSessions)
        assertEquals(1, stats.voiceCallSessions)
        assertEquals(3, stats.totalRuns)
        assertEquals(2, stats.successfulRuns)
        assertEquals(1, stats.delegatedRuns)
        assertEquals(1, stats.operatorRuns)
        assertEquals(3, stats.totalToolCalls)
        assertEquals(2, stats.successfulToolCalls)
        assertEquals(2, stats.toolCallsByName["read_screen"])
        assertEquals(setOf("deepseek-v4-flash"), stats.models)
    }

    @Test
    fun `empty repositories yield zeroed stats`() = runBlocking {
        val chats = ChatHistoryRepository(tmp.newFolder("chats"))
        val calls = ChatHistoryRepository(tmp.newFolder("calls"))

        val stats = computeFeedbackStats(chats, calls)

        assertEquals(0, stats.totalRuns)
        assertEquals(0, stats.totalToolCalls)
        assertTrue(stats.toolCallsByName.isEmpty())
        assertTrue(stats.models.isEmpty())
    }

    @Test
    fun `prefill text shows top tools and models`() {
        val stats = FeedbackStats(
            chatSessions = 2,
            voiceCallSessions = 1,
            totalRuns = 10,
            successfulRuns = 9,
            delegatedRuns = 2,
            operatorRuns = 3,
            totalToolCalls = 25,
            successfulToolCalls = 24,
            toolCallsByName = linkedMapOf("read_screen" to 9, "open_app" to 5),
            models = setOf("gpt-4o", "deepseek-v4-flash")
        )

        val text = stats.toPrefillText()

        assertTrue(text, text.contains("Chats: 2 · Voice calls: 1"))
        assertTrue(text, text.contains("Runs: 10 · succeeded: 9 · delegated: 2 · operator: 3"))
        assertTrue(text, text.contains("Tool calls: 25 (OK 24 / FAIL 1)"))
        assertTrue(text, text.contains("read_screen 9 · open_app 5"))
        assertTrue(text, text.contains("Models: deepseek-v4-flash, gpt-4o"))
    }
}
