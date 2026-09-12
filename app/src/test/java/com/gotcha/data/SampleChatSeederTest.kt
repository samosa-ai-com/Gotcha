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
