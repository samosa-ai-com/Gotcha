package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.Settings
import com.gotcha.llm.LLMClient
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.ShadowExternalStorageManager
import com.gotcha.tools.AgentMode
import com.gotcha.tools.FileResolver
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies the rendered system prompt carries the language directive and the
 * English-tool-calls carve-out (issue #42 P0-5) — the highest-risk item in the
 * multilingual change, since a translated tool argument fails silently.
 *
 * Also covers the Personal Info settings: the `<user_profile>` block and the
 * reply-style directive are only useful if they actually reach the wire, and a
 * field that silently fails to is indistinguishable from one the user never set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class AgentPromptTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var workDir: File
    private lateinit var savedWorkingDir: String

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        server = MockWebServer()
        server.start()
        workDir = File(context.filesDir, "agent-prompt-test").apply {
            deleteRecursively()
            mkdirs()
        }
        savedWorkingDir = FileResolver.WORKING_DIR_BASE
    }

    @After
    fun tearDown() {
        server.shutdown()
        FileResolver.WORKING_DIR_BASE = savedWorkingDir
        workDir.deleteRecursively()
        ShadowExternalStorageManager.resetGranted()
    }

    private fun buildEngine(mode: AgentMode, settings: Settings = testSettings(), mcpNames: List<String> = emptyList()): AgentEngine {
        val engine = AgentEngine(
            appContext = context,
            events = RecordingAgentEvents(),
            historyRepository = ChatHistoryRepository(context, "agent-prompt-test-chats"),
            settingsProvider = { settings },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString()) },
            workingDirRoot = workDir.absolutePath,
            mcpServerNamesProvider = { mcpNames }
        )
        engine.sessionId = "agent-prompt-test-$mode"
        FileResolver.WORKING_DIR_BASE = workDir.absolutePath
        return engine
    }

    private fun testSettings(personal: Settings.() -> Settings = { this }): Settings =
        Settings(
            apiKey = "test-key",
            baseUrl = server.url("/").toString(),
            preferredLanguage = "Hindi",
            maxToolRounds = 4
        ).personal()

    private suspend fun requestBodyFor(
        mode: AgentMode,
        settings: Settings = testSettings(),
        personaId: String? = null,
        mcpNames: List<String> = emptyList()
    ): String {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        )
        buildEngine(mode, settings, mcpNames).apply { sessionPersonaId = personaId }.run(mode)
        return server.takeRequest().body.readUtf8()
    }

    @Test
    fun `working directory reaches the wire once, after the history, not in the system prefix`() = runTest {
        // Issue #121: a per-chat path in the first system message broke prefix-cache
        // reuse across chats, so it must ride in the tail message after the history.
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        )
        val engine = buildEngine(AgentMode.OPERATOR)
        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive("hello")
        )
        engine.run(AgentMode.OPERATOR)
        val messages = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .let { it as kotlinx.serialization.json.JsonObject }["messages"]
            .let { it as kotlinx.serialization.json.JsonArray }
            .map { it as kotlinx.serialization.json.JsonObject }
        fun role(i: Int) = (messages[i]["role"] as kotlinx.serialization.json.JsonPrimitive).content
        fun text(i: Int) = messages[i]["content"].toString()

        val userIndex = messages.indices.single { role(it) == "user" && text(it).contains("hello") }
        val withPath = messages.indices.filter { text(it).contains("Working directory: ${workDir.absolutePath}") }
        assertEquals("system", role(0))
        assertFalse(text(0).contains("Working directory"))
        assertEquals(listOf(userIndex + 1), withPath)
        assertEquals("system", role(withPath.single()))
        assertTrue(text(withPath.single()).contains("Current date/time: "))
    }

    @Test
    fun `MONITOR prompt contains the language directive and English-tool-calls clause`() = runTest {
        val body = requestBodyFor(AgentMode.MONITOR)
        assertTrue(body.contains("Respond to the user in Hindi"))
        assertTrue(body.contains("ALWAYS use English for tool names"))
    }

    @Test
    fun `OPERATOR prompt contains the language directive and English-tool-calls clause`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR)
        assertTrue(body.contains("Respond to the user in Hindi"))
        assertTrue(body.contains("ALWAYS use English for tool names"))
    }

    @Test
    fun `user profile block carries reply language and currency facts`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR)
        assertTrue(body.contains("<user_profile>"))
        assertTrue(body.contains("Reply language: Hindi"))
        assertTrue(body.contains("Preferred currency:"))
    }

    @Test
    fun `voice language does not affect the written reply language`() = runTest {
        // Issue #74 split the two: voiceLanguage drives TTS and STT only, so a
        // German voice must not leak into the directive or the profile block.
        val body = requestBodyFor(
            AgentMode.OPERATOR,
            testSettings { copy(voiceLanguage = "German") }
        )
        assertTrue(body.contains("Respond to the user in Hindi"))
        assertTrue(body.contains("Reply language: Hindi"))
        // Only the messages: the tool schemas list every language as an allowed value.
        val messages = kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { it as kotlinx.serialization.json.JsonObject }["messages"].toString()
        assertFalse(messages.contains("German"))
    }

    @Test
    fun `user profile block carries the personal info the user filled in`() = runTest {
        val body = requestBodyFor(
            AgentMode.OPERATOR,
            testSettings {
                copy(
                    userName = "Ada",
                    userLocation = "Munich, Germany",
                    userOccupation = "Backend engineer",
                    userBackground = "Works on\npayments infrastructure"
                )
            }
        )
        assertTrue(body.contains("Name: Ada"))
        assertTrue(body.contains("Location: Munich, Germany"))
        assertTrue(body.contains("Occupation: Backend engineer"))
        // Newlines are collapsed so one fact can never span two lines of the block.
        assertTrue(body.contains("Background: Works on payments infrastructure"))
    }

    @Test
    fun `blank personal info fields are left out of the user profile block`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR, testSettings { copy(userName = "   ") })
        assertTrue(body.contains("<user_profile>"))
        assertFalse(body.contains("Name:"))
        assertFalse(body.contains("Occupation:"))
    }

    @Test
    fun `reply style preference becomes a directive next to the language one`() = runTest {
        val body = requestBodyFor(
            AgentMode.OPERATOR,
            testSettings { copy(userResponseStyle = "No bullet lists.") }
        )
        assertTrue(body.contains("how they want replies written"))
        assertTrue(body.contains("No bullet lists."))
    }

    @Test
    fun `no reply style preference adds no directive`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR)
        assertFalse(body.contains("how they want replies written"))
    }

    @Test
    fun `chosen persona reaches the system prompt`() = runTest {
        val body = requestBodyFor(AgentMode.MONITOR, personaId = "doctor")
        assertTrue(body.contains("has chosen the Doctor persona"))
        // Not the costume alone: the disclaimer the persona carries has to land too.
        assertTrue(body.contains("you are not making a diagnosis"))
    }

    @Test
    fun `no persona adds no directive`() = runTest {
        val body = requestBodyFor(AgentMode.MONITOR)
        assertFalse(body.contains("has chosen the"))
    }

    @Test
    fun `an unknown persona id is ignored rather than failing the turn`() = runTest {
        val body = requestBodyFor(AgentMode.MONITOR, personaId = "no-such-persona")
        assertFalse(body.contains("has chosen the"))
    }

    @Test
    fun `the mode reminder still follows the persona directive`() = runTest {
        // The role must never be able to talk the agent out of its mode limits,
        // so the read-only reminder stays the last word in the system message.
        val body = requestBodyFor(AgentMode.MONITOR, personaId = "doctor")
        val persona = body.indexOf("has chosen the Doctor persona")
        val reminder = body.indexOf("You are in MONITOR (read-only) mode")
        assertTrue(persona in 0 until reminder)
    }

    @Test
    fun `one engine switching modes sends the new mode's prompt`() = runTest {
        // The reported doubt: after Monitor -> Operator, does the next run go
        // out with Operator's prompt, or stale Monitor wording? One engine,
        // two turns, two wire bodies.
        repeat(2) {
            server.enqueue(
                MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
            )
        }
        val engine = buildEngine(AgentMode.MONITOR)
        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive("look around")
        )
        engine.run(AgentMode.MONITOR)
        val first = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("You are in MONITOR (read-only) mode"))

        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive("now change it")
        )
        engine.run(AgentMode.OPERATOR)
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("You are in OPERATOR mode"))
        assertTrue(!second.contains("You are in MONITOR (read-only) mode"))
    }

    @Test
    fun `MONITOR prompt names connected MCP servers and suggests Operator`() = runTest {
        val body = requestBodyFor(AgentMode.MONITOR, mcpNames = listOf("stub-markdown"))
        assertTrue(body.contains("Connected MCP servers: stub-markdown"))
        assertTrue(body.contains("suggest switching"))
    }

    @Test
    fun `OPERATOR prompt carries no MCP switch line`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR, mcpNames = listOf("stub-markdown"))
        assertTrue(!body.contains("need Operator mode"))
    }

    @Test
    fun `MONITOR prompt without MCP servers names none`() = runTest {
        val body = requestBodyFor(AgentMode.MONITOR)
        assertTrue(!body.contains("Connected MCP servers"))
    }
}
