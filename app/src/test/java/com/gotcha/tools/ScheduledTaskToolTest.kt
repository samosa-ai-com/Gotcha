package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.agent.ComposerAttachment
import com.gotcha.data.GotchaStorage
import com.gotcha.data.MemoryStore
import com.gotcha.data.Settings
import com.gotcha.scheduled.RepeatKind
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskStore
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The scheduled-task tools' own checks (issue #132): what a request must hold
 * before the user is asked anything, and what an approved one saves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTaskToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("UTC")
    private val now = LocalDateTime.parse("2026-10-18T12:00").atZone(zone).toInstant().toEpochMilli()
    private lateinit var store: ScheduledTaskStore
    private lateinit var tool: ScheduledTaskTool

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        store = ScheduledTaskStore(context)
        store.all().forEach { store.delete(it.id) }
        tool = ScheduledTaskTool(
            context,
            store,
            zone = { zone },
            clock = { now },
            chatTitle = { id -> if (id == "news-chat") "Morning news" else null }
        )
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun request(result: ToolResult): ScheduleRequest {
        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.startsWith(ScheduledTaskTool.CONFIRM_PREFIX))
        return ScheduledTaskTool.decodePayload(result.message.removePrefix(ScheduledTaskTool.CONFIRM_PREFIX))!!
    }

    @Test
    fun `a valid create asks for approval and saves nothing yet`() {
        val result = tool.prepareCreate(
            args(
                """{"name":"News","prompt":"Summarise the news","start":"2026-10-19T08:00","repeat":"weekly","weekdays":["mon","fri"]}"""
            )
        )

        val request = request(result)
        assertEquals("2026-10-19T08:00", request.startLocal)
        assertEquals(RepeatKind.WEEKLY, request.repeat!!.kind)
        assertEquals(listOf(1, 5), request.repeat!!.weekdays)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a create missing its fields or with bad ones is refused before any prompt`() {
        assertFalse(tool.prepareCreate(args("""{"prompt":"p","start":"2026-10-19T08:00"}""")).success)
        assertFalse(tool.prepareCreate(args("""{"name":"n","start":"2026-10-19T08:00"}""")).success)
        assertFalse(tool.prepareCreate(args("""{"name":"n","prompt":"p"}""")).success)
        val badStart = tool.prepareCreate(args("""{"name":"n","prompt":"p","start":"tomorrow"}"""))
        assertTrue(badStart.message, badStart.message.contains("YYYY-MM-DDTHH:MM"))
        val badRepeat = tool.prepareCreate(
            args("""{"name":"n","prompt":"p","start":"2026-10-19T08:00","repeat":"minutely"}""")
        )
        assertTrue(badRepeat.message, badRepeat.message.contains("Invalid 'repeat'"))
        val badDay = tool.prepareCreate(
            args("""{"name":"n","prompt":"p","start":"2026-10-19T08:00","repeat":"weekly","weekdays":["funday"]}""")
        )
        assertTrue(badDay.message, badDay.message.contains("Invalid weekday"))
        val badInterval = tool.prepareCreate(
            args("""{"name":"n","prompt":"p","start":"2026-10-19T08:00","repeat":"hourly","interval":0}""")
        )
        assertTrue(badInterval.message, badInterval.message.contains("Invalid 'interval'"))
    }

    @Test
    fun `a one-time task in the past is refused`() {
        val result = tool.prepareCreate(args("""{"name":"n","prompt":"p","start":"2026-10-18T11:00"}"""))
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("already passed"))
    }

    @Test
    fun `the agent can set up only so many tasks`() {
        repeat(ScheduledTaskTool.MAX_AGENT_TASKS) {
            store.put(
                ScheduledTask(
                    id = "a$it",
                    name = "n",
                    prompt = "p",
                    startLocal = "2026-10-19T08:00",
                    createdByAgent = true
                )
            )
        }
        // The user's own don't count.
        store.put(ScheduledTask(id = "u", name = "n", prompt = "p", startLocal = "2026-10-19T08:00"))

        val result = tool.prepareCreate(args("""{"name":"n","prompt":"p","start":"2026-10-19T08:00"}"""))

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("the most allowed"))
    }

    @Test
    fun `an approved create is saved, armed and capped at the chat's mode`() {
        val request = request(
            tool.prepareCreate(
                args(
                    """{"name":"News","prompt":"Summarise","start":"2026-10-19T08:00","repeat":"daily",""" +
                        """"mode":"operator","same_chat":true}"""
                )
            )
        )

        val result = tool.apply(request, sessionId = "chat-1", chatMode = AgentMode.MONITOR)

        assertTrue(result.message, result.success)
        val saved = store.all().single()
        assertEquals(AgentMode.MONITOR, saved.mode)
        assertTrue(saved.createdByAgent)
        assertTrue(saved.sameChat)
        assertEquals("chat-1", saved.chatSessionId)
        assertEquals(LocalDateTime.parse("2026-10-19T08:00").atZone(zone).toInstant().toEpochMilli(), saved.nextRunAt)
        assertTrue(result.message, result.message.contains(saved.id))
    }

    @Test
    fun `the mode defaults to the chat's`() {
        val request = request(tool.prepareCreate(args("""{"name":"n","prompt":"p","start":"2026-10-19T08:00"}""")))
        tool.apply(request, sessionId = "chat", chatMode = AgentMode.OPERATOR)
        assertEquals(AgentMode.OPERATOR, store.all().single().mode)
    }

    // ---- memory note links (Second Brain Phase 2) ----

    private fun withMemoryNote(name: String = "plan.md", block: (note: File) -> Unit) {
        val savedRoot = GotchaStorage.rootPath
        GotchaStorage.rootPath = File(context.filesDir, "sched-mem-test/Gotcha").absolutePath
        try {
            MemoryStore.ensureDefaults(Settings())
            val note = File(GotchaStorage.memoryNotesDir(), name)
            note.writeText(
                listOf(
                    "---",
                    "title: Plan",
                    "area: Personal",
                    "type: note",
                    "updated: 2026-10-29",
                    "---",
                    "",
                    "Body.",
                    ""
                ).joinToString("\n")
            )
            block(note)
        } finally {
            GotchaStorage.rootPath = savedRoot
            File(context.filesDir, "sched-mem-test").deleteRecursively()
        }
    }

    @Test
    fun `a create with a memory note saves the link`() {
        withMemoryNote { note ->
            val result = tool.prepareCreate(
                args(
                    """{"name":"Plan","prompt":"Work the plan","start":"2026-10-19T08:00",""" +
                        """"memory_note":"${note.absolutePath}"}"""
                )
            )

            val req = request(result)
            assertEquals(note.canonicalPath, req.memoryNote)
            val applied = tool.apply(req, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            assertTrue(applied.message, applied.success)
            assertEquals(note.canonicalPath, store.all().single().memoryNotePath)
        }
    }

    @Test
    fun `a create rejects a memory note outside memory`() {
        val outside = File(context.filesDir, "outside.md").apply { writeText("# Outside\n") }
        try {
            val result = tool.prepareCreate(
                args(
                    """{"name":"Plan","prompt":"Work the plan","start":"2026-10-19T08:00",""" +
                        """"memory_note":"${outside.absolutePath}"}"""
                )
            )
            assertFalse(result.message, result.success)
            assertTrue(result.message, result.message.contains("not a memory"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `an edit can link a note and the listing names it`() {
        withMemoryNote { note ->
            val created = request(
                tool.prepareCreate(args("""{"name":"Plan","prompt":"Work it","start":"2026-10-19T08:00"}"""))
            )
            tool.apply(created, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            val task = store.all().single()
            assertNull(task.memoryNotePath)

            val edited = request(
                tool.prepareEdit(
                    args("""{"task_id":"${task.id}","memory_note":"${note.absolutePath}"}""")
                )
            )
            assertEquals(note.canonicalPath, edited.memoryNote)
            tool.apply(edited, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            assertEquals(note.canonicalPath, store.all().single().memoryNotePath)
            assertTrue(tool.list().message, tool.list().message.contains("memory note"))
        }
    }

    @Test
    fun `an edit with a blank memory note clears the link`() {
        withMemoryNote { note ->
            val created = request(
                tool.prepareCreate(
                    args(
                        """{"name":"Plan","prompt":"Work it","start":"2026-10-19T08:00",""" +
                            """"memory_note":"${note.absolutePath}"}"""
                    )
                )
            )
            tool.apply(created, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            val task = store.all().single()
            assertEquals(note.canonicalPath, task.memoryNotePath)

            val cleared = request(
                tool.prepareEdit(args("""{"task_id":"${task.id}","memory_note":""}"""))
            )
            assertTrue(cleared.clearMemoryNote)
            tool.apply(cleared, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            assertNull(store.all().single().memoryNotePath)
        }
    }

    @Test
    fun `the approval names a removed memory note`() = runTest {
        store.put(
            ScheduledTask(
                id = "t1",
                name = "Plan",
                prompt = "p",
                startLocal = "2026-10-19T08:00",
                memoryNotePath = "/mem/notes/plan.md"
            )
        )
        val words = tool.describe(
            ScheduleRequest(taskId = "t1", clearMemoryNote = true),
            sessionId = "chat",
            chatMode = AgentMode.OPERATOR
        )
        assertTrue(words, words.contains("(removed)"))
    }

    @Test
    fun `an edit with an explicit null memory note keeps the link`() {
        withMemoryNote { note ->
            val created = request(
                tool.prepareCreate(
                    args(
                        """{"name":"Plan","prompt":"Work it","start":"2026-10-19T08:00",""" +
                            """"memory_note":"${note.absolutePath}"}"""
                    )
                )
            )
            tool.apply(created, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            val task = store.all().single()

            val kept = request(
                tool.prepareEdit(
                    args("""{"task_id":"${task.id}","name":"Renamed","memory_note":null}""")
                )
            )
            assertNull(kept.memoryNote)
            assertFalse(kept.clearMemoryNote)
            tool.apply(kept, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            val saved = store.all().single()
            assertEquals("Renamed", saved.name)
            assertEquals(note.canonicalPath, saved.memoryNotePath)
        }
    }

    @Test
    fun `a create with a blank memory note shows no link line`() = runTest {
        var created: ScheduleRequest? = null
        withMemoryNote {
            created = request(
                tool.prepareCreate(
                    args("""{"name":"Plan","prompt":"Work it","start":"2026-10-19T08:00","memory_note":""}""")
                )
            )
            assertTrue(created!!.clearMemoryNote)
        }
        val words = tool.describe(created!!, sessionId = "chat", chatMode = AgentMode.OPERATOR)
        assertFalse(words, words.contains("Memory note"))
    }

    @Test
    fun `an edit can point a task at a different note`() {
        withMemoryNote { first ->
            val people = GotchaStorage.memoryPeopleDir().apply { mkdirs() }
            val second = File(people, "plan.md")
            second.writeText(
                listOf(
                    "---",
                    "title: Person Plan",
                    "area: People",
                    "type: person",
                    "updated: 2026-10-29",
                    "---",
                    "",
                    "Body.",
                    ""
                ).joinToString("\n")
            )
            val created = request(
                tool.prepareCreate(
                    args(
                        """{"name":"Plan","prompt":"Work it","start":"2026-10-19T08:00",""" +
                            """"memory_note":"${first.absolutePath}"}"""
                    )
                )
            )
            tool.apply(created, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            val task = store.all().single()

            val moved = request(
                tool.prepareEdit(
                    args("""{"task_id":"${task.id}","memory_note":"${second.absolutePath}"}""")
                )
            )
            tool.apply(moved, sessionId = "chat", chatMode = AgentMode.OPERATOR)
            // Same-named files stay apart in the listing.
            val listing = tool.list().message
            assertTrue(listing, listing.contains("people/plan.md"))
            assertFalse(listing, listing.contains("notes/plan.md"))
        }
    }

    @Test
    fun `files are checked when asked and read when approved`() {
        val notes = File(context.filesDir, "notes.txt").apply { writeText("Buy milk") }
        val missing = tool.prepareCreate(
            args(
                """{"name":"n","prompt":"p","start":"2026-10-19T08:00","files":["${File(
                    context.filesDir,
                    "nope.txt"
                ).path}"]}"""
            )
        )
        assertFalse(missing.success)

        val request = request(
            tool.prepareCreate(
                args("""{"name":"n","prompt":"p","start":"2026-10-19T08:00","files":["${notes.path}"]}""")
            )
        )
        tool.apply(request, sessionId = "chat", chatMode = AgentMode.OPERATOR)

        val saved = store.all().single()
        assertEquals(listOf("notes.txt"), saved.attachmentNames)
        val document = store.loadAttachments(saved)!!.single() as ComposerAttachment.Document
        assertTrue(document.attachment.text, document.attachment.text.contains("Buy milk"))
    }

    @Test
    fun `an edit changes only what it names and re-arms a new schedule`() {
        store.put(
            ScheduledTask(
                id = "t",
                name = "Old",
                prompt = "Old prompt",
                startLocal = "2026-10-19T08:00",
                nextRunAt = 1L
            )
        )

        val request = request(tool.prepareEdit(args("""{"task_id":"t","start":"2026-10-20T09:00","enabled":false}""")))
        assertNull(request.name)
        tool.apply(request, sessionId = "chat", chatMode = AgentMode.OPERATOR)

        val saved = store.get("t")!!
        assertEquals("Old", saved.name)
        assertEquals("Old prompt", saved.prompt)
        assertEquals("2026-10-20T09:00", saved.startLocal)
        assertFalse(saved.enabled)
        assertNull(saved.dueAt)
    }

    @Test
    fun `an edit of an unknown task or with nothing in it is refused`() {
        assertTrue(tool.prepareEdit(args("""{"task_id":"nope","name":"x"}""")).message.contains("No scheduled task"))
        store.put(ScheduledTask(id = "t", name = "n", prompt = "p", startLocal = "2026-10-19T08:00"))
        assertTrue(tool.prepareEdit(args("""{"task_id":"t"}""")).message.contains("Nothing to change"))
    }

    @Test
    fun `the approval prompt shows the whole task`() = runTest {
        val request = request(
            tool.prepareCreate(
                args(
                    """{"name":"News","prompt":"Summarise the news","start":"2026-10-19T08:00","repeat":"daily","interval":2}"""
                )
            )
        )

        val prompt = tool.describe(request, sessionId = "chat", chatMode = AgentMode.OPERATOR)

        assertTrue(prompt, prompt.contains("Name: News"))
        assertTrue(prompt, prompt.contains("every 2 days at 08:00"))
        assertTrue(prompt, prompt.contains("Mode: Operator"))
        assertTrue(prompt, prompt.contains("a new chat each time"))
        assertTrue(prompt, prompt.contains("Prompt: Summarise the news"))
        assertTrue(prompt, prompt.contains("without asking you first"))
    }

    @Test
    fun `an edit asked from another chat names the chat a same-chat task stays in`() = runTest {
        store.put(
            ScheduledTask(
                id = "t",
                name = "News",
                prompt = "Summarise",
                startLocal = "2026-10-19T08:00",
                sameChat = true,
                chatSessionId = "news-chat"
            )
        )

        val rename = request(tool.prepareEdit(args("""{"task_id":"t","name":"Headlines"}""")))
        val prompt = tool.describe(rename, sessionId = "other-chat", chatMode = AgentMode.OPERATOR)
        assertTrue(prompt, prompt.contains("Runs in: the chat \"Morning news\" (not this one)"))
        tool.apply(rename, sessionId = "other-chat", chatMode = AgentMode.OPERATOR)
        assertEquals("news-chat", store.get("t")!!.chatSessionId)

        // Asked from the pinned chat itself, it is "this chat".
        val fromPinned = tool.describe(rename, sessionId = "news-chat", chatMode = AgentMode.OPERATOR)
        assertTrue(fromPinned, fromPinned.contains("Runs in: this chat, each time"))

        // same_chat=true moves it here, and says so.
        val repin = request(tool.prepareEdit(args("""{"task_id":"t","same_chat":true}""")))
        val repinPrompt = tool.describe(repin, sessionId = "other-chat", chatMode = AgentMode.OPERATOR)
        assertTrue(repinPrompt, repinPrompt.contains("Runs in: this chat, each time"))
        tool.apply(repin, sessionId = "other-chat", chatMode = AgentMode.OPERATOR)
        assertEquals("other-chat", store.get("t")!!.chatSessionId)

        // A pinned chat that was deleted is not passed off as this one.
        store.put(store.get("t")!!.copy(chatSessionId = "gone"))
        val orphan = tool.describe(rename, sessionId = "other-chat", chatMode = AgentMode.OPERATOR)
        assertTrue(orphan, orphan.contains("no longer saved"))
    }

    @Test
    fun `list shows each task with its id, and delete asks first`() {
        store.put(ScheduledTask(id = "t", name = "News", prompt = "Summarise", startLocal = "2026-10-19T08:00"))

        val listed = tool.list().message
        assertTrue(listed, listed.contains("t: \"News\""))
        assertTrue(listed, listed.contains("prompt: Summarise"))

        val delete = tool.prepareDelete(args("""{"task_id":"t"}"""))
        assertEquals("${ScheduledTaskTool.DELETE_CONFIRM_PREFIX}t:News", delete.message)
        assertNotNull(store.get("t"))

        assertTrue(tool.doDelete("t").success)
        assertNull(store.get("t"))
    }

    @Test
    fun `weekdays and start times are read the ways a model writes them`() {
        assertEquals(1, ScheduledTaskTool.parseWeekday("Monday"))
        assertEquals(7, ScheduledTaskTool.parseWeekday("su"))
        assertEquals(3, ScheduledTaskTool.parseWeekday("3"))
        assertNull(ScheduledTaskTool.parseWeekday("m"))
        assertEquals(LocalDateTime.parse("2026-10-19T08:00"), ScheduledTaskTool.parseStart("2026-10-19 08:00:30"))
    }
}
