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

    private fun buildEngine(mode: AgentMode, settings: Settings = testSettings()): AgentEngine {
        val engine = AgentEngine(
            appContext = context,
            events = RecordingAgentEvents(),
            historyRepository = ChatHistoryRepository(context, "agent-prompt-test-chats"),
            settingsProvider = { settings },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString()) },
            workingDirRoot = workDir.absolutePath
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
        settings: Settings = testSettings()
    ): String {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        )
        buildEngine(mode, settings).run(mode)
        return server.takeRequest().body.readUtf8()
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
    fun `user profile block carries preferred language and currency facts`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR)
        assertTrue(body.contains("<user_profile>"))
        assertTrue(body.contains("Preferred language: Hindi"))
        assertTrue(body.contains("Preferred currency:"))
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
}
