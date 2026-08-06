package com.gotcha.connectors.homeassistant

import com.gotcha.connectors.CredentialStore
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

private class RouterMemoryStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) {
        map[connectorId] = blob
    }

    override fun clear(connectorId: String) {
        map.remove(connectorId)
    }
}

class HomeAssistantToolsTest {

    private lateinit var server: MockWebServer
    private lateinit var connector: HomeAssistantConnector
    private lateinit var router: HomeAssistantTools

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ToolRegistry.clearDynamicTools()
        connector = HomeAssistantConnector(RouterMemoryStore(), HomeAssistantMcpClient())
        router = HomeAssistantTools { connector }
    }

    @After
    fun tearDown() {
        server.shutdown()
        ToolRegistry.clearDynamicTools()
    }

    private fun connect() = runTest {
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))
        connector.connect(server.url("/").toString(), "llat-1")
    }

    @Test
    fun `toolNames follow the connector's registered set`() = runTest {
        assertTrue(router.toolNames.isEmpty())
        connect()
        assertEquals(setOf("HassGetState", "HassTurnOn"), router.toolNames)
    }

    @Test
    fun `execute routes a tool call to the server`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(callOkJson))

        val result = router.execute(
            "HassGetState",
            buildJsonObject { put("entity_id", "light.office") }
        )

        assertTrue(result.success)
        assertEquals("The office light is on", result.message)
    }

    @Test
    fun `execute surfaces server-side errors as tool errors`() = runTest {
        connect()
        server.enqueue(MockResponse().setBody(callErrorJson))

        val result = router.execute("HassGetState", buildJsonObject {})

        assertFalse(result.success)
        assertEquals("Entity not found", result.message)
    }

    @Test
    fun `execute without a connection asks the user to connect`() = runTest {
        val result = router.execute("HassGetState", buildJsonObject {})

        assertFalse(result.success)
        assertTrue(result.message.contains("not connected"))
    }

    @Test
    fun `execute with a missing backend reports the failure`() = runTest {
        val deadRouter = HomeAssistantTools { null }
        val result = deadRouter.execute("HassGetState", buildJsonObject {})
        assertFalse(result.success)
        assertTrue(result.message.contains("not connected"))
    }

    private companion object {
        val initJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "protocolVersion":"2025-06-18",
              "capabilities":{},
              "serverInfo":{"name":"home-assistant"}
            }}
        """.trimIndent()

        val toolsJson = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"HassGetState","description":"Ask for the state of an entity",
               "inputSchema":{"type":"object","properties":{"entity_id":{"type":"string"}}}},
              {"name":"HassTurnOn","description":"Turn on a device",
               "inputSchema":{"type":"object","properties":{"name":{"type":"string"}}}}
            ]}}
        """.trimIndent()

        val callOkJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "content":[{"type":"text","text":"The office light is on"}],
              "isError":false
            }}
        """.trimIndent()

        val callErrorJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "content":[{"type":"text","text":"Entity not found"}],
              "isError":true
            }}
        """.trimIndent()
    }
}
