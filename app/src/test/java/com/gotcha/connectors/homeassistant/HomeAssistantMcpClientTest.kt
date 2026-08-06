package com.gotcha.connectors.homeassistant

import com.gotcha.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The MCP client speaks JSON-RPC over Streamable HTTP to a Home Assistant
 * endpoint. Exercised against a MockWebServer standing in for HA's stateless
 * `/api/mcp` handler, which answers every POST with a single JSON object.
 */
class HomeAssistantMcpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HomeAssistantMcpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HomeAssistantMcpClient()
        ToolRegistry.clearDynamicTools()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ToolRegistry.clearDynamicTools()
    }

    private fun endpoint(): String = server.url("/api/mcp").toString()

    @Test
    fun `initialize sends the handshake and parses the result`() = runTest {
        server.enqueue(MockResponse().setBody(initJson))

        val result = client.initialize(endpoint(), "tok-1")

        assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals(
            "home-assistant",
            result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content
        )
        val sent = server.takeRequest()
        assertEquals("Bearer tok-1", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"method\":\"initialize\""))
        assertTrue(body.contains("\"protocolVersion\":\"2025-06-18\""))
    }

    @Test
    fun `listTools parses server-defined tool schemas`() = runTest {
        server.enqueue(MockResponse().setBody(listToolsJson))

        val tools = client.listTools(endpoint(), "tok-1")

        assertEquals(3, tools.size)
        assertEquals("HassGetState", tools[0].name)
        assertEquals("Ask for the state of an entity", tools[0].description)
        assertEquals(
            "string",
            tools[0].inputSchema["properties"]?.jsonObject
                ?.get("entity_id")?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
        assertEquals("HassTurnOn", tools[1].name)
        // Missing inputSchema falls back to an empty object schema.
        assertEquals("object", tools[2].inputSchema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `callTool sends arguments and returns text content`() = runTest {
        server.enqueue(MockResponse().setBody(callOkJson))

        val result = client.callTool(
            endpoint(),
            "tok-1",
            "HassTurnOn",
            buildJsonObject { put("name", "Office Light") }
        )

        assertTrue(result.success)
        assertEquals("Turned on the office light", result.text)
        val sent = server.takeRequest()
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"name\":\"HassTurnOn\""))
        assertTrue(body.contains("Office Light"))
    }

    @Test
    fun `callTool reports server-side isError results`() = runTest {
        server.enqueue(MockResponse().setBody(callErrorJson))

        val result = client.callTool(endpoint(), "tok-1", "HassGetState", buildJsonObject {})

        assertFalse(result.success)
        assertEquals("Entity not found", result.text)
    }

    @Test
    fun `jsonrpc error surfaces the server message`() = runTest {
        server.enqueue(MockResponse().setBody(rpcErrorJson))

        try {
            client.listTools(endpoint(), "tok-1")
            fail("expected McpRpcException")
        } catch (e: McpRpcException) {
            assertTrue(e.message!!.contains("Unknown tool"))
        }
    }

    @Test
    fun `http 401 maps to an actionable token error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid token"}"""))

        try {
            client.initialize(endpoint(), "bad-token")
            fail("expected McpHttpException")
        } catch (e: McpHttpException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("access token"))
        }
    }

    @Test
    fun `http 404 names the missing integration`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"not configured"}"""))

        try {
            client.initialize(endpoint(), "tok-1")
            fail("expected McpHttpException")
        } catch (e: McpHttpException) {
            assertEquals(404, e.code)
            assertTrue(e.message!!.contains("Model Context Protocol Server"))
        }
    }

    @Test
    fun `sse data frames are parsed defensively`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message\ndata: $sseToolsPayload\n\n")
        )

        val tools = client.listTools(endpoint(), "tok-1")

        assertTrue(tools.isEmpty())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"tools/list\""))
    }

    @Test
    fun `every request carries the bearer token`() = runTest {
        server.enqueue(MockResponse().setBody(callOkJson))

        client.callTool(endpoint(), "secret-token", "HassTurnOn", buildJsonObject {})

        val sent = server.takeRequest()
        assertEquals("Bearer secret-token", sent.getHeader("Authorization"))
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(sent.getHeader("Accept")!!.contains("application/json"))
    }

    @Test
    fun `mcpEndpoint normalises base urls`() {
        assertEquals(
            "http://ha.local:8123/api/mcp",
            HomeAssistantMcpClient.mcpEndpoint("http://ha.local:8123")
        )
        assertEquals(
            "http://ha.local:8123/api/mcp",
            HomeAssistantMcpClient.mcpEndpoint("http://ha.local:8123/")
        )
        assertEquals(
            "https://ha.example.com/hass/api/mcp",
            HomeAssistantMcpClient.mcpEndpoint("https://ha.example.com/hass")
        )
        // A full /api/mcp URL is accepted without double-appending.
        assertEquals(
            "https://ha.example.com/api/mcp",
            HomeAssistantMcpClient.mcpEndpoint("https://ha.example.com/api/mcp")
        )
    }

    private companion object {
        val initJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "protocolVersion":"2025-06-18",
              "capabilities":{},
              "serverInfo":{"name":"home-assistant","version":"2026.8"}
            }}
        """.trimIndent()

        val listToolsJson = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"HassGetState","description":"Ask for the state of an entity",
               "inputSchema":{"type":"object","properties":{"entity_id":{"type":"string"}},"required":["entity_id"]}},
              {"name":"HassTurnOn","description":"Turn on a device",
               "inputSchema":{"type":"object","properties":{"name":{"type":"string"}}}},
              {"name":"bare","description":"no schema at all"}
            ]}}
        """.trimIndent()

        val callOkJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "content":[{"type":"text","text":"Turned on the office light"}],
              "isError":false
            }}
        """.trimIndent()

        val callErrorJson = """
            {"jsonrpc":"2.0","id":1,"result":{
              "content":[{"type":"text","text":"Entity not found"}],
              "isError":true
            }}
        """.trimIndent()

        val rpcErrorJson = """
            {"jsonrpc":"2.0","id":1,"error":{
              "code":-32602,"message":"Unknown tool: HassNope"
            }}
        """.trimIndent()

        const val sseToolsPayload =
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""
    }
}
