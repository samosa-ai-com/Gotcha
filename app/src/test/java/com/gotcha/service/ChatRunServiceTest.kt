package com.gotcha.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.gotcha.R
import com.gotcha.notifications.ChatCompletionNotifier
import java.util.concurrent.TimeUnit
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

    private val services = mutableListOf<ServiceController<ChatRunService>>()

    @After
    fun tearDown() {
        ChatRunService.stop()
        // The service's foreground state is shared, as in the app: one left up would leak into the next test.
        services.forEach { it.destroy() }
    }

    private fun startedService(): ServiceController<ChatRunService> {
        ChatRunService.start(application, chat)
        val intent = Intent(application, ChatRunService::class.java)
        val controller = Robolectric.buildService(ChatRunService::class.java, intent)
        controller.create().startCommand(0, 1)
        ShadowLooper.idleMainLooper()
        services += controller
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

    @Test
    fun `update changes the notification of the run in progress`() {
        val controller = startedService()

        ChatRunService.update(chat.copy(chatTitle = "Trip to Lisbon"))
        ShadowLooper.idleMainLooper()

        assertEquals(
            "Gotcha is working on “Trip to Lisbon”…",
            controller.notification().extras.getString(NotificationCompat.EXTRA_TITLE)
        )
    }

    @Test
    fun `update never starts a run, nor touches another chat's`() {
        ChatRunService.update(chat)
        assertNull(ChatRunService.running.value)
        assertNull(shadowOf(application).nextStartedService)

        ChatRunService.start(application, chat)
        ChatRunService.update(RunningChat("other", "Other"))
        assertEquals(chat, ChatRunService.running.value)
    }

    @Test
    fun `the ongoing notification sits in its chat's slot with the running icon`() {
        val controller = startedService()

        assertEquals(
            ChatCompletionNotifier.notificationId("session-1"),
            shadowOf(controller.get()).lastForegroundNotificationId
        )
        assertEquals(R.drawable.ic_notification_running, controller.notification().smallIcon.resId)
    }

    @Test
    fun `finish turns the ongoing notification into the finished one, in place`() {
        val controller = startedService()
        val slot = ChatCompletionNotifier.notificationId("session-1")

        ChatRunService.finish(application, "session-1", finished("Done: Plan my trip"))
        ShadowLooper.idleMainLooper()

        // Detached, not removed. Posted only once Android has re-posted the
        // detached notification, or that copy would cover the finished one.
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertFalse(shadowOf(controller.get()).notificationShouldRemoved)
        assertEquals(controller.notification(), posted(slot))
        // Still started until the finished notification is up, so the process lives to post it.
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        androidDetaches(slot)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertEquals("Done: Plan my trip", posted(slot)?.extras?.getString(NotificationCompat.EXTRA_TITLE))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(ChatRunService.running.value)
    }

    @Test
    fun `the finished notification still comes when Android is slow to detach`() {
        startedService()

        ChatRunService.finish(application, "session-1", finished("Done: Plan my trip"))
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)

        assertEquals(
            "Done: Plan my trip",
            posted(ChatCompletionNotifier.notificationId("session-1"))?.extras?.getString(NotificationCompat.EXTRA_TITLE)
        )
    }

    @Test
    fun `finish without the service in the foreground posts the notification itself`() {
        ChatRunService.start(application, chat)

        ChatRunService.finish(application, "session-1", finished("Failed: Plan my trip"))

        assertEquals(
            "Failed: Plan my trip",
            posted(ChatCompletionNotifier.notificationId("session-1"))?.extras?.getString(NotificationCompat.EXTRA_TITLE)
        )
        assertNull(ChatRunService.running.value)
    }

    @Test
    fun `a finished run followed at once by one in another chat still leaves its notification`() {
        val controller = startedService()

        ChatRunService.finish(application, "session-1", finished("Done: Plan my trip"))
        ChatRunService.start(application, RunningChat("session-2", "Second"))
        controller.startCommand(0, 2)
        ShadowLooper.idleMainLooper()
        androidDetaches(ChatCompletionNotifier.notificationId("session-1"))
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertEquals(
            "Done: Plan my trip",
            posted(ChatCompletionNotifier.notificationId("session-1"))?.extras?.getString(NotificationCompat.EXTRA_TITLE)
        )
        assertEquals(ChatCompletionNotifier.notificationId("session-2"), shadowOf(controller.get()).lastForegroundNotificationId)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `opening the chat of the run in progress leaves its notification alone`() {
        val controller = startedService()

        ChatCompletionNotifier(application).cancel("session-1")

        assertEquals(controller.notification(), posted(ChatCompletionNotifier.notificationId("session-1")))
    }

    private fun finished(title: String): Notification =
        NotificationCompat.Builder(application, ChatCompletionNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_done)
            .setContentTitle(title)
            .build()

    /** What Android does some time after stopForeground(DETACH), and Robolectric doesn't: the flag goes. */
    private fun androidDetaches(id: Int) {
        val detached = Notification.Builder.recoverBuilder(application, posted(id)).build()
        detached.flags = detached.flags and Notification.FLAG_FOREGROUND_SERVICE.inv()
        application.getSystemService(NotificationManager::class.java).notify(id, detached)
    }

    private fun posted(id: Int): Notification? =
        shadowOf(application.getSystemService(NotificationManager::class.java)).getNotification(id)

    @Test
    fun `a name cut short keeps a single ellipsis`() {
        assertEquals(
            "Gotcha is working on “Find the largest files in my…”",
            ChatRunService.title(chat.copy(chatTitle = "Find the largest files in my…"))
        )
    }
}
