package com.gotcha.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.connectors.mcp.FakeMcpServer
import com.gotcha.connectors.mcp.McpServersConnector
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.i18n.stringLookup
import com.gotcha.llm.LLMClient
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.testsupport.ShadowExternalStorageManager
import com.gotcha.tools.AgentMode
import com.gotcha.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * `list_mcp_servers` and `manage_mcp_servers` end to end: model call →
 * validation → approval → the MCP connector → audit log, against a scripted
 * model and a fake MCP server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowExternalStorageManager::class])
class McpServerLoopTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var model: MockWebServer
    private lateinit var mcp: MockWebServer
    private val fake = FakeMcpServer()
    private lateinit var events: RecordingAgentEvents
    private lateinit var engine: AgentEngine
    private lateinit var workDir: File
    private val actionLog: File get() = File(context.filesDir, "action_log.txt")

    private val connector: McpServersConnector
        get() = ConnectorRegistry.byId(McpServersConnector.ID) as McpServersConnector

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        model = MockWebServer().apply { start() }
        mcp = MockWebServer().apply {
            dispatcher = fake
            start()
        }
        workDir = File(context.filesDir, "mcp-loop-test").apply {
            deleteRecursively()
            mkdirs()
        }
        actionLog.delete()
        val repository = SettingsRepository(context)
        repository.save(
            Settings(
                provider = LlmProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = model.url("/").toString(),
                model = "gpt-4o",
                maxToolRounds = 4
            )
        )
        events = RecordingAgentEvents()
        engine = AgentEngine(
            appContext = context,
            events = events,
            historyRepository = ChatHistoryRepository(context, "mcp-loop-test-chats"),
            settingsProvider = { repository.load() },
            clientProvider = { LLMClient(apiKey = "test-key", baseUrl = model.url("/").toString()) },
            workingDirRoot = workDir.absolutePath
        )
        engine.sessionId = "mcp-loop-test"
        connector.disconnect()
    }

    @After
    fun tearDown() {
        connector.disconnect()
        ToolRegistry.clearAllDynamicTools()
        model.shutdown()
        mcp.shutdown()
        workDir.deleteRecursively()
        actionLog.delete()
    }

    private fun mcpUrl() = mcp.url("/s/SECRETPATH/mcp").toString()

    private fun enqueueCall(name: String, args: kotlinx.serialization.json.JsonObject, id: String = "call_1") {
        val encoded = args.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        model.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                  {"id":"$id","type":"function","function":{"name":"$name","arguments":"$encoded"}}]}}]}"""
            )
        )
    }

    private fun enqueueReply() {
        model.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Done."}}]}"""))
    }

    private fun lastModelRequest(): String {
        var body = ""
        repeat(model.requestCount) { body = model.takeRequest().body.readUtf8() }
        return body
    }

    private fun addArgs(token: String = "tok-123") = buildJsonObject {
        put("action", "add")
        put("name", "Docs")
        put("url", mcpUrl())
        put("token", token)
        put("reason", "You asked me to connect your docs server.")
    }

    @Test
    fun `adding asks, saves, offers the tools and keeps the token out of the log`() = runTest {
        fake.requiredAuth = "Bearer tok-123"
        enqueueCall("manage_mcp_servers", addArgs())
        enqueueReply()

        engine.run(AgentMode.OPERATOR)

        val prompt = events.confirmationRequests.single()
        assertTrue(prompt, prompt.startsWith("manage_mcp_servers:"))
        assertTrue(prompt, prompt.contains("Address: ${mcpUrl()}"))
        assertTrue(prompt, prompt.contains("Sends a token: yes"))
        assertFalse(prompt, prompt.contains("tok-123"))

        val saved = connector.servers().single()
        assertEquals("tok-123", saved.token)
        assertTrue(ToolRegistry.contains("mcp_docs__search"))
        assertTrue(lastModelRequest().contains("mcp_docs__search"))

        val log = actionLog.readText()
        assertTrue(log, log.contains("manage_mcp_servers\t(approved) add \"Docs\""))
        assertFalse(log, log.contains("tok-123"))
        assertFalse(log, log.contains("SECRETPATH"))
    }

    @Test
    fun `declining adds nothing and tells the model`() = runTest {
        events.confirmationAnswer = false
        enqueueCall("manage_mcp_servers", addArgs())
        enqueueReply()

        engine.run(AgentMode.OPERATOR)

        assertTrue(connector.servers().isEmpty())
        assertTrue(lastModelRequest().contains("declined"))
        assertTrue(actionLog.readText().contains("manage_mcp_servers\t(denied)"))
    }

    @Test
    fun `adding asks even in a chat that skips approvals, switching off does not`() = runTest {
        engine.skipProtections = true
        enqueueCall("manage_mcp_servers", addArgs(token = ""))
        enqueueReply()
        engine.run(AgentMode.OPERATOR)
        assertEquals(1, events.confirmationRequests.size)

        val id = connector.servers().single().id
        enqueueCall(
            "manage_mcp_servers",
            buildJsonObject {
                put("action", "set_enabled")
                put("server_id", id)
                put("enabled", false)
            }
        )
        enqueueReply()
        engine.run(AgentMode.OPERATOR)

        assertEquals(1, events.confirmationRequests.size)
        assertFalse(connector.servers().single().enabled)
        assertTrue(actionLog.readText().contains("(pre-approved) switch off \"Docs\""))
    }

    @Test
    fun `listing shows ids and tools but no token, header value or address path`() = runTest {
        connector.save(null, "Docs", mcpUrl(), "tok-123", mapOf("X-Api-Key" to "key-456"), strings())
        enqueueCall(com.gotcha.tools.McpServerTool.LIST, buildJsonObject {})
        enqueueReply()

        engine.run(AgentMode.MONITOR)

        val body = lastModelRequest()
        assertTrue(body, body.contains("server_id: ${connector.servers().single().id}"))
        assertTrue(body, body.contains("token: set"))
        assertTrue(body, body.contains("headers: X-Api-Key"))
        assertTrue(body, body.contains("mcp_docs__search"))
        assertFalse(body, body.contains("tok-123"))
        assertFalse(body, body.contains("key-456"))
        assertFalse(body, body.contains("SECRETPATH"))
    }

    @Test
    fun `managing is not offered in Monitor`() = runTest {
        enqueueCall("manage_mcp_servers", addArgs())
        enqueueReply()

        engine.run(AgentMode.MONITOR)

        assertTrue(events.confirmationRequests.isEmpty())
        assertTrue(connector.servers().isEmpty())
    }

    private fun strings() = context.stringLookup()
}
