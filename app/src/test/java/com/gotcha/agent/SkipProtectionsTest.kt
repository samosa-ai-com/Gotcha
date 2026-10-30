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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Per-chat Skip Protections (issue #155): with the skip on, per-tool
 * confirmations and the foreground-control ask are pre-granted without
 * prompting; default-off behavior is unchanged (pinned by AgentLoopTest).
 * Voice calls never set the flag, so they are not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class SkipProtectionsTest {

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

        workDir = File(context.filesDir, "skip-protections-test").apply {
            deleteRecursively()
            mkdirs()
        }
        savedWorkingDir = FileResolver.WORKING_DIR_BASE

        events = RecordingAgentEvents()
        engine = AgentEngine(
            appContext = context,
            events = events,
            historyRepository = ChatHistoryRepository(context, "skip-protections-test-chats"),
            settingsProvider = {
                Settings(
                    provider = LlmProvider.OPENAI_COMPATIBLE,
                    apiKey = "test-key",
                    baseUrl = server.url("/").toString(),
                    model = "gpt-4o",
                    maxToolRounds = 4
                )
            },
            clientProvider = {
                LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString())
            },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "skip-protections-test"
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
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(this)
        )

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
    fun `skip on lets a destructive tool through with no prompt`() = runTest {
        engine.skipProtections = true
        installFakePackage("com.example.victim", "Victim App")
        enqueueToolCall("uninstall_app", """{"package_name":"com.example.victim"}""")
        enqueueTextReply("Done.")

        engine.run(AgentMode.OPERATOR)

        assertTrue(
            "no confirmation may be raised while skipped: ${events.confirmationRequests}",
            events.confirmationRequests.isEmpty()
        )
        assertTrue(
            "the action must run, not report a decline: ${events.uiMessages}",
            events.uiMessages.none { it.contains("declined", ignoreCase = true) }
        )
        val audit = File(context.filesDir, "action_log.txt").readText()
        assertTrue(
            "the skip must be audited as pre-approved:\n$audit",
            audit.contains("uninstall_app") && audit.contains("pre-approved")
        )
    }

    @Test
    fun `skip on skips the foreground ask`() = runTest {
        engine.skipProtections = true
        installFakePackage("com.example.chat", "Chat App")
        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""")
        enqueueTextReply("Opened.")

        engine.run(AgentMode.OPERATOR)

        assertTrue(
            "no foreground ask may be raised while skipped: ${events.foregroundControlRequests}",
            events.foregroundControlRequests.isEmpty()
        )
    }

    @Test
    fun `revoke re-arms both gates`() = runTest {
        engine.skipProtections = true
        installFakePackage("com.example.victim", "Victim App")
        installFakePackage("com.example.chat", "Chat App")
        enqueueToolCall("uninstall_app", """{"package_name":"com.example.victim"}""")
        enqueueTextReply("Done.")
        engine.run(AgentMode.OPERATOR)
        assertTrue(events.confirmationRequests.isEmpty())

        engine.revokeSkipProtections()

        enqueueToolCall("uninstall_app", """{"package_name":"com.example.victim"}""")
        enqueueTextReply("Left it.")
        engine.run(AgentMode.OPERATOR)
        assertTrue(
            "revoking must bring the confirmation back: ${events.confirmationRequests}",
            events.confirmationRequests.isNotEmpty()
        )

        enqueueToolCall("open_app", """{"package_name":"com.example.chat"}""")
        enqueueTextReply("Opened.")
        engine.run(AgentMode.OPERATOR)
        assertTrue(
            "revoking must bring the foreground ask back: ${events.foregroundControlRequests}",
            events.foregroundControlRequests.isNotEmpty()
        )
    }

    @Test
    fun `the prompt names the skip only when it is on`() {
        assertFalse(
            engine.agentInstructionText(AgentMode.OPERATOR).contains("Protections are OFF")
        )
        engine.skipProtections = true
        val prompt = engine.agentInstructionText(AgentMode.OPERATOR)
        assertTrue(prompt.contains("Protections are OFF"))
        assertTrue(prompt.contains("question tool"))
    }
}
