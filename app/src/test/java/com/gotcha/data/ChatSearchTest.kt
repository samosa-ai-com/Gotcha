package com.gotcha.data

import com.gotcha.agent.MessageKind
import com.gotcha.agent.UiMessage
import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drawer's chat search: titles first, then the text of the chat itself. */
class ChatSearchTest {

    private fun chat(
        id: String,
        title: String,
        vararg said: String
    ) = ChatSession(
        id = id,
        title = title,
        lastModified = 0L,
        messages = emptyList(),
        displayMessages = said.mapIndexed { index, text ->
            UiMessage(id = index.toLong(), kind = MessageKind.USER, text = text)
        }
    )

    @Test
    fun `an empty query matches nothing, so the drawer shows every chat`() {
        val sessions = listOf(chat("a", "Groceries", "milk"))
        assertTrue(ChatSearch.search(sessions, "").isEmpty())
        assertTrue(ChatSearch.search(sessions, "   ").isEmpty())
    }

    @Test
    fun `a title match returns the chat with no snippet`() {
        val sessions = listOf(chat("a", "Groceries", "milk"))
        val hits = ChatSearch.search(sessions, "groc")

        assertEquals(1, hits.size)
        assertEquals("a", hits.single().sessionId)
        assertNull("the title already says why it matched", hits.single().snippet)
    }

    @Test
    fun `a match inside the chat returns the line it was said in`() {
        val sessions = listOf(
            chat("a", "Trip", "Paris in May", "then Berlin in June"),
            chat("b", "Work", "send the report")
        )
        val hit = ChatSearch.search(sessions, "berlin").single()

        assertEquals("a", hit.sessionId)
        assertTrue("snippet should hold the match: ${hit.snippet}", hit.snippet!!.contains("Berlin"))
    }

    @Test
    fun `matching ignores case`() {
        val sessions = listOf(chat("a", "Trip", "Paris in May"))
        assertEquals(1, ChatSearch.search(sessions, "PARIS").size)
        assertEquals(1, ChatSearch.search(sessions, "paris").size)
    }

    @Test
    fun `no match returns nothing`() {
        assertTrue(ChatSearch.search(listOf(chat("a", "Trip", "Paris")), "zebra").isEmpty())
    }

    @Test
    fun `chats keep the order they were given`() {
        val sessions = listOf(chat("new", "Trip", "Paris"), chat("old", "Travel", "Paris"))
        // Both match on text, neither on title: the drawer order is kept.
        assertEquals(listOf("new", "old"), ChatSearch.search(sessions, "paris").map { it.sessionId })
        // Only the first matches on title.
        assertEquals(listOf("new"), ChatSearch.search(sessions, "trip").map { it.sessionId })
    }

    @Test
    fun `a chat saved before transcripts were kept is searched by its history`() {
        val legacy = ChatSession(
            id = "legacy",
            title = "Old chat",
            lastModified = 0L,
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("the old words"))),
            displayMessages = emptyList()
        )
        val hit = ChatSearch.search(listOf(legacy), "old words").single()
        assertEquals("legacy", hit.sessionId)
        assertTrue(hit.snippet!!.contains("old words"))
    }

    @Test
    fun `a snippet never splits a surrogate pair`() {
        // Emoji-dense text: the context slice and the 120-character cut both
        // land mid-pair without the boundary guard.
        val text = "😀".repeat(300) + " needle " + "😀".repeat(300)
        val snippet = ChatSearch.search(listOf(chat("a", "Emoji", text)), "needle").single().snippet!!
        assertTrue(snippet.contains("needle"))
        assertFalse("a lone surrogate would render as a broken character: $snippet", hasLoneSurrogate(snippet))
    }

    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return true
                    i += 2
                }
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }

    @Test
    fun `a long chat gets a short snippet around the match`() {
        val long = "start " + "filler ".repeat(200) + "needle" + " tail ".repeat(200)
        val hit = ChatSearch.search(listOf(chat("a", "Long", long)), "needle").single()
        val snippet = hit.snippet!!

        assertTrue("snippet must stay short: ${snippet.length}", snippet.length <= 130)
        assertTrue(snippet.contains("needle"))
        assertTrue("a cut snippet marks the cut", snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `blank lines are skipped, so a match never lands on whitespace`() {
        val sessions = listOf(
            ChatSession(
                id = "a",
                title = "Blanks",
                lastModified = 0L,
                messages = emptyList(),
                displayMessages = listOf(
                    UiMessage(id = 0, kind = MessageKind.USER, text = "   "),
                    UiMessage(id = 1, kind = MessageKind.ASSISTANT, text = "the answer")
                )
            )
        )
        val hit = ChatSearch.search(sessions, "answer").single()
        assertEquals("the answer", hit.snippet)
    }

    @Test
    fun `a match at the very start is not prefixed with an ellipsis`() {
        val hit = ChatSearch.search(listOf(chat("a", "T", "needle at the start")), "needle").single()
        assertTrue(hit.snippet!!.startsWith("needle"))
    }
}
