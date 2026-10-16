package com.gotcha.connectors.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stateful Streamable HTTP MCP server for tests: hands out a session id,
 * insists on it (and on the auth header, when [requiredAuth] is set), pages
 * `tools/list` two tools at a time, and can answer over SSE.
 */
class FakeMcpServer(
    var tools: List<String> = listOf(
        """{"name":"search","description":"Search the docs",""" +
            """"inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},""" +
            """"annotations":{"readOnlyHint":true}}""",
        """{"name":"create_page","description":"Create a page","inputSchema":{"type":"object","properties":{}}}""",
        """{"name":"delete_page","inputSchema":{"type":"object"}}"""
    ),
    var requiredAuth: String? = null,
    var sse: Boolean = false
) : Dispatcher() {

    data class Seen(
        val method: String?,
        val sessionId: String?,
        val protocolVersion: String?,
        val auth: String?,
        val headers: Map<String, String>
    )

    val seen = CopyOnWriteArrayList<Seen>()
    private var session = 0
    var currentSession: String? = null
        private set

    /** Forget the session, as a restarted server would: the next request with the old id gets 404. */
    fun expireSession() {
        currentSession = null
    }

    var callResult: String = """{"content":[{"type":"text","text":"found 3 pages"}]}"""

    /** When set, `tools/call` answers with a JSON-RPC error carrying this message. */
    var callError: String? = null

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "GET") return MockResponse().setResponseCode(405)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val method = body["method"]?.jsonPrimitive?.contentOrNull
        val id = (body["id"] as? JsonPrimitive)?.longOrNull
        seen += Seen(
            method,
            request.getHeader(McpHttpClient.HEADER_SESSION_ID),
            request.getHeader(McpHttpClient.HEADER_PROTOCOL_VERSION),
            request.getHeader("Authorization"),
            request.headers.toMap()
        )
        if (requiredAuth != null && request.getHeader("Authorization") != requiredAuth) {
            return MockResponse().setResponseCode(401).setBody("bad token")
        }
        if (method == "initialize") {
            currentSession = "s-${++session}"
            return reply(
                id,
                """{"protocolVersion":"2025-06-18","serverInfo":{"name":"Docs","version":"2.1"},"capabilities":{"tools":{}}}"""
            )
                .setHeader(McpHttpClient.HEADER_SESSION_ID, currentSession!!)
        }
        if (request.getHeader(McpHttpClient.HEADER_SESSION_ID) != currentSession) {
            return MockResponse().setResponseCode(404).setBody("session not found")
        }
        return when (method) {
            "notifications/initialized" -> MockResponse().setResponseCode(202)
            "tools/list" -> {
                val start = body["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
                val page = tools.drop(start).take(2)
                val next = if (start + 2 < tools.size) ""","nextCursor":"${start + 2}"""" else ""
                reply(id, """{"tools":[${page.joinToString(",")}]$next}""")
            }
            "tools/call" -> callError?.let {
                MockResponse().setBody("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"$it"}}""")
            } ?: reply(id, callResult)
            else -> MockResponse().setBody(
                """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}"""
            )
        }
    }

    private fun reply(id: Long?, result: String): MockResponse {
        val message = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
        if (!sse) return MockResponse().setHeader("Content-Type", "application/json").setBody(message)
        // A progress notification and a server request before the response, as a real stream may carry.
        val stream = buildString {
            append(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
            )
            append("data: {\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}\n\n")
            append("event: message\ndata: ").append(message).append("\n\n")
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
    }
}
