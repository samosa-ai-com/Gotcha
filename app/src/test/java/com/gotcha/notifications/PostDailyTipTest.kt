package com.gotcha.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.data.ToolSummary
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the alarm does when it fires: [postDailyTip] (issue #101). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostDailyTipTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val day = 24L * 3600_000L
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        manager.cancelAll()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SettingsRepository(application).save(Settings())
    }

    private fun chat(id: String, endedAt: Long, tools: List<String>, sample: Boolean = false) = runBlocking {
        val run = RunSummary(
            startedAt = endedAt - 1_000,
            endedAt = endedAt,
            userPrompt = "p",
            finalReply = "r",
            model = "m",
            agentMode = "MONITOR",
            delegated = false,
            succeeded = true,
            toolCalls = tools.map { ToolSummary(it, success = true, result = "") }
        )
        ChatHistoryRepository(application).saveSession(
            ChatSession(
                id = id,
                title = id,
                lastModified = endedAt,
                messages = emptyList(),
                runSummaries = listOf(run),
                isSample = sample
            ),
            touch = false
        )
    }

    @Test
    fun `posts a tip and remembers it, so the next day's differs`() = runBlocking {
        val first = postDailyTip(application, now)
        val second = postDailyTip(application, now + day)
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first?.id, second?.id)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `nothing when tips are off`() = runBlocking {
        SettingsRepository(application).save(Settings(dailyTipsEnabled = false))
        assertNull(postDailyTip(application, now))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `nothing on a day Gotcha was already used`() = runBlocking {
        chat("today", endedAt = now - 1_000, tools = listOf("read_screen"))
        assertNull(postDailyTip(application, now))
    }

    @Test
    fun `a sample chat is not use, and tools used before are passed over`() = runBlocking {
        chat("sample", endedAt = now - 1_000, tools = emptyList(), sample = true)
        // Every tool but the podcast ones has been run, long ago.
        val tried = DAILY_TIPS.filter { it.id != "podcast" }.flatMap { it.tools }
        chat("old", endedAt = now - 7 * day, tools = tried)
        assertEquals("podcast", postDailyTip(application, now)?.id)
    }
}
