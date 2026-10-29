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
import org.junit.Assert.assertNull
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

    @Test
    fun `a cross-origin redirect is refused, so the secret header stays put`() = runTest {
        val elsewhere = MockWebServer().apply { start() }
        val redirecting = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", elsewhere.url("/mcp").toString())
            }
            start()
        }
        // Even a client built to follow redirects is made not to.
        val following = McpHttpClient(OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build())
        try {
            val e = failureOf {
                following.session(
                    McpEndpoint(redirecting.url("/mcp").toString(), mapOf("X-API-Key" to "secret"))
                ).initialize()
            }
            assertEquals(McpFailure.REDIRECT, e.kind)
            assertEquals(307, e.httpCode)
            assertEquals(elsewhere.url("/mcp").toString(), e.detail)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            redirecting.shutdown()
            elsewhere.shutdown()
        }
    }

    @Test
    fun `a same-origin redirect is followed with the headers intact`() = runTest {
        val redirecting = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/mcp") {
                        return MockResponse()
                            .setResponseCode(307)
                            .setHeader("Location", "/mcp/")
                    }
                    return fake.dispatch(request)
                }
            }
            start()
        }
        try {
            val info = client.session(
                McpEndpoint(redirecting.url("/mcp").toString(), mapOf("X-API-Key" to "secret"))
            ).initialize()
            assertEquals("Docs", info.name)
            // Both hops carried the secret: same origin, so nothing leaked.
            redirecting.takeRequest(5, TimeUnit.SECONDS)
            val followed = redirecting.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/mcp/", followed.path)
            assertEquals("secret", followed.getHeader("X-API-Key"))
        } finally {
            redirecting.shutdown()
        }
    }

    @Test
    fun `a same-origin 303 is not replayed as POST`() = runTest {
        val redirecting = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setResponseCode(303)
                    .setHeader("Location", "/mcp/")
            }
            start()
        }
        try {
            val e = failureOf {
                client.session(McpEndpoint(redirecting.url("/mcp").toString())).initialize()
            }
            assertEquals(McpFailure.HTTP, e.kind)
            assertEquals(303, e.httpCode)
            assertEquals(1, redirecting.requestCount)
        } finally {
            redirecting.shutdown()
        }
    }

    @Test
    fun `redirect loops fail instead of spinning`() = runTest {
        val looping = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "/mcp")
            }
            start()
        }
        try {
            val e = failureOf {
                client.session(McpEndpoint(looping.url("/mcp").toString())).initialize()
            }
            assertEquals(McpFailure.HTTP, e.kind)
            assertEquals(307, e.httpCode)
            // 1 initial + 5 followed hops, then give up.
            assertEquals(6, looping.requestCount)
        } finally {
            looping.shutdown()
        }
    }

    @Test
    fun `redirect targets are classified without sending anything`() {
        val client = McpHttpClient()
        // Same origin, absolute or relative: follow.
        assertEquals(
            "http://h:1/mcp/",
            client.redirectTarget("http://h:1/mcp", "/mcp/", 307)
        )
        assertEquals(
            "http://h:1/other",
            client.redirectTarget("http://h:1/mcp", "http://h:1/other", 301)
        )
        // An http-to-https upgrade on the same host: follow.
        assertEquals(
            "https://h/mcp",
            client.redirectTarget("http://h/mcp", "https://h/mcp", 307)
        )
        // Another host, another port, or a downgrade: refuse with the destination.
        assertEquals("http://other/mcp", failureTarget("http://h/mcp", "http://other/mcp"))
        assertEquals("http://h:2/mcp", failureTarget("http://h:1/mcp", "http://h:2/mcp"))
        assertEquals("http://h/mcp", failureTarget("https://h/mcp", "http://h/mcp"))
    }

    private fun failureTarget(current: String, location: String): String {
        try {
            McpHttpClient().redirectTarget(current, location, 307)
        } catch (e: McpException) {
            assertEquals(McpFailure.REDIRECT, e.kind)
            return e.detail
        }
        fail("expected a redirect refusal")
        error("unreachable")
    }

    @Test
    fun `an event line that never ends is cut at the cap, not buffered whole`() = runTest {
        val cap = 4 * 1024 * 1024
        val endless = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                // Past the cap with no newline, then the server goes quiet. A reader that
                // waits for the end of the line would sit here until the read timeout.
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + "x".repeat(cap + cap / 4))
                    .throttleBody(cap + 64L * 1024, 2, TimeUnit.SECONDS)
            }
            start()
        }
        val impatient = McpHttpClient(OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build())
        try {
            val e = failureOf { impatient.session(McpEndpoint(endless.url("/mcp").toString())).initialize() }
            assertEquals(McpFailure.NOT_MCP, e.kind)
            assertTrue(e.detail, e.detail.contains("larger than"))
        } finally {
            endless.shutdown()
        }
    }

    @Test
    fun `a missing trailing slash is toggled once before failing`() = runTest {
        // Serves only the slashed spelling, 404s the bare one, never redirects.
        val picky = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/mcp/") return MockResponse().setResponseCode(404)
                    return fake.dispatch(request)
                }
            }
            start()
        }
        try {
            val info = client.session(McpEndpoint(picky.url("/mcp").toString())).initialize()
            assertEquals("Docs", info.name)
        } finally {
            picky.shutdown()
        }
    }

    @Test
    fun `an extra trailing slash is toggled once before failing`() = runTest {
        // Serves only the bare spelling, 404s the slashed one, never redirects.
        val picky = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/mcp") return MockResponse().setResponseCode(404)
                    return fake.dispatch(request)
                }
            }
            start()
        }
        try {
            val info = client.session(McpEndpoint(picky.url("/mcp/").toString())).initialize()
            assertEquals("Docs", info.name)
        } finally {
            picky.shutdown()
        }
    }

    @Test
    fun `two missing spellings still fail, on the configured URL`() = runTest {
        // 404s everything: the retry must not mask a genuinely absent server,
        // and the session keeps the URL the user entered.
        val absent = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setResponseCode(404)
            }
            start()
        }
        try {
            val session = client.session(McpEndpoint(absent.url("/mcp").toString()))
            val e = failureOf { session.initialize() }
            assertEquals(McpFailure.NOT_FOUND, e.kind)
            // One POST + old-transport GET per spelling, then give up.
            val paths = List(4) { absent.takeRequest(5, TimeUnit.SECONDS)!!.path }
            assertEquals(listOf("/mcp", "/mcp", "/mcp/", "/mcp/"), paths)
            assertEquals(absent.url("/mcp").toString(), session.endpoint.url)
        } finally {
            absent.shutdown()
        }
    }

    @Test
    fun `slash toggling leaves the rest of the URL alone`() {
        val client = McpHttpClient()
        assertEquals("http://h/mcp/", client.slashToggled("http://h/mcp"))
        assertEquals("http://h/mcp", client.slashToggled("http://h/mcp/"))
        assertEquals("http://h/a/b/", client.slashToggled("http://h/a/b"))
        assertEquals("http://h/mcp/?k=v", client.slashToggled("http://h/mcp?k=v"))
        assertNull(client.slashToggled("http://h/"))
        assertNull(client.slashToggled("not a url"))
    }
}
