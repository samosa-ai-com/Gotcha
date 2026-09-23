package com.gotcha.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.CompletionPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCompletionNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val notifier = ChatCompletionNotifier(application)

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun `posts on its own channel with a tap that opens the chat`() {
        assertTrue(
            notifier.notify("s1", "Trip to Japan", RunOutcome.DONE, "Here is the plan.", CompletionPreview.SHORT)
        )

        val notification = posted().single()
        assertEquals(ChatCompletionNotifier.CHANNEL_ID, notification.channelId)
        assertEquals("Done: Trip to Japan", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Here is the plan.", notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)

        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals("s1", tap.getStringExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID))
        assertEquals(ChatCompletionNotifier.ACTION_OPEN_CHAT_SESSION, tap.action)
    }

    @Test
    fun `a later run in the same chat replaces the earlier notification`() {
        notifier.notify("s1", "Chat", RunOutcome.DONE, "first", CompletionPreview.SHORT)
        notifier.notify("s1", "Chat", RunOutcome.FAILED, "second", CompletionPreview.SHORT)
        notifier.notify("s2", "Other", RunOutcome.DONE, "other", CompletionPreview.SHORT)

        assertEquals(2, posted().size)
        notifier.cancel("s1")
        assertEquals("Done: Other", posted().single().extras.getString(NotificationCompat.EXTRA_TITLE))
    }

    @Test
    fun `nothing is posted without permission`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(notifier.canPost())
        assertFalse(notifier.notify("s1", "Chat", RunOutcome.DONE, "reply", CompletionPreview.SHORT))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `title says how the run ended`() {
        assertEquals("Done: Chat", ChatCompletionNotifier.notificationTitle("Chat", RunOutcome.DONE))
        assertEquals("Failed: Chat", ChatCompletionNotifier.notificationTitle("Chat", RunOutcome.FAILED))
        assertEquals("Stopped: Chat", ChatCompletionNotifier.notificationTitle("Chat", RunOutcome.STOPPED))
        assertEquals("Done: Chat", ChatCompletionNotifier.notificationTitle(" ", RunOutcome.DONE))
    }

    @Test
    fun `preview follows the setting and drops markdown`() {
        val reply = "## Plan\n\n- **Tokyo** first\n- `Kyoto` next"

        assertNull(ChatCompletionNotifier.previewText(reply, CompletionPreview.NONE))
        assertEquals(
            "Plan • Tokyo first • Kyoto next",
            ChatCompletionNotifier.previewText(reply, CompletionPreview.SHORT)
        )
        assertEquals(
            "Plan\n• Tokyo first\n• Kyoto next",
            ChatCompletionNotifier.previewText(reply, CompletionPreview.FULL)
        )
        assertNull(ChatCompletionNotifier.previewText("  ", CompletionPreview.SHORT))
        assertEquals(
            "Summary: One",
            ChatCompletionNotifier.previewText("Summary:\n\n---\n\nOne\n***", CompletionPreview.SHORT)
        )
    }

    @Test
    fun `short preview is cut to one line with an ellipsis`() {
        val short = ChatCompletionNotifier.previewText("word ".repeat(100), CompletionPreview.SHORT)!!

        assertTrue(short.endsWith("…"))
        assertTrue(short.length <= 141)
    }

    @Test
    fun `hidden preview falls back to a line about the outcome`() {
        notifier.notify("s1", "Chat", RunOutcome.FAILED, "secret reply", CompletionPreview.NONE)

        val text = posted().single().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        assertEquals(ChatCompletionNotifier.defaultBody(RunOutcome.FAILED), text)
    }
}
