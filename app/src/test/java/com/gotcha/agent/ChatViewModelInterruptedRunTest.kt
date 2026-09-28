package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.FunctionCall
import com.gotcha.llm.ToolCall
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.notifications.NotificationTarget
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The next start after a run died with the process (issue #105). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelInterruptedRunTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyRepository: ChatHistoryRepository
    private lateinit var inbox: LocalNotificationStore
    private val sessionId = "interrupted-session"

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        historyRepository = ChatHistoryRepository(application)
        inbox = LocalNotificationStore(application)
        inbox.clearHistory()
        settingsRepository.save(
            Settings(provider = LlmProvider.OPENAI_COMPATIBLE, apiKey = "test-key", baseUrl = "http://127.0.0.1:1/v1")
        )
    }

    @After
    fun tearDown() {
        runBlocking { historyRepository.deleteSession(sessionId) }
    }

    /** A chat saved mid-round: the model asked for a tool whose result never came. */
    private fun saveHalfFinishedChat() = runBlocking {
        historyRepository.saveSession(
            ChatSession(
                id = sessionId,
                title = "Big files",
                lastModified = 1L,
                messages = listOf(
                    ChatMessage(role = "user", content = JsonPrimitive("Find big files")),
                    ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(ToolCall(id = "call-1", function = FunctionCall("list_files", "{}")))
                    )
                ),
                displayMessages = listOf(UiMessage(1, MessageKind.USER, "Find big files"))
            )
        )
    }

    private fun startGotcha(): ChatViewModel {
        val viewModel = ChatViewModel(application)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && viewModel.sessions.value.isEmpty()) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        return viewModel
    }

    private fun anotherProcess() = RunInProgressMarker(settingsRepository.prefs) { "killed-process" }

    @Test
    fun `a run killed with the process is reported in its chat and the inbox`() {
        saveHalfFinishedChat()
        anotherProcess().mark(sessionId)

        startGotcha()

        val session = runBlocking { historyRepository.loadSession(sessionId) }!!
        assertEquals(MessageKind.ERROR, session.displayMessages.last().kind)
        assertTrue(session.displayMessages.last().text.startsWith("This task was interrupted"))
        assertEquals("call-1", session.messages.last().toolCallId)
        val entry = inbox.entries().single()
        assertEquals("Interrupted: Big files", entry.title)
        assertEquals(NotificationTarget.Chat(sessionId), entry.target)
        assertNull(anotherProcess().interruptedSession())
    }

    @Test
    fun `it is reported once`() {
        saveHalfFinishedChat()
        anotherProcess().mark(sessionId)
        startGotcha()

        startGotcha()

        assertEquals(1, inbox.entries().size)
        val notices = runBlocking { historyRepository.loadSession(sessionId) }!!
            .displayMessages.count { it.kind == MessageKind.ERROR }
        assertEquals(1, notices)
    }

    @Test
    fun `a run that ends normally leaves no marker`() {
        val viewModel = startGotcha()
        viewModel.sendMessage("Hello")
        val deadline = System.currentTimeMillis() + 5_000
        ShadowLooper.idleMainLooper()
        while (System.currentTimeMillis() < deadline && viewModel.uiState.value.isBusy) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()

        assertNull(anotherProcess().interruptedSession())
        assertTrue(inbox.entries().isEmpty())
    }
}
