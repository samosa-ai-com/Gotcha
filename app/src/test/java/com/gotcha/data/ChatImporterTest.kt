package com.gotcha.data

import com.gotcha.agent.ComposerAttachment
import com.gotcha.agent.MessageKind
import com.gotcha.agent.UiMessage
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.visionUserMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Chat import (issue #83): every file Gotcha writes imports back unchanged,
 * damaged input is refused with a reason (per chat where possible), and a
 * clash with an existing chat is resolved the way the user chose.
 */
class ChatImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var repo: ChatHistoryRepository
    private var idCounter = 0
    private val now = 1_790_000_000_000L

    private fun importer() = ChatImporter(repo, now = { now }, newId = { "new-${idCounter++}" })

    @Before
    fun setUp() {
        dir = tmp.newFolder("chats")
        repo = ChatHistoryRepository(dir)
    }

    private fun text(role: String, text: String) = ChatMessage(role = role, content = JsonPrimitive(text))

    /** A chat using every field a session stores, so a lossless import is checked field by field. */
    private fun fullSession(id: String = "chat-1", title: String = "Trip plans") = ChatSession(
        id = id,
        title = title,
        lastModified = 1_700_000_000_000L,
        messages = listOf(visionUserMessage("Where is this?", "QUJD", "jpeg"), text("assistant", "Lisbon.")),
        tokenCount = 321,
        displayMessages = listOf(
            UiMessage(
                0,
                MessageKind.USER,
                "Where is this?",
                attachments = listOf(ComposerAttachment.Image("a1", "photo.jpg", "QUJD"))
            ),
            UiMessage(1, MessageKind.ASSISTANT, "Lisbon.", imageBase64 = "U0NS")
        ),
        agentMode = "OPERATOR",
        runSummaries = listOf(
            RunSummary(
                startedAt = 1L,
                endedAt = 2L,
                userPrompt = "Where is this?",
                finalReply = "Lisbon.",
                model = "m",
                agentMode = "OPERATOR",
                delegated = false,
                succeeded = true,
                toolCalls = emptyList()
            )
        ),
        personaId = "guide"
    )

    private fun backup(vararg sessions: ChatSession, includeImages: Boolean = true): ByteArray =
        ChatArchive.encode(
            ChatArchive(
                exportedAt = now,
                includesImages = includeImages,
                sessions = if (includeImages) sessions.toList() else sessions.map(ChatArchive::withoutImages)
            )
        ).toByteArray()

    private fun ready(result: ImportParseResult): ImportPreview =
        (result as? ImportParseResult.Ready)?.preview ?: error("expected a preview, got $result")

    private fun failure(result: ImportParseResult): String =
        (result as? ImportParseResult.Failed)?.message ?: error("expected a failure, got $result")

    // ---- round trips ----

    @Test
    fun `a backup imports back exactly`() = runBlocking {
        val original = fullSession()
        val preview = ready(importer().preview(backup(original)))
        assertEquals(ImportFormat.BACKUP, preview.format)
        assertEquals(1, preview.newCount)

        val result = importer().commit(preview, DuplicateStrategy.KEEP_BOTH)
        assertEquals(1, result.imported)
        assertEquals(original, repo.loadSession("chat-1"))
    }

    @Test
    fun `a stored chat file imports as it is`() = runBlocking {
        val bytes = ChatArchive.json.encodeToString(ChatSession.serializer(), fullSession()).toByteArray()
        val preview = ready(importer().preview(bytes))
        assertEquals(ImportFormat.CHAT_FILE, preview.format)
        importer().commit(preview, DuplicateStrategy.KEEP_BOTH)
        assertEquals(fullSession(), repo.loadSession("chat-1"))
    }

    @Test
    fun `a markdown export imports as a continuable chat`() = runBlocking {
        val markdown = ChatMarkdown.export(
            listOf(text("user", "Plan a trip to Lisbon"), text("assistant", "Here's a plan.")),
            "md-chat",
            "Lisbon trip",
            now = 1_700_000_000_000L
        )
        val preview = ready(importer().preview(markdown.toByteArray()))
        assertEquals(ImportFormat.MARKDOWN, preview.format)
        assertTrue(preview.warnings.any { "text only" in it })

        importer().commit(preview, DuplicateStrategy.KEEP_BOTH)
        val saved = repo.loadSession("md-chat")!!
        assertEquals("Lisbon trip", saved.title)
        assertEquals(listOf("Plan a trip to Lisbon", "Here's a plan."), saved.messages.map { it.textContent })
        assertEquals(1_700_000_000_000L / 1000 * 1000, saved.lastModified)
        assertTrue(saved.displayMessages.isEmpty()) // rebuilt from messages when opened
    }

    @Test
    fun `a markdown export without a title is named from its first message`() = runBlocking {
        val markdown = ChatMarkdown.export(listOf(text("user", "Remind me to call mum tomorrow at nine")), "x", null)
        val saved = ready(importer().preview(markdown.toByteArray())).items.single().session
        assertEquals("Remind me to call mum tomorrow at nine".take(30), saved.title)
    }

    @Test
    fun `a backup without images drops every image but keeps the words`() = runBlocking {
        val preview = ready(importer().preview(backup(fullSession(), includeImages = false)))
        assertTrue(preview.warnings.any { "without images" in it })
        val session = preview.items.single().session
        assertEquals(0, session.messages.sumOf { it.imageCount })
        assertEquals("Where is this?", session.messages[0].textContent)
        assertTrue(session.displayMessages.all { it.imageBase64 == null && it.attachments.isEmpty() })
    }

    // ---- refusing a file ----

    @Test
    fun `files that aren't Gotcha chats are refused with a reason`() = runBlocking {
        assertTrue("empty" in failure(importer().preview(ByteArray(0))))
        assertTrue("valid JSON" in failure(importer().preview("{\"format\": ".toByteArray())))
        assertTrue("isn't a Gotcha" in failure(importer().preview("""{"hello":"world"}""".toByteArray())))
        assertTrue("isn't a Gotcha" in failure(importer().preview("just some notes".toByteArray())))
        assertTrue("isn't a Gotcha" in failure(importer().preview("[1,2,3]".toByteArray())))
    }

    @Test
    fun `a backup from a newer version is refused`() = runBlocking {
        val bytes = """{"format":"gotcha-chats","version":99,"exportedAt":0,"sessions":[]}""".toByteArray()
        assertTrue("newer version" in failure(importer().preview(bytes)))
    }

    @Test
    fun `an oversized file is refused before it is parsed`() = runBlocking {
        val bytes = ByteArray(ChatImporter.MAX_BYTES + 1)
        assertTrue("MB" in failure(importer().preview(bytes)))
    }

    @Test
    fun `a backup with no chats is refused`() = runBlocking {
        assertTrue("no chats" in failure(importer().preview(backup())))
    }

    // ---- per-chat validation ----

    @Test
    fun `a damaged chat is reported and the rest still import`() = runBlocking {
        val good = ChatArchive.json.encodeToString(ChatSession.serializer(), fullSession())
        val bytes = """
            {"format":"gotcha-chats","version":1,"exportedAt":0,"sessions":[
              $good,
              {"id":"broken","title":"Broken chat","messages":"not a list"},
              {"id":"empty","title":"Empty chat","lastModified":1,"messages":[]},
              {"id":"odd","title":"Odd chat","lastModified":1,"messages":[{"role":"wizard","content":"hi"}]}
            ]}
        """.trimIndent().toByteArray()
        val preview = ready(importer().preview(bytes))
        assertEquals(1, preview.items.size)
        assertEquals(listOf("Broken chat", "Empty chat", "Odd chat"), preview.rejected.map { it.title })

        val result = importer().commit(preview, DuplicateStrategy.KEEP_BOTH)
        assertEquals(1, result.imported)
        assertEquals(3, result.failed.size)
        assertNull(repo.loadSession("broken"))
    }

    @Test
    fun `an id that could escape the chats folder is replaced`() = runBlocking {
        val sneaky = fullSession(id = "../../shared_prefs/settings")
        val preview = ready(importer().preview(backup(sneaky)))
        val id = preview.items.single().session.id
        assertEquals("new-0", id)
        importer().commit(preview, DuplicateStrategy.KEEP_BOTH)
        assertEquals(listOf("new-0.json"), dir.list()!!.toList())
    }

    @Test
    fun `a missing title and an impossible date are repaired`() = runBlocking {
        val session = fullSession().copy(title = "", lastModified = now * 10)
        val repaired = ready(importer().preview(backup(session))).items.single().session
        assertEquals("Where is this?", repaired.title)
        assertEquals(now, repaired.lastModified)
    }

    @Test
    fun `two chats sharing an id in one file both import`() = runBlocking {
        val preview = ready(importer().preview(backup(fullSession(title = "A"), fullSession(title = "B"))))
        assertEquals(2, preview.items.map { it.session.id }.toSet().size)
        assertEquals(2, importer().commit(preview, DuplicateStrategy.KEEP_BOTH).imported)
        assertEquals(2, repo.listSessions().size)
    }

    // ---- duplicates ----

    @Test
    fun `importing a chat that is already here changes nothing`() = runBlocking {
        repo.saveSession(fullSession(), touch = false)
        val preview = ready(importer().preview(backup(fullSession())))
        assertEquals(1, preview.identicalCount)

        val result = importer().commit(preview, DuplicateStrategy.REPLACE)
        assertEquals(ImportResult(0, 0, 1, emptyList(), emptyList()), result)
        assertEquals(1, repo.listSessions().size)
    }

    @Test
    fun `re-importing a chat's own markdown export is recognised as a duplicate`() = runBlocking {
        val stored = fullSession()
        repo.saveSession(stored, touch = false)
        val markdown = ChatMarkdown.export(stored.messages, stored.id, stored.title)
        assertEquals(1, ready(importer().preview(markdown.toByteArray())).identicalCount)
    }

    private suspend fun conflictPreview(): ImportPreview {
        repo.saveSession(fullSession(), touch = false)
        val changed = fullSession().copy(messages = fullSession().messages + text("user", "And the weather?"))
        return ready(importer().preview(backup(changed))).also { assertEquals(1, it.conflictCount) }
    }

    @Test
    fun `keep both imports a changed chat beside the original`() = runBlocking {
        val result = importer().commit(conflictPreview(), DuplicateStrategy.KEEP_BOTH)
        assertEquals(1, result.imported)
        assertEquals(2, fullSession().messages.size.let { repo.loadSession("chat-1")!!.messages.size })
        val copy = repo.loadSession(result.writtenIds.single())!!
        assertEquals("Trip plans (imported)", copy.title)
        assertEquals(3, copy.messages.size)
    }

    @Test
    fun `replace overwrites the original`() = runBlocking {
        val result = importer().commit(conflictPreview(), DuplicateStrategy.REPLACE)
        assertEquals(1, result.replaced)
        assertEquals(3, repo.loadSession("chat-1")!!.messages.size)
        assertEquals(1, repo.listSessions().size)
    }

    @Test
    fun `skip leaves the original alone`() = runBlocking {
        val result = importer().commit(conflictPreview(), DuplicateStrategy.SKIP)
        assertEquals(1, result.skipped)
        assertEquals(2, repo.loadSession("chat-1")!!.messages.size)
    }

    @Test
    fun `a chat with a running task is never replaced`() = runBlocking {
        val result = importer().commit(conflictPreview(), DuplicateStrategy.REPLACE, protectedIds = setOf("chat-1"))
        assertEquals(0, result.replaced)
        assertTrue(result.failed.single().reason.contains("running"))
        assertEquals(2, repo.loadSession("chat-1")!!.messages.size)
    }

    @Test
    fun `a chat saved between preview and import is treated as a clash`() = runBlocking {
        val preview = ready(importer().preview(backup(fullSession())))
        assertEquals(1, preview.newCount)
        repo.saveSession(fullSession().copy(messages = listOf(text("user", "other"))), touch = false)

        val result = importer().commit(preview, DuplicateStrategy.KEEP_BOTH)
        assertNotEquals("chat-1", result.writtenIds.single())
        assertEquals("other", repo.loadSession("chat-1")!!.messages.single().textContent)
    }

    @Test
    fun `withoutImages leaves a chat with no images untouched`() {
        val plain = fullSession().copy(
            messages = listOf(text("user", "hi")),
            displayMessages = listOf(UiMessage(0, MessageKind.USER, "hi"))
        )
        assertEquals(plain, ChatArchive.withoutImages(plain))
        assertFalse(ChatArchive.encode(ChatArchive(exportedAt = 0, sessions = listOf(plain))).isBlank())
    }
}
