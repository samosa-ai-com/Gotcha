package com.gotcha.agent

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.gotcha.GotchaApp
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.service.ChatRunService
import com.gotcha.tools.AgentMode
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Issue #111: a chat run belongs to the app-scoped [ChatRunner], not to the
 * ViewModel of the screen that started it, so it goes on when MainActivity
 * finishes (swiped out of Recents, Back on the home screen) and a later screen
 * picks it up. The LLM base URL refuses connections, so a run left alone ends
 * as a failure; one cancelled with its ViewModel would end as "stopped".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRunnerLifecycleTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val historyRepository = ChatHistoryRepository(application)

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        ChatRunService.stop()
        SettingsRepository(application).save(
            Settings(provider = LlmProvider.OPENAI_COMPATIBLE, apiKey = "test-key", baseUrl = "http://127.0.0.1:1/v1")
        )
    }

    /** A chat screen whose ViewModel can be cleared as when its Activity finishes for good. */
    private class Screen(application: Application) {
        val store = ViewModelStore()
        val viewModel: ChatViewModel = ViewModelProvider(
            store,
            // Not getInstance(): that factory is cached with the first test's Application.
            ViewModelProvider.AndroidViewModelFactory(application)
        )[ChatViewModel::class.java]

        fun finish() {
            viewModel.setForeground(false)
            store.clear()
        }
    }

    private fun runner() = ChatRunner.of(application)

    private fun waitForRunToFinish() {
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && runner().isRunning) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun savedTranscript(sessionId: String): List<UiMessage> =
        runBlocking { historyRepository.loadSession(sessionId) }!!.displayMessages

    @Test
    fun `the runner is held by the app, one for every screen`() {
        assertTrue(application is GotchaApp)
        assertSame(Screen(application).viewModel.runner, Screen(application).viewModel.runner)
    }

    @Test
    fun `a run goes on after its screen is gone and ends on its own`() {
        val screen = Screen(application)
        screen.viewModel.sendMessage("Hello")
        val sessionId = screen.viewModel.uiState.value.runningSessionId!!

        screen.finish()
        assertTrue("clearing the screen stopped the run", runner().isRunning)
        waitForRunToFinish()

        val transcript = savedTranscript(sessionId)
        assertEquals("Hello", transcript.first().text)
        assertFalse(
            "the run was cancelled with its ViewModel",
            transcript.any { it.text == "Agent was interrupted by the user." }
        )
        val finished = shadowOf(manager).allNotifications.single()
        assertTrue(finished.extras.getString(NotificationCompat.EXTRA_TITLE)!!.startsWith("Failed: "))
    }

    @Test
    fun `a screen created mid-run shows the running chat`() {
        val first = Screen(application)
        first.viewModel.sendMessage("Hello")
        val sessionId = first.viewModel.uiState.value.runningSessionId!!
        first.finish()

        val second = Screen(application).viewModel
        val state = second.uiState.value

        assertEquals(sessionId, state.activeSessionId)
        assertTrue(state.isBusy)
        assertEquals(listOf("Hello"), state.messages.map { it.text })
        waitForRunToFinish()
        // The run's last bubble reaches the screen now following it.
        assertEquals(runner().state.value.transcript, second.uiState.value.messages)
        assertFalse(second.uiState.value.isBusy)
    }

    @Test
    fun `a screen created after the run starts a fresh chat`() {
        val first = Screen(application)
        first.viewModel.sendMessage("Hello")
        val sessionId = first.viewModel.uiState.value.runningSessionId!!
        first.finish()
        waitForRunToFinish()

        val state = Screen(application).viewModel.uiState.value

        assertNotEquals(sessionId, state.activeSessionId)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `stop on the ongoing notification works with no screen`() {
        // A server that takes the run's request and never answers, so the run is
        // still waiting on the model when Stop arrives, whatever the timing. Later
        // requests (the chat title, made as the stopped run saves) are dropped.
        ServerSocket(0).use { silent ->
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
            val screen = Screen(application)
            screen.viewModel.sendMessage("Hello")
            val sessionId = screen.viewModel.uiState.value.runningSessionId!!
            screen.finish()
            ShadowLooper.idleMainLooper()
            assertTrue(runner().isRunning)

            Robolectric.buildService(ChatRunService::class.java, Intent().setAction(ChatRunService.ACTION_STOP_RUN))
                .create()
                .startCommand(0, 1)
            waitForRunToFinish()

            assertFalse(runner().isRunning)
            assertEquals("Agent was interrupted by the user.", savedTranscript(sessionId).last().text)
        }
    }

    @Test
    fun `a permission ask is answered no once the screen is gone`() {
        val screen = Screen(application)
        val gate = CoroutineScope(Dispatchers.Unconfined).async {
            runner().awaitPermissionGrant(Manifest.permission.CAMERA)
        }
        assertEquals(Manifest.permission.CAMERA, screen.viewModel.uiState.value.pendingPermission)

        screen.store.clear()

        assertTrue("the ask waited for its timeout", gate.isCompleted)
        assertFalse(runBlocking { gate.await() })
        assertNull(runner().state.value.pendingPermission)
    }

    @Test
    fun `a question outlives the screen and is answered from the next one`() {
        val first = Screen(application)
        val gate = CoroutineScope(Dispatchers.Unconfined).async {
            runner().awaitQuestionAnswer(PendingQuestion("Which folder?"))
        }
        first.finish()
        assertFalse(gate.isCompleted)

        val second = Screen(application).viewModel
        assertEquals("Which folder?", second.uiState.value.pendingQuestion?.question)
        second.submitAnswer("Downloads")

        assertEquals("Downloads", runBlocking { gate.await() })
        assertNull(second.uiState.value.pendingQuestion)
    }

    @Test
    fun `a second send before the first run starts is refused`() {
        // Saved, and not the chat the engine is on, so the first send suspends
        // loading it from disk before the run shows as busy.
        runBlocking {
            historyRepository.saveSession(ChatSession(id = "double-tap", title = "Saved", lastModified = 0L, messages = emptyList()))
        }
        runner().bindFresh("elsewhere", AgentMode.MONITOR)
        val view = ViewedChat("double-tap", emptyList(), AgentMode.MONITOR, personaId = null)

        runner().send(view, "first", emptyList(), isVoice = false)
        runner().send(view, "second", emptyList(), isVoice = false)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !runner().isRunning) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        waitForRunToFinish()

        val userTexts = savedTranscript("double-tap").filter { it.kind == MessageKind.USER }.map { it.text }
        assertEquals(listOf("first"), userTexts)
    }
}
