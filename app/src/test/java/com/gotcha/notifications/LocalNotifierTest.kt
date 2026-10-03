package com.gotcha.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private lateinit var store: LocalNotificationStore

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        manager.cancelAll()
        store = LocalNotificationStore(application)
    }

    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun `a tip opens a drafted chat and marks its inbox entry read`() {
        grant()
        val tip = DAILY_TIPS.single { it.id == "do_not_disturb" }
        assertTrue(LocalNotifier(application).post(tipCandidate(tip, 0L, java.time.ZoneOffset.UTC), store))

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(LocalNotifier.TIP_CHANNEL_ID, notification.channelId)
        assertEquals(tip.title, notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(tip.prompt, intent.getStringExtra(LocalNotifier.EXTRA_DRAFT_PROMPT))
        assertEquals(AgentMode.OPERATOR.name, intent.getStringExtra(LocalNotifier.EXTRA_DRAFT_MODE))
        assertEquals(store.entries().single().id, intent.getStringExtra(LocalNotificationStore.EXTRA_INBOX_ENTRY_ID))
    }

    @Test
    fun `a reminder opens its chat and stays private on the lock screen`() {
        grant()
        val reminder = LocalCandidate(
            NotificationCategory.UNFINISHED_CHAT,
            "k",
            "Finish what you started",
            "“Plan” stopped",
            NotificationTarget.Chat("s")
        )
        LocalNotifier(application).post(reminder, store)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(LocalNotifier.REMINDER_CHANNEL_ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("s", intent.getStringExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID))
    }

    @Test
    fun `nothing is posted or filed without the permission`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val tip = tipCandidate(DAILY_TIPS.first(), 0L, java.time.ZoneOffset.UTC)
        assertFalse(LocalNotifier(application).post(tip, store))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(store.entries().isEmpty())
    }
}
