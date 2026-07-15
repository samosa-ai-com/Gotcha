package com.gotcha.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uses the internal File-based constructor so no Android Context is needed.
 * Verifies the directory isolation that keeps voice-call sessions ("calls")
 * out of the main chat list ("chats").
 */
class ChatHistoryRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(id: String) = ChatSession(
        id = id,
        title = "Test $id",
        lastModified = 0L,
        messages = emptyList()
    )

    @Test
    fun `save then load round-trips a session`() = runBlocking {
        val repo = ChatHistoryRepository(tmp.newFolder("chats"))
        repo.saveSession(session("abc"))
        val loaded = repo.loadSession("abc")
        assertEquals("abc", loaded!!.id)
        assertEquals("Test abc", loaded.title)
    }

    @Test
    fun `sessions saved in the calls directory are invisible to the chats repository`() = runBlocking {
        val root = tmp.newFolder("filesDir")
        val chatsRepo = ChatHistoryRepository(File(root, "chats"))
        val callsRepo = ChatHistoryRepository(File(root, "calls"))

        callsRepo.saveSession(session("call-1"))
        chatsRepo.saveSession(session("chat-1"))

        assertEquals(listOf("chat-1"), chatsRepo.listSessions().map { it.id })
        assertEquals(listOf("call-1"), callsRepo.listSessions().map { it.id })
        assertNull(chatsRepo.loadSession("call-1"))
    }

    @Test
    fun `deleteSession removes the backing file`() = runBlocking {
        val dir = tmp.newFolder("calls")
        val repo = ChatHistoryRepository(dir)
        repo.saveSession(session("gone"))
        assertTrue(File(dir, "gone.json").exists())

        repo.deleteSession("gone")

        assertFalse(File(dir, "gone.json").exists())
        assertTrue(repo.listSessions().isEmpty())
    }
}
