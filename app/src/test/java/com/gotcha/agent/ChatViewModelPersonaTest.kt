package com.gotcha.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.tools.AgentMode
import com.gotcha.ui.PERSONAS
import com.gotcha.ui.personaById
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The persona picked on the home screen decides how every turn of that chat is
 * answered, so what matters is where it ends up: in the UI state the picker
 * reads back, and on the engine, which is the only thing the system prompt is
 * built from.
 *
 * Only the synchronous entry points are exercised, for the reason spelled out
 * in [ChatViewModelContextUsageTest] — `viewModelScope.launch` hands off to
 * Dispatchers.IO, and that round trip isn't observable from this tier. The
 * reopen path is covered by the round-trip test in
 * [com.gotcha.data.ChatHistoryRepositoryTest] plus the engine-side assertions
 * in [AgentPromptTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelPersonaTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyRepository: ChatHistoryRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(application)
        historyRepository = ChatHistoryRepository(application)
        settingsRepository.save(Settings(apiKey = "test-key"))
        viewModel = ChatViewModel(application)
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        runBlocking { historyRepository.deleteSession("persona-session") }
    }

    /** The engine is private; its persona is what the system prompt is built from. */
    private fun engine(): AgentEngine {
        val field = ChatViewModel::class.java.getDeclaredField("agentEngine").apply { isAccessible = true }
        return field.get(viewModel) as AgentEngine
    }

    private fun enginePersonaId(): String? = engine().sessionPersonaId

    /** Which session the engine is bound to — the one a persona would apply to. */
    private fun enginePersonaOwner(): String? = engine().sessionId

    /**
     * Mark [id] as the session with a run in flight (null once it finishes). The
     * real path runs an agent loop over the network, which this tier can't drive.
     */
    private fun seedRunningSession(id: String?) {
        val stateField = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(viewModel) as MutableStateFlow<ChatUiState>
        flow.value = flow.value.copy(runningSessionId = id)
    }

    private fun seedMessages(messages: List<UiMessage>) {
        val stateField = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val flow = stateField.get(viewModel) as MutableStateFlow<ChatUiState>
        flow.value = flow.value.copy(messages = messages)
    }

    @Test
    fun `picking a persona stamps it on the chat and the engine`() {
        val doctor = requireNotNull(personaById("doctor"))

        viewModel.setPersona(doctor)

        assertEquals("doctor", viewModel.uiState.value.activePersonaId)
        assertEquals("doctor", enginePersonaId())
    }

    @Test
    fun `a persona applies its own default mode`() {
        // Seeded personas all start read-only; the selector has to follow so the
        // user can see where the persona put them.
        viewModel.setAgent(AgentMode.OPERATOR)

        viewModel.setPersona(requireNotNull(personaById("doctor")))

        assertEquals(AgentMode.MONITOR, viewModel.uiState.value.activeAgent)
    }

    @Test
    fun `tapping the chosen persona again clears it without moving the mode`() {
        viewModel.setPersona(requireNotNull(personaById("chef")))
        viewModel.setAgent(AgentMode.OPERATOR)

        viewModel.setPersona(null)

        assertNull(viewModel.uiState.value.activePersonaId)
        assertNull(enginePersonaId())
        assertEquals(AgentMode.OPERATOR, viewModel.uiState.value.activeAgent)
    }

    @Test
    fun `a chat that already has messages keeps the persona it started with`() {
        viewModel.setPersona(requireNotNull(personaById("tutor")))
        seedMessages(listOf(UiMessage(id = 1, kind = MessageKind.USER, text = "hello")))

        viewModel.setPersona(requireNotNull(personaById("chef")))

        assertEquals("tutor", viewModel.uiState.value.activePersonaId)
        assertEquals("tutor", enginePersonaId())
    }

    @Test
    fun `a new chat never inherits the previous persona`() {
        viewModel.setPersona(PERSONAS.first())

        viewModel.clearChat()

        assertNull(viewModel.uiState.value.activePersonaId)
        assertNull(enginePersonaId())
    }

    /**
     * The view-only transition: a run is in flight in another chat, so the engine
     * stays bound to that one and must not pick up a persona chosen on the blank
     * chat the user browsed to. The persona waits in UI state and reaches the
     * engine only when the viewed chat becomes the engine's, at send time.
     */
    @Test
    fun `a persona picked while another chat runs never touches the running one`() {
        val runningId = requireNotNull(viewModel.uiState.value.activeSessionId)
        seedRunningSession(runningId)
        // Browse to a new blank chat; the engine stays on the running session.
        viewModel.clearChat()
        assertEquals(runningId, enginePersonaOwner())

        viewModel.setPersona(requireNotNull(personaById("doctor")))

        assertEquals("doctor", viewModel.uiState.value.activePersonaId)
        assertNull("the running chat's prompt must not gain a persona", enginePersonaId())
    }

    @Test
    fun `the persona reaches the engine when the viewed chat becomes the engine's`() = runBlocking {
        val runningId = requireNotNull(viewModel.uiState.value.activeSessionId)
        seedRunningSession(runningId)
        viewModel.clearChat()
        viewModel.setPersona(requireNotNull(personaById("chef")))
        val viewedId = requireNotNull(viewModel.uiState.value.activeSessionId)

        // The run finishes and the user sends their first message here: sendMessage
        // binds the engine to the viewed session before running it.
        seedRunningSession(null)
        viewModel.bindEngineToViewedSession(viewedId)

        assertEquals(viewedId, enginePersonaOwner())
        assertEquals("chef", enginePersonaId())
    }

    @Test
    fun `a saved session carries its persona back`() = runBlocking {
        historyRepository.saveSession(
            ChatSession(
                id = "persona-session",
                title = "Knee pain",
                lastModified = 1L,
                messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("hi"))),
                personaId = "doctor"
            )
        )

        viewModel.openSession("persona-session")
        awaitPersona("doctor")

        assertEquals("doctor", viewModel.uiState.value.activePersonaId)
        assertEquals("doctor", enginePersonaId())
    }

    /** openSession suspends on IO and resumes on Main; drain until it lands. */
    private fun awaitPersona(expected: String) {
        repeat(POLL_ATTEMPTS) {
            ShadowLooper.idleMainLooper()
            if (viewModel.uiState.value.activePersonaId == expected) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MS = 20L
    }
}
