package com.gotcha.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seeding is a one-shot, and the cases that matter are the ones where it must
 * NOT happen: an upgrading user with chats of their own, and a user who deleted
 * the samples and does not want them back.
 *
 * Robolectric is here only for [SharedPreferences]; the repository uses its
 * internal File constructor against a temporary folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleChatSeederTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("sample_chat_seeder_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun repo() = ChatHistoryRepository(tmp.newFolder())

    @Test
    fun `seeds the samples into an empty chat list`() = runBlocking {
        val repo = repo()
        assertTrue(SampleChatSeeder.seedIfNeeded(repo, prefs))
        val seeded = repo.listSessions()
        assertEquals(SampleChats.all().map { it.id }.toSet(), seeded.map { it.id }.toSet())
        assertTrue(seeded.all { it.isSample })
        assertTrue(prefs.getBoolean(SampleChatSeeder.SEEDED_KEY, false))
    }

    @Test
    fun `seeded samples keep their crafted order`() = runBlocking {
        val repo = repo()
        SampleChatSeeder.seedIfNeeded(repo, prefs)
        // listSessions sorts newest first, so the drawer shows the screen Q&A
        // above the device action — the save must not have restamped both.
        assertEquals(
            listOf(SampleChats.SCREEN_QA_ID, SampleChats.DEVICE_ACTION_ID),
            repo.listSessions().map { it.id }
        )
    }

    @Test
    fun `a second run seeds nothing`() = runBlocking {
        val repo = repo()
        SampleChatSeeder.seedIfNeeded(repo, prefs)
        assertFalse(SampleChatSeeder.seedIfNeeded(repo, prefs))
        assertEquals(SampleChats.all().size, repo.listSessions().size)
    }

    @Test
    fun `deleted samples do not come back`() = runBlocking {
        val repo = repo()
        SampleChatSeeder.seedIfNeeded(repo, prefs)
        repo.listSessions().forEach { repo.deleteSession(it.id) }
        assertFalse(SampleChatSeeder.seedIfNeeded(repo, prefs))
        assertTrue(repo.listSessions().isEmpty())
    }

    /**
     * The half-written case, which is the one that can strand an install without
     * samples forever: if a failed attempt left the first sample behind, the next
     * launch's "is the list empty?" check would see it and skip retrying.
     *
     * The failure is made real rather than mocked — a directory sitting where the
     * second sample's file belongs makes that one write throw, and only that one.
     */
    @Test
    fun `a partial write leaves nothing behind and retries on the next launch`() = runBlocking {
        val dir = tmp.newFolder()
        val repo = ChatHistoryRepository(dir)
        val blocker = java.io.File(dir, "${SampleChats.SCREEN_QA_ID}.json").apply { mkdirs() }

        assertFalse(SampleChatSeeder.seedIfNeeded(repo, prefs))
        assertTrue(
            "the sample that did write must be cleaned up, or the retry never runs",
            repo.listSessions().isEmpty()
        )
        assertFalse(
            "the flag must stay unset so the next launch tries again",
            prefs.getBoolean(SampleChatSeeder.SEEDED_KEY, false)
        )

        // Next launch, with whatever blocked the write gone.
        assertTrue(blocker.delete())
        assertTrue(SampleChatSeeder.seedIfNeeded(repo, prefs))
        assertEquals(SampleChats.all().map { it.id }.toSet(), repo.listSessions().map { it.id }.toSet())
        assertTrue(prefs.getBoolean(SampleChatSeeder.SEEDED_KEY, false))
    }

    @Test
    fun `an upgrading user with chats is never seeded`() = runBlocking {
        val repo = repo()
        repo.saveSession(
            ChatSession(id = "mine", title = "My chat", lastModified = 0L, messages = emptyList())
        )
        assertFalse(SampleChatSeeder.seedIfNeeded(repo, prefs))
        assertEquals(listOf("mine"), repo.listSessions().map { it.id })
        // The flag is still written, so emptying the list later does not
        // suddenly turn an established install into a fresh one.
        assertTrue(prefs.getBoolean(SampleChatSeeder.SEEDED_KEY, false))
        repo.deleteSession("mine")
        assertFalse(SampleChatSeeder.seedIfNeeded(repo, prefs))
        assertTrue(repo.listSessions().isEmpty())
    }
}
