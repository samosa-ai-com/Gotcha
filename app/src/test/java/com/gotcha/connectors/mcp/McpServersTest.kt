package com.gotcha.connectors.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Naming, URL rules and schema clean-up for user-added MCP servers (issue #130). */
class McpServersTest {

    private val validName = Regex("^[a-zA-Z0-9_-]{1,64}$")

    private fun tool(name: String) = McpTool(name = name, inputSchema = buildJsonObject { put("type", "object") })

    private fun server(id: String, name: String) = McpServerConfig(id = id, name = name, url = "https://x")

    @Test
    fun `tool names are prefixed by the server and fit the chat APIs`() {
        val names = McpToolNames.exposedNames("docs", listOf(tool("search"), tool("pages.get"), tool("x".repeat(80))))

        assertEquals(listOf("search", "pages.get", "x".repeat(80)), names.values.toList())
        assertTrue("mcp_docs__search" in names)
        assertTrue("mcp_docs__pages_get" in names)
        names.keys.forEach { assertTrue(it, validName.matches(it)) }
    }

    @Test
    fun `names that clash after cleaning stay distinct`() {
        val names = McpToolNames.exposedNames("docs", listOf(tool("a.b"), tool("a_b"), tool("a b")))

        assertEquals(3, names.size)
        assertEquals(listOf("a.b", "a_b", "a b"), names.values.toList())
        names.keys.forEach { assertTrue(it, validName.matches(it)) }
    }

    @Test
    fun `server slugs are short, clean and unique`() {
        assertEquals("my_docs", McpToolNames.slug("My Docs!"))
        assertEquals("server", McpToolNames.slug("日本"))
        val slugs = McpToolNames.slugs(listOf(server("1", "Docs"), server("2", "docs"), server("3", "DOCS")))
        assertEquals(listOf("docs", "docs2", "docs3"), listOf("1", "2", "3").map { slugs.getValue(it) })
    }

    @Test
    fun `http is fine locally or with nothing secret, and warns otherwise`() {
        assertNull(validateMcpServer("https://mcp.example.com/mcp", "t", emptyMap()))
        assertNull(validateMcpServer("http://192.168.1.20:8080/mcp", "t", emptyMap()))
        assertNull(validateMcpServer("http://homeassistant.local:8123/mcp", "t", emptyMap()))
        assertNull(validateMcpServer("http://mcp.example.com/mcp", "", emptyMap()))
        // Both schemes are accepted now (issue #154): cleartext with a secret
        // validates, but still earns the warning.
        assertNull(validateMcpServer("http://mcp.example.com/mcp", "t", emptyMap()))
        assertNull(validateMcpServer("http://8.8.8.8/mcp", "", mapOf("X-Api-Key" to "k")))
        assertFalse(isCleartextWithSecret("https://mcp.example.com/mcp", "t", emptyMap()))
        assertFalse(isCleartextWithSecret("http://192.168.1.20:8080/mcp", "t", emptyMap()))
        assertFalse(isCleartextWithSecret("http://mcp.example.com/mcp", "", emptyMap()))
        assertTrue(isCleartextWithSecret("http://mcp.example.com/mcp", "t", emptyMap()))
        assertTrue(isCleartextWithSecret("http://8.8.8.8/mcp", "", mapOf("X-Api-Key" to "k")))
        assertFalse(isCleartextWithSecret("not a url", "t", emptyMap()))
        assertEquals(McpConfigProblem.BAD_URL, validateMcpServer("mcp.example.com", "", emptyMap()))
        assertEquals(McpConfigProblem.BAD_URL, validateMcpServer("ftp://mcp.example.com", "", emptyMap()))
        assertEquals(McpConfigProblem.BAD_HEADER, validateMcpServer("https://x.com", "", mapOf("Bad Name" to "v")))
        assertEquals(McpConfigProblem.BAD_TOKEN, validateMcpServer("https://x.com", "tökén", emptyMap()))
        assertEquals(McpConfigProblem.BAD_TOKEN, validateMcpServer("https://x.com", "", mapOf("X-A" to "a\nb")))
    }

    @Test
    fun `local hosts are recognised without resolving names`() {
        listOf(
            "localhost", "10.0.0.2", "172.20.1.1", "192.168.0.9", "127.0.0.1",
            "100.101.102.103", "box.lan", "[fd12::1]", "[::1]", "fe80::1"
        ).forEach { assertTrue(it, isLocalHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "example.com", "local.example.com", "[2001:db8::1]")
            .forEach { assertFalse(it, isLocalHost(it)) }
    }

    @Test
    fun `header lines parse and format back`() {
        val parsed = parseHeaderLines("X-Api-Key: abc\n\n  X-Team : core  ")
        assertEquals(mapOf("X-Api-Key" to "abc", "X-Team" to "core"), parsed)
        assertEquals("X-Api-Key: abc\nX-Team: core", formatHeaderLines(parsed!!))
        assertNull(parseHeaderLines("no colon here"))
        assertEquals(emptyMap<String, String>(), parseHeaderLines("  "))
    }

    @Test
    fun `schemas become object schemas with properties, and non-objects are refused`() {
        val bare = normalizedInputSchema(buildJsonObject {})!!
        assertEquals("\"object\"", bare["type"].toString())
        assertTrue("properties" in bare)
        val full = Json.parseToJsonElement(
            """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""
        ).jsonObject
        assertEquals(full, normalizedInputSchema(full))
        assertTrue("properties" in normalizedInputSchema(buildJsonObject { put("type", "object") })!!)
        assertNull(normalizedInputSchema(buildJsonObject { put("type", "string") }))
    }

    @Test
    fun `the endpoint carries the bearer token beside the custom headers`() {
        val config = McpServerConfig(
            id = "1",
            name = "n",
            url = "https://x",
            token = " t ",
            headers = mapOf("X-A" to "b")
        )
        assertEquals(mapOf("X-A" to "b", "Authorization" to "Bearer t"), config.endpoint().headers)
        assertEquals(emptyMap<String, String>(), config.copy(token = "", headers = emptyMap()).endpoint().headers)
    }
}
