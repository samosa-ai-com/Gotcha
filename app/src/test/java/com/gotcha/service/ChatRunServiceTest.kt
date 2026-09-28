package com.gotcha.service

import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.gotcha.notifications.ChatCompletionNotifier
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The keep-alive service for a chat run (issue #105). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRunServiceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val chat = RunningChat(sessionId = "session-1", chatTitle = "Plan my trip")

    @Before
    fun reset() {
        ChatRunService.stop()
    }

    @After
    fun tearDown() {
        ChatRunService.stop()
    }

    private fun startedService(): ServiceController<ChatRunService> {
        ChatRunService.start(application, chat)
        val intent = Intent(application, ChatRunService::class.java)
        val controller = Robolectric.buildService(ChatRunService::class.java, intent)
        controller.create().startCommand(0, 1)
        ShadowLooper.idleMainLooper()
        return controller
    }

    private fun ServiceController<ChatRunService>.notification(): Notification =
        shadowOf(get()).lastForegroundNotification

    @Test
    fun `start asks Android for the foreground service`() {
        ChatRunService.start(application, chat)

        val started = shadowOf(application).nextStartedService
        assertEquals(ChatRunService::class.java.name, started.component?.className)
        assertEquals(chat, ChatRunService.running.value)
    }

    @Test
    fun `the ongoing notification names the chat and opens it`() {
        val notification = startedService().notification()

        assertEquals(
            "Gotcha is working on “Plan my trip”…",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE)
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ChatCompletionNotifier.ACTION_OPEN_CHAT_SESSION, tap.action)
        assertEquals("session-1", tap.getStringExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID))
        assertEquals(ChatRunService.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun `a chat that may not be named is not`() {
        assertEquals("Gotcha is working on a task…", ChatRunService.title(chat.copy(chatTitle = null)))
    }

    @Test
    fun `stop removes the notification and stops the service`() {
        val controller = startedService()

        ChatRunService.stop()
        ShadowLooper.idleMainLooper()

        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(ChatRunService.running.value)
    }

    @Test
    fun `a run that ended before the service came up still goes foreground, then stops`() {
        ChatRunService.start(application, chat)
        ChatRunService.stop()

        val controller = Robolectric.buildService(ChatRunService::class.java).create().startCommand(0, 1)
        ShadowLooper.idleMainLooper()

        // startForeground is owed to Android even so, or the app crashes.
        assertEquals(ChatRunService.NOTIFICATION_ID, shadowOf(controller.get()).lastForegroundNotificationId)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a new run while the service is up keeps it running`() {
        val controller = startedService()

        ChatRunService.start(application, RunningChat("session-2", "Second"))
        ShadowLooper.idleMainLooper()

        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(
            "Gotcha is working on “Second”…",
            controller.notification().extras.getString(NotificationCompat.EXTRA_TITLE)
        )
    }

    @Test
    fun `Stop on the notification asks for the run to be stopped`() = runBlocking {
        val controller = startedService()
        val stopAction = controller.notification().actions.single()
        assertEquals("Stop", stopAction.title)
        val request = async(start = CoroutineStart.UNDISPATCHED) { ChatRunService.stopRequests.first() }

        controller.withIntent(shadowOf(stopAction.actionIntent).savedIntent).startCommand(0, 2)

        request.await()
        assertEquals(ChatRunService.ACTION_STOP_RUN, shadowOf(stopAction.actionIntent).savedIntent.action)
    }
}
