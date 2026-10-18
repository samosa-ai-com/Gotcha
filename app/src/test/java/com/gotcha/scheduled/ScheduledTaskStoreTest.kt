package com.gotcha.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.agent.Attachment
import com.gotcha.agent.ComposerAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [ScheduledTaskStore]: the tasks and the files kept with them (issue #132). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTaskStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.filesDir, "scheduled-store-test")
    private lateinit var store: ScheduledTaskStore

    @Before
    fun setUp() {
        dir.deleteRecursively()
        store = ScheduledTaskStore(context.getSharedPreferences("scheduled-store-test", Context.MODE_PRIVATE), dir)
    }

    private fun task(id: String, chat: String? = null) =
        ScheduledTask(id = id, name = "Task $id", prompt = "p", startLocal = "2026-10-19T08:00", chatSessionId = chat)

    private val document = ComposerAttachment.Document(
        "a",
        Attachment(name = "notes.txt", mimeType = "text/plain", size = 5, text = "hello")
    )

    @Test
    fun `tasks are added, replaced and deleted`() {
        store.put(task("1"))
        store.put(task("2"))
        store.put(task("1").copy(name = "Renamed"))

        assertEquals(listOf("Renamed", "Task 2"), store.all().map { it.name })

        store.delete("1")
        assertEquals(listOf("2"), store.all().map { it.id })
    }

    @Test
    fun `update changes one task and reports a missing one`() {
        store.put(task("1"))
        val updated = store.update("1") { it.copy(enabled = false) }
        assertEquals(false, updated!!.enabled)
        assertEquals(false, store.get("1")!!.enabled)
        assertNull(store.update("nope") { it })
    }

    @Test
    fun `files are kept with the task and go with it`() {
        val withFile = task("1").copy(attachmentNames = listOf("notes.txt"))
        store.put(withFile)
        store.saveAttachments("1", listOf(document))

        assertEquals(listOf(document), store.loadAttachments(withFile))

        store.delete("1")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a task whose files are gone reports them unreadable`() {
        val withFile = task("1").copy(attachmentNames = listOf("notes.txt"))
        assertNull(store.loadAttachments(withFile))
        assertEquals(emptyList<ComposerAttachment>(), store.loadAttachments(task("2")))
    }

    @Test
    fun `a deleted chat is forgotten by the tasks pinned to it`() {
        store.put(task("1", chat = "chat-a"))
        store.put(task("2", chat = "chat-b"))

        store.forgetChat("chat-a")

        assertNull(store.get("1")!!.chatSessionId)
        assertEquals("chat-b", store.get("2")!!.chatSessionId)
    }
}
