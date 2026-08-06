package com.gotcha.connectors.homeassistant

import com.gotcha.connectors.CredentialStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) {
        map[connectorId] = blob
    }

    override fun clear(connectorId: String) {
        map.remove(connectorId)
    }
}

class HomeAssistantConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var connector: HomeAssistantConnector

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryCredentialStore()
        connector = HomeAssistantConnector(store, HomeAssistantMcpClient())
        ToolRegistry.clearDynamicTools()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ToolRegistry.clearDynamicTools()
    }

    private fun baseUrl(): String = server.url("/").toString()

    @Test
    fun `connect validates the server, stores credentials and registers tools`() = runTest {
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))

        val status = connector.connect(baseUrl(), "llat-1")

        assertTrue(status.contains("Connected to"))
        assertTrue(status.contains("3 Home Assistant tool(s)"))
        assertTrue(connector.isConnected())
        assertTrue(store.loadRaw("homeassistant")!!.contains("llat-1"))
        assertEquals(setOf("HassGetState", "HassTurnOn", "GetLiveContext"), connector.toolNames)

        // Server-defined tools are registered and routed.
        assertTrue(ToolRegistry.contains("HassGetState"))
        assertTrue(ToolRegistry.contains("HassTurnOn"))
        // Read-only classification feeds Monitor's slice.
        assertTrue("HassGetState" in ToolRegistry.monitorTools)
        assertTrue("GetLiveContext" in ToolRegistry.monitorTools)
        assertFalse("HassTurnOn" in ToolRegistry.monitorTools)
        val operatorNames = ToolRegistry.toolsForAgent(AgentMode.OPERATOR).map { it.function.name }
        assertTrue(operatorNames.containsAll(setOf("HassGetState", "HassTurnOn", "GetLiveContext")))
    }

    @Test
    fun `connect rejects a blank url or token without a network call`() = runTest {
        assertTrue(connector.connect("", "tok").contains("URL"))
        assertTrue(connector.connect("http://ha.local:8123", "  ").contains("token"))
        assertFalse(connector.isConnected())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `connect with a rejected token keeps the connector disconnected`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid token"}"""))

        val status = connector.connect(baseUrl(), "bad-token")

        assertTrue(status.contains("Could not connect"))
        assertFalse(connector.isConnected())
        assertNull(store.loadRaw("homeassistant"))
        assertTrue(ToolRegistry.dynamicTools.isEmpty())
    }

    @Test
    fun `callTool reaches the server with the stored credentials`() = runTest {
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))
        connector.connect(baseUrl(), "llat-1")
        server.enqueue(MockResponse().setBody(callOkJson))

        val result = connector.callTool(
            "HassGetState",
            buildJsonObject { put("entity_id", "light.office") }
        )

        assertTrue(result.success)
        assertEquals("The office light is on", result.text)
        // The call went to the same endpoint with the stored bearer token.
        val sent = server.takeRequest()
        assertEquals("Bearer llat-1", sent.getHeader("Authorization"))
    }

    @Test
    fun `disconnect clears credentials and unregisters tools`() = runTest {
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))
        connector.connect(baseUrl(), "llat-1")
        assertTrue(ToolRegistry.contains("HassTurnOn"))

        connector.disconnect()

        assertFalse(connector.isConnected())
        assertNull(store.loadRaw("homeassistant"))
        assertTrue(ToolRegistry.dynamicTools.isEmpty())
    }

    @Test
    fun `reloading from the store re-registers the cached tools`() = runTest {
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))
        connector.connect(baseUrl(), "llat-1")

        // A fresh connector (as after an app restart) loads the stored snapshot.
        val reloaded = HomeAssistantConnector(store, HomeAssistantMcpClient())

        assertTrue(reloaded.isConnected())
        assertEquals(setOf("HassGetState", "HassTurnOn", "GetLiveContext"), reloaded.toolNames)
        assertTrue(ToolRegistry.contains("HassGetState"))
        assertTrue(ToolRegistry.contains("HassTurnOn"))
        ToolRegistry.clearDynamicTools()
    }

    @Test
    fun `refreshTools re-reads the server tool list`() = runTest {
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))
        connector.connect(baseUrl(), "llat-1")
        // The server now exposes one fewer tool.
        server.enqueue(MockResponse().setBody(singleToolJson))

        val status = connector.refreshTools()

        assertTrue(status.contains("1 Home Assistant tool(s)"))
        assertEquals(setOf("HassGetState"), connector.toolNames)
        assertTrue(ToolRegistry.contains("HassGetState"))
        assertFalse(ToolRegistry.contains("HassTurnOn"))
    }

    @Test
    fun `isReadOnlyTool classifies assist intents conservatively`() {
        assertTrue(HomeAssistantConnector.isReadOnlyTool("HassGetState"))
        assertTrue(HomeAssistantConnector.isReadOnlyTool("GetLiveContext"))
        assertTrue(HomeAssistantConnector.isReadOnlyTool("HassClimateGetTemperature"))
        assertFalse(HomeAssistantConnector.isReadOnlyTool("HassTurnOn"))
        assertFalse(HomeAssistantConnector.isReadOnlyTool("HassLightSet"))
        assertFalse(HomeAssistantConnector.isReadOnlyTool("HassMediaPause"))
    }

    @Test
    fun `statusLine shows the host when connected`() = runTest {
        assertEquals("Not connected", connector.statusLine())
        server.enqueue(MockResponse().setBody(initJson))
        server.enqueue(MockResponse().setBody(toolsJson))
        connector.connect(baseUrl(), "llat-1")
        assertTrue(connector.statusLine().startsWith("Connected to"))
        assertTrue(connector.statusLine().contains("localhost"))
    }

    private companion object {
        val initJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "protocolVersion":"2025-06-18",
              "capabilities":{},
              "serverInfo":{"name":"home-assistant","version":"2026.8"}
            }}
        """.trimIndent()

        val toolsJson = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"HassGetState","description":"Ask for the state of an entity",
               "inputSchema":{"type":"object","properties":{"entity_id":{"type":"string"}}}},
              {"name":"HassTurnOn","description":"Turn on a device",
               "inputSchema":{"type":"object","properties":{"name":{"type":"string"}}}},
              {"name":"GetLiveContext","description":"Snapshot of the current assist context",
               "inputSchema":{"type":"object","properties":{}}}
            ]}}
        """.trimIndent()

        val singleToolJson = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"HassGetState","description":"Ask for the state of an entity",
               "inputSchema":{"type":"object","properties":{}}}
            ]}}
        """.trimIndent()

        val callOkJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "content":[{"type":"text","text":"The office light is on"}],
              "isError":false
            }}
        """.trimIndent()
    }
}
