package com.gotcha.tools

import com.gotcha.connectors.mcp.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation and redaction behind `manage_mcp_servers`: pure, no connector involved. */
class McpServerToolTest {

    private val docs = McpServerConfig(
        id = "s1",
        name = "Docs",
        url = "https://mcp.example.com/api/s/SECRETPATH/mcp",
        token = "tok-123",
        headers = mapOf("X-Api-Key" to "key-456")
    )
    private val servers = listOf(docs)

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun parse(json: String) = McpServerTool.parse(obj(json), servers)

    private fun error(json: String): String {
        val result = parse(json)
        assertTrue("expected a refusal for $json", result.isFailure)
        return result.exceptionOrNull()!!.message!!
    }

    // ---- add ----

    @Test
    fun `add takes a name, an address, a token and headers`() {
        val request = parse(
            """{"action":"add","name":" Wiki ","url":"https://wiki.example.org/mcp","token":"t",
               "headers":{"X-Team":"a"}}"""
        ).getOrThrow() as McpServerTool.Request.Add
        assertEquals("Wiki", request.name)
        assertEquals("https://wiki.example.org/mcp", request.url)
        assertEquals("t", request.token)
        assertEquals(mapOf("X-Team" to "a"), request.headers)
        assertTrue(McpServerTool.alwaysAsk(request))
    }

    @Test
    fun `add refuses a missing name, a bad address, bad headers and a duplicate`() {
        assertTrue(error("""{"action":"add","url":"https://a.example/mcp"}""").contains("'name'"))
        assertTrue(error("""{"action":"add","name":"A"}""").contains("'url'"))
        assertTrue(error("""{"action":"add","name":"A","url":"not a url"}""").contains("'url'"))
        assertTrue(error("""{"action":"add","name":"A","url":"ftp://a.example"}""").contains("'url'"))
        assertTrue(error("""{"action":"add","name":"A","url":"https://a.example","headers":"x"}""").contains("headers"))
        assertTrue(
            error("""{"action":"add","name":"A","url":"https://a.example","headers":{"Bad Name":"v"}}""")
                .contains("header name")
        )
        assertTrue(error("""{"action":"add","name":"A","url":"${docs.url}/"}""").contains("already added"))
        assertTrue(error("""{"action":"add","name":"${"x".repeat(61)}","url":"https://a.example"}""").contains("60"))
    }

    // ---- edit ----

    @Test
    fun `renaming keeps the token and headers and need not ask in a skipping chat`() {
        val request = parse("""{"action":"edit","server_id":"s1","name":"Team docs"}""").getOrThrow()
            as McpServerTool.Request.Edit
        assertEquals("Team docs", request.name)
        assertEquals(docs.token, request.token)
        assertEquals(docs.headers, request.headers)
        assertFalse(request.droppedSecrets)
        assertFalse(McpServerTool.alwaysAsk(request))
    }

    @Test
    fun `a new path on the same host keeps the secrets but still always asks`() {
        val request = parse("""{"action":"edit","server_id":"s1","url":"https://mcp.example.com/v2"}""")
            .getOrThrow() as McpServerTool.Request.Edit
        assertEquals(docs.token, request.token)
        assertFalse(request.droppedSecrets)
        assertTrue(McpServerTool.alwaysAsk(request))
    }

    @Test
    fun `moving to another host drops the saved token and headers`() {
        val request = parse("""{"action":"edit","server_id":"s1","url":"https://evil.example/mcp"}""")
            .getOrThrow() as McpServerTool.Request.Edit
        assertEquals("", request.token)
        assertEquals(emptyMap<String, String>(), request.headers)
        assertTrue(request.droppedSecrets)
        assertTrue(McpServerTool.alwaysAsk(request))
    }

    @Test
    fun `moving host with new secrets uses only the new ones`() {
        val request = parse(
            """{"action":"edit","server_id":"s1","url":"https://new.example/mcp","token":"new","headers":{}}"""
        ).getOrThrow() as McpServerTool.Request.Edit
        assertEquals("new", request.token)
        assertEquals(emptyMap<String, String>(), request.headers)
        assertFalse(request.droppedSecrets)
    }

    @Test
    fun `an empty token removes it and an edit needs something to change`() {
        val request = parse("""{"action":"edit","server_id":"s1","token":""}""").getOrThrow()
            as McpServerTool.Request.Edit
        assertEquals("", request.token)
        assertTrue(error("""{"action":"edit","server_id":"s1"}""").contains("at least one"))
    }

    // ---- remove, set_enabled, unknowns ----

    @Test
    fun `remove and set_enabled need a known server and set_enabled a boolean`() {
        assertTrue(parse("""{"action":"remove","server_id":"s1"}""").getOrThrow() is McpServerTool.Request.Remove)
        val off = parse("""{"action":"set_enabled","server_id":"s1","enabled":false}""").getOrThrow()
        assertEquals(McpServerTool.Request.SetEnabled(docs, false), off)
        assertFalse(McpServerTool.alwaysAsk(off))
        assertTrue(error("""{"action":"set_enabled","server_id":"s1","enabled":"no"}""").contains("'enabled'"))
        assertTrue(error("""{"action":"remove","server_id":"nope"}""").contains("list_mcp_servers"))
        assertTrue(error("""{"action":"remove"}""").contains("'server_id'"))
        assertTrue(error("""{"action":"explode","server_id":"s1"}""").contains("unknown action"))
        assertTrue(error("""{}""").contains("'action'"))
    }

    // ---- what leaves the tool ----

    @Test
    fun `addresses are shown without their path or query`() {
        assertEquals("https://mcp.example.com/…", McpServerTool.redactedAddress(docs.url))
        assertEquals("http://192.168.1.5:8123/…", McpServerTool.redactedAddress("http://192.168.1.5:8123/mcp?key=x"))
        assertEquals("https://a.example", McpServerTool.redactedAddress("https://a.example/"))
    }

    @Test
    fun `audit lines name the host and never a secret or the path`() {
        val lines = listOf(
            """{"action":"add","name":"Docs2","url":"https://x.example/s/SECRETPATH","token":"tok-123"}""",
            """{"action":"edit","server_id":"s1","url":"https://y.example/mcp"}""",
            """{"action":"remove","server_id":"s1"}""",
            """{"action":"set_enabled","server_id":"s1","enabled":true}"""
        ).map { McpServerTool.auditLine(parse(it).getOrThrow()) }
        assertEquals("add \"Docs2\" (x.example)", lines[0])
        assertEquals("edit \"Docs\" (mcp.example.com → y.example)", lines[1])
        lines.forEach { line ->
            assertFalse(line, line.contains("SECRETPATH") || line.contains("tok-123") || line.contains("key-456"))
        }
    }

    @Test
    fun `a pending request can be taken once`() {
        val pending = McpServerTool.PendingRequests()
        val id = pending.put(McpServerTool.Pending(McpServerTool.Request.Remove(docs), null))
        assertEquals(McpServerTool.Request.Remove(docs), pending.take(id)?.request)
        assertNull(pending.take(id))
    }
}
