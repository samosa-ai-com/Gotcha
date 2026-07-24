package com.gotcha.connectors.mail

import com.gotcha.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackend(override val idPrefix: String) : MailBackend {
    var lastListQuery: String? = null
    var lastUnreadOnly: Boolean = false
    var lastMax: Int = -1
    var sentMessage: OutgoingEmail? = null
    val markedRead = mutableMapOf<String, Boolean>()
    var listResult: List<EmailSummary> = emptyList()
    var readResult: EmailFull? = null

    override suspend fun list(query: String?, unreadOnly: Boolean, max: Int): List<EmailSummary> {
        lastListQuery = query
        lastUnreadOnly = unreadOnly
        lastMax = max
        return listResult
    }

    override suspend fun read(id: String): EmailFull = readResult ?: error("no read result set")

    override suspend fun send(message: OutgoingEmail): String {
        sentMessage = message
        return "sent to ${message.to.joinToString()}"
    }

    override suspend fun markRead(id: String, read: Boolean) {
        markedRead[id] = read
    }
}

class EmailToolsTest {

    private fun buildArgs(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    @Test
    fun `list_emails prefers gmail backend over imap`() = runTest {
        val gmail = FakeBackend("gmail").apply {
            listResult = listOf(EmailSummary("gmail:1", "a@b.com", "Hi", "2024-01-01", true, "snip"))
        }
        val imap = FakeBackend("imap")
        val tools = EmailTools(
            gmailBackend = { gmail },
            imapBackend = { imap },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val result = tools.execute("list_emails", buildArgs {})
        assertTrue(result.success)
        assertTrue(result.message.contains("gmail:1"))
        assertEquals(-1, imap.lastMax) // imap never called
    }

    @Test
    fun `list_emails falls back to imap when gmail not connected`() = runTest {
        val imap = FakeBackend("imap").apply {
            listResult = listOf(EmailSummary("imap:INBOX:5", "c@d.com", "Yo", "2024-01-02", false, "snip2"))
        }
        val tools = EmailTools(
            gmailBackend = { null },
            imapBackend = { imap },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val result = tools.execute("list_emails", buildArgs { put("max", 5) })
        assertTrue(result.success)
        assertTrue(result.message.contains("imap:INBOX:5"))
        assertEquals(5, imap.lastMax)
    }

    @Test
    fun `list_emails with no backend steers to settings`() = runTest {
        val tools = EmailTools(
            gmailBackend = { null },
            imapBackend = { null },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val result = tools.execute("list_emails", buildArgs {})
        assertTrue(!result.success)
        assertTrue(result.message.contains("Settings"))
        assertTrue(result.message.contains("compose_email"))
    }

    @Test
    fun `read_email routes by id prefix`() = runTest {
        val gmail = FakeBackend("gmail").apply {
            readResult = EmailFull("gmail:1", "a@b.com", "me@x.com", "", "Subj", "2024-01-01", "Body text")
        }
        val imap = FakeBackend("imap")
        val tools = EmailTools(
            gmailBackend = { gmail },
            imapBackend = { imap },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val result = tools.execute("read_email", buildArgs { put("id", "gmail:1") })
        assertTrue(result.success)
        assertTrue(result.message.contains("Body text"))
    }

    @Test
    fun `read_email with disconnected backend for id prefix errors`() = runTest {
        val tools = EmailTools(
            gmailBackend = { null },
            imapBackend = { null },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val result = tools.execute("read_email", buildArgs { put("id", "gmail:1") })
        assertTrue(!result.success)
    }

    @Test
    fun `send_email returns confirm payload without sending`() = runTest {
        val gmail = FakeBackend("gmail")
        val tools = EmailTools(
            gmailBackend = { gmail },
            imapBackend = { null },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val args = buildArgs {
            putJsonArray("to") { add("x@y.com") }
            put("subject", "Subj")
            put("body", "Body")
        }
        val result = tools.execute("send_email", args)
        assertTrue(result.success)
        assertTrue(result.message.startsWith(EmailTools.CONFIRM_SEND_PREFIX))
        assertEquals(null, gmail.sentMessage) // not sent yet
    }

    @Test
    fun `executeSendConfirmed sends via primary backend`() = runTest {
        val gmail = FakeBackend("gmail")
        val tools = EmailTools(
            gmailBackend = { gmail },
            imapBackend = { null },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val args = buildArgs {
            putJsonArray("to") { add("x@y.com") }
            put("subject", "Subj")
            put("body", "Body")
        }
        val confirm = tools.execute("send_email", args)
        val payload = confirm.message.removePrefix(EmailTools.CONFIRM_SEND_PREFIX)
        val result = tools.executeSendConfirmed(payload)
        assertTrue(result.success)
        assertEquals("x@y.com", gmail.sentMessage?.to?.first())
    }

    @Test
    fun `mark_email_read routes to correct backend by prefix`() = runTest {
        val imap = FakeBackend("imap")
        val tools = EmailTools(
            gmailBackend = { null },
            imapBackend = { imap },
            composeLauncher = { _, _, _ -> ToolResult.ok("composed") }
        )
        val result = tools.execute(
            "mark_email_read",
            buildArgs {
                put("id", "imap:INBOX:9")
                put("read", false)
            }
        )
        assertTrue(result.success)
        assertEquals(false, imap.markedRead["imap:INBOX:9"])
    }

    @Test
    fun `compose_email delegates to compose launcher`() = runTest {
        var capturedTo: String? = null
        val tools = EmailTools(
            gmailBackend = { null },
            imapBackend = { null },
            composeLauncher = { to, _, _ ->
                capturedTo = to
                ToolResult.ok("opened")
            }
        )
        val result = tools.execute("compose_email", buildArgs { put("to", "z@z.com") })
        assertTrue(result.success)
        assertEquals("z@z.com", capturedTo)
    }
}
