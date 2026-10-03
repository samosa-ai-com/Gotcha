package com.gotcha.agent

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.service.ChatRunService
import com.gotcha.service.RunningChat
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Task-finished notifications from [ChatViewModel] (issue #97): one per run that
 * ends while Gotcha is in the background, none in the foreground or when the
 * setting is off. The LLM base URL refuses connections, so every run here ends
 * quickly as a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelCompletionNotificationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ChatViewModel

    private fun start(enabled: Boolean = true, granted: Boolean = true) {
        FakeAndroidKeyStore.setUp()
        if (granted) shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settingsRepository = SettingsRepository(application)
        settingsRepository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:1/v1",
                chatCompletionNotificationsEnabled = enabled
            )
        )
        viewModel = ChatViewModel(application)
        ShadowLooper.idleMainLooper()
    }

    @Before
    fun clearTray() {
        manager.cancelAll()
        ChatRunService.stop()
    }

    private fun waitForRunToFinish() {
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && viewModel.uiState.value.isBusy) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun `a run that ends in the background posts one notification for that chat`() {
        start()
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()

        val notification = posted().single()
        assertTrue(notification.extras.getString(NotificationCompat.EXTRA_TITLE)!!.startsWith("Failed: "))
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(
            viewModel.uiState.value.activeSessionId,
            tap.getStringExtra(ChatCompletionNotifier.EXTRA_OPEN_SESSION_ID)
        )
    }

    @Test
    fun `a failed tool call the agent recovers from still finishes as Done`() {
        // First the model calls a tool that fails; then, seeing the error, it replies.
        val toolCall = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[""" +
            """{"id":"call-1","type":"function","function":{"name":"no_such_tool","arguments":"{}"}}]},""" +
            """"finish_reason":"tool_calls"}]}"""
        val reply = """{"choices":[{"message":{"role":"assistant","content":"Here it is."},"finish_reason":"stop"}]}"""
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(if (server.requestCount == 1) toolCall else reply)
        }
        server.start()
        try {
            start()
            settingsRepository.save(settingsRepository.load().copy(baseUrl = server.url("/v1/").toString()))
            viewModel.refreshSettings()
            viewModel.sendMessage("Hello")
            viewModel.setForeground(false)
            // The engine pauses between model calls on the main looper, whose clock
            // only moves when the test moves it.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && posted().isEmpty()) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                Thread.sleep(10)
            }

            val title = posted().single().extras.getString(NotificationCompat.EXTRA_TITLE)!!
            assertTrue(title, title.startsWith("Done: "))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a run that ends in the foreground posts nothing`() {
        start()
        viewModel.sendMessage("Hello")
        waitForRunToFinish()

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `nothing is posted when the setting is off`() {
        start(enabled = false)
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `returning to the chat clears its notification`() {
        start()
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()
        assertEquals(1, posted().size)

        viewModel.setForeground(true)

        assertTrue(posted().isEmpty())
    }

    private val inbox by lazy { LocalNotificationStore(application) }

    /** Marking a chat read is written off the main thread; waits for it. */
    private fun waitForInbox(unread: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && inbox.unreadCount() != unread) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `returning to the chat marks its inbox entry read and keeps it`() {
        start()
        viewModel.sendMessage("Hello")
        viewModel.setForeground(false)
        waitForRunToFinish()
        assertEquals(1, inbox.unreadCount())
        val changesBefore = viewModel.inboxChanges.value

        viewModel.setForeground(true)
        waitForInbox(unread = 0)

        assertEquals(0, inbox.unreadCount())
        assertTrue(inbox.entries().single().read)
        assertTrue(viewModel.inboxChanges.value > changesBefore)
    }

    @Test
    fun `opening the chat from elsewhere marks its inbox entry read`() {
        start()
        viewModel.sendMessage("Hello")
        val runChat = viewModel.uiState.value.activeSessionId!!
        viewModel.setForeground(false)
        waitForRunToFinish()
        // Back in the app on another chat: the finished one is still unseen.
        viewModel.openSession(null)
        viewModel.setForeground(true)
        waitForInbox(unread = 0)
        assertEquals(1, inbox.unreadCount())

        viewModel.openSession(runChat)
        waitForInbox(unread = 0)

        assertEquals(0, inbox.unreadCount())
        assertEquals(1, inbox.entries().size)
    }

    @Test
    fun `hint promises a notification when one can be posted`() {
        start()
        viewModel.sendMessage("Hello")
        ShadowLooper.idleMainLooper()

        assertEquals(
            backgroundHintText(vibrate = true, chime = false, notify = true),
            viewModel.uiState.value.backgroundHint
        )
        waitForRunToFinish()
    }

    @Test
    fun `permission is asked once, on the first request, and never blocks the run`() {
        start(granted = false)
        viewModel.sendMessage("First")
        ShadowLooper.idleMainLooper()
        assertTrue(viewModel.uiState.value.askNotificationPermission)
        waitForRunToFinish()
        // The run finished while the ask was still unanswered.
        assertFalse(viewModel.uiState.value.isBusy)

        viewModel.onNotificationPermissionResult(false)
        viewModel.sendMessage("Second")
        ShadowLooper.idleMainLooper()

        assertFalse(viewModel.uiState.value.askNotificationPermission)
        waitForRunToFinish()
    }

    @Test
    fun `granting mid-run upgrades the hint`() {
        start(granted = false)
        viewModel.sendMessage("Hello")
        ShadowLooper.idleMainLooper()
        assertEquals(backgroundHintText(vibrate = true, chime = false), viewModel.uiState.value.backgroundHint)

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.onNotificationPermissionResult(true)

        assertEquals(
            backgroundHintText(vibrate = true, chime = false, notify = true),
            viewModel.uiState.value.backgroundHint
        )
        assertFalse(viewModel.uiState.value.askNotificationPermission)
        waitForRunToFinish()
    }

    /** Every value [ChatRunService.running] takes, recorded as it is set. */
    private fun recordRunning(): Pair<MutableList<RunningChat?>, Job> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<RunningChat?>())
        val job = CoroutineScope(Dispatchers.Unconfined).launch { ChatRunService.running.collect { seen += it } }
        return seen to job
    }

    @Test
    fun `a run holds the keep-alive service until it ends`() {
        start()
        val (seen, job) = recordRunning()
        viewModel.sendMessage("Hello")
        waitForRunToFinish()
        job.cancel()

        val started = shadowOf(application).nextStartedService
        assertEquals(ChatRunService::class.java.name, started?.component?.className)
        assertEquals(viewModel.uiState.value.activeSessionId, seen.filterNotNull().single().sessionId)
        assertNull(ChatRunService.running.value)
    }

    @Test
    fun `the keep-alive notification does not name a chat when chats are not to be named`() {
        start()
        settingsRepository.save(settingsRepository.load().copy(notificationsMentionChats = false))
        viewModel.refreshSettings()
        val (seen, job) = recordRunning()
        viewModel.sendMessage("Hello")
        waitForRunToFinish()
        job.cancel()

        assertNull(seen.filterNotNull().single().chatTitle)
    }
}
