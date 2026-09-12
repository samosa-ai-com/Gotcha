package com.gotcha.data

import com.gotcha.agent.MessageKind
import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SampleChats] is hand-written content that the app persists and then replays
 * as if it were a real transcript. These are the invariants that replay relies
 * on — most importantly that continuing a sample sends a history the provider
 * will accept.
 */
class SampleChatsTest {

    private val samples = SampleChats.all(now = 1_000_000L)

    @Test
    fun `ships one or two samples with stable unique ids`() {
        assertTrue("Expected 1-2 samples, got ${samples.size}", samples.size in 1..2)
        assertEquals(samples.size, samples.map { it.id }.distinct().size)
        assertEquals(
            listOf(SampleChats.DEVICE_ACTION_ID, SampleChats.SCREEN_QA_ID).sorted(),
            samples.map { it.id }.sorted()
        )
    }

    @Test
    fun `every sample is marked as one and has a title`() {
        samples.forEach { sample ->
            assertTrue("Unmarked sample: ${sample.id}", sample.isSample)
            assertTrue("Blank title in ${sample.id}", sample.title.isNotBlank())
        }
    }

    @Test
    fun `samples are dated before now so a real chat sorts above them`() {
        val now = 1_000_000L
        SampleChats.all(now).forEach { sample ->
            assertTrue("${sample.id} is not in the past", sample.lastModified < now)
        }
        // And they are ordered relative to each other, so the drawer can't shuffle.
        assertEquals(
            SampleChats.all(now).map { it.lastModified }.distinct().size,
            samples.size
        )
    }

    @Test
    fun `each sample shows a short exchange, opened by the user`() {
        samples.forEach { sample ->
            assertTrue("${sample.id} has no transcript", sample.displayMessages.isNotEmpty())
            assertEquals(
                "${sample.id} must open with the user",
                MessageKind.USER,
                sample.displayMessages.first().kind
            )
            assertEquals(
                "${sample.id} must end with the assistant",
                MessageKind.ASSISTANT,
                sample.displayMessages.last().kind
            )
            val userTurns = sample.displayMessages.count { it.kind == MessageKind.USER }
            assertTrue("${sample.id} shows $userTurns exchanges, expected 1-2", userTurns in 1..2)
            assertTrue(
                "${sample.id} has a blank bubble",
                sample.displayMessages.all { it.text.isNotBlank() }
            )
        }
    }

    @Test
    fun `display message ids are unique and ordered`() {
        // The transcript is restored verbatim and keyed by id in the LazyColumn,
        // and ChatViewModel resumes numbering from the maximum.
        samples.forEach { sample ->
            val ids = sample.displayMessages.map { it.id }
            assertEquals("Duplicate ids in ${sample.id}", ids.size, ids.distinct().size)
            assertEquals("Unordered ids in ${sample.id}", ids.sorted(), ids)
        }
    }

    @Test
    fun `history is plain alternating text with no orphan tool calls`() {
        samples.forEach { sample ->
            val roles = sample.messages.map { it.role }
            assertEquals(
                "${sample.id} must alternate user/assistant",
                List(roles.size) { if (it % 2 == 0) "user" else "assistant" },
                roles
            )
            sample.messages.forEach { message ->
                assertTrue("Empty history message in ${sample.id}", message.hasText)
                // A fabricated tool call has no result to match it, and the
                // provider rejects the whole request if the user carries on.
                assertNull("Fabricated tool_calls in ${sample.id}", message.toolCalls)
                assertNull("Fabricated tool_call_id in ${sample.id}", message.toolCallId)
            }
        }
    }

    @Test
    fun `history matches what the transcript shows the user saying`() {
        samples.forEach { sample ->
            assertEquals(
                "${sample.id}: history and transcript disagree",
                sample.displayMessages.filter { it.kind == MessageKind.USER }.map { it.text },
                sample.messages.filter { it.role == "user" }.map { it.textContent }
            )
        }
    }

    @Test
    fun `agent mode is a real mode and both modes are demonstrated`() {
        val modes = samples.map { sample ->
            val raw = requireNotNull(sample.agentMode) { "${sample.id} has no agent mode" }
            AgentMode.valueOf(raw)
        }
        assertEquals("Samples should show both halves of the selector", 2, modes.distinct().size)
    }

    @Test
    fun `samples start with a clean slate`() {
        // Seeded chats were never run, so there is nothing to count or share:
        // a non-zero token count would show a context readout in the drawer for
        // a conversation that never cost anything.
        samples.forEach { sample ->
            assertEquals(0, sample.tokenCount)
            assertTrue(sample.runSummaries.isEmpty())
        }
    }
}
