package com.gotcha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GotchaStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        // Set before the getter is ever touched, so its Android-dependent
        // default (Environment.getExternalStorageDirectory()) is never
        // evaluated under plain JUnit.
        GotchaStorage.rootPath = tmp.newFolder("Gotcha").absolutePath
    }

    // ---- slugify ----

    @Test
    fun `slugify collapses non-alphanumeric runs to a single dash`() {
        assertEquals("Plan-my-trip-to-Goa", GotchaStorage.slugify("Plan my/trip: to Goa!!"))
    }

    @Test
    fun `slugify trims leading and trailing punctuation`() {
        assertEquals("hello", GotchaStorage.slugify("---hello---"))
    }

    @Test
    fun `slugify keeps unicode letters`() {
        assertEquals("Café-München", GotchaStorage.slugify("Café München"))
    }

    @Test
    fun `slugify collapses emoji to dashes`() {
        assertEquals("trip", GotchaStorage.slugify("🌴 trip 🚀"))
    }

    @Test
    fun `slugify truncates to 40 chars`() {
        val long = "a".repeat(60)
        assertEquals(40, GotchaStorage.slugify(long).length)
    }

    @Test
    fun `slugify falls back to New-Chat for blank or fully-punctuation input`() {
        assertEquals("New-Chat", GotchaStorage.slugify(""))
        assertEquals("New-Chat", GotchaStorage.slugify("   "))
        assertEquals("New-Chat", GotchaStorage.slugify("!!!"))
    }

    // ---- shortId / chatDirName ----

    @Test
    fun `shortId strips dashes and takes first 8 chars`() {
        assertEquals("a3f9c1d2", GotchaStorage.shortId("a3f9c1d2-1234-5678-9abc-def012345678"))
    }

    @Test
    fun `chatDirName combines slug and short id`() {
        assertEquals(
            "Plan-my-trip_a3f9c1d2",
            GotchaStorage.chatDirName("Plan my trip", "a3f9c1d2-1234-5678-9abc-def012345678")
        )
    }

    // ---- findChatDir ----

    @Test
    fun `findChatDir matches by short id suffix regardless of title part`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val dir = File(GotchaStorage.chatsRoot(), "Some-Old-Title_a3f9c1d2")
        dir.mkdirs()
        val found = GotchaStorage.findChatDir(sessionId)
        assertEquals(dir.absolutePath, found?.absolutePath)
    }

    @Test
    fun `findChatDir returns null when no dir matches`() {
        assertEquals(null, GotchaStorage.findChatDir("a3f9c1d2-1234-5678-9abc-def012345678"))
    }

    // ---- ensureChatDir ----

    @Test
    fun `ensureChatDir creates a new dir with readable name when none exists`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val dir = GotchaStorage.ensureChatDir(sessionId, "Plan my trip")
        assertTrue(dir.exists())
        assertEquals("Plan-my-trip_a3f9c1d2", dir.name)
    }

    @Test
    fun `ensureChatDir does not create the dir when create is false`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val dir = GotchaStorage.ensureChatDir(sessionId, "New Chat", create = false)
        assertFalse(dir.exists())
    }

    @Test
    fun `ensureChatDir renames an existing dir while preserving contents`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val oldDir = GotchaStorage.ensureChatDir(sessionId, "New Chat")
        File(oldDir, "notes.txt").writeText("hello")

        val renamed = GotchaStorage.ensureChatDir(sessionId, "Plan my trip to Goa")

        assertEquals("Plan-my-trip-to-Goa_a3f9c1d2", renamed.name)
        assertTrue(renamed.exists())
        assertFalse(oldDir.exists())
        assertEquals("hello", File(renamed, "notes.txt").readText())
    }

    @Test
    fun `ensureChatDir is idempotent on a second call with the same title`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val first = GotchaStorage.ensureChatDir(sessionId, "Plan my trip")
        File(first, "notes.txt").writeText("hello")
        val second = GotchaStorage.ensureChatDir(sessionId, "Plan my trip")

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals("hello", File(second, "notes.txt").readText())
        assertEquals(1, GotchaStorage.chatsRoot().listFiles()?.size)
    }

    // ---- archiveChatDir ----

    @Test
    fun `archiveChatDir moves the chat dir under old_chats preserving contents`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val dir = GotchaStorage.ensureChatDir(sessionId, "Plan my trip")
        File(dir, "notes.txt").writeText("hello")

        GotchaStorage.archiveChatDir(sessionId)

        assertFalse(dir.exists())
        val archived = File(GotchaStorage.archiveRoot(), dir.name)
        assertTrue(archived.exists())
        assertEquals("hello", File(archived, "notes.txt").readText())
    }

    @Test
    fun `archiveChatDir suffixes with a timestamp on name collision`() {
        val sessionId = "a3f9c1d2-1234-5678-9abc-def012345678"
        val dir = GotchaStorage.ensureChatDir(sessionId, "Plan my trip")
        GotchaStorage.archiveRoot().mkdirs()
        File(GotchaStorage.archiveRoot(), dir.name).mkdirs()

        GotchaStorage.archiveChatDir(sessionId)

        assertFalse(dir.exists())
        val entries = GotchaStorage.archiveRoot().listFiles().orEmpty()
        assertTrue(entries.any { it.name.startsWith("${dir.name}_") })
    }

    @Test
    fun `archiveChatDir is a no-op when the chat dir does not exist`() {
        GotchaStorage.archiveChatDir("a3f9c1d2-1234-5678-9abc-def012345678")
        assertFalse(GotchaStorage.archiveRoot().exists() && GotchaStorage.archiveRoot().listFiles()?.isNotEmpty() == true)
    }
}
