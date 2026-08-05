package com.gotcha.agent

import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the pure history-truncation helpers backing the Edit / Revert
 * message features. These mirror the screen-observation heuristics the
 * transcript rebuild applies, so injected vision messages never count as user
 * turns and are always dropped together with their parent turn.
 */
class ChatHistoryEditTest {

    private fun msg(role: String, text: String) = ChatMessage(
        role = role,
        content = JsonPrimitive(text)
    )

    // ---- isRealUserPrompt ----

    @Test
    fun `real prompts are detected`() {
        assertTrue(isRealUserPrompt(msg("user", "What is in this image?")))
        assertTrue(isRealUserPrompt(msg("user", "Send a text to mom")))
    }

    @Test
    fun `non user roles are rejected`() {
        assertFalse(isRealUserPrompt(msg("assistant", "Sure thing.")))
        assertFalse(isRealUserPrompt(msg("tool", "result: ok")))
        assertFalse(isRealUserPrompt(msg("system", "behave")))
    }

    @Test
    fun `injected screen and image observations are rejected`() {
        assertFalse(isRealUserPrompt(msg("user", "[Screen State]")))
        assertFalse(isRealUserPrompt(msg("user", "Screen text: Hello")))
        assertFalse(isRealUserPrompt(msg("user", "x\n── UI Elements ──\nfoo")))
        assertFalse(isRealUserPrompt(msg("user", "The assistant read an image file: shot.png")))
    }

    // ---- userTurnStarts ----

    @Test
    fun `turn starts point only at real prompts`() {
        val history = listOf(
            msg("user", "First question"),
            msg("assistant", "Working…"),
            msg("tool", "result: 1"),
            msg("user", "[Screen State]"), // injected during a run
            msg("user", "Second question"),
            msg("assistant", "Done."),
            msg("user", "The assistant read an image file: a.png") // injected
        )

        assertEquals(listOf(0, 4), userTurnStarts(history))
    }

    @Test
    fun `empty history has no turns`() {
        assertTrue(userTurnStarts(emptyList()).isEmpty())
    }

    // ---- truncateHistoryAtTurn, revert (dropTurn = false) ----

    @Test
    fun `revert keeps history up to and including the target prompt`() {
        val history = listOf(
            msg("user", "A"),
            msg("assistant", "reply A"),
            msg("tool", "r1"),
            msg("user", "B"),
            msg("user", "[Screen State]"),
            msg("assistant", "reply B"),
            msg("user", "C"),
            msg("assistant", "reply C")
        )

        val kept = truncateHistoryAtTurn(history, 1, dropTurn = false)

        assertEquals(listOf("A", "reply A", "r1", "B"), kept.map { it.textContent })
    }

    @Test
    fun `revert on the first prompt keeps just that prompt`() {
        val history = listOf(
            msg("user", "A"),
            msg("assistant", "reply A"),
            msg("user", "B")
        )

        assertEquals(
            listOf("A"),
            truncateHistoryAtTurn(history, 0, dropTurn = false).map { it.textContent }
        )
    }

    // ---- truncateHistoryAtTurn, edit (dropTurn = true) ----

    @Test
    fun `edit on the first turn drops the whole turn`() {
        val history = listOf(
            msg("user", "A"),
            msg("assistant", "reply A"),
            msg("tool", "r1"),
            msg("user", "B"),
            msg("user", "[Screen State]"),
            msg("assistant", "reply B")
        )

        assertTrue(truncateHistoryAtTurn(history, 0, dropTurn = true).isEmpty())
    }

    @Test
    fun `edit keeps earlier turns and drops the target turn`() {
        val history = listOf(
            msg("user", "A"),
            msg("assistant", "reply A"),
            msg("user", "B"),
            msg("assistant", "reply B")
        )

        val kept = truncateHistoryAtTurn(history, 1, dropTurn = true)

        assertEquals(listOf("A", "reply A"), kept.map { it.textContent })
    }

    @Test
    fun `edit drops injected observations after the target prompt`() {
        val history = listOf(
            msg("user", "A"),
            msg("assistant", "reply A"),
            msg("tool", "r1"),
            msg("user", "B"),
            msg("user", "[Screen State]"), // injected during B's run
            msg("assistant", "reply B")
        )

        val kept = truncateHistoryAtTurn(history, 1, dropTurn = true)

        assertEquals(listOf("A", "reply A", "r1"), kept.map { it.textContent })
    }

    // ---- out of range / empty ----

    @Test
    fun `out of range turn returns the original list`() {
        val history = listOf(msg("user", "A"), msg("assistant", "reply"))

        assertSame(history, truncateHistoryAtTurn(history, 5, dropTurn = true))
        assertSame(history, truncateHistoryAtTurn(history, -1, dropTurn = false))
    }

    @Test
    fun `empty history returns empty`() {
        assertTrue(truncateHistoryAtTurn(emptyList(), 0, dropTurn = false).isEmpty())
        assertTrue(truncateHistoryAtTurn(emptyList(), 0, dropTurn = true).isEmpty())
    }

    /**
     * Regression for the production clear() + addAll() swap in
     * [ChatViewModel.editMessage]/[revertTo]: the truncated result must be a
     * detached copy, not a live subList view, or clearing the backing history
     * before addAll would throw a ConcurrentModificationException.
     */
    @Test
    fun `truncated result survives clearing the source history`() {
        val history = mutableListOf(
            msg("user", "A"),
            msg("assistant", "reply A"),
            msg("user", "B"),
            msg("assistant", "reply B")
        )

        val edited = truncateHistoryAtTurn(history, 1, dropTurn = true)
        history.clear()
        history.addAll(edited)
        assertEquals(listOf("A", "reply A"), history.map { it.textContent })

        history.add(msg("user", "B"))
        history.add(msg("assistant", "reply B"))
        val reverted = truncateHistoryAtTurn(history, 0, dropTurn = false)
        history.clear()
        history.addAll(reverted)
        assertEquals(listOf("A"), history.map { it.textContent })
    }
}
