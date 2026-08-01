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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Context-window accounting in [AgentEngine]:
 *  - Post-compaction estimate includes the static prompt overhead so the meter
 *    doesn't collapse (Bug 1).
 *  - When the API omits `usage`, the meter still moves (Bug 3).
 *  - Same fallback lets `checkAndCompactHistory()` fire so history doesn't
 *    grow unbounded against the real model context (Bug 3 cont.).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class AgentEngineOverheadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var engine: AgentEngine
    private lateinit var events: TokenRecordingAgentEvents
    private lateinit var workDir: File
    private lateinit var savedWorkingDir: String

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        server = MockWebServer()
        server.start()

        workDir = File(context.filesDir, "agent-overhead-test").apply {
            deleteRecursively()
            mkdirs()
        }
        savedWorkingDir = FileResolver.WORKING_DIR_BASE

        events = TokenRecordingAgentEvents()
        engine = AgentEngine(
            appContext = context,
            events = events,
            historyRepository = ChatHistoryRepository(context, "agent-overhead-test-chats"),
            settingsProvider = {
                Settings(
                    apiKey = "test-key",
                    baseUrl = server.url("/").toString(),
                    // Keep the loop short and put the threshold in reach so the
                    // compaction path is exercised on demand.
                    maxContextTokens = 800,
                    maxToolRounds = 4
                )
            },
            clientProvider = {
                LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString())
            },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "agent-overhead-test"
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

    /**
     * Enqueue a plain assistant reply. The response payload omits `usage` so
     * the engine's missing-usage fallback path is exercised end-to-end.
     */
    private fun enqueueTextReplyWithoutUsage(text: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""
            )
        )
    }

    /**
     * Enqueue a compaction summary that the engine produces when shrinking
     * history. The body deliberately omits `usage`.
     */
    private fun enqueueCompactionSummary(summary: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":${summary.toJsonStringLiteral()}}}]}"""
            )
        )
    }

    private fun String.toJsonStringLiteral(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- tests ----

    /**
     * Bug 1 — Post-compaction estimate previously measured history only
     * (`(summary.length + preserveLast.length) / 4`), so the meter visibly
     * collapsed. With the fix, it includes [AgentEngine.PROMPT_OVERHEAD_TOKENS]
     * so the value is in the same units as the next round's API-reported count.
     */
    @Test
    fun `post compaction estimate includes the prompt overhead constant`() = runTest {
        // First reply: a long, chatty turn that pushes us past the 80 %
        // threshold (800 * 0.8 = 640). The body is over 2 560 characters
        // long, so even with no `usage` reported the fallback estimate lands
        // above the threshold.
        val firstReply = "x".repeat(3_000)
        enqueueTextReplyWithoutUsage(firstReply)

        // Second reply (post-compaction): a short summary.
        enqueueCompactionSummary("Compacted summary.")

        // Seed the chat with enough history that compaction is meaningful.
        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive("do the thing")
        )

        engine.run(AgentMode.OPERATOR)

        // We expect at least one compaction-trigger onTokenCount emission.
        val compactionEvents = events.tokenCounts.filter { it > 0 }
        assertTrue(
            "expected onTokenCount to fire with the fallback estimate, got: ${events.tokenCounts}",
            compactionEvents.isNotEmpty()
        )

        // The fallback path also covers history-only messages that are shorter
        // than the overhead, so the bar can never collapse below the static
        // prefix: every post-compaction onTokenCount call must be at least
        // PROMPT_OVERHEAD_TOKENS.
        compactionEvents.forEach {
            assertTrue(
                "post-compaction onTokenCount must include the overhead floor ($it < ${AgentEngine.PROMPT_OVERHEAD_TOKENS})",
                it >= AgentEngine.PROMPT_OVERHEAD_TOKENS
            )
        }

        // The persisted engine tokenCount must also be at least the overhead.
        assertTrue(
            "engine.tokenCount must include the overhead floor (${engine.tokenCount} < ${AgentEngine.PROMPT_OVERHEAD_TOKENS})",
            engine.tokenCount >= AgentEngine.PROMPT_OVERHEAD_TOKENS
        )
    }

    /**
     * Bug 3 — If the server omits `usage`, the meter used to stay at 0
     * forever and compaction never fired. The fix falls back to a local
     * `(history.length / 4) + PROMPT_OVERHEAD_TOKENS` estimate so onTokenCount
     * still fires and `checkAndCompactHistory` can still trigger.
     */
    @Test
    fun `missing usage falls back to a local estimate so the meter moves`() = runTest {
        // Pre-populate with enough history that the fallback estimate will be
        // visibly non-zero.
        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive(
                "tell me a long story about a robot that wanted to learn to paint"
            )
        )
        enqueueTextReplyWithoutUsage("ok")

        engine.run(AgentMode.OPERATOR)

        // The fallback path should have produced at least one non-zero
        // onTokenCount emission, and the engine's tokenCount should reflect it.
        assertTrue(
            "expected onTokenCount fallback to be non-zero, got: ${events.tokenCounts}",
            events.tokenCounts.any { it > 0 }
        )
        assertTrue(
            "engine.tokenCount must reflect the fallback (${engine.tokenCount})",
            engine.tokenCount > 0
        )
        assertTrue(
            "engine.tokenCount must be at least the overhead floor (${engine.tokenCount} < ${AgentEngine.PROMPT_OVERHEAD_TOKENS})",
            engine.tokenCount >= AgentEngine.PROMPT_OVERHEAD_TOKENS
        )
    }

    /**
     * Companion to the test above: when the fallback estimate crosses the
     * 80% compaction threshold, the engine must actually call the compaction
     * LLM. Without this, history grows unbounded against the real model
     * context window.
     */
    @Test
    fun `missing usage still triggers compaction at the threshold`() = runTest {
        // Push the engine over the threshold directly so we don't have to
        // script many long rounds.
        engine.tokenCount = (Settings().maxContextTokens * 0.85).toInt()

        // Two enqueued responses: one for the compaction summarization call,
        // then a normal text reply for the resumed loop.
        enqueueCompactionSummary("Compacted.")
        enqueueTextReplyWithoutUsage("done")

        engine.history += com.gotcha.llm.ChatMessage(
            role = "user",
            content = kotlinx.serialization.json.JsonPrimitive("hi")
        )

        engine.run(AgentMode.OPERATOR)

        // Compaction fires before the loop's first round and replaces history
        // with `[assistant: summary, user: preserved]`. The resumed loop then
        // appends one more assistant reply, so we end up at size 3 — what
        // matters is that compaction happened at all, which is observable
        // through the summary bubble it injects.
        assertEquals(
            "compaction summary must be in history",
            true,
            engine.history.any { it.textContent.contains("Compacted.") }
        )
        assertTrue(
            "compaction must have fired — history should contain the user message that survived it",
            engine.history.any { it.role == "user" && it.textContent == "hi" }
        )
    }
}

/**
 * A small [AgentEvents] variant that captures token-count emissions so the
 * overhead-aware tests can assert on the fallback path. Mirrors the recording
 * shape of [RecordingAgentEvents] but keeps a separate `tokenCounts` list.
 */
private class TokenRecordingAgentEvents : AgentEvents {

    val tokenCounts = mutableListOf<Int>()
    val assistantReplies = mutableListOf<String>()
    val uiMessages = mutableListOf<String>()
    var historyResets = 0
        private set

    override fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String?,
        subAgentSteps: List<String>,
        reasoningContent: String?
    ) {
        uiMessages += text
    }

    override fun onActivity(activity: String?) = Unit

    override fun onTokenCount(totalTokens: Int) {
        tokenCounts += totalTokens
    }

    override fun onAssistantReply(text: String) {
        assistantReplies += text
    }

    override fun onSubAgentUpdate(running: String?, currentAction: String?) = Unit

    override fun onPermissionRequest(marker: String) = Unit

    override fun onHistoryReset() {
        historyResets++
    }

    override suspend fun awaitQuestionAnswer(question: PendingQuestion): String = ""

    override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean = true
}
