package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.LLMClient
import com.gotcha.scheduled.ScheduledTaskStore
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.ShadowExternalStorageManager
import com.gotcha.tools.AgentMode
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
 * `schedule_agent_task` end to end (issue #132): model call → checks →
 * approval → [ScheduledTaskStore] → audit log, and the tools withheld where
 * they must not be offered. Driven by a scripted server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class ScheduleTaskLoopTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var events: RecordingAgentEvents
    private lateinit var engine: AgentEngine
    private lateinit var store: ScheduledTaskStore
    private lateinit var workDir: File
    private val actionLog: File get() = File(context.filesDir, "action_log.txt")

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        server = MockWebServer()
        server.start()
        workDir = File(context.filesDir, "schedule-loop-test").apply {
            deleteRecursively()
            mkdirs()
        }
        actionLog.delete()
        store = ScheduledTaskStore(context)
        store.all().forEach { store.delete(it.id) }

        val repository = SettingsRepository(context)
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
            historyRepository = ChatHistoryRepository(context, "schedule-loop-test-chats"),
            settingsProvider = { repository.load() },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = server.url("/").toString()) },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "schedule-loop-chat"
    }

    @After
    fun tearDown() {
        server.shutdown()
        workDir.deleteRecursively()
        actionLog.delete()
    }

    private fun enqueueScheduleCall(argsJson: String) {
        val args = argsJson.replace("\\", "\\\\").replace("\"", "\\\"")
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"schedule_agent_task","arguments":"$args"}}]}}]}"""
            )
        )
    }

    private fun enqueueTextReply(text: String) {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""))
    }

    private fun requestBodies(): List<String> = List(server.requestCount) { server.takeRequest().body.readUtf8() }

    private val newsTask = """{"name":"News","prompt":"Summarise the news","start":"2099-10-19T08:00","repeat":"daily"}"""

    @Test
    fun `approving saves the task, asked from this chat, and audits it`() = runTest {
        enqueueScheduleCall(newsTask)
        enqueueTextReply("Scheduled.")

        engine.run(AgentMode.OPERATOR)

        val prompt = events.confirmationRequests.single()
        assertTrue(prompt, prompt.startsWith("schedule_agent_task:"))
        assertTrue(prompt, prompt.contains("Prompt: Summarise the news"))
        val saved = store.all().single()
        assertEquals("News", saved.name)
        assertEquals(AgentMode.OPERATOR, saved.mode)
        assertTrue(saved.createdByAgent)
        assertTrue(requestBodies().last().contains("task_id ${saved.id}"))
        assertTrue(actionLog.readText().contains("schedule_agent_task\t(approved) News"))
    }

    @Test
    fun `declining saves nothing and tells the model not to retry`() = runTest {
        events.confirmationAnswer = false
        enqueueScheduleCall(newsTask)
        enqueueTextReply("Okay.")

        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.confirmationRequests.size)
        assertTrue(store.all().isEmpty())
        assertTrue(requestBodies().last().contains("declined"))
        assertTrue(actionLog.readText().contains("schedule_agent_task\t(denied)"))
    }

    @Test
    fun `monitor is offered only the list, and refused the rest`() = runTest {
        enqueueScheduleCall(newsTask)
        enqueueTextReply("I can't.")

        engine.run(AgentMode.MONITOR)

        val bodies = requestBodies()
        assertTrue(bodies.first().contains("\"list_scheduled_tasks\""))
        assertFalse(bodies.first().contains("\"schedule_agent_task\""))
        assertTrue(events.confirmationRequests.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a run a schedule started is offered none of the schedule tools`() = runTest {
        engine.scheduledRun = true
        enqueueScheduleCall(newsTask)
        enqueueTextReply("I can't.")

        engine.run(AgentMode.OPERATOR)

        val first = requestBodies().first()
        listOf("schedule_agent_task", "edit_scheduled_task", "list_scheduled_tasks", "delete_scheduled_task").forEach {
            assertFalse(it, first.contains("\"$it\""))
        }
        assertTrue(events.confirmationRequests.isEmpty())
        assertTrue(store.all().isEmpty())
    }
}
