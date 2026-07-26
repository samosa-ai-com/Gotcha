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
 * End-to-end coverage of the agent loop — LLM response → [com.gotcha.tools.ToolExecutor]
 * → `ToolResult` → follow-up turn — driven by a scripted MockWebServer, including the
 * confirmation gate for destructive tools.
 *
 * The plan calls this the highest-value untested path: a handful of these catch more real
 * breakage than 200 leaf unit tests, because everything below has to actually fit together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class AgentLoopTest {

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

        workDir = File(context.filesDir, "agent-loop-test").apply {
            deleteRecursively()
            mkdirs()
        }
        savedWorkingDir = FileResolver.WORKING_DIR_BASE

        events = RecordingAgentEvents()
        engine = AgentEngine(
            appContext = context,
            events = events,
            historyRepository = ChatHistoryRepository(context, "agent-loop-test-chats"),
            settingsProvider = {
                Settings(
                    apiKey = "test-key",
                    baseUrl = server.url("/").toString(),
                    // Keep the loop short so a scripted script that never terminates fails fast.
                    maxToolRounds = 4
                )
            },
            clientProvider = {
                LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString())
            },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "agent-loop-test"
        FileResolver.WORKING_DIR_BASE = workDir.absolutePath
    }

    @After
    fun tearDown() {
        server.shutdown()
        FileResolver.WORKING_DIR_BASE = savedWorkingDir
        workDir.deleteRecursively()
        ShadowExternalStorageManager.resetGranted()
    }

    // ---- scripting helpers ----

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

    // ---- the loop ----

    @Test
    fun `a tool call is executed and its result is fed back for a follow-up turn`() = runTest {
        val target = File(workDir, "note.txt")
        enqueueToolCall("write_file", """{"path":"${target.absolutePath}","content":"from the agent"}""")
        enqueueTextReply("Done — I wrote the file.")

        engine.run(AgentMode.OPERATOR)

        // 1. the tool actually ran
        assertTrue("the tool did not run: file missing", target.isFile)
        assertEquals("from the agent", target.readText())

        // 2. the loop took a second turn rather than stopping at the tool call
        assertEquals("expected two LLM calls (tool round + follow-up)", 2, server.requestCount)

        // 3. the tool result was sent back to the model
        server.takeRequest()
        val followUp = server.takeRequest().body.readUtf8()
        assertTrue("the follow-up turn carried no tool result:\n$followUp", followUp.contains("\"tool\""))

        // 4. the final assistant reply reached the host
        assertEquals("Done — I wrote the file.", events.assistantReplies.lastOrNull())
    }

    @Test
    fun `a plain text response ends the loop after one call`() = runTest {
        enqueueTextReply("No tools needed.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, server.requestCount)
        assertEquals("No tools needed.", events.assistantReplies.lastOrNull())
    }

    @Test
    fun `a failing tool reports the error back to the model instead of aborting`() = runTest {
        // Missing the required 'content' argument: dispatch returns a missing() error.
        enqueueToolCall("write_file", """{"path":"${File(workDir, "x.txt").absolutePath}"}""")
        enqueueTextReply("I could not write the file.")

        engine.run(AgentMode.OPERATOR)

        assertEquals("the loop should continue after a tool error", 2, server.requestCount)
        server.takeRequest()
        val followUp = server.takeRequest().body.readUtf8()
        assertTrue("the error was not returned to the model:\n$followUp", followUp.contains("content"))
    }

    @Test
    fun `an empty model response surfaces an error rather than looping`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, server.requestCount)
        assertTrue(
            "expected an error bubble, got: ${events.uiMessages}",
            events.uiMessages.any { it.contains("empty response") }
        )
    }

    @Test
    fun `a transport failure surfaces an error and stops the loop`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, server.requestCount)
        assertTrue("expected an error bubble, got: ${events.uiMessages}", events.uiMessages.isNotEmpty())
    }

    // ---- the confirmation gate ----

    /**
     * `uninstall_app` does not uninstall directly: it resolves the package and returns a
     * `CONFIRM_UNINSTALL:` marker, which the loop turns into an `awaitConfirmation` call and
     * only then performs the removal. This asserts the whole marker → gate → decision path.
     */
    @Test
    fun `a destructive tool is gated on user confirmation and is not run when denied`() = runTest {
        installFakePackage("com.example.victim", "Victim App")
        events.confirmationAnswer = false
        enqueueToolCall("uninstall_app", """{"package_name":"com.example.victim"}""")
        enqueueTextReply("Okay, I left it installed.")

        engine.run(AgentMode.OPERATOR)

        assertTrue("the destructive tool was not gated", events.confirmationRequests.isNotEmpty())
        assertTrue(
            "the gate should name the tool: ${events.confirmationRequests}",
            events.confirmationRequests.any { it.contains("uninstall_app") }
        )
        assertTrue(
            "the model should be told the user declined: ${events.uiMessages}",
            events.uiMessages.any { it.contains("declined", ignoreCase = true) }
        )
    }

    @Test
    fun `approving the gate lets the destructive tool proceed`() = runTest {
        installFakePackage("com.example.victim", "Victim App")
        events.confirmationAnswer = true
        enqueueToolCall("uninstall_app", """{"package_name":"com.example.victim"}""")
        enqueueTextReply("Removed.")

        engine.run(AgentMode.OPERATOR)

        assertTrue("the gate was never shown", events.confirmationRequests.isNotEmpty())
        assertFalse(
            "an approved uninstall should not report a decline: ${events.uiMessages}",
            events.uiMessages.any { it.contains("declined", ignoreCase = true) }
        )
    }

    private fun installFakePackage(packageName: String, label: String) {
        val applicationInfo = android.content.pm.ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            nonLocalizedLabel = label
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(
            android.content.pm.PackageInfo().apply {
                this.packageName = packageName
                this.applicationInfo = applicationInfo
            }
        )
    }

    @Test
    fun `the confirmation gate is not triggered for a non-destructive tool`() = runTest {
        enqueueToolCall("write_file", """{"path":"${File(workDir, "safe.txt").absolutePath}","content":"ok"}""")
        enqueueTextReply("Written.")

        engine.run(AgentMode.OPERATOR)

        assertTrue(
            "a harmless write should not prompt the user: ${events.confirmationRequests}",
            events.confirmationRequests.isEmpty()
        )
    }

    // ---- agent-mode enforcement, end to end ----

    @Test
    fun `Monitor mode refuses an Operator-only tool`() = runTest {
        enqueueToolCall("write_file", """{"path":"${File(workDir, "nope.txt").absolutePath}","content":"x"}""")
        enqueueTextReply("I cannot do that in Monitor mode.")

        engine.run(AgentMode.MONITOR)

        assertFalse("the tool ran despite Monitor mode", File(workDir, "nope.txt").exists())
        server.takeRequest()
        val followUp = server.takeRequest().body.readUtf8()
        assertTrue(
            "the model was not told why the tool was refused:\n$followUp",
            followUp.contains("Monitor") || followUp.contains("not available")
        )
    }

    // ---- history ----

    @Test
    fun `the exchange is recorded in history for the next turn`() = runTest {
        enqueueTextReply("Recorded.")

        engine.run(AgentMode.OPERATOR)

        assertTrue("nothing was appended to history", engine.history.isNotEmpty())
        assertTrue(
            "the assistant reply is missing from history: ${engine.history}",
            engine.history.any { it.textContent.contains("Recorded.") }
        )
    }
}
