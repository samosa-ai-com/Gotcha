package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.LLMClient
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.ShadowExternalStorageManager
import com.gotcha.tools.AgentMode
import com.gotcha.tools.GotchaSettingsUpdate
import com.gotcha.tools.ToolResult
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
 * `update_gotcha_settings` end to end (issue #99): model call → validation →
 * confirmation → [SettingsRepository] → audit log, driven by a scripted server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class SettingsUpdateLoopTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var repository: SettingsRepository
    private lateinit var events: RecordingAgentEvents
    private lateinit var engine: AgentEngine
    private lateinit var workDir: File
    private val actionLog: File get() = File(context.filesDir, "action_log.txt")

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        server = MockWebServer()
        server.start()
        workDir = File(context.filesDir, "settings-update-test").apply {
            deleteRecursively()
            mkdirs()
        }
        actionLog.delete()

        repository = SettingsRepository(context)
        repository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = server.url("/").toString(),
                model = "gpt-4o",
                maxToolRounds = 4
            )
        )

        events = RecordingAgentEvents()
        engine = AgentEngine(
            appContext = context,
            events = events,
            historyRepository = ChatHistoryRepository(context, "settings-update-test-chats"),
            settingsProvider = { repository.load() },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString()) },
            onUpdateGotchaSettings = { plan ->
                repository.save(plan.applyTo(repository.load()))
                ToolResult.ok(GotchaSettingsUpdate.appliedMessage(plan))
            },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "settings-update-test"
    }

    @After
    fun tearDown() {
        server.shutdown()
        workDir.deleteRecursively()
        actionLog.delete()
    }

    private fun enqueueSettingsCall(changesJson: String, id: String = "call_1") {
        val args = """{"changes":$changesJson,"reason":"You asked."}"""
            .replace("\\", "\\\\").replace("\"", "\\\"")
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"$id","type":"function",
                   "function":{"name":"update_gotcha_settings","arguments":"$args"}}]}}]}"""
            )
        )
    }

    private fun enqueueTextReply(text: String) {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""))
    }

    private fun lastRequestBody(): String {
        var body = ""
        repeat(server.requestCount) { body = server.takeRequest().body.readUtf8() }
        return body
    }

    @Test
    fun `approving saves the change through SettingsRepository and audits it`() = runTest {
        enqueueSettingsCall("""{"reply_chime":true,"completion_preview":"full"}""")
        enqueueTextReply("Done.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.confirmationRequests.size)
        val prompt = events.confirmationRequests.single()
        assertTrue(prompt, prompt.startsWith("update_gotcha_settings:"))
        assertTrue(prompt, prompt.contains("Chime when a reply arrives: Off → On"))
        assertTrue(prompt, prompt.contains("Reply shown in the task-finished notification: short → full"))
        assertTrue(prompt, prompt.contains("Affects: privacy, notifications."))

        val saved = repository.load()
        assertTrue(saved.notifyChimeEnabled)
        assertEquals(com.gotcha.data.CompletionPreview.FULL, saved.chatCompletionPreview)

        assertTrue(lastRequestBody().contains("now saved"))
        val log = actionLog.readText()
        assertTrue(log, log.contains("update_gotcha_settings\t(approved) Chime when a reply arrives: Off → On"))
    }

    @Test
    fun `denying leaves every setting unchanged and tells the model`() = runTest {
        val before = repository.load()
        events.confirmationAnswer = false
        enqueueSettingsCall("""{"reply_chime":true,"connectors":{"google":false}}""")
        enqueueTextReply("Okay, left as is.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.confirmationRequests.size)
        assertEquals(before, repository.load())
        assertTrue(lastRequestBody().contains("declined"))
        val log = actionLog.readText()
        assertTrue(log, log.contains("update_gotcha_settings\t(denied)"))
        assertTrue(log, log.contains("\tFAIL\t"))
    }

    @Test
    fun `every change asks again, even right after an approval`() = runTest {
        enqueueSettingsCall("""{"reply_chime":true}""", id = "call_1")
        enqueueSettingsCall("""{"auto_read_replies":true}""", id = "call_2")
        enqueueTextReply("Both done.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(2, events.confirmationRequests.size)
        assertTrue(repository.load().notifyChimeEnabled)
        assertTrue(repository.load().autoReadReplies)
    }

    @Test
    fun `an invalid request is refused before any prompt and changes nothing`() = runTest {
        val before = repository.load()
        enqueueSettingsCall("""{"reply_chime":true,"apiKey":"sk-evil"}""")
        enqueueTextReply("I can't change that.")

        engine.run(AgentMode.OPERATOR)

        assertTrue(events.confirmationRequests.isEmpty())
        assertEquals(before, repository.load())
        assertTrue(lastRequestBody().contains("is not a setting this tool can change"))
    }

    @Test
    fun `a request that changes nothing does not prompt`() = runTest {
        enqueueSettingsCall("""{"reply_vibration":true}""")
        enqueueTextReply("Already on.")

        engine.run(AgentMode.OPERATOR)

        assertTrue(events.confirmationRequests.isEmpty())
        assertTrue(lastRequestBody().contains("already have the requested values"))
    }

    @Test
    fun `Monitor mode cannot change settings`() = runTest {
        val before = repository.load()
        enqueueSettingsCall("""{"reply_chime":true}""")
        enqueueTextReply("Not in Monitor mode.")

        engine.run(AgentMode.MONITOR)

        assertTrue(events.confirmationRequests.isEmpty())
        assertEquals(before, repository.load())
        assertFalse(repository.load().notifyChimeEnabled)
    }
}
