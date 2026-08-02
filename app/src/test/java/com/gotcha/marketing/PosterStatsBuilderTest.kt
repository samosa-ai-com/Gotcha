package com.gotcha.marketing

import com.gotcha.data.RunSummary
import com.gotcha.data.ToolSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterStatsBuilderTest {

    private fun run(
        startedAt: Long,
        endedAt: Long,
        model: String,
        tools: List<ToolSummary>
    ) = RunSummary(
        startedAt = startedAt,
        endedAt = endedAt,
        userPrompt = "p",
        finalReply = "r",
        model = model,
        agentMode = "OPERATOR",
        delegated = false,
        succeeded = true,
        toolCalls = tools
    )

    @Test
    fun `duration is the sum of run spans in seconds`() {
        val runs = listOf(
            run(1000, 4000, "m1", emptyList()), // 3s
            run(5000, 12_000, "m1", emptyList()) // 7s
        )
        val stats = PosterStatsBuilder.from(runs)
        assertEquals(10L, stats.totalDurationSeconds)
    }

    @Test
    fun `tool count is distinct across runs`() {
        val runs = listOf(
            run(0, 1000, "m1", listOf(ToolSummary("read_file", true, "ok"))),
            run(1000, 2000, "m1", listOf(ToolSummary("read_file", true, "ok"), ToolSummary("write_file", true, "ok")))
        )
        val stats = PosterStatsBuilder.from(runs)
        assertEquals(2, stats.toolCount)
    }

    @Test
    fun `runCount reflects the number of runs`() {
        val runs = listOf(
            run(0, 1, "m", emptyList()),
            run(0, 1, "m", emptyList()),
            run(0, 1, "m", emptyList())
        )
        assertEquals(3, PosterStatsBuilder.from(runs).runCount)
    }

    @Test
    fun `durationDisplay formats under a minute`() {
        val stats = PosterStatsBuilder.from(listOf(run(0, 42_000, "m", emptyList())))
        assertEquals("42s", stats.durationDisplay)
    }

    @Test
    fun `durationDisplay formats minutes`() {
        val stats = PosterStatsBuilder.from(listOf(run(0, 2 * 60 * 1000 + 30 * 1000, "m", emptyList())))
        assertEquals("2m 30s", stats.durationDisplay)
    }

    @Test
    fun `durationDisplay formats hours`() {
        val stats = PosterStatsBuilder.from(listOf(run(0, 3 * 3600 * 1000L + 5 * 60 * 1000, "m", emptyList())))
        assertTrue(stats.durationDisplay.startsWith("3h"))
    }
}
