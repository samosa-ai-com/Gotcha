package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.llm.ChatRequest
import com.gotcha.llm.LLMClient
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.ShadowExternalStorageManager
import com.gotcha.tools.AgentMode
import com.gotcha.tools.FileResolver
import com.gotcha.tools.ScreenPerception
import com.gotcha.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
                    provider = LlmProvider.OPENAI_COMPATIBLE,
                    apiKey = "test-key",
                    baseUrl = server.url("/").toString(),
                    model = "gpt-4o",
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
    fun `a malformed tool call does not poison subsequent turns (issue 13)`() = runTest {
        // The exact shape that bricked issue #13: the model emitted a tool call
        // whose `arguments` are not valid JSON (a cut-off summary string).
        enqueueToolCall("finish_task", """{"summary": "cut off at the end""")
        enqueueTextReply("Got it.")

        engine.run(AgentMode.OPERATOR)

        // The loop continues past the malformed call instead of aborting.
        assertEquals("expected a follow-up turn after the malformed call", 2, server.requestCount)
        server.takeRequest()
        val followUpBody = server.takeRequest().body.readUtf8()
        val sent = Json { ignoreUnknownKeys = true }.decodeFromString(ChatRequest.serializer(), followUpBody)

        // The poisoned arguments were neutralized before they could be re-sent,
        // so a strict server would not 400 the whole chat on this history.
        val replayedArguments = sent.messages
            .flatMap { it.toolCalls.orEmpty() }
            .filter { it.id == "call_1" }
            .map { it.function.arguments }
        assertTrue("the tool call was not replayed", replayedArguments.isNotEmpty())
        replayedArguments.forEach { assertEquals("{}", it) }

        // The model still saw the truthful error text so it could recover.
        assertTrue(
            "the malformed-arguments error was not fed back to the model:\n$followUpBody",
            sent.messages.any { it.role == "tool" && it.textContent.contains("Malformed tool arguments") }
        )
        assertEquals("Got it.", events.assistantReplies.lastOrNull())
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

    // ---- ending the turn (issue #20) ----

    /**
     * Until `finish_task` existed, the only exit from the loop was a reply that
     * happened to carry no tool calls — so an agent that kept calling tools never
     * fired `onAssistantReply`, and on a voice call the user heard nothing at all.
     */
    @Test
    fun `finish_task ends the loop and delivers the summary to the host`() = runTest {
        enqueueToolCall("finish_task", """{"summary":"Searched Amazon for bicycles."}""")
        // Would be consumed if the loop wrongly took another turn.
        enqueueTextReply("should never be requested")

        engine.run(AgentMode.OPERATOR)

        assertEquals("finish_task should end the turn, not start another", 1, server.requestCount)
        assertEquals("Searched Amazon for bicycles.", events.assistantReplies.lastOrNull())
        assertTrue(
            "the summary is missing from history: ${engine.history}",
            engine.history.any { it.textContent.contains("Searched Amazon for bicycles.") }
        )
    }

    /**
     * `finish_task` is a terminal signal: any sibling tool calls the model bundles
     * with it must NOT execute, otherwise the user only hears the summary while a
     * side effect (file write, sub-agent launch, anything else) runs silently.
     */
    @Test
    fun `tool calls batched after finish_task are skipped`() = runTest {
        val sideEffect = File(workDir, "should-not-exist.txt")
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"call_finish","type":"function","function":{"name":"finish_task",
                   "arguments":"{\"summary\":\"All done.\"}"}},
                  {"id":"call_write","type":"function","function":{"name":"write_file",
                   "arguments":"{\"path\":\"${sideEffect.absolutePath}\",\"content\":\"sneaky\"}"}}
                ]}}]}
                """.trimIndent()
            )
        )

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, server.requestCount)
        assertEquals("All done.", events.assistantReplies.lastOrNull())
        assertFalse(
            "tool calls ordered after finish_task must not run",
            sideEffect.exists()
        )
    }

    /**
     * The mirror image: a tool call placed BEFORE `finish_task` in the same batch
     * must still run, so the loop persists the effect the user asked for and the
     * summary describes what actually happened.
     */
    @Test
    fun `tool calls batched before finish_task still run`() = runTest {
        val kept = File(workDir, "kept.txt")
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"call_write","type":"function","function":{"name":"write_file",
                   "arguments":"{\"path\":\"${kept.absolutePath}\",\"content\":\"kept\"}"}},
                  {"id":"call_finish","type":"function","function":{"name":"finish_task",
                   "arguments":"{\"summary\":\"Wrote it.\"}"}}
                ]}}]}
                """.trimIndent()
            )
        )

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, server.requestCount)
        assertEquals("Wrote it.", events.assistantReplies.lastOrNull())
        assertTrue(
            "tool calls ordered before finish_task must still run",
            kept.isFile && kept.readText() == "kept"
        )
    }

    /**
     * The issue #20 loop: the model delegates, gets a prose report back, cannot see
     * the screen the sub-agent left behind, and delegates a reworded copy of the
     * same task forever. The byte-identical guard cannot catch it — every round
     * differs — so the delegation guard stops it and reports what the sub-agent
     * actually found, rather than leaving the user with silence.
     */
    @Test
    fun `endless re-delegation is stopped and the sub-agent result is delivered`() = runTest {
        // Three top-level rounds that do nothing but delegate; each spawns a
        // sub-agent that answers immediately. maxConsecutiveDelegations is 3.
        repeat(3) { round ->
            enqueueToolCall("task", """{"description":"Shop","prompt":"round $round"}""", id = "call_$round")
            enqueueToolCall("ask_final_answer", """{"answer":"Found 3 bicycles."}""", id = "sub_$round")
        }

        engine.run(AgentMode.OPERATOR)

        assertTrue(
            "the guard should have explained the stop: ${events.uiMessages}",
            events.uiMessages.any { it.contains("delegated", ignoreCase = true) }
        )
        assertEquals(
            "the sub-agent's result should still reach the user",
            "Found 3 bicycles.",
            events.assistantReplies.lastOrNull()
        )
    }

    /** One round before giving up, the model is told to stop delegating. */
    @Test
    fun `the model is nudged to finish before the delegation guard gives up`() = runTest {
        repeat(3) { round ->
            enqueueToolCall("task", """{"description":"Shop","prompt":"round $round"}""", id = "call_$round")
            enqueueToolCall("ask_final_answer", """{"answer":"Found 3 bicycles."}""", id = "sub_$round")
        }

        engine.run(AgentMode.OPERATOR)

        val bodies = (1..server.requestCount).map { server.takeRequest().body.readUtf8() }
        assertTrue(
            "no request carried the re-delegation reminder",
            bodies.any { it.contains("delegated to a sub-agent on every round") }
        )
    }

    /** A round that delegates *and* does something else is real progress, not a loop. */
    @Test
    fun `mixing a delegation with other work does not trip the guard`() = runTest {
        repeat(2) { round ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"choices":[{"message":{"role":"assistant","tool_calls":[
                      {"id":"call_$round","type":"function","function":{"name":"task",
                       "arguments":"{\"description\":\"Shop\",\"prompt\":\"round $round\"}"}},
                      {"id":"batt_$round","type":"function","function":{"name":"get_battery_info",
                       "arguments":"{}"}}
                    ]}}]}
                    """.trimIndent()
                )
            )
            enqueueToolCall("ask_final_answer", """{"answer":"Found 3 bicycles."}""", id = "sub_$round")
        }
        enqueueTextReply("All done.")

        engine.run(AgentMode.OPERATOR)

        assertFalse(
            "the guard fired on rounds that were not delegation-only: ${events.uiMessages}",
            events.uiMessages.any { it.contains("delegated to a sub-agent") }
        )
        assertEquals("All done.", events.assistantReplies.lastOrNull())
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

    // ---- foreground control (issue #98) ----

    private fun addUserMessage(text: String) {
        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive(text)
        )
    }

    @Test
    fun `foreground control is asked once per request, naming the app and the reason`() = runTest {
        installFakePackage("com.example.chat", "Chat App")
        addUserMessage("Say hi to Sam on Chat App")
        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""", id = "call_1")
        enqueueToolCall("open_setting", """{"setting":"wifi"}""", id = "call_2")
        enqueueTextReply("Done.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(
            "a second foreground step in the same request must not ask again",
            1,
            events.foregroundControlRequests.size
        )
        val request = events.foregroundControlRequests.single()
        assertEquals("open_app", request.toolName)
        assertEquals("Chat App", request.appLabel)
        assertTrue(request.promptText().contains("Say hi to Sam on Chat App"))
        assertEquals(listOf(true to "Chat App", false to "Chat App"), events.foregroundControlChanges)
    }

    @Test
    fun `a new request asks again`() = runTest {
        installFakePackage("com.example.chat", "Chat App")
        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""")
        enqueueTextReply("Opened.")
        engine.run(AgentMode.OPERATOR)

        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""")
        enqueueTextReply("Opened again.")
        engine.run(AgentMode.OPERATOR)

        assertEquals(2, events.foregroundControlRequests.size)
    }

    @Test
    fun `denying foreground control blocks every foreground step in the request`() = runTest {
        installFakePackage("com.example.chat", "Chat App")
        events.foregroundControlAnswer = false
        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""", id = "call_1")
        enqueueToolCall("open_setting", """{"setting":"wifi"}""", id = "call_2")
        enqueueTextReply("I left your apps alone.")

        engine.run(AgentMode.OPERATOR)

        assertEquals("a denial is remembered for the request", 1, events.foregroundControlRequests.size)
        assertTrue("control never started, so there is nothing to end", events.foregroundControlChanges.isEmpty())
        assertTrue(
            "the model should be told the user said no: ${events.uiMessages}",
            events.uiMessages.count { it.contains("did not allow Gotcha") } == 2
        )
        val launched = org.robolectric.Shadows.shadowOf(
            context as android.app.Application
        ).nextStartedActivity
        assertEquals("a denied open_app must not launch anything", null, launched)
    }

    @Test
    fun `background tools never ask for foreground control`() = runTest {
        enqueueToolCall("write_file", """{"path":"${File(workDir, "bg.txt").absolutePath}","content":"ok"}""")
        enqueueTextReply("Written.")

        engine.run(AgentMode.OPERATOR)

        assertTrue(events.foregroundControlRequests.isEmpty())
        assertTrue(events.foregroundControlChanges.isEmpty())
    }

    @Test
    fun `a sub-agent's foreground step is gated too`() = runTest {
        installFakePackage("com.example.chat", "Chat App")
        events.foregroundControlAnswer = false
        enqueueToolCall("task", """{"description":"Chat","prompt":"open the chat app"}""", id = "call_1")
        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""", id = "sub_1")
        enqueueToolCall("ask_final_answer", """{"answer":"Not allowed to open it."}""", id = "sub_2")
        enqueueTextReply("I couldn't open it.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(listOf("open_app"), events.foregroundControlRequests.map { it.toolName })
    }

    @Test
    fun `the destructive-action confirmation still fires after foreground control is allowed`() = runTest {
        installFakePackage("com.example.chat", "Chat App")
        installFakePackage("com.example.victim", "Victim App")
        events.confirmationAnswer = false
        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""", id = "call_1")
        enqueueToolCall("uninstall_app", """{"package_name":"com.example.victim"}""", id = "call_2")
        enqueueTextReply("Left it installed.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.foregroundControlRequests.size)
        assertTrue(events.confirmationRequests.any { it.contains("uninstall_app") })
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

    // ---- run summaries (the marketing-poster raw material) ----

    @Test
    fun `a successful run emits a run summary with the tool record`() = runTest {
        val target = File(workDir, "summarized.txt")
        enqueueToolCall("write_file", """{"path":"${target.absolutePath}","content":"hello"}""")
        enqueueTextReply("Done.")

        engine.run(AgentMode.OPERATOR)

        assertEquals("exactly one summary for one run", 1, events.runSummaries.size)
        val summary = events.runSummaries.single()
        assertTrue(summary.succeeded)
        assertEquals(AgentMode.OPERATOR.name, summary.agentMode)
        assertEquals("gpt-4o", summary.model)
        assertEquals("Done.", summary.finalReply)
        assertTrue(
            "the executed tool must appear in the summary: ${summary.toolCalls}",
            summary.toolCalls.any { it.name == "write_file" && it.success }
        )
        assertTrue("the run must have a duration", summary.endedAt >= summary.startedAt)
    }

    @Test
    fun `the run summary records the user prompt and failed tools`() = runTest {
        // The engine's history has the user message appended by the host before
        // run() is called (mirrors ChatViewModel.sendMessage). Pin the title so
        // saveCurrentSession's generateTitleIfNeeded skips its extra LLM call.
        engine.restoreTitle("Wifi fix chat")
        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive("Fix my wifi please")
        )
        enqueueToolCall("toggle_wifi", """{}""")
        enqueueTextReply("Could not toggle Wi-Fi directly; opened settings instead.")

        engine.run(AgentMode.OPERATOR)

        val summary = events.runSummaries.single()
        assertEquals("Fix my wifi please", summary.userPrompt)
        assertTrue(
            "the failed tool must be recorded as failed: ${summary.toolCalls}",
            summary.toolCalls.any { it.name == "toggle_wifi" && !it.success }
        )
    }

    @Test
    fun `a failed run emits a summary marked not succeeded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.runSummaries.size)
        assertFalse(events.runSummaries.single().succeeded)
    }

    @Test
    fun `finish_task emits a succeeded run summary`() = runTest {
        enqueueToolCall("finish_task", """{"summary":"All done."}""")

        engine.run(AgentMode.OPERATOR)

        val summary = events.runSummaries.single()
        assertTrue(summary.succeeded)
        assertEquals("All done.", summary.finalReply)
    }

    @Test
    fun `run summaries persist into the saved session`() = runTest {
        enqueueToolCall("finish_task", """{"summary":"Persisted."}""")

        engine.run(AgentMode.OPERATOR)
        engine.saveCurrentSession()

        val saved = ChatHistoryRepository(context, "agent-loop-test-chats").loadSession("agent-loop-test")
        assertTrue(saved != null)
        assertTrue("the summary must survive save/reload: ${saved!!.runSummaries}", saved.runSummaries.isNotEmpty())
    }

    // ---- screen-read chrome events ----

    /**
     * The accessibility service is final with a private `instance` setter, so a
     * real `read_screen` tool round cannot run in Robolectric. These tests drive
     * the engine's injection methods directly — same code path the loop runs on
     * a successful read — and assert the chrome-hide/restore + pulse contract.
     * The screenshot capture itself fails in Robolectric, which is exactly the
     * text-read-only path that must still fire `onScreenReadDone`.
     */
    @Test
    fun `read_screen capture hides chrome then fires the pulse`() = runTest {
        engine.injectReadScreenObservation()

        assertEquals(listOf(true, false), events.screenCaptureChrome)
        assertEquals(1, events.screenReadDoneCount)
    }

    @Test
    fun `read_screen_raw capture hides chrome and fires the pulse even when the screenshot fails`() = runTest {
        engine.injectFullResScreenshot(
            ToolResult.ok("read_screen_raw:On-screen text\n- Test screen text")
        )

        assertEquals(listOf(true, false), events.screenCaptureChrome)
        assertEquals(1, events.screenReadDoneCount)
    }

    // ---- MediaProjection consent is requested on demand, never at launch ----

    /**
     * Consent is no longer requested in `MainActivity.onCreate`, so a failed capture
     * with no token held is the only thing that can ask for it. If this stops firing,
     * `read_screen` loses its screenshot for good on devices without accessibility.
     */
    @Test
    fun `a failed capture with no consent token asks for screenshot consent`() = runTest {
        ScreenPerception.mediaProjectionResultData = null

        engine.injectReadScreenObservation()

        assertTrue(
            "expected a consent request, got ${events.permissionRequests}",
            events.permissionRequests.contains("special:screenshot_consent")
        )
    }

    // ---- permissions, asked for when they are needed (issue #79) ----

    /**
     * The whole point of dropping the first-launch permission burst: a tool that
     * needs a runtime permission asks for it *here*, mid-turn, and the call is
     * retried the moment it is granted. Without the retry the grant lands a beat
     * too late — the model has already been told the tool failed, and the user
     * has to repeat themselves after saying yes.
     */
    @Test
    fun `a missing runtime permission is asked for mid-turn and the call retried on a grant`() = runTest {
        enqueueToolCall("get_location", "{}")
        enqueueTextReply("Here's where you are.")
        events.permissionAnswer = { permission ->
            org.robolectric.Shadows.shadowOf(
                org.robolectric.RuntimeEnvironment.getApplication()
            ).grantPermissions(permission)
            true
        }

        engine.run(AgentMode.OPERATOR)

        assertEquals(
            "the tool's own permission should have been asked for, not a Settings marker",
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            events.permissionAsks
        )
        assertEquals(
            "the call should have run twice: once to discover the gap, once with the grant",
            2,
            events.activities.count { it == "Running: get_location…" }
        )
        server.takeRequest()
        val followUp = server.takeRequest().body.readUtf8()
        assertFalse(
            "the model was told about a permission that had just been granted:\n$followUp",
            followUp.contains("Location permission is not granted")
        )
    }

    /**
     * "Not now" is a real answer. The tool's own message stands, the model is
     * told, and nothing is retried behind the user's back.
     */
    @Test
    fun `declining a permission leaves the tool failure as the answer`() = runTest {
        enqueueToolCall("get_location", "{}")
        enqueueTextReply("I can't see your location.")
        events.permissionAnswer = { false }

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.activities.count { it == "Running: get_location…" })
        server.takeRequest()
        val followUp = server.takeRequest().body.readUtf8()
        assertTrue(
            "the model should have been told the permission is missing:\n$followUp",
            followUp.contains("Location permission is not granted")
        )
    }

    /**
     * A special access has no runtime dialog — it lives on a Settings screen —
     * so it goes out as a marker and the turn continues. It must be emitted
     * exactly once: it used to be emitted twice per call, which opened the
     * system settings screen, then opened it again.
     */
    @Test
    fun `a special-access marker is emitted once and never asked as a runtime permission`() = runTest {
        // Accessibility is off in this environment, so `tap` is withheld and the
        // executor answers with the capability's marker.
        enqueueToolCall("tap", """{"x":10,"y":10}""")
        enqueueTextReply("I need accessibility access first.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(
            listOf(ToolResult.ACCESSIBILITY_ACCESS),
            events.permissionRequests
        )
        assertTrue("a special access must never reach the runtime path", events.permissionAsks.isEmpty())
    }

    /**
     * From API 34 a consent token backs exactly one capture session. Holding on to a
     * spent token would keep the request above from ever firing again, so the token
     * must be dropped as it is consumed. (The capture itself cannot succeed under
     * Robolectric; only the token bookkeeping is under test.)
     */
    @Test
    fun `a consumed MediaProjection token is not reused for the next capture`() = runTest {
        val savedContext = ScreenPerception.appContext
        ScreenPerception.appContext = context
        ScreenPerception.mediaProjectionResultData = android.content.Intent()
        try {
            ScreenPerception.captureRawScreenshotBytes()

            assertEquals(null, ScreenPerception.mediaProjectionResultData)
        } finally {
            ScreenPerception.appContext = savedContext
            ScreenPerception.mediaProjectionResultData = null
        }
    }
}
