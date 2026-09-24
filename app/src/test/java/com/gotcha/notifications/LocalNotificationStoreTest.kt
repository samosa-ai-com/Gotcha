package com.gotcha.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNotificationStoreTest {

    private val store = LocalNotificationStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_local", Context.MODE_PRIVATE)
    )
    private val now = 1_790_000_000_000L

    @Test
    fun `entries are listed newest first with their targets`() {
        store.addEntry(
            NotificationCategory.DAILY_TIP,
            "Tip",
            "b",
            NotificationTarget.Draft("p", AgentMode.OPERATOR),
            now = now
        )
        store.addEntry(NotificationCategory.TASK_FINISHED, "Done", "b", NotificationTarget.Chat("s"), now = now + 1)
        val entries = store.entries()
        assertEquals(listOf("Done", "Tip"), entries.map { it.title })
        assertEquals(NotificationTarget.Chat("s"), entries[0].target)
        assertEquals(NotificationTarget.Draft("p", AgentMode.OPERATOR), entries[1].target)
        assertEquals(NotificationCategory.DAILY_TIP, entries[1].categoryOrNull)
    }

    @Test
    fun `reading entries clears the unread count`() {
        val id = store.addEntry(NotificationCategory.SERVER, "a", "b", NotificationTarget.Home, now = now)
        store.addEntry(NotificationCategory.SERVER, "c", "d", NotificationTarget.Home, now = now)
        assertEquals(2, store.unreadCount())
        store.markRead(id)
        assertEquals(1, store.unreadCount())
        store.markAllRead()
        assertEquals(0, store.unreadCount())
    }

    @Test
    fun `entries older than 30 days drop out`() {
        store.addEntry(NotificationCategory.SERVER, "old", "b", NotificationTarget.Home, now = now - 31 * DAY_MS)
        store.addEntry(NotificationCategory.SERVER, "new", "b", NotificationTarget.Home, now = now)
        assertEquals(listOf("new"), store.entries().map { it.title })
    }

    @Test
    fun `clearing history keeps what was sent`() {
        val c = LocalCandidate(NotificationCategory.INACTIVITY, "k", "t", "b", NotificationTarget.Home)
        store.addEntry(c.category, c.title, c.body, c.target, now = now)
        store.recordSent(c, now)
        store.clearHistory()
        assertTrue(store.entries().isEmpty())
        assertEquals(listOf("k"), store.sent().map { it.key })
    }

    @Test
    fun `a chat choice overrides the persona default, and is forgotten with the chat`() {
        assertNull(store.chatChoice("s"))
        assertTrue(store.isChatSensitive("s", "doctor"))
        store.setChatKeptOut("s", false)
        assertFalse(store.isChatSensitive("s", "doctor"))
        store.setChatKeptOut("s", true)
        assertTrue(store.isChatSensitive("s", null))
        store.forgetChat("s")
        assertNull(store.chatChoice("s"))
    }
}
