package com.gotcha.connectors.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.connectors.ConnectorRegistry
import com.gotcha.connectors.CredentialStore
import com.gotcha.i18n.stringLookup
import com.gotcha.tools.AgentMode
import com.gotcha.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class MemoryStore : CredentialStore {
    val map = mutableMapOf<String, String>()
    override fun loadRaw(connectorId: String): String? = map[connectorId]
    override fun saveRaw(connectorId: String, blob: String) {
        map[connectorId] = blob
    }

    override fun clear(connectorId: String) {
        map.remove(connectorId)
    }
}

/** The MCP servers connector: storing servers and offering their tools (issue #130). */
@RunWith(RobolectricTestRunner::class)
class McpServersConnectorTest {

    /** Messages are string resources; Robolectric reads the English ones. */
    private val strings = ApplicationProvider.getApplicationContext<Context>().stringLookup()

    private val server = MockWebServer()
    private val fake = FakeMcpServer()
    private val store = MemoryStore()
    private lateinit var connector: McpServersConnector

    @Before
    fun setUp() {
        ToolRegistry.clearAllDynamicTools()
        server.dispatcher = fake
        server.start()
        connector = McpServersConnector(store)
    }

    @After
    fun tearDown() {
        server.shutdown()
        ToolRegistry.clearAllDynamicTools()
    }

    private fun url() = server.url("/mcp").toString()

    private suspend fun add(name: String = "Docs", token: String = ""): McpServerConfig {
        val (saved, message) = connector.save(null, name, url(), token, emptyMap(), strings)
        assertTrue(message, saved)
        return connector.servers().last()
    }

    @Test
    fun `saving a server stores it and offers its tools, read-only ones to Monitor too`() = runTest {
        val (saved, message) = connector.save(null, "Docs", url(), "", emptyMap(), strings)

        assertTrue(saved)
        assertEquals("Connected to Docs: 3 tools.", message)
        assertEquals(setOf("mcp_docs__search", "mcp_docs__create_page", "mcp_docs__delete_page"), connector.toolNames)
        assertEquals(connector.toolNames, ToolRegistry.dynamicToolsByOwner["mcp"])
        assertTrue(ToolRegistry.isAllowedForAgent("mcp_docs__search", AgentMode.MONITOR))
        assertFalse(ToolRegistry.isAllowedForAgent("mcp_docs__create_page", AgentMode.MONITOR))
        assertTrue(ToolRegistry.isAllowedForAgent("mcp_docs__create_page", AgentMode.OPERATOR))
        assertTrue(
            ToolRegistry.definition("mcp_docs__search")!!.function.description.startsWith("[MCP server \"Docs\"]")
        )
        assertTrue(store.map.containsKey("mcp"))
    }

    @Test
    fun `connected message carries the cleartext warning when one is due`() {
        val config = McpServerConfig(
            id = "x",
            name = "Remote",
            url = "http://mcp.example.com/mcp"
        )
        assertEquals(
            "Connected to mcp.example.com: 0 tools. Use https for a server outside your local network. " +
                "A token or header sent over http can be read on the way.",
            connector.connectedMessage("http://mcp.example.com/mcp", "t", emptyMap(), config, 0, strings)
        )
        assertEquals(
            "Connected to mcp.example.com: 0 tools.",
            connector.connectedMessage("https://mcp.example.com/mcp", "t", emptyMap(), config, 0, strings)
        )
    }

    @Test
    fun `a server that cannot be reached is not saved, and says why`() = runTest {
        fake.requiredAuth = "Bearer right"

        val (saved, message) = connector.save(null, "Docs", url(), "wrong", emptyMap(), strings)

        assertFalse(saved)
        assertEquals("The server refused the token or headers (HTTP 401). Check them and try again.", message)
        assertTrue(connector.servers().isEmpty())
        assertTrue(ToolRegistry.dynamicTools.isEmpty())
    }

    @Test
    fun `invalid input is refused before anything is sent`() = runTest {
        assertEquals(
            "Enter a full URL starting with http:// or https://.",
            connector.save(null, "Docs", "not a url", "", emptyMap(), strings).second
        )
        assertEquals("Enter a name for the server.", connector.save(null, " ", url(), "", emptyMap(), strings).second)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a tool call reaches its server under the server's own name`() = runTest {
        add()

        val result = connector.callTool("mcp_docs__search", buildJsonObject { put("q", "x") })

        assertTrue(result.success)
        assertEquals("found 3 pages", result.message)
        assertEquals("tools/call", fake.seen.last().method)
    }

    @Test
    fun `switching a server off withholds its tools, and on brings them back`() = runTest {
        val docs = add()

        connector.setServerEnabled(docs.id, false)
        assertTrue(connector.toolNames.isEmpty())
        assertNull(ToolRegistry.dynamicToolsByOwner["mcp"])
        assertFalse(connector.callTool("mcp_docs__search", buildJsonObject {}).success)

        connector.setServerEnabled(docs.id, true)
        assertEquals(3, connector.toolNames.size)
    }

    @Test
    fun `removing a server removes its tools and its stored entry`() = runTest {
        val docs = add()

        connector.remove(docs.id)

        assertTrue(connector.toolNames.isEmpty())
        assertTrue(ToolRegistry.dynamicTools.isEmpty())
        assertFalse(store.map.containsKey("mcp"))
    }

    @Test
    fun `two servers with the same tool names get distinct prefixes`() = runTest {
        add("Docs")
        add("Docs")

        assertEquals(6, connector.toolNames.size)
        assertTrue("mcp_docs2__search" in connector.toolNames)
    }

    @Test
    fun `tools come back from the store after a restart, before any network call`() = runTest {
        add()
        ToolRegistry.clearAllDynamicTools()
        val requests = server.requestCount

        val restarted = McpServersConnector(store)

        assertEquals(3, restarted.toolNames.size)
        assertEquals(restarted.toolNames, ToolRegistry.dynamicTools)
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun `a failed refresh keeps the tools and shows the error`() = runTest {
        val docs = add()
        server.shutdown()

        val message = connector.refresh(docs.id, strings)

        assertTrue(message, message.startsWith("Couldn't reach the server"))
        assertEquals(message, connector.server(docs.id)!!.lastError)
        assertEquals(3, connector.toolNames.size)
    }

    @Test
    fun `the connector switch hides every server's tools`() = runTest {
        add()

        assertTrue("mcp_docs__search" in ConnectorRegistry.hiddenToolNamesFor(emptySet()))
        assertFalse("mcp_docs__search" in ConnectorRegistry.hiddenToolNamesFor(setOf("mcp")))
    }

    @Test
    fun `status line counts servers that are on and their tools`() = runTest {
        val docs = add()
        add("Wiki")
        connector.setServerEnabled(docs.id, false)

        assertEquals("1 of 2 servers on · 3 tools", connector.statusLine(strings))
    }
}
