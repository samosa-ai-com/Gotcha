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
import java.time.LocalDate
import java.time.ZoneId

/** What the alarms do when they fire: [runLocalNotificationCheck] (issues #100, #101). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNotificationCheckTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val zone = ZoneId.systemDefault()
    private val noon = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        manager.cancelAll()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SettingsRepository(application).save(Settings())
        // Opened a week ago: long enough that reminders are allowed, and inactivity is due.
        LocalNotificationStore(application).setLastOpenedAt(noon - 7 * DAY_MS)
    }

    private fun chat(
        id: String,
        endedAt: Long,
        tools: List<String> = emptyList(),
        succeeded: Boolean = true,
        sample: Boolean = false,
        persona: String? = null
    ) = runBlocking {
        val run = RunSummary(
            startedAt = endedAt - 1_000,
            endedAt = endedAt,
            userPrompt = "p",
            finalReply = "r",
            model = "m",
            agentMode = "MONITOR",
            delegated = false,
            succeeded = succeeded,
            toolCalls = tools.map { ToolSummary(it, success = true, result = "") }
        )
        ChatHistoryRepository(application).saveSession(
            ChatSession(
                id = id,
                title = "Chat $id",
                lastModified = endedAt,
                messages = emptyList(),
                runSummaries = listOf(run),
                isSample = sample,
                personaId = persona
            ),
            touch = false
        )
    }

    private fun check(now: Long = noon, includeTip: Boolean = true) =
        runBlocking { runLocalNotificationCheck(application, now, includeTip) }

    @Test
    fun `sends one thing, files it in the inbox, and never the same tip twice running`() {
        LocalNotificationStore(application).setLastOpenedAt(noon - HOUR_MS * 3)
        val first = check()
        val second = check(noon + DAY_MS)
        assertEquals(NotificationCategory.DAILY_TIP, first?.category)
        assertNotEquals(first?.title, second?.title)
        assertEquals(2, LocalNotificationStore(application).entries().size)
    }

    @Test
    fun `nothing when turned off`() {
        SettingsRepository(application).save(Settings(localNotificationsEnabled = false))
        assertNull(check())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `an unfinished chat comes before the tip, on the reminders channel`() {
        chat("a", endedAt = noon - 3 * HOUR_MS, succeeded = false)
        // A run earlier today also means no tip.
        val sent = check()
        assertEquals(NotificationCategory.UNFINISHED_CHAT, sent?.category)
        assertEquals(NotificationTarget.Chat("a"), sent?.target)
        assertEquals(LocalNotifier.REMINDER_CHANNEL_ID, shadowOf(manager).allNotifications.single().channelId)
    }

    @Test
    fun `a doctor chat is never the subject of a reminder`() {
        chat("a", endedAt = noon - 3 * HOUR_MS, succeeded = false, persona = "doctor")
        // What's left is the inactivity nudge, which may not name the chat either.
        val sent = check()
        assertEquals(NotificationCategory.INACTIVITY, sent?.category)
        assertEquals(NotificationTarget.Home, sent?.target)
    }

    @Test
    fun `no reminders while Gotcha is in use`() {
        chat("a", endedAt = noon - 3 * HOUR_MS, succeeded = false)
        LocalNotificationStore(application).setLastOpenedAt(noon - HOUR_MS)
        assertNull(check())
    }

    @Test
    fun `nothing during quiet hours`() {
        val night = LocalDate.now(zone).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        assertNull(check(night))
    }

    @Test
    fun `the daily cap holds across alarms`() {
        SettingsRepository(application).save(Settings(maxLocalNotificationsPerDay = 1))
        assertNotNull(check())
        chat("a", endedAt = noon - 3 * HOUR_MS, succeeded = false)
        assertNull(check(noon + HOUR_MS, includeTip = false))
    }

    @Test
    fun `tips favour tools not tried yet, and samples don't count as use`() {
        LocalNotificationStore(application).setLastOpenedAt(noon - HOUR_MS * 3)
        chat("sample", endedAt = noon - 1_000, sample = true)
        val tried = DAILY_TIPS.filter { it.id != "podcast" }.flatMap { it.tools }
        chat("old", endedAt = noon - 3 * DAY_MS, tools = tried)
        val podcast = DAILY_TIPS.single { it.id == "podcast" }
        assertEquals(podcast.title, check()?.title)
    }
}
