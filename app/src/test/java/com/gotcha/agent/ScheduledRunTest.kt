package com.gotcha.agent

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.gotcha.R
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.scheduled.ScheduledStart
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskNotifier
import com.gotcha.scheduled.ScheduledTaskRuns
import com.gotcha.scheduled.ScheduledTaskStore
import com.gotcha.scheduled.TaskRunStatus
import com.gotcha.service.ChatRunService
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.tools.AgentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A scheduled task's run (issue #132) on the app's [ChatRunner]: its prompt
 * starts a normal run in a new chat or at the end of its own one, never
 * alongside another run, without moving the screen, and how it ended goes into
 * the task's history. The LLM base URL refuses connections, so a run ends as a
 * failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledRunTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val historyRepository = ChatHistoryRepository(application)
    private lateinit var store: ScheduledTaskStore

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        ChatRunService.stop()
        refusingModel()
        store = ScheduledTaskStore(application)
        store.all().forEach { store.delete(it.id) }
    }

    private fun refusingModel() {
        SettingsRepository(application).save(
            Settings(provider = LlmProvider.OPENAI_COMPATIBLE, apiKey = "test-key", baseUrl = "http://127.0.0.1:1/v1")
        )
        runner().refreshSettings()
    }

    private fun runner() = ChatRunner.of(application)

    private fun task(
        id: String = "news",
        sameChat: Boolean = false,
        chat: String? = null,
        enabled: Boolean = true
    ) = ScheduledTask(
        id = id,
        name = "Morning news",
        prompt = "Summarise the news",
        startLocal = "2099-01-01T08:00",
        agentMode = AgentMode.MONITOR.name,
        sameChat = sameChat,
        chatSessionId = chat,
        enabled = enabled
    ).also { store.put(it) }

    /** Runs [id] as "Run now" does, letting the main looper turn until it has started. */
    private fun runNow(id: String): ScheduledStart {
        val started = CoroutineScope(Dispatchers.Unconfined).async { ScheduledTaskRuns.runNow(application, id) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!started.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        return runBlocking { started.await() }
    }

    private fun waitForRunToFinish() {
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && runner().isRunning) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a task's prompt starts a run in a new chat, and the failure is in its history`() {
        task()

        val result = runNow("news") as ScheduledStart.Started
        waitForRunToFinish()

        val chat = runBlocking { historyRepository.loadSession(result.sessionId) }!!
        assertEquals("Summarise the news", chat.displayMessages.first().text)
        val saved = store.get("news")!!
        val run = saved.lastRun!!
        assertEquals(TaskRunStatus.FAILED, run.status)
        assertEquals(result.sessionId, run.sessionId)
        assertNotNull(run.detail)
        // One retry by default: it is armed.
        assertNotNull(saved.retryAt)
    }

    @Test
    fun `with no AI set up the task is not started, and says why`() {
        SettingsRepository(application).save(Settings(provider = LlmProvider.SAMOSA_AI))
        runner().refreshSettings()
        task()

        val result = runNow("news")

        assertTrue(result is ScheduledStart.Failed)
        assertTrue(!runner().isRunning)
        val run = store.get("news")!!.lastRun!!
        assertEquals(TaskRunStatus.NOT_STARTED, run.status)
        assertTrue(run.detail!!, run.detail!!.contains("isn't set up"))
    }

    @Test
    fun `a run out of sight ends in a notification named after the task`() {
        task()

        runNow("news")
        waitForRunToFinish()

        val finished = shadowOf(manager).allNotifications.single()
        val title = finished.extras.getString(NotificationCompat.EXTRA_TITLE)!!
        assertTrue(title, title.contains("Morning news"))
    }

    @Test
    fun `same-chat mode adds the prompt to the end of its chat`() {
        runBlocking {
            historyRepository.saveSession(
                ChatSession(
                    id = "kept",
                    title = "Daily news",
                    lastModified = 0L,
                    messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Yesterday"))),
                    displayMessages = listOf(UiMessage(0, MessageKind.USER, "Yesterday"))
                )
            )
        }
        task(sameChat = true, chat = "kept")

        val result = runNow("news") as ScheduledStart.Started
        waitForRunToFinish()

        assertEquals("kept", result.sessionId)
        val shown = runBlocking { historyRepository.loadSession("kept") }!!.displayMessages.map { it.text }
        assertEquals(listOf("Yesterday", "Summarise the news"), shown.take(2))
    }

    @Test
    fun `a same-chat run keeps the chat's own mode`() {
        runBlocking {
            historyRepository.saveSession(
                ChatSession(
                    id = "operator-chat",
                    title = "Kept",
                    lastModified = 0L,
                    messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("Earlier"))),
                    agentMode = AgentMode.OPERATOR.name
                )
            )
        }
        // The task runs in Monitor; the chat stays an Operator chat.
        task(sameChat = true, chat = "operator-chat")

        runNow("news")
        waitForRunToFinish()

        assertEquals(
            AgentMode.OPERATOR.name,
            runBlocking { historyRepository.loadSession("operator-chat") }!!.agentMode
        )
    }

    @Test
    fun `a same-chat task whose chat is gone starts one and keeps it`() {
        task(sameChat = true, chat = "deleted")

        val result = runNow("news") as ScheduledStart.Started
        waitForRunToFinish()

        assertTrue(result.sessionId != "deleted")
        assertEquals(result.sessionId, store.get("news")!!.chatSessionId)
    }

    @Test
    fun `a second start while busy queues its turn instead of dropping it`() {
        ServerSocket(0).use { silent ->
            // Holds the run's request unanswered; later ones (the title, as the
            // stopped run saves) are dropped at once.
            thread(isDaemon = true) {
                val held = runCatching { silent.accept() }.getOrNull()
                while (!silent.isClosed) runCatching { silent.accept().close() }
                held?.close()
            }
            SettingsRepository(application).save(
                Settings(
                    provider = LlmProvider.OPENAI_COMPATIBLE,
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${silent.localPort}/v1"
                )
            )
            runner().refreshSettings()
            task()
            val first = runNow("news")
            assertTrue(first is ScheduledStart.Started)

            task(id = "second")
            assertEquals(ScheduledStart.Queued, runNow("second"))
            val queued = store.get("second")!!
            assertNotNull("a queued start keeps its retry", queued.retryAt)
            assertNotNull("a queued start keeps its wait start", queued.busySince)
            val waitingTitle = application.getString(R.string.scheduled_waiting_title, "Morning news")
            fun waitingPosted() = shadowOf(manager).allNotifications.any {
                it.channelId == ScheduledTaskNotifier.CHANNEL_ID &&
                    it.extras.getString(NotificationCompat.EXTRA_TITLE) == waitingTitle
            }
            assertTrue("queueing posts the waiting notification", waitingPosted())

            // A wait that already gave up reports Busy, not Queued.
            task(id = "third")
            store.update("third") { it.copy(busySince = System.currentTimeMillis() - 61 * 60_000L) }
            assertEquals(ScheduledStart.Busy, runNow("third"))
            assertEquals(TaskRunStatus.SKIPPED_BUSY, store.get("third")!!.lastRun!!.status)

            // A paused task is promised no queue: nothing is persisted.
            task(id = "fourth", enabled = false)
            assertEquals(ScheduledStart.Busy, runNow("fourth"))
            val paused = store.get("fourth")!!
            assertNull(paused.retryAt)
            assertNull(paused.busySince)

            runner().stop()
            waitForRunToFinish()

            // The queued retry fires once the runner frees up.
            val retryAt = store.get("second")!!.retryAt!!
            val fired = CoroutineScope(Dispatchers.Unconfined).async {
                ScheduledTaskRuns.fireDue(application, retryAt)
            }
            val fireDeadline = System.currentTimeMillis() + 5_000
            while (!fired.isCompleted && System.currentTimeMillis() < fireDeadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
            runBlocking { fired.await() }
            waitForRunToFinish()
            val dequeued = store.get("second")!!
            assertNull("the retry is spent once the run starts", dequeued.retryAt)
            assertNull("the wait ends once the run starts", dequeued.busySince)
            assertTrue("starting the run clears the waiting notification", !waitingPosted())
            runner().stop()
            waitForRunToFinish()
        }
        assertEquals(TaskRunStatus.STOPPED, store.get("news")!!.lastRun!!.status)
    }

    @Test
    fun `the screen stays on the chat it shows`() {
        val viewModel = ViewModelProvider(
            ViewModelStore(),
            ViewModelProvider.AndroidViewModelFactory(application)
        )[ChatViewModel::class.java]
        val viewed = viewModel.uiState.value.activeSessionId
        task()

        val result = runNow("news") as ScheduledStart.Started
        ShadowLooper.idleMainLooper()

        assertEquals(viewed, viewModel.uiState.value.activeSessionId)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(result.sessionId != viewed)
        waitForRunToFinish()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }
}
