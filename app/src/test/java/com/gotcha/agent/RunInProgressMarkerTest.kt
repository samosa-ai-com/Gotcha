package com.gotcha.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.FunctionCall
import com.gotcha.llm.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Noticing a run that died with the process (issue #105). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunInProgressMarkerTest {

    private val prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("marker-test", Context.MODE_PRIVATE)

    private fun marker(process: String) = RunInProgressMarker(prefs) { process }

    @Test
    fun `a marker left by another process is an interrupted run`() {
        marker("dead").mark("chat-1")

        assertEquals("chat-1", marker("new").interruptedSession())
    }

    @Test
    fun `a marker of this process is not`() {
        marker("same").mark("chat-1")

        assertNull(marker("same").interruptedSession())
    }

    @Test
    fun `a cleared marker is nothing`() {
        marker("dead").mark("chat-1")
        marker("dead").clear()

        assertNull(marker("new").interruptedSession())
    }

    private fun call(id: String) = ToolCall(id = id, function = FunctionCall("list_files", "{}"))
    private fun user(text: String) = ChatMessage(role = "user", content = JsonPrimitive(text))
    private fun result(id: String) = ChatMessage(role = "tool", content = JsonPrimitive("ok"), toolCallId = id)

    @Test
    fun `tool calls left without results get one each`() {
        val history = listOf(
            user("Find big files"),
            ChatMessage(role = "assistant", toolCalls = listOf(call("a"), call("b"))),
            result("a")
        )

        val closed = closeOrphanedToolCalls(history)

        assertEquals(4, closed.size)
        assertEquals("b", closed.last().toolCallId)
        assertEquals("tool", closed.last().role)
    }

    @Test
    fun `a complete history is left alone`() {
        val history = listOf(
            user("Find big files"),
            ChatMessage(role = "assistant", toolCalls = listOf(call("a"))),
            result("a")
        )

        assertSame(history, closeOrphanedToolCalls(history))
    }

    @Test
    fun `tool calls before the last user message are not this run's`() {
        val history = listOf(
            ChatMessage(role = "assistant", toolCalls = listOf(call("a"))),
            user("Something else")
        )

        assertSame(history, closeOrphanedToolCalls(history))
    }
}
