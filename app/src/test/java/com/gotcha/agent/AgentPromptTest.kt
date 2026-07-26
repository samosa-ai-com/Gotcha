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

    private fun buildEngine(mode: AgentMode): AgentEngine {
        val engine = AgentEngine(
            appContext = context,
            events = RecordingAgentEvents(),
            historyRepository = ChatHistoryRepository(context, "agent-prompt-test-chats"),
            settingsProvider = {
                Settings(
                    apiKey = "test-key",
                    baseUrl = server.url("/").toString(),
                    preferredLanguage = "Hindi",
                    maxToolRounds = 4
                )
            },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString()) },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "agent-prompt-test-$mode"
        FileResolver.WORKING_DIR_BASE = workDir.absolutePath
        return engine
    }

    private suspend fun requestBodyFor(mode: AgentMode): String {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        )
        buildEngine(mode).run(mode)
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
    fun `env block carries preferred language and currency facts`() = runTest {
        val body = requestBodyFor(AgentMode.OPERATOR)
        assertTrue(body.contains("Preferred language: Hindi"))
        assertTrue(body.contains("Preferred currency:"))
    }
}
