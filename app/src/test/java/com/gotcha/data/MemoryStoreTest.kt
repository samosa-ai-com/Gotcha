package com.gotcha.data

import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryStoreTest {

    private lateinit var tmpRoot: File
    private var savedOverride: String? = null

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("gotcha-memory-test").toFile()
        savedOverride = try {
            GotchaStorage.rootPath
        } catch (_: Exception) {
            null
        }
        GotchaStorage.rootPath = File(tmpRoot, "Gotcha").absolutePath
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
        if (savedOverride != null) {
            GotchaStorage.rootPath = savedOverride!!
        } else {
            GotchaStorage.resetRootPathForTests()
        }
    }

    @Test
    fun `ensureDefaults creates memory tree and seeds files`() {
        MemoryStore.ensureDefaults(Settings(userName = "Ada"))
        assertTrue(GotchaStorage.memoryRoot().isDirectory)
        assertTrue(GotchaStorage.soulFile().exists())
        assertTrue(GotchaStorage.memoryIndexFile().exists())
        assertTrue(GotchaStorage.memoryUserFile().readText().contains("Ada"))
        assertTrue(File(GotchaStorage.memoryAreasDir(), "work.md").exists())
    }

    @Test
    fun `startup message contains capped soul and memory index`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.soulFile().writeText("S".repeat(2000))
        val msg = MemoryStore.buildStartupMessage()
        assertNotNull(msg)
        val text = msg!!.textContent
        assertTrue(text.contains(MemoryStore.STARTUP_MARKER))
        assertTrue(text.contains("truncated"))
        assertTrue(MemoryStore.isStartupMessage(msg))
    }

    @Test
    fun `startup message null when files missing`() {
        assertNull(MemoryStore.buildStartupMessage())
    }

    @Test
    fun `non startup message is not detected`() {
        val other = ChatMessage(role = "system", content = JsonPrimitive("hello"))
        assertFalse(MemoryStore.isStartupMessage(other))
    }

    @Test
    fun `pointer block is stable and cheap`() {
        val first = MemoryStore.buildMemoryPointerBlock()
        val second = MemoryStore.buildMemoryPointerBlock()
        assertTrue(first == second)
        assertTrue(first.contains("<memory>"))
        assertTrue(first.length < 800)
    }

    @Test
    fun `syncUserFile reflects profile changes`() {
        MemoryStore.ensureDefaults(Settings(userName = "Ada"))
        MemoryStore.syncUserFile(Settings(userName = "Grace", userOccupation = "Engineer"))
        val text = GotchaStorage.memoryUserFile().readText()
        assertTrue(text.contains("Grace"))
        assertFalse(text.contains("Ada"))
    }

    @Test
    fun `user file includes reply style`() {
        MemoryStore.syncUserFile(Settings(userResponseStyle = "No bullet lists."))
        assertTrue(GotchaStorage.memoryUserFile().readText().contains("No bullet lists."))
    }

    @Test
    fun `startup message uses absolute paths`() {
        MemoryStore.ensureDefaults(Settings())
        val text = MemoryStore.buildStartupMessage()!!.textContent
        assertTrue(text.contains(GotchaStorage.memoryRoot().absolutePath))
        assertFalse(text.contains("Paths:\n  Gotcha/memory/"))
    }

    @Test
    fun `pointer block is conditional on availability`() {
        val pointer = MemoryStore.buildMemoryPointerBlock()
        assertTrue(pointer.contains("if that message is absent"))
        assertTrue(pointer.contains("ABSOLUTE paths"))
    }
}
