package com.gotcha.agent

import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [synthesizeRunSummariesFromHistory], the fallback that lets the
 * "Share your Gotcha moment" card work for chats created before run summaries
 * were recorded.
 */
class RunSummarySynthesisTest {

    private fun msg(role: String, text: String) = ChatMessage(
        role = role,
        content = JsonPrimitive(text)
    )

    @Test
    fun `each user to assistant exchange becomes one summary`() {
        val history = listOf(
            msg("user", "Plan my Goa trip"),
            msg("assistant", "Here is a 3-day itinerary."),
            msg("user", "Book a cab"),
            msg("assistant", "Opened the cab app.")
        )

        val summaries = synthesizeRunSummariesFromHistory(history, "gpt-4o", "OPERATOR")

        assertEquals(2, summaries.size)
        assertEquals("Plan my Goa trip", summaries[0].userPrompt)
        assertEquals("Here is a 3-day itinerary.", summaries[0].finalReply)
        assertEquals("Book a cab", summaries[1].userPrompt)
        assertEquals("Opened the cab app.", summaries[1].finalReply)
    }

    @Test
    fun `tool rounds between prompt and reply collapse into one exchange`() {
        val history = listOf(
            msg("user", "Fix my wifi"),
            msg("tool", "result: settings opened"),
            msg("tool", "result: wifi enabled"),
            msg("assistant", "Wi-Fi is back on.")
        )

        val summaries = synthesizeRunSummariesFromHistory(history, "gpt-4o", "OPERATOR")

        assertEquals(1, summaries.size)
        assertEquals("Fix my wifi", summaries.single().userPrompt)
        assertEquals("Wi-Fi is back on.", summaries.single().finalReply)
    }

    @Test
    fun `an unanswered prompt yields no summary`() {
        val history = listOf(
            msg("user", "Hello?")
        )

        val summaries = synthesizeRunSummariesFromHistory(history, "gpt-4o", "MONITOR")

        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `empty history yields no summary`() {
        assertTrue(synthesizeRunSummariesFromHistory(emptyList(), "gpt-4o", "MONITOR").isEmpty())
    }

    @Test
    fun `only the final assistant text of an exchange survives`() {
        val history = listOf(
            msg("user", "Set a timer"),
            msg("assistant", "Which duration?"),
            msg("tool", "answer: 5 minutes"),
            msg("assistant", "Timer set for 5 minutes.")
        )

        val summary = synthesizeRunSummariesFromHistory(history, "gpt-4o", "OPERATOR").single()

        assertEquals("Set a timer", summary.userPrompt)
        assertEquals("Timer set for 5 minutes.", summary.finalReply)
    }

    @Test
    fun `model and agent mode are carried through`() {
        val history = listOf(
            msg("user", "Play some music"),
            msg("assistant", "Started your playlist.")
        )

        val summary = synthesizeRunSummariesFromHistory(history, "chai-small", "OPERATOR").single()

        assertEquals("chai-small", summary.model)
        assertEquals("OPERATOR", summary.agentMode)
        assertTrue(summary.succeeded)
        assertTrue(summary.toolCalls.isEmpty())
    }
}
