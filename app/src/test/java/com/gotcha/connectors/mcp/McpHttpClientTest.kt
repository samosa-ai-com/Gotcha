package com.gotcha.connectors.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The Streamable HTTP client for user-added MCP servers (issue #130). */
class McpHttpClientTest {

    private val server = MockWebServer()
    private val fake = FakeMcpServer()
    private val client = McpHttpClient()

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun endpoint(headers: Map<String, String> = emptyMap()) =
        McpEndpoint(server.url("/mcp").toString(), headers)

    private suspend fun failureOf(block: suspend () -> Unit): McpException {
        try {
            block()
        } catch (e: McpException) {
            return e
        }
        fail("expected an McpException")
        error("unreachable")
    }

    @Test
    fun `initialize sends initialized, then the session id and protocol version on every request`() = runTest {
        val session = client.session(endpoint())

        val info = session.initialize()
        session.listTools()

        assertEquals(McpServerInfo("Docs", "2.1", "2025-06-18"), info)
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "tools/list"),
            fake.seen.map { it.method }
        )
        val first = fake.seen.first()
        assertEquals(null, first.sessionId)
        assertEquals(null, first.protocolVersion)
        fake.seen.drop(1).forEach {
            assertEquals("s-1", it.sessionId)
            assertEquals("2025-06-18", it.protocolVersion)
        }
    }

    @Test
    fun `tools come from every page, with read-only from the server's annotation`() = runTest {
        val tools = client.session(endpoint()).listTools()

        assertEquals(listOf("search", "create_page", "delete_page"), tools.map { it.name })
        assertEquals(listOf(true, false, false), tools.map { it.readOnly })
        assertEquals("Search the docs", tools.first().description)
    }

    @Test
    fun `a first call initialises on its own`() = runTest {
        val out = client.session(endpoint()).callTool("search", buildJsonObject { put("q", "x") })

        assertEquals(McpToolOutput(isError = false, text = "found 3 pages"), out)
        assertEquals("initialize", fake.seen.first().method)
    }

    @Test
    fun `a dropped session is re-initialised once and the request retried`() = runTest {
        val session = client.session(endpoint())
        session.initialize()
        fake.expireSession()

        val out = session.callTool("search", buildJsonObject {})

        assertEquals("found 3 pages", out.text)
        assertEquals(
            listOf(
                "initialize",
                "notifications/initialized",
                "tools/call",
                "initialize",
                "notifications/initialized",
                "tools/call"
            ),
            fake.seen.map { it.method }
        )
        assertEquals("s-2", fake.seen.last().sessionId)
    }

    @Test
    fun `event-stream replies skip notifications and server requests`() = runTest {
        fake.sse = true

        val tools = client.session(endpoint()).listTools()

        assertEquals(3, tools.size)
    }

    @Test
    fun `auth and custom headers go on every request, and a refusal is AUTH`() = runTest {
        fake.requiredAuth = "Bearer right"
        val ok = client.session(endpoint(mapOf("Authorization" to "Bearer right", "X-Api-Key" to "k")))
        ok.listTools()
        assertTrue(fake.seen.all { it.auth == "Bearer right" && it.headers["X-Api-Key"] == "k" })

        val e = failureOf { client.session(endpoint(mapOf("Authorization" to "Bearer wrong"))).initialize() }
        assertEquals(McpFailure.AUTH, e.kind)
        assertEquals(401, e.httpCode)
    }

    @Test
    fun `tool output flattens content and keeps the error flag`() = runTest {
        fake.callResult = """{"isError":true,"content":[{"type":"text","text":"no such page"},""" +
            """{"type":"image","mimeType":"image/png","data":"AAAA"},""" +
            """{"type":"resource","resource":{"uri":"file:///a","text":"body"}}]}"""

        val out = client.session(endpoint()).callTool("create_page", buildJsonObject {})

        assertTrue(out.isError)
        assertEquals("no such page\n[image image/png not shown]\nbody", out.text)
    }

    @Test
    fun `structured content stands in when there is no text`() = runTest {
        fake.callResult = """{"content":[],"structuredContent":{"count":3}}"""

        assertEquals("""{"count":3}""", client.session(endpoint()).callTool("search", buildJsonObject {}).text)
    }

    @Test
    fun `a JSON-RPC error is RPC with the server's message`() = runTest {
        fake.callError = "Unknown tool: nope"

        val e = failureOf { client.session(endpoint()).callTool("nope", buildJsonObject {}) }

        assertEquals(McpFailure.RPC, e.kind)
        assertEquals("Unknown tool: nope", e.detail)
    }

    @Test
    fun `a reply that stalls half-way is NETWORK, not a crash`() = runTest {
        val stalled = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n")
                    .throttleBody(4, 5, TimeUnit.SECONDS)
            }
            start()
        }
        val impatient = McpHttpClient(OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build())
        try {
            val e = failureOf { impatient.session(McpEndpoint(stalled.url("/mcp").toString())).initialize() }
            assertEquals(McpFailure.NETWORK, e.kind)
        } finally {
            stalled.shutdown()
        }
    }

    @Test
    fun `nothing at the URL is NOT_FOUND, an old SSE server is OLD_TRANSPORT`() = runTest {
        val plain = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
            }
            start()
        }
        val old = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.method == "GET") {
                    MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("event: endpoint\ndata: /messages?session=1\n\n")
                } else {
                    MockResponse().setResponseCode(405)
                }
            }
            start()
        }
        try {
            val notFound = failureOf { client.session(McpEndpoint(plain.url("/mcp").toString())).initialize() }
            assertEquals(McpFailure.NOT_FOUND, notFound.kind)
            val oldTransport = failureOf { client.session(McpEndpoint(old.url("/sse").toString())).initialize() }
            assertEquals(McpFailure.OLD_TRANSPORT, oldTransport.kind)
        } finally {
            plain.shutdown()
            old.shutdown()
        }
    }

    @Test
    fun `a server that is not MCP is NOT_MCP, and an unreachable one is NETWORK`() = runTest {
        val html = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setHeader("Content-Type", "text/html").setBody("<html>hello</html>")
            }
            start()
        }
        try {
            assertEquals(
                McpFailure.NOT_MCP,
                failureOf { client.session(McpEndpoint(html.url("/").toString())).initialize() }.kind
            )
        } finally {
            html.shutdown()
        }
        val closed = MockWebServer().apply { start() }
        val url = closed.url("/mcp").toString()
        closed.shutdown()
        assertEquals(McpFailure.NETWORK, failureOf { client.session(McpEndpoint(url)).initialize() }.kind)
    }
}
