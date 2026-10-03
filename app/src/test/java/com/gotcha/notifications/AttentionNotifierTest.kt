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

/** The "Gotcha needs your answer" notification for a paused run (issue #108). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttentionNotifierTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val notifier = AttentionNotifier(application)

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(manager).allNotifications

    private fun text(n: android.app.Notification) = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()

    @Test
    fun `a question posts on its own high-importance channel with a tap that opens the chat`() {
        assertTrue(
            notifier.notify("s1", AttentionKind.QUESTION, "Build", "Which folder?", CompletionPreview.SHORT)
        )

        val notification = posted().single()
        assertEquals(AttentionNotifier.CHANNEL_ID, notification.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(AttentionNotifier.CHANNEL_ID).importance
        )
        assertEquals("Gotcha has a question", notification.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Which folder?", text(notification))
        assertEquals("Build", notification.extras.getString(NotificationCompat.EXTRA_SUB_TEXT))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)

        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals("s1", tap.getStringExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID))
        assertEquals(ChatCompletionNotifier.ACTION_OPEN_CHAT_SESSION, tap.action)
    }

    @Test
    fun `one slot - a newer ask replaces the older one, and cancel clears it`() {
        notifier.notify("s1", AttentionKind.QUESTION, "Chat", "first?", CompletionPreview.SHORT)
        notifier.notify("s1", AttentionKind.CONFIRMATION, "Chat", null, CompletionPreview.SHORT)

        val title = posted().single().extras.getString(NotificationCompat.EXTRA_TITLE)
        assertEquals("Gotcha is waiting for your OK", title)
        notifier.cancel()
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a chat that may not be named shows neither the chat nor the question`() {
        notifier.notify(
            "s1",
            AttentionKind.QUESTION,
            "Doctor chat",
            "Any allergies?",
            CompletionPreview.FULL,
            named = false
        )

        val notification = posted().single()
        assertEquals(AttentionNotifier.defaultBody(AttentionKind.QUESTION), text(notification))
        assertNull(notification.extras.getString(NotificationCompat.EXTRA_SUB_TEXT))
    }

    @Test
    fun `a confirmation never shows the actions, only that it is waiting`() {
        notifier.notify("s1", AttentionKind.CONFIRMATION, "Chat", "Delete 3 files", CompletionPreview.FULL)

        assertEquals(AttentionNotifier.defaultBody(AttentionKind.CONFIRMATION), text(posted().single()))
    }

    @Test
    fun `nothing is posted without permission`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(notifier.notify("s1", AttentionKind.QUESTION, "Chat", "q?", CompletionPreview.SHORT))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `the preview drops code fences and follows the preview setting`() {
        val question = "In Termux, run:\n\n```bash\necho hi\n```\n\nDone?"

        val short = AttentionNotifier.questionPreview(question, CompletionPreview.SHORT)
        val full = AttentionNotifier.questionPreview(question, CompletionPreview.FULL)
        assertEquals("In Termux, run: echo hi Done?", short)
        assertEquals("In Termux, run:\necho hi\nDone?", full)
        assertNull(AttentionNotifier.questionPreview(question, CompletionPreview.NONE))
        assertNull(AttentionNotifier.questionPreview(null, CompletionPreview.SHORT))
    }
}
