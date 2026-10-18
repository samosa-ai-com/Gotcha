package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.LlmProvider
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
 * A permission a delegated sub-agent's tool runs into reaches the user through
 * the same prompt a main-agent tool's does (issue #133). It used to be logged
 * in the sub-agent's private history, where nobody saw it.
 *
 * One MockWebServer script serves both loops: the main agent's `task` call,
 * then the sub-agent's rounds, then the main agent's reply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class SubAgentPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var engine: AgentEngine
    private lateinit var events: RecordingAgentEvents
    private lateinit var workDir: File
    private lateinit var savedWorkingDir: String

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        server = MockWebServer()
        server.start()

        workDir = File(context.filesDir, "sub-agent-permission-test").apply {
            deleteRecursively()
            mkdirs()
        }
        savedWorkingDir = FileResolver.WORKING_DIR_BASE

        events = RecordingAgentEvents()
        val settings = Settings(
            provider = LlmProvider.OPENAI_COMPATIBLE,
            apiKey = "test-key",
            baseUrl = server.url("/").toString(),
            model = "gpt-4o",
            maxToolRounds = 4
        )
        engine = AgentEngine(
            appContext = context,
            events = events,
            historyRepository = ChatHistoryRepository(context, "sub-agent-permission-test-chats"),
            settingsProvider = { settings },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString()) },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "sub-agent-permission-test"
        FileResolver.WORKING_DIR_BASE = workDir.absolutePath
    }

    @After
    fun tearDown() {
        server.shutdown()
        FileResolver.WORKING_DIR_BASE = savedWorkingDir
        workDir.deleteRecursively()
        ShadowExternalStorageManager.resetGranted()
    }

    private fun enqueueToolCall(tool: String, argsJson: String, id: String = "call_1") {
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"$id","type":"function",
                   "function":{"name":"$tool","arguments":${argsJson.toJsonStringLiteral()}}}
                ]}}]}
                """.trimIndent()
            )
        )
    }

    private fun enqueueTextReply(text: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""
            )
        )
    }

    private fun String.toJsonStringLiteral(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * A sub-agent's tool used to log "permission not granted" in the sub-agent's
     * private history and move on, so the user never saw a prompt. It now asks
     * through the host like a main-agent tool, and retries only that one call.
     */
    @Test
    fun `a sub-agent's missing runtime permission is asked for and its tool retried on a grant`() = runTest {
        enqueueToolCall("task", """{"description":"Locate","prompt":"Find where I am"}""")
        enqueueToolCall("get_location", "{}", id = "sub_1")
        enqueueToolCall("ask_final_answer", """{"answer":"Found it."}""", id = "sub_2")
        enqueueTextReply("You are here.")
        events.permissionAnswer = { permission ->
            org.robolectric.Shadows.shadowOf(
                org.robolectric.RuntimeEnvironment.getApplication()
            ).grantPermissions(permission)
            true
        }

        engine.run(AgentMode.OPERATOR)

        assertEquals(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION), events.permissionAsks)
        assertEquals(4, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        val subFollowUp = server.takeRequest().body.readUtf8()
        assertFalse(
            "the sub-agent was told about a permission that had just been granted:\n$subFollowUp",
            subFollowUp.contains("Location permission is not granted")
        )
    }

    /**
     * A refusal reaches the sub-agent as a clear "stop and report", and a model
     * that calls the tool again anyway does not put a second prompt in front of
     * the user.
     */
    @Test
    fun `a sub-agent asks once and is told to stop when the permission is declined`() = runTest {
        enqueueToolCall("task", """{"description":"Locate","prompt":"Find where I am"}""")
        enqueueToolCall("get_location", "{}", id = "sub_1")
        enqueueToolCall("get_location", "{}", id = "sub_2")
        enqueueToolCall("ask_final_answer", """{"answer":"No location access."}""", id = "sub_3")
        enqueueTextReply("I can't see your location.")
        events.permissionAnswer = { false }

        engine.run(AgentMode.OPERATOR)

        assertEquals(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION), events.permissionAsks)
        server.takeRequest()
        server.takeRequest()
        val subFollowUp = server.takeRequest().body.readUtf8()
        assertTrue(
            "the sub-agent was not told the user declined:\n$subFollowUp",
            subFollowUp.contains("did not grant") && subFollowUp.contains("Do not retry")
        )
    }

    /**
     * The calls after a declined one in the same response were planned before
     * the model heard "no", so they must not run; each still answers its call
     * id, or the next request is malformed.
     */
    @Test
    fun `a declined permission stops the rest of that response's calls`() = runTest {
        enqueueToolCall("task", """{"description":"Locate","prompt":"Find where I am and save it"}""")
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"sub_1","type":"function","function":{"name":"get_location","arguments":"{}"}},
                  {"id":"sub_2","type":"function","function":{"name":"write_file",
                   "arguments":${"""{"path":"where.txt","content":"here"}""".toJsonStringLiteral()}}}
                ]}}]}
                """.trimIndent()
            )
        )
        enqueueToolCall("ask_final_answer", """{"answer":"No location access."}""", id = "sub_3")
        enqueueTextReply("I can't see your location.")
        events.permissionAnswer = { false }

        engine.run(AgentMode.OPERATOR)

        assertFalse(
            "a call ran after the user declined the permission before it",
            workDir.walkTopDown().any { it.name == "where.txt" }
        )
        server.takeRequest()
        server.takeRequest()
        val subFollowUp = server.takeRequest().body.readUtf8()
        assertTrue(
            "the skipped call has no result of its own:\n$subFollowUp",
            subFollowUp.contains("\"tool_call_id\":\"sub_2\"") && subFollowUp.contains("Not run")
        )
    }
}
